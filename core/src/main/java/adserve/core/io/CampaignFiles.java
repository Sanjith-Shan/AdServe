package adserve.core.io;

import adserve.core.model.Campaign;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Reads and writes the campaign catalogue as JSON (data/work/campaigns.json). */
public final class CampaignFiles {
    private static final ObjectMapper MAPPER = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private CampaignFiles() {}

    public static List<Campaign> read(Path p) throws IOException {
        return MAPPER.readValue(p.toFile(), new TypeReference<>() {});
    }

    public static void write(Path p, List<Campaign> campaigns) throws IOException {
        MAPPER.writeValue(p.toFile(), campaigns);
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }
}
