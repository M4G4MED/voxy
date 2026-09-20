package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.GsonBuilder;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.VoxyCommon;

import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in configuration for filling in terrain beyond the vanilla loaded view
 *  distance. Kept fully standalone (own file, own gson) at .voxy/distant_terrain.json
 *  so it does not participate in the main config type registry. */
public class DistantTerrainConfig {
    private static final com.google.gson.Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .create();

    // Master switch for distant terrain filling. Off by default.
    public boolean enabled = false;

    // Phase A: read already-saved chunks straight from the world's own region files
    // (only possible when the world save directory is locally accessible).
    public boolean read_saved_terrain = true;

    // Phase B: approximate terrain for chunks that were never saved, by evaluating
    // the world's own noise + surface rules off-thread (singleplayer only).
    public boolean synthesize_unsaved_terrain = true;

    // Radius (in chunks) around the player kept filled with distant terrain.
    public int radius_chunks = 96;

    // Chunks of extra gap added on top of the player's current render distance
    // before LoDs start. 0 = LoDs begin right where normal chunks end.
    public int margin_chunks = 0;

    // Max region-file tasks handed to the background worker per pass.
    public int max_regions_per_pass = 8;

    // Max noise-built chunk tasks handed to the background worker per pass.
    public int max_chunks_per_pass = 64;

    private static DistantTerrainConfig INSTANCE = null;

    public static DistantTerrainConfig get() {
        if (INSTANCE == null) {
            INSTANCE = load();
        }
        return INSTANCE;
    }

    private static Path getConfigPath() {
        return VoxyCommon.getPlatformUtil().getConfigDir().resolve(".voxy").resolve("distant_terrain.json");
    }

    private static DistantTerrainConfig load() {
        try {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, DistantTerrainConfig.class);
                    if (conf != null) {
                        return conf;
                    }
                }
            } else {
                var conf = new DistantTerrainConfig();
                Files.createDirectories(path.getParent());
                Files.writeString(path, GSON.toJson(conf));
                return conf;
            }
        } catch (Exception e) {
            Logger.error("Failed to load distant terrain config", e);
        }
        return new DistantTerrainConfig();
    }

    public void save() {
        try {
            var path = getConfigPath();
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(this));
        } catch (Exception e) {
            Logger.error("Failed to save distant terrain config", e);
        }
    }
}
