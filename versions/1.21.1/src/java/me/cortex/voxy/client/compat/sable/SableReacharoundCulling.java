package me.cortex.voxy.client.compat.sable;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

public final class SableReacharoundCulling {
    private static final double HYSTERESIS_BLOCKS = 32.0D;

    // filter() runs once per RenderType layer per frame (several times per frame with
    // the same camera); reuse one list + one scratch vector instead of allocating per
    // call and per sub-level. Safe because each renderSectionLayer call fully consumes
    // the returned iterable before the next filter() invocation (sequential rendering),
    // and Sable never retains it.
    private static final List<ClientSubLevel> SCRATCH_VISIBLE = new ArrayList<>();
    private static final Vector3d SCRATCH_CENTER = new Vector3d();

    private SableReacharoundCulling() {
    }

    public static Iterable<ClientSubLevel> filter(Iterable<ClientSubLevel> subLevels, double cameraX, double cameraZ) {
        if (!SableClientRenderDistance.isVoxyRenderDistanceActive()) {
            return subLevels;
        }

        int vanillaRenderDistanceChunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        double renderDistanceBlocks = SableClientRenderDistance.getRenderDistanceBlocks(vanillaRenderDistanceChunks) + HYSTERESIS_BLOCKS;
        if (!Double.isFinite(renderDistanceBlocks) || renderDistanceBlocks <= 0.0D) {
            return subLevels;
        }

        SCRATCH_VISIBLE.clear();
        for (ClientSubLevel subLevel : subLevels) {
            if (isInRenderDistance(subLevel, cameraX, cameraZ, renderDistanceBlocks)) {
                SCRATCH_VISIBLE.add(subLevel);
            }
        }

        return SCRATCH_VISIBLE;
    }

    private static boolean isInRenderDistance(ClientSubLevel subLevel, double cameraX, double cameraZ, double renderDistanceBlocks) {
        BoundingBox3ic bounds = subLevel.getPlot().getBoundingBox();
        if (bounds == null) {
            return true;
        }

        Vector3d center = SCRATCH_CENTER;
        center.set(
                (bounds.minX() + bounds.maxX() + 1) * 0.5D,
                (bounds.minY() + bounds.maxY() + 1) * 0.5D,
                (bounds.minZ() + bounds.maxZ() + 1) * 0.5D
        );
        subLevel.renderPose().transformPosition(center);

        double halfWidth = Math.max(0.5D, bounds.width() * 0.5D);
        double halfLength = Math.max(0.5D, bounds.length() * 0.5D);
        double horizontalRadius = Math.sqrt(halfWidth * halfWidth + halfLength * halfLength);

        double dx = center.x - cameraX;
        double dz = center.z - cameraZ;
        return (dx * dx + dz * dz) <= (renderDistanceBlocks + horizontalRadius) * (renderDistanceBlocks + horizontalRadius);
    }
}
