package adserve.server.rest;

import ads.v1.AdRequest;
import adserve.server.DecisionService;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Thin REST facade over the same decision path, for demos and curl: the AdRequest as protobuf
 * JSON in, the AdResponse as protobuf JSON out. Shedding is a 503 with Retry-After and a
 * Cache-Control max-age so intermediaries hold off too.
 */
@RestController
public class DecisionController {
    private static final JsonFormat.Parser PARSER = JsonFormat.parser().ignoringUnknownFields();
    private static final JsonFormat.Printer PRINTER = JsonFormat.printer().omittingInsignificantWhitespace();

    private final DecisionService service;

    public DecisionController(DecisionService service) {
        this.service = service;
    }

    @PostMapping(path = "/v1/decide", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> decide(@RequestBody String body) throws InvalidProtocolBufferException {
        AdRequest.Builder b = AdRequest.newBuilder();
        try {
            PARSER.merge(body, b);
        } catch (InvalidProtocolBufferException e) {
            return ResponseEntity.badRequest().body("{\"error\":\"" + e.getMessage().replace("\"", "'") + "\"}");
        }
        return switch (service.decide(b.build())) {
            case DecisionService.Served s -> ResponseEntity.ok(PRINTER.print(s.response()));
            case DecisionService.Shed s -> {
                long secs = Math.max(1, (s.retryAfterMs() + 999) / 1000);
                yield ResponseEntity.status(503)
                        .header(HttpHeaders.RETRY_AFTER, Long.toString(secs))
                        .header(HttpHeaders.CACHE_CONTROL, "max-age=" + secs)
                        .body("{\"error\":\"overloaded\",\"retryAfterMs\":" + s.retryAfterMs() + "}");
            }
            case DecisionService.Failed f -> ResponseEntity.status(503).body("{\"error\":\"" + f.reason() + "\"}");
        };
    }
}
