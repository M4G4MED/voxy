package me.cortex.voxy.client.mixin.sable;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import me.cortex.voxy.client.compat.sable.VoxySableDepthShim;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class MixinSableDepthShim {
    @Inject(method = "renderSectionLayer", at = @At("HEAD"))
    private void voxy$beginCombinedDepth(
            Iterable<ClientSubLevel> subLevels,
            RenderType renderType,
            ShaderInstance shader,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            float partialTicks,
            CallbackInfo ci
    ) {
        // Sable calls renderSectionLayer for every RenderType layer every frame even
        // when there are no sub-levels to draw; running the shim then costs several
        // full-screen depth blits per layer per frame for zero visual benefit.
        // end() is deliberately NOT gated: it is a no-op when begin() was skipped,
        // and gating it risks leaking activeState if the two callbacks ever observed
        // different iterable instances.
        if (isEmptySubLevelIterable(subLevels)) {
            return;
        }
        VoxySableDepthShim.begin(modelView, projection);
    }

    @Inject(method = "renderSectionLayer", at = @At("RETURN"))
    private void voxy$endCombinedDepth(
            Iterable<ClientSubLevel> subLevels,
            RenderType renderType,
            ShaderInstance shader,
            double cameraX,
            double cameraY,
            double cameraZ,
            Matrix4f modelView,
            Matrix4f projection,
            float partialTicks,
            CallbackInfo ci
    ) {
        VoxySableDepthShim.end();
    }

    @Unique
    private static boolean isEmptySubLevelIterable(Iterable<ClientSubLevel> subLevels) {
        return subLevels instanceof java.util.Collection<?> collection
                ? collection.isEmpty()
                : !subLevels.iterator().hasNext();
    }
}
