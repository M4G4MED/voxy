package me.cortex.voxy.client.mixin.minecraft;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.gl.Capabilities;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

import java.io.PrintWriter;
import java.io.StringWriter;

// Ported from cortex 12111-line MixinGlDebug (upstream enabled it in client.voxy.mixins.json).
// 1.21.1 adaptation: GlDebug lives in com.mojang.blaze3d.platform (not blaze3d.opengl), and
// GlDebug$LogEntry is package-private there, so the log entry is matched by class name and
// stringified instead of typed.
// Behavior: when a GL debug message was emitted from within voxy code, log it with the full java
// stack trace (so GL spam is attributable); suppress the expected debug noise from
// Capabilities.testShaderCompilesOk feature probing entirely.
@Mixin(com.mojang.blaze3d.platform.GlDebug.class)
public class MixinGlDebug {
    // printDebugLog is private static in 1.21.1 -> the WrapOperation callback must be static
    @WrapOperation(method = "printDebugLog", at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;info(Ljava/lang/String;Ljava/lang/Object;)V", remap = false))
    private static void voxy$wrapDebug(Logger instance, String base, Object msgObj, Operation<Void> original) {
        if (isGlDebugLogEntry(msgObj)) {
            var throwable = new Throwable(String.valueOf(msgObj));
            if (isCausedByVoxy(throwable.getStackTrace())) {
                if (!isCausedByShaderCompileTest(throwable.getStackTrace())) {
                    original.call(instance, base + "\n" + getStackTraceAsString(throwable), throwable);
                }
            } else {
                original.call(instance, base, msgObj);
            }
        } else {
            original.call(instance, base, msgObj);
        }
    }

    @Unique
    private static boolean isGlDebugLogEntry(Object msgObj) {
        //GlDebug$LogEntry is package-private in 1.21.1 -> match by class name
        return msgObj != null && msgObj.getClass().getName().startsWith("com.mojang.blaze3d.platform.GlDebug$LogEntry");
    }

    @Unique
    private static String getStackTraceAsString(Throwable throwable) {
        StringWriter sw = new StringWriter();
        PrintWriter pw = new PrintWriter(sw);
        throwable.printStackTrace(pw);
        return sw.toString();
    }

    @Unique
    private static boolean isCausedByVoxy(StackTraceElement[] trace) {
        for (var elem : trace) {
            if (elem.getClassName().startsWith("me.cortex.voxy")) {
                return true;
            }
        }
        return false;
    }

    @Unique
    private static boolean isCausedByShaderCompileTest(StackTraceElement[] trace) {
        for (var elem : trace) {
            if (elem.getClassName().equals(Capabilities.class.getName()) && elem.getMethodName().equals("testShaderCompilesOk")) {
                return true;
            }
        }
        return false;
    }
}
