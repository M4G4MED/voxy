package me.cortex.voxy.client.mixin;

import me.cortex.voxy.commonImpl.VoxyCommon;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class ClientVoxyMixinPlugin implements IMixinConfigPlugin {
    private static boolean valkyrienSkiesInstalled;
    private static boolean nvidiumInstalled;
    private static boolean connectorInstalled;
    private static boolean sableInstalled;
    private static boolean sodiumInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        valkyrienSkiesInstalled = VoxyCommon.getPlatformUtil().isModLoaded("valkyrienskies");
        nvidiumInstalled = VoxyCommon.getPlatformUtil().isModLoaded("nvidium");
        connectorInstalled = VoxyCommon.getPlatformUtil().isModLoaded("connector");
        sableInstalled = VoxyCommon.getPlatformUtil().isModLoaded("sable");
        sodiumInstalled = VoxyCommon.getPlatformUtil().isModLoaded("sodium");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override public List<String> getMixins() {
        List<String> mixins = new ArrayList<>();
        if (valkyrienSkiesInstalled && !nvidiumInstalled) {
            mixins.add("sodium.MixinSodiumWorldRendererVS");
        } else {
            mixins.add("sodium.MixinDefaultChunkRenderer");
        }

        if (connectorInstalled) {
            mixins.add("sodium.MixinShaderLoader");
        }

        // Sable contraption/sub-level compat is 1.21.1 only: the mixin classes
        // live in the versions/1.21.1 source layer and are absent from the
        // 1.20.1 jar, so only add them when Sable is actually present.
        if (sableInstalled) {
            mixins.add("minecraft.MixinGameRendererSableRenderDistance");
            mixins.add("sable.MixinSableReacharoundCulling");
            mixins.add("sable.MixinSableDepthShim");
            if (sodiumInstalled) {
                mixins.add("sable.MixinSableSubLevelRenderSectionManager");
            }
        }

        return mixins;
    }

    @Override
    public String getRefMapperConfig() { return null; }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
