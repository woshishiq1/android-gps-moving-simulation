package io.github.mwarevn.movingsimulation.xposed

import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import io.github.mwarevn.movingsimulation.BuildConfig

class HookEntry : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        // Skip system apps to avoid breaking the system
        if (lpparam.packageName == "android" || lpparam.packageName == BuildConfig.APPLICATION_ID) {
            if (lpparam.packageName == BuildConfig.APPLICATION_ID) {
                XposedHelpers.findAndHookMethod("io.github.mwarevn.movingsimulation.ui.viewmodel.MainViewModel", lpparam.classLoader, "updateXposedState", object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        param.result = null
                    }
                })
            }
            LocationHook.initHooks(lpparam)
            return
        }

        // Initialize anti-detection hooks FIRST to hide our presence
        AntiDetection.initAntiDetection(lpparam)

        // Then initialize location hooks
        LocationHook.initHooks(lpparam)
    }
}