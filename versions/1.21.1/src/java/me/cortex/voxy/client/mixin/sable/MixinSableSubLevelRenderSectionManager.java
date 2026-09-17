package me.cortex.voxy.client.mixin.sable;

import me.cortex.voxy.client.compat.sable.SableClientRenderDistance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.sublevel.render.sodium.SubLevelRenderSectionManager", remap = false)
public abstract class MixinSableSubLevelRenderSectionManager {
    @ModifyArg(
            method = "<init>",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/RenderSectionManager;<init>(Lnet/minecraft/class_638;ILnet/caffeinemc/mods/sodium/client/gl/device/CommandList;)V"
            ),
            index = 1,
            remap = false
    )
    private static int voxy$extendSableRenderDistance(int renderDistanceChunks) {
        return SableClientRenderDistance.extendVanillaRenderDistanceChunks(renderDistanceChunks);
    }
}
