package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.ClientSessionEvents;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MixinMinecraft {
    // Quit-to-title in 1.20.1/1.21.1 goes through disconnect(Screen) or
    // disconnect(Screen, boolean); the old {"disconnect","clearLevel"} target with
    // expect=1 only ever bound the no-arg disconnect() (called at game exit), so the
    // Voxy instance — and the per-world RocksDB LOCK under saves/<world>/voxy/ —
    // stayed open on the world selection screen, making in-game world deletion fail
    // with FileSystemException on LOCK.
    // Each overload gets its own injection (require=1 each) so a missing descriptor
    // fails fast at mixin apply instead of silently falling back to one binding.
    // sessionEnd is idempotency-guarded by inSession for the delegating overloads.
    @Inject(method = "disconnect()V", at = @At("TAIL"))
    private void voxy$injectWorldCloseNoArg(CallbackInfo ci) {
        this.voxy$endSessionIfInOne();
    }

    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;)V", at = @At("TAIL"))
    private void voxy$injectWorldCloseScreen(CallbackInfo ci) {
        this.voxy$endSessionIfInOne();
    }

    //? if 1.21.1 {
    // The (Screen, boolean) overload only exists from 1.20.3 onwards.
    @Inject(method = "disconnect(Lnet/minecraft/client/gui/screens/Screen;Z)V", at = @At("TAIL"))
    private void voxy$injectWorldCloseScreenBool(CallbackInfo ci) {
        this.voxy$endSessionIfInOne();
    }
    //? }

    private void voxy$endSessionIfInOne() {
        if (ClientSessionEvents.inSession) {
            ClientSessionEvents.sessionEnd();
        }
    }
}
