package me.cortex.voxy.client.config;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import me.cortex.voxy.client.core.SSAO;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.util.cpu.CpuLayout;
import me.cortex.voxy.commonImpl.VoxyCommon;
//? if 1.21.1
import me.cortex.voxy.commonImpl.compat.sable.SableContraptionRenderDistance;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
//? if 1.20.1
import me.jellysquid.mods.sodium.client.gui.options.storage.OptionStorage;

public class VoxyConfig
//? if 1.20.1
    implements OptionStorage<VoxyConfig>
{
    private static final Gson GSON = new GsonBuilder()
            .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
            .setPrettyPrinting()
            .excludeFieldsWithModifiers(Modifier.PRIVATE)
            .create();

    public static VoxyConfig CONFIG = loadOrCreate();

    public boolean enabled = true;
    public boolean enableRendering = true;
    public boolean ingestEnabled = true;
    public float sectionRenderDistance = 16;
    //? if 1.21.1
    public int simulatedContraptionRenderDistancePercent = 50;
    public int serviceThreads = (int) Math.max(CpuLayout.getCoreCount()/1.5, 1);
    public float subDivisionSize = 64;
    public int skyFogDistance = 96;
    public float fogIntensity = 1.0f;
    public float fogDensity = 0.0f;
    public boolean adaptCloudDistance = true;
    public int cloudDistance = 0;
    public boolean dontUseSodiumBuilderThreads = false;

    public String ssaoMode;

    public boolean useEnvironmentalFog = true;

    public SSAO.SSAOMode getSSAOMode() {
        if (this.ssaoMode == null) return SSAO.SSAOMode.AUTO;
        try {
            return SSAO.SSAOMode.valueOf(this.ssaoMode.toUpperCase(Locale.ROOT));
        } catch (Exception e) { return SSAO.SSAOMode.AUTO; }
    }

    public void setSSAOMode(SSAO.SSAOMode mode) {
        this.ssaoMode = mode.name().toLowerCase(Locale.ROOT);
    }

    private static VoxyConfig loadOrCreate() {
        if (VoxyCommon.isAvailable()) {
            var path = getConfigPath();
            if (Files.exists(path)) {
                try (FileReader reader = new FileReader(path.toFile())) {
                    var conf = GSON.fromJson(reader, VoxyConfig.class);
                    if (conf != null) {
                        conf.save();
                        return conf;
                    } else {
                        Logger.error("Failed to load voxy config, resetting");
                    }
                } catch (IOException e) {
                    Logger.error("Could not parse config", e);
                }
            }
            Logger.info("Config doesnt exist, creating new");
            var config = new VoxyConfig();
            config.save();
            return config;
        } else {
            var config = new VoxyConfig();
            config.enabled = false;
            config.enableRendering = false;
            return config;
        }
    }

    public void save() {
        if (!VoxyCommon.isAvailable()) {
            Logger.info("Not saving config since voxy is unavalible");
            //? if 1.21.1 {
            this.syncSableContraptionRenderDistance();
            //? }
            return;
        }

        try {
            //? if 1.21.1 {
            JsonObject json = GSON.toJsonTree(this).getAsJsonObject();
            if (!VoxyCommon.getPlatformUtil().isModLoaded("sable")) {
                json.remove("simulated_contraption_render_distance_percent");
            }
            Files.writeString(getConfigPath(), GSON.toJson(json));
            //? } else {
            Files.writeString(getConfigPath(), GSON.toJson(this));
            //? }
        } catch (IOException e) {
            Logger.error("Failed to write config file", e);
        }
        //? if 1.21.1 {
        this.syncSableContraptionRenderDistance();
        //? }
    }

    private static Path getConfigPath() {
        return VoxyCommon.getPlatformUtil().getConfigDir().resolve("voxy-config.json");
    }

    //? if 1.20.1 {
    @Override
    public VoxyConfig getData() {
        return this;
    }
    //? }

    public boolean isRenderingEnabled() {
        return VoxyCommon.isAvailable() && this.enabled && this.enableRendering;
    }

    //? if 1.21.1 {
    public void syncSableContraptionRenderDistance() {
        SableContraptionRenderDistance.updateClientConfig(
                this.isRenderingEnabled(),
                this.sectionRenderDistance,
                this.simulatedContraptionRenderDistancePercent
        );
    }
    //? }
}
