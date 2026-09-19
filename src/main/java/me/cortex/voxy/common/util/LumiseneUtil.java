package me.cortex.voxy.common.util;

import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class LumiseneUtil {
    private static final float MIN_VISIBLE_WALL_HEIGHT = 1.0f / 16.0f;
    private static final Map<BlockState, Boolean> STATE_CACHE = new ConcurrentHashMap<>();

    private LumiseneUtil() {
    }

    public static boolean isLumisene(Mapper mapper, long mappingId) {
        return !Mapper.isAir(mappingId) && isLumisene(mapper.getBlockStateFromBlockId(Mapper.getBlockId(mappingId)));
    }

    public static boolean isLumisene(BlockState state) {
        return STATE_CACHE.computeIfAbsent(state, LumiseneUtil::computeIsLumisene);
    }

    private static boolean computeIsLumisene(BlockState state) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (isLumiseneId(blockId)) {
            return true;
        }

        FluidState fluidState = state.getFluidState();
        return !fluidState.isEmpty() && isLumiseneId(BuiltInRegistries.FLUID.getKey(fluidState.getType()));
    }

    public static boolean isThinLumisene(BlockState state) {
        if (!isLumisene(state)) {
            return false;
        }

        FluidState fluidState = state.getFluidState();
        if (fluidState.isEmpty()) {
            return false;
        }

        float height = fluidState.getOwnHeight();
        return height > 0.0f && height < MIN_VISIBLE_WALL_HEIGHT;
    }

    private static boolean isLumiseneId(ResourceLocation id) {
        return id != null && "supplementaries".equals(id.getNamespace()) && id.getPath().contains("lumisene");
    }
}
