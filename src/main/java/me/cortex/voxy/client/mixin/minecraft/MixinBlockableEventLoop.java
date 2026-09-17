package me.cortex.voxy.client.mixin.minecraft;

import me.cortex.voxy.client.LoadException;
import net.minecraft.util.thread.BlockableEventLoop;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

// Ported from cortex 12111-line MixinBlockableEventLoop (upstream commit ea7d45db era, force-crash on LoadException).
// Upstream @Redirects BlockableEventLoop.isNonRecoverable, but MC 1.21.1 has no isNonRecoverable —
// doRunTask just catch(Exception) + LOGGER.error(FATAL_MARKER, "Error executing task on {}").
// Instead we wrap the Runnable.run() call itself and rethrow LoadException as an Error, which the
// catch(Exception) in doRunTask cannot swallow, so it propagates to the client loop's crash handler.
// Without this, a Voxy LoadException during login (see MixinClientCommonPacketListenerImpl) only
// spams the log forever instead of crashing with a real crash report.
@Mixin(BlockableEventLoop.class)
public abstract class MixinBlockableEventLoop {

    @Redirect(method = "doRunTask", at = @At(value = "INVOKE", target = "Ljava/lang/Runnable;run()V"))
    private void voxy$forceCrashOnLoadError(Runnable task) {
        try {
            task.run();
        } catch (LoadException loadException) {
            //Rethrow as an Error so doRunTask's catch(Exception) cannot eat it -> game crash screen
            VoxyLoadError error = new VoxyLoadError(loadException.getMessage());
            error.setStackTrace(loadException.getStackTrace());
            if (loadException.getCause() != null) error.initCause(loadException.getCause());
            throw error;
        }
    }

    public static class VoxyLoadError extends Error {
        public VoxyLoadError(String message) {
            super(message);
        }
    }
}
