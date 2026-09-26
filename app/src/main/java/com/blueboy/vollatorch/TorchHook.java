package com.blueboy.vollatorch;

import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;

import java.io.OutputStream;
import java.net.Socket;
import java.net.InetSocketAddress;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * VollaTorchStrength — Xposed/LSPosed module.
 *
 * The Volla Quintus (algiz) A16 ROM ships the camera device HAL's
 * getTorchStrengthLevel / turnOnTorchWithStrengthLevel as ENOSYS stubs, and its
 * static metadata reports FLASH_INFO_STRENGTH_MAXIMUM_LEVEL = 1, so no torch
 * brightness slider appears / works. The flash LEDs themselves accept 0..31 on
 * /sys/class/leds/mt6360_flash_ch1|ch2/brightness (verified on-device).
 *
 * This module works purely in the Java framework, so it never touches the broken
 * vendor HAL and never affects camera enumeration:
 *
 *  1) Hook CameraManager.getCameraCharacteristics -> overwrite
 *     FLASH_INFO_STRENGTH_MAXIMUM_LEVEL (and DEFAULT_LEVEL) so apps (FlashDim,
 *     SystemUI QS) show a real slider with MAX steps.
 *
 *  2) Hook CameraManager.turnOnTorchWithStrengthLevel(String,int) -> swallow the
 *     call (the vendor HAL would throw) and instead ask a small root daemon to
 *     write the level onto both flash sysfs channels. Also hook setTorchMode to
 *     map plain on/off to full / zero.
 *
 * The root daemon listens on an abstract Unix domain socket (event-driven, no
 * polling) and is started by the companion KernelSU module.
 */
public class TorchHook implements IXposedHookLoadPackage {

    // must match the daemon
    private static final String SOCKET_NAME = "vollatorchd";
    private static final int MAX_LEVEL = 31; // = mt6360_flash_chX max_brightness

    // ANDROID_FLASH_INFO_STRENGTH_MAXIMUM_LEVEL / DEFAULT_LEVEL keys.
    // These CameraCharacteristics.Key constants exist since API 33 (Android 13).
    // We resolve them reflectively so the module builds/runs even if the SDK
    // constant name differs across platform versions.

    @Override
    public void handleLoadPackage(final LoadPackageParam lpparam) {
        // Hook everywhere CameraManager is used (system_server for SystemUI QS,
        // and normal apps like FlashDim). Cheap: we only install method hooks.
        hookCharacteristics(lpparam);
        hookTorch(lpparam);
    }

    private void hookCharacteristics(final LoadPackageParam lpparam) {
        try {
            XposedHelpers.findAndHookMethod(
                CameraManager.class, "getCameraCharacteristics", String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object chars = param.getResult();
                        if (chars == null) return;
                        try {
                            Object maxKey = getKey("FLASH_INFO_STRENGTH_MAXIMUM_LEVEL");
                            Object defKey = getKey("FLASH_INFO_STRENGTH_DEFAULT_LEVEL");
                            if (maxKey != null) {
                                overrideKey(chars, maxKey, MAX_LEVEL);
                            }
                            if (defKey != null) {
                                overrideKey(chars, defKey, 1);
                            }
                        } catch (Throwable t) {
                            XposedBridge.log("[VollaTorch] characteristics override failed: " + t);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log("[VollaTorch] hookCharacteristics failed: " + t);
        }
    }

    private void hookTorch(final LoadPackageParam lpparam) {
        // turnOnTorchWithStrengthLevel(String cameraId, int strength) — API 33+
        try {
            XposedHelpers.findAndHookMethod(
                CameraManager.class, "turnOnTorchWithStrengthLevel",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        int level = (int) param.args[1];
                        if (level < 1) level = 1;
                        if (level > MAX_LEVEL) level = MAX_LEVEL;
                        if (writeLevel(level)) {
                            // vendor HAL would throw ENOSYS; we handled it.
                            param.setResult(null);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log("[VollaTorch] hook turnOnTorchWithStrengthLevel failed: " + t);
        }

        // setTorchMode(String cameraId, boolean enabled) — map to full / off so the
        // plain toggle still uses our path and stays consistent with the slider.
        try {
            XposedHelpers.findAndHookMethod(
                CameraManager.class, "setTorchMode",
                String.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        boolean on = (boolean) param.args[1];
                        // Only intercept for the main (rear) camera id "0"; let other
                        // ids fall through untouched.
                        Object idObj = param.args[0];
                        if (!"0".equals(String.valueOf(idObj))) return;
                        if (writeLevel(on ? MAX_LEVEL : 0)) {
                            param.setResult(null);
                        }
                    }
                });
        } catch (Throwable t) {
            XposedBridge.log("[VollaTorch] hook setTorchMode failed: " + t);
        }
    }

    private static Object getKey(String name) {
        try {
            java.lang.reflect.Field f = CameraCharacteristics.class.getField(name);
            return f.get(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /** Force a value into the (immutable) CameraCharacteristics result map. */
    @SuppressWarnings("unchecked")
    private static void overrideKey(Object chars, Object key, int value) {
        // CameraMetadataNative backing object holds the values. The public API is
        // read-only, so we reach the private CameraMetadataNative and set the vendor
        // key. Simplest robust route: wrap get() via a per-instance override map.
        // CameraCharacteristics stores results in a CameraMetadataNative field "mProperties".
        try {
            Object nativeMeta = XposedHelpers.getObjectField(chars, "mProperties");
            // CameraMetadataNative.set(Key, T)
            XposedHelpers.callMethod(nativeMeta, "set", key, value);
        } catch (Throwable t) {
            XposedBridge.log("[VollaTorch] overrideKey via mProperties failed, trying set(): " + t);
            try {
                XposedHelpers.callMethod(chars, "set", key, value);
            } catch (Throwable t2) {
                XposedBridge.log("[VollaTorch] overrideKey fallback failed: " + t2);
            }
        }
    }

    /** Ask the root daemon to write `level` to both flash channels. */
    private static boolean writeLevel(int level) {
        try {
            // Abstract namespace socket: prefix with '\0'.
            android.net.LocalSocket sock = new android.net.LocalSocket();
            sock.connect(new android.net.LocalSocketAddress(
                    SOCKET_NAME, android.net.LocalSocketAddress.Namespace.ABSTRACT));
            OutputStream os = sock.getOutputStream();
            os.write((Integer.toString(level) + "\n").getBytes());
            os.flush();
            sock.close();
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[VollaTorch] daemon write failed (level=" + level + "): " + t);
            return false;
        }
    }
}
