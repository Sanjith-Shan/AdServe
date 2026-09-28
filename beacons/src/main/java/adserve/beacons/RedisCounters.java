package adserve.beacons;

import adserve.core.caps.CapCounts;
import adserve.core.caps.CapKeys;
import adserve.core.caps.CapStore;
import adserve.core.caps.CapStoreUnavailableException;
import adserve.core.caps.Windows;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.SocketOptions;
import io.lettuce.core.TimeoutOptions;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Frequency-cap and spend counters in Redis. Written by two processes, AdServe at decision time
 * and the beacon consumer when the device's IMPRESSION beacon arrives, both through the same Lua
 * script with the same stable event id: the script sets the event key with NX and only then
 * increments, so an impression counts once however many times it is written.
 *
 * <p>{@code idempotent=false} swaps in the naive version (bare INCR per delivery) for experiment 3.
 */
public final class RedisCounters implements CapStore, AutoCloseable {

    /** KEYS[1] event key, KEYS[2..n] counters; ARGV[1] event TTL, ARGV[2..n] counter TTLs. */
    static final String COUNT_ONCE = """
            if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
              for i = 2, #KEYS do
                redis.call('INCR', KEYS[i])
                redis.call('EXPIRE', KEYS[i], ARGV[i])
              end
              return 1
            end
            return 0
            """;

    /**
     * A whole pod in one call. KEYS: per impression (event, day, week), then the viewer's hour key
     * last. ARGV: event TTL, day TTL, week TTL, hour TTL. Returns how many impressions counted.
     */
    static final String COUNT_POD_ONCE = """
            local counted = 0
            local hour = KEYS[#KEYS]
            for i = 1, #KEYS - 1, 3 do
              if redis.call('SET', KEYS[i], '1', 'NX', 'EX', ARGV[1]) then
                redis.call('INCR', KEYS[i + 1])
                redis.call('EXPIRE', KEYS[i + 1], ARGV[2])
                redis.call('INCR', KEYS[i + 2])
                redis.call('EXPIRE', KEYS[i + 2], ARGV[3])
                redis.call('INCR', hour)
                counted = counted + 1
              end
            end
            if counted > 0 then redis.call('EXPIRE', hour, ARGV[4]) end
            return counted
            """;

    /** KEYS[1] event key, KEYS[2] spend counter; ARGV[1] TTL, ARGV[2] amount. */
    static final String SPEND_ONCE = """
            if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
              redis.call('INCRBY', KEYS[2], ARGV[2])
              redis.call('EXPIRE', KEYS[2], ARGV[1])
              return 1
            end
            return 0
            """;

    /**
     * Per-creative quality counters for the auction's optional skip-rate term. KEYS[1] event key,
     * KEYS[2] the creative's hash; ARGV[1] TTL, ARGV[2] field ("imp" or "complete").
     */
    static final String QUALITY_ONCE = """
            if redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1]) then
              redis.call('HINCRBY', KEYS[2], ARGV[2], 1)
              redis.call('EXPIRE', KEYS[2], ARGV[1])
              return 1
            end
            return 0
            """;

    private final RedisClient client;
    private final java.util.concurrent.atomic.AtomicReferenceArray<StatefulRedisConnection<String, String>> conns;
    private final java.util.concurrent.atomic.AtomicReferenceArray<RedisAsyncCommands<String, String>> cmds;
    private final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
    private volatile long nextConnectAttemptMs;
    private final long timeoutMs;
    private final boolean idempotent;
    private final String countSha;
    private final String spendSha;
    private final String podSha;
    private final String qualitySha;
    private final LongAdder writeErrors = new LongAdder();

    public RedisCounters(String uri, long timeoutMs, boolean idempotent) {
        this(uri, timeoutMs, idempotent, 1);
    }

    /** {@code connections} multiplexed connections, used round-robin by the decision path. */
    public RedisCounters(String uri, long timeoutMs, boolean idempotent, int connections) {
        this.client = RedisClient.create(uri);
        // Fail fast while disconnected instead of queueing commands: a request must not wait on
        // a Redis that is down, it must fall through to the cap mode.
        client.setOptions(ClientOptions.builder()
                .autoReconnect(true)
                .disconnectedBehavior(ClientOptions.DisconnectedBehavior.REJECT_COMMANDS)
                .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(500)).build())
                .timeoutOptions(TimeoutOptions.enabled(Duration.ofSeconds(2)))
                .build());
        this.conns = new java.util.concurrent.atomic.AtomicReferenceArray<>(Math.max(1, connections));
        this.cmds = new java.util.concurrent.atomic.AtomicReferenceArray<>(conns.length());
        this.timeoutMs = timeoutMs;
        this.idempotent = idempotent;
        this.countSha = sha1(COUNT_ONCE);
        this.spendSha = sha1(SPEND_ONCE);
        this.podSha = sha1(COUNT_POD_ONCE);
        this.qualitySha = sha1(QUALITY_ONCE);
        // Connect now if Redis is up; if it is not, start anyway and connect on first use. A
        // serving node must be able to start (and serve, in its cap mode) while Redis is down.
        ensureConnected();
    }

    /** Opens any missing connection, at most once a second while Redis is unreachable. */
    private synchronized boolean ensureConnected() {
        if (cmds.get(0) != null && cmds.get(cmds.length() - 1) != null) return true;
        long now = System.currentTimeMillis();
        if (now < nextConnectAttemptMs) return cmds.get(0) != null;
        boolean opened = false;
        for (int i = 0; i < cmds.length(); i++) {
            if (cmds.get(i) != null) continue;
            try {
                StatefulRedisConnection<String, String> c = client.connect();
                conns.set(i, c);
                cmds.set(i, c.async());
                opened = true;
            } catch (RuntimeException e) {
                nextConnectAttemptMs = now + 1000;
                break;
            }
        }
        if (opened) loadScripts();
        return cmds.get(0) != null;
    }

    /** Loads both scripts; also called again whenever Redis answers NOSCRIPT (after a restart). */
    public void loadScripts() {
        RedisAsyncCommands<String, String> c = cmds.get(0);
        if (c == null) return;
        try {
            c.scriptLoad(COUNT_ONCE).get(2, TimeUnit.SECONDS);
            c.scriptLoad(SPEND_ONCE).get(2, TimeUnit.SECONDS);
            c.scriptLoad(COUNT_POD_ONCE).get(2, TimeUnit.SECONDS);
            c.scriptLoad(QUALITY_ONCE).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            // Redis unreachable right now; evalsha will fall back to eval when it returns.
        }
    }

    static String sha1(String s) {
        try {
            byte[] d = java.security.MessageDigest.getInstance("SHA-1").digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(d);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private RedisAsyncCommands<String, String> pick() {
        RedisAsyncCommands<String, String> c = cmds.length() == 1 ? cmds.get(0)
                : cmds.get(Math.floorMod(next.getAndIncrement(), cmds.length()));
        if (c != null) return c;
        if (ensureConnected()) {
            c = cmds.get(0);
            if (c != null) return c;
        }
        throw new CapStoreUnavailableException("redis not connected", null);
    }

    private <T> RedisFuture<T> evalScript(String sha, String script, ScriptOutputType type, String[] keys, String... args) {
        RedisFuture<T> f = pick().evalsha(sha, type, keys, args);
        f.whenComplete((v, e) -> {
            if (e != null && String.valueOf(e.getMessage()).contains("NOSCRIPT")) loadScripts();
        });
        return f;
    }

    public static String spendKey(String campaignId, long day) {
        return "sp:{" + campaignId + "}:d" + day;
    }

    static String spendEventKey(String campaignId, String eventId) {
        return "evs:{" + campaignId + "}:" + eventId;
    }

    @Override
    public CapCounts fetch(String viewerId, List<String> campaignIds, long nowMs) {
        int n = campaignIds.size();
        String[] keys = new String[2 * n + 1];
        for (int i = 0; i < n; i++) {
            keys[2 * i] = CapKeys.day(viewerId, campaignIds.get(i), nowMs);
            keys[2 * i + 1] = CapKeys.week(viewerId, campaignIds.get(i), nowMs);
        }
        keys[2 * n] = CapKeys.viewerHour(viewerId, nowMs);
        List<KeyValue<String, String>> values = await(pick().mget(keys));
        long[] day = new long[n];
        long[] week = new long[n];
        for (int i = 0; i < n; i++) {
            day[i] = parse(values.get(2 * i));
            week[i] = parse(values.get(2 * i + 1));
        }
        return new CapCounts(day, week, parse(values.get(2 * n)), true);
    }

    private <T> T await(RedisFuture<T> f) {
        try {
            return f.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CapStoreUnavailableException("interrupted", e);
        } catch (Exception e) {
            f.cancel(false);
            throw new CapStoreUnavailableException("counter store did not answer in " + timeoutMs + " ms", e);
        }
    }

    private static long parse(KeyValue<String, String> kv) {
        return kv.hasValue() ? Long.parseLong(kv.getValue()) : 0;
    }

    /** Fire-and-forget; the returned future is only for callers (tests, the consumer) that want to wait. */
    @Override
    public void recordImpression(String viewerId, String campaignId, String eventId, long tsMs) {
        recordImpressionAsync(viewerId, campaignId, eventId, tsMs);
    }

    public RedisFuture<?> recordImpressionAsync(String viewerId, String campaignId, String eventId, long tsMs) {
        String[] counters = {
                CapKeys.day(viewerId, campaignId, tsMs),
                CapKeys.week(viewerId, campaignId, tsMs),
                CapKeys.viewerHour(viewerId, tsMs)};
        RedisFuture<?> f;
        try {
            if (idempotent) {
                String[] keys = new String[counters.length + 1];
                keys[0] = CapKeys.event(viewerId, eventId);
                System.arraycopy(counters, 0, keys, 1, counters.length);
                f = evalScript(countSha, COUNT_ONCE, ScriptOutputType.INTEGER, keys,
                        String.valueOf(CapKeys.EVENT_TTL_S), String.valueOf(CapKeys.DAY_TTL_S),
                        String.valueOf(CapKeys.WEEK_TTL_S), String.valueOf(CapKeys.HOUR_TTL_S));
            } else {
                for (int i = 1; i < counters.length; i++) pick().incr(counters[i]);
                f = pick().incr(counters[0]);
            }
        } catch (RuntimeException e) {
            writeErrors.increment();
            return null;
        }
        f.whenComplete((v, e) -> {
            if (e != null) writeErrors.increment();
        });
        return f;
    }

    @Override
    public void recordPod(String viewerId, String[] campaignIds, String[] eventIds, long tsMs) {
        if (!idempotent || campaignIds.length == 1) {
            for (int i = 0; i < campaignIds.length; i++) recordImpression(viewerId, campaignIds[i], eventIds[i], tsMs);
            return;
        }
        String[] keys = new String[3 * campaignIds.length + 1];
        for (int i = 0; i < campaignIds.length; i++) {
            keys[3 * i] = CapKeys.event(viewerId, eventIds[i]);
            keys[3 * i + 1] = CapKeys.day(viewerId, campaignIds[i], tsMs);
            keys[3 * i + 2] = CapKeys.week(viewerId, campaignIds[i], tsMs);
        }
        keys[keys.length - 1] = CapKeys.viewerHour(viewerId, tsMs);
        try {
            RedisFuture<Long> f = evalScript(podSha, COUNT_POD_ONCE, ScriptOutputType.INTEGER, keys,
                    String.valueOf(CapKeys.EVENT_TTL_S), String.valueOf(CapKeys.DAY_TTL_S),
                    String.valueOf(CapKeys.WEEK_TTL_S), String.valueOf(CapKeys.HOUR_TTL_S));
            f.whenComplete((v, e) -> {
                if (e != null) writeErrors.increment();
            });
        } catch (RuntimeException e) {
            writeErrors.increment();
        }
    }

    /** Counts confirmed spend once per event id. Returns 1 if this call counted it. */
    public RedisFuture<Long> recordSpendAsync(String campaignId, String eventId, long micros, long tsMs) {
        if (!idempotent) {
            return pick().incrby(spendKey(campaignId, Windows.day(tsMs)), micros);
        }
        return evalScript(spendSha, SPEND_ONCE, ScriptOutputType.INTEGER,
                new String[]{spendEventKey(campaignId, eventId), spendKey(campaignId, Windows.day(tsMs))},
                String.valueOf(CapKeys.DAY_TTL_S), String.valueOf(micros));
    }

    /** Confirmed spend for each campaign today; off the decision path (budget sync). */
    public long[] spend(List<String> campaignIds, long day) {
        if (campaignIds.isEmpty()) return new long[0];
        List<String> keys = new ArrayList<>(campaignIds.size());
        for (String c : campaignIds) keys.add(spendKey(c, day));
        List<KeyValue<String, String>> v;
        try {
            v = pick().mget(keys.toArray(String[]::new)).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new CapStoreUnavailableException("spend read failed", e);
        }
        long[] out = new long[campaignIds.size()];
        for (int i = 0; i < out.length; i++) out[i] = parse(v.get(i));
        return out;
    }

    static final long QUALITY_TTL_S = 7 * 86_400L;

    public static String qualityKey(String creativeId) {
        return "cq:{" + creativeId + "}";
    }

    /** Counts one IMPRESSION ("imp") or COMPLETE ("complete") of a creative, once per event id. */
    public RedisFuture<Long> recordCreativeEventAsync(String creativeId, String field, String eventId) {
        return evalScript(qualitySha, QUALITY_ONCE, ScriptOutputType.INTEGER,
                new String[]{"qe:" + eventId, qualityKey(creativeId)},
                String.valueOf(QUALITY_TTL_S), field);
    }

    /** {impressions, completes} per creative; off the decision path (quality sync). */
    public long[][] creativeQuality(List<String> creativeIds) {
        long[][] out = new long[creativeIds.size()][2];
        RedisAsyncCommands<String, String> c = pick();
        List<RedisFuture<List<KeyValue<String, String>>>> pending = new ArrayList<>(creativeIds.size());
        for (String id : creativeIds) pending.add(c.hmget(qualityKey(id), "imp", "complete"));
        try {
            for (int i = 0; i < out.length; i++) {
                List<KeyValue<String, String>> v = pending.get(i).get(1, TimeUnit.SECONDS);
                out[i][0] = parse(v.get(0));
                out[i][1] = parse(v.get(1));
            }
        } catch (Exception e) {
            throw new CapStoreUnavailableException("quality read failed", e);
        }
        return out;
    }

    public long writeErrors() {
        return writeErrors.sum();
    }

    public RedisAsyncCommands<String, String> commands() {
        return pick();
    }

    @Override
    public void close() {
        for (int i = 0; i < conns.length(); i++) {
            if (conns.get(i) != null) conns.get(i).close();
        }
        client.shutdown();
    }
}
