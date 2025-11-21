package io.github.mwarevn.movingsimulation.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig

class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        XposedBridge.log("GPS Setter HookEntry: Loading package ${lpparam.packageName}")
        
        // Hook our own app to bypass Xposed detection
        if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
            XposedBridge.log("GPS Setter HookEntry: Hooking own app ${lpparam.packageName}")
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
                XposedBridge.log("GPS Setter HookEntry: Successfully hooked MainViewModel")
            } catch (e: Throwable) {
                XposedBridge.log("GPS Setter HookEntry: Failed to hook MainViewModel: ${e.message}")
            }
            return
        }

        // Hook system server ONLY if explicitly enabled in settings
        // This is DANGEROUS and can cause bootloop if done incorrectly
        // By default, we only hook at app level (safe)
        if (lpparam.packageName == "android") {
            XposedBridge.log("GPS Setter HookEntry: Hooking system server (android)")
            LocationHook.initHooks(lpparam)
            return
        }

        // For all other apps (including banking apps)
        // Initialize anti-detection hooks FIRST to hide our presence
        XposedBridge.log("GPS Setter HookEntry: Initializing hooks for app ${lpparam.packageName}")
        
        // Check GPS state BEFORE initializing hooks
        val settings = Xshare()
        XposedBridge.log("GPS Setter HookEntry: GPS started=${settings.isStarted} for package ${lpparam.packageName}")
        
        AntiDetection.initAntiDetection(lpparam)

        // Then initialize location hooks at app level (safe, no system hook)
        LocationHook.initHooks(lpparam)
        
        XposedBridge.log("GPS Setter HookEntry: Completed initialization for package ${lpparam.packageName}")
    }
}