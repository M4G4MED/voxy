package me.cortex.voxy.client.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import me.cortex.voxy.client.config.VoxyConfig;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.commonImpl.compat.sable.SableContraptionRenderDistance;
import net.minecraft.client.Minecraft;

public final class SableClientRenderDistance {
    private static final int BLOCKS_PER_CHUNK = 16;
    private static boolean renderDataRefreshUnavailable;

    private SableClientRenderDistance() {
    }

    public static int extendVanillaRenderDistanceChunks(int vanillaRenderDistanceChunks) {
        if (!VoxyConfig.CONFIG.isRenderingEnabled()) {
            return vanillaRenderDistanceChunks;
        }

        return SableContraptionRenderDistance.extendVanillaRenderDistanceChunks(
                vanillaRenderDistanceChunks,
                VoxyConfig.CONFIG.sectionRenderDistance,
                VoxyConfig.CONFIG.simulatedContraptionRenderDistancePercent
        );
    }

    public static double getRenderDistanceBlocks(int vanillaRenderDistanceChunks) {
        return extendVanillaRenderDistanceChunks(vanillaRenderDistanceChunks) * (double) BLOCKS_PER_CHUNK;
    }

    public static boolean isVoxyRenderDistanceActive() {
        return VoxyConfig.CONFIG.isRenderingEnabled() && VoxyConfig.CONFIG.simulatedContraptionRenderDistancePercent > 0;
    }

    public static void refreshSableRenderData() {
        if (renderDataRefreshUnavailable) {
            return;
        }

        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.level == null) {
                return;
            }

            ClientSubLevelContainer container = SubLevelContainer.getContainer(minecraft.level);
            if (container == null) {
                return;
            }

            for (ClientSubLevel subLevel : container.getAllSubLevels()) {
                subLevel.updateRenderData();
            }
        } catch (NoClassDefFoundError e) {
            renderDataRefreshUnavailable = true;
        } catch (RuntimeException | LinkageError e) {
            renderDataRefreshUnavailable = true;
            Logger.warn("Disabling Sable render distance refresh after render data update failed", e);
        }
    }
}
