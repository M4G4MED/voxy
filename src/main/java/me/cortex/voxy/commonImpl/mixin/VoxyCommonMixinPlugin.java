package me.cortex.voxy.commonImpl.mixin;

import me.cortex.voxy.commonImpl.VoxyCommon;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public class VoxyCommonMixinPlugin implements IMixinConfigPlugin {
    private static boolean sableInstalled;

    @Override
    public void onLoad(String mixinPackage) {
        sableInstalled = VoxyCommon.getPlatformUtil().isModLoaded("sable");
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) { return true; }

    @Override
    public List<String> getMixins() {
        // Sable sub-level compat is 1.21.1-only (the classes live in the
        // versions/1.21.1 source layer and target dev.ryanhcode.sable types),
        // so only list them when Sable is actually installed.
        if (!sableInstalled) {
            return List.of();
        }
        List<String> mixins = new ArrayList<>();
        mixins.add("minecraft.MixinServerLevel");
        mixins.add("sable.MixinPhysicsChunkTicketManager");
        mixins.add("sable.MixinSubLevelHoldingChunk");
        mixins.add("sable.MixinSubLevelHoldingChunkMap");
        mixins.add("sable.SableSubLevelHoldingChunkMapAccessor");
        mixins.add("sable.MixinSubLevelTrackingSystem");
        // Client-only target; never apply on a dedicated server.
        if (!VoxyCommon.IS_DEDICATED_SERVER) {
            mixins.add("sable.MixinClientSubLevelFinalizeLighting");
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
