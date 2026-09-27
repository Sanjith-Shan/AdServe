package adserve.server.config;

import adserve.core.io.CampaignFiles;
import adserve.core.model.Campaign;
import adserve.core.pacing.PacingPlan;
import com.fasterxml.jackson.core.type.TypeReference;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

/**
 * Pacing plans from the forecast file the simulator writes (the previous day's eligible traffic
 * per campaign per slot). Campaigns without a forecast, such as ones created through the
 * management API, fall back to the file's all-campaign curve, and without a file to a flat day.
 */
public final class Forecasts implements Function<Campaign, PacingPlan> {
    private final Map<String, double[]> byCampaign;
    private final int slots;

    public Forecasts(String file, int slots) throws IOException {
        this.slots = slots;
        if (file != null && !file.isBlank() && Files.exists(Path.of(file))) {
            byCampaign = CampaignFiles.mapper().readValue(Path.of(file).toFile(), new TypeReference<>() {});
        } else {
            byCampaign = Map.of();
        }
    }

    @Override
    public PacingPlan apply(Campaign c) {
        double[] w = byCampaign.get(c.id());
        if (w == null) w = byCampaign.get("*");
        if (w == null || w.length != slots) return PacingPlan.flat(slots);
        return new PacingPlan(w, 86_400_000L);
    }
}
