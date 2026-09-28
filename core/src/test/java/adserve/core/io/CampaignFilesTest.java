package adserve.core.io;

import adserve.core.model.Campaign;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CampaignFilesTest {
    /** BUG_LOG bug 13: targeting sets used to iterate in a per-JVM order, so every write reordered them. */
    @Test
    void writingTheSampleBackIsByteIdentical() throws Exception {
        Path sample = null;
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path f = p.resolve("data/sample/campaigns.json");
            if (Files.exists(f)) { sample = f; break; }
        }
        assertThat(sample).isNotNull();
        List<Campaign> cs = CampaignFiles.read(sample);
        Path a = Files.createTempFile("campaigns", ".json");
        Path b = Files.createTempFile("campaigns", ".json");
        CampaignFiles.write(a, cs);
        CampaignFiles.write(b, CampaignFiles.read(a));
        assertThat(Files.readString(b)).isEqualTo(Files.readString(a));
        for (Campaign c : cs) {
            assertThat(List.copyOf(c.targeting().geos())).isSorted();
        }
    }
}
