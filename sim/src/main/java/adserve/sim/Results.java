package adserve.sim;

import adserve.core.io.CampaignFiles;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

/** Appends one JSON object per line to results/<file>.jsonl. */
public final class Results {
    private Results() {}

    public static ObjectMapper json() {
        return CampaignFiles.mapper();
    }

    public static ObjectNode line(String experiment) {
        ObjectNode n = json().createObjectNode();
        n.put("experiment", experiment);
        n.put("at", Instant.now().toString());
        Machine.describe(n);
        return n;
    }

    public static void append(String file, ObjectNode n) throws IOException {
        Path p = Path.of("results", file);
        Files.createDirectories(p.getParent());
        String s = json().writer().without(com.fasterxml.jackson.databind.SerializationFeature.INDENT_OUTPUT)
                .writeValueAsString(n) + "\n";
        Files.writeString(p, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
