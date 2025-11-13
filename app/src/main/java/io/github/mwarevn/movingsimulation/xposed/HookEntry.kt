package io.github.mwarevn.movingsimulation.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig

class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Hook our own app to bypass Xposed detection
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
            try {
                XposedHelpers.findAndHookMethod(
                    "io.github.mwarevn.movingsimulation.ui.viewmodel.MainViewModel",
                    lpparam.classLoader,
                    "updateXposedState",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = null
                        }
                    }
                )
            } catch (e: Throwable) {
                XposedBridge.log("Failed to hook MainViewModel: ${e.message}")
            }
            return
        }

        // Hook system server ONLY if explicitly enabled in settings
        // This is DANGEROUS and can cause bootloop if done incorrectly
        // By default, we only hook at app level (safe)
        if (lpparam.packageName == "android") {
            LocationHook.initHooks(lpparam)
            return
        }

        // For all other apps (including banking apps)
        // Initialize anti-detection hooks FIRST to hide our presence
        AntiDetection.initAntiDetection(lpparam)

        // Then initialize location hooks at app level (safe, no system hook)
        LocationHook.initHooks(lpparam)
    }
}