package adserve.sim.load;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Reads one counter from the server's Prometheus endpoint; NaN if the server does not answer. */
final class ServerMetrics {
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    static double value(String name) {
        try {
            String body = HTTP.send(HttpRequest.newBuilder(URI.create("http://localhost:28080/actuator/prometheus"))
                    .timeout(Duration.ofSeconds(3)).build(), HttpResponse.BodyHandlers.ofString()).body();
            for (String line : body.split("\n")) {
                if (line.startsWith(name + " ") || line.startsWith(name + "{")) {
                    return Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1));
                }
            }
        } catch (Exception e) {
            // server down or metric missing
        }
        return Double.NaN;
    }
}
