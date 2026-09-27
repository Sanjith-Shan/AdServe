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

    private final RedisClient client;
    private final StatefulRedisConnection<String, String>[] conns;
    private final RedisAsyncCommands<String, String>[] cmds;
    private final java.util.concurrent.atomic.AtomicInteger next = new java.util.concurrent.atomic.AtomicInteger();
    private final RedisAsyncCommands<String, String> cmd;
    private final long timeoutMs;
    private final boolean idempotent;
    private final String countSha;
    private final String spendSha;
    private final String podSha;
    private final LongAdder writeErrors = new LongAdder();

    public RedisCounters(String uri, long timeoutMs, boolean idempotent) {
        this(uri, timeoutMs, idempotent, 1);
    }

    /** {@code connections} multiplexed connections, used round-robin by the decision path. */
    @SuppressWarnings("unchecked")
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
        this.conns = new StatefulRedisConnection[Math.max(1, connections)];
        this.cmds = new RedisAsyncCommands[conns.length];
        for (int i = 0; i < conns.length; i++) {
            conns[i] = client.connect();
            cmds[i] = conns[i].async();
        }
        this.cmd = cmds[0];
        this.timeoutMs = timeoutMs;
        this.idempotent = idempotent;
        this.countSha = sha1(COUNT_ONCE);
        this.spendSha = sha1(SPEND_ONCE);
        this.podSha = sha1(COUNT_POD_ONCE);
        loadScripts();
    }

    /** Loads both scripts; also called again whenever Redis answers NOSCRIPT (after a restart). */
    public void loadScripts() {
        try {
            cmd.scriptLoad(COUNT_ONCE).get(2, TimeUnit.SECONDS);
            cmd.scriptLoad(SPEND_ONCE).get(2, TimeUnit.SECONDS);
            cmd.scriptLoad(COUNT_POD_ONCE).get(2, TimeUnit.SECONDS);
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
        return cmds.length == 1 ? cmd : cmds[Math.floorMod(next.getAndIncrement(), cmds.length)];
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
                for (int i = 1; i < counters.length; i++) cmd.incr(counters[i]);
                f = cmd.incr(counters[0]);
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
            return cmd.incrby(spendKey(campaignId, Windows.day(tsMs)), micros);
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
            v = cmd.mget(keys.toArray(String[]::new)).get(1, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new CapStoreUnavailableException("spend read failed", e);
        }
        long[] out = new long[campaignIds.size()];
        for (int i = 0; i < out.length; i++) out[i] = parse(v.get(i));
        return out;
    }

    public long writeErrors() {
        return writeErrors.sum();
    }

    public RedisAsyncCommands<String, String> commands() {
        return cmd;
    }

    @Override
    public void close() {
        for (StatefulRedisConnection<String, String> c : conns) c.close();
        client.shutdown();
    }
}
