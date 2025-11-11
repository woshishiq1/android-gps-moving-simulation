package io.github.mwarevn.movingsimulation.xposed

import android.content.pm.ApplicationInfo
import android.os.Build
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.reflect.Member

/**
 * Anti-detection hooks to hide LSposed/Xposed framework presence from target apps
 * Optimized to minimize performance impact
 */
object AntiDetection {

    private val XPOSED_CLASS_NAMES = setOf(
        "de.robv.android.xposed.XposedBridge",
        "de.robv.android.xposed.XposedHelpers",
        "de.robv.android.xposed.XC_MethodHook",
        "de.robv.android.xposed.IXposedHookLoadPackage",
        "de.robv.android.xposed.callbacks.XC_LoadPackage",
        "io.github.libxposed.api.XposedInterface",
        "io.github.libxposed.api.XposedModule",
        "org.lsposed.lspd.core.Startup",
        "org.lsposed.lspd.impl.LSPosedContext"
    )

    private val SUSPICIOUS_PACKAGES = setOf(
        "de.robv.android.xposed.installer",
        "io.github.lsposed.manager",
        "org.meowcat.edxposed.manager"
    )

    // Cache for class name checks to avoid repeated string operations
    private val classNameCache = mutableMapOf<String, Boolean>()

    // Fast check using cache
    private fun isXposedClassName(className: String): Boolean {
        return classNameCache.getOrPut(className) {
            XPOSED_CLASS_NAMES.any { className.startsWith(it) } ||
                    className.contains(".xposed.", ignoreCase = true) ||
                    className.contains(".lsposed.", ignoreCase = true)
        }
    }

    fun initAntiDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Core hooks - essential for bypassing detection
            hookClassForName(lpparam)
            hookClassLoader(lpparam)  // Some apps use ClassLoader directly
            hookStackTrace(lpparam)   // Stack trace inspection is common
            hookBuildFields(lpparam)
            hookPackageManager(lpparam)

            // Optional hooks - enable if still detected
            // hookSystemProperties(lpparam)
            // hookApplicationInfo(lpparam)
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection init failed: ${e.message}")
        }
    }

    /**
     * Hook Class.forName() to prevent detection of Xposed classes
     * Optimized: Fast path for non-Xposed classes
     */
    private fun hookClassForName(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook 3-arg version (most common)
            XposedHelpers.findAndHookMethod(
                Class::class.java,
                "forName",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                ClassLoader::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        // Fast check - most class names won't match
                        if (isXposedClassName(className)) {
                            param.throwable = ClassNotFoundException(className)
                        }
                    }
                }
            )

            // Hook 1-arg version
            XposedHelpers.findAndHookMethod(
                Class::class.java,
                "forName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        if (isXposedClassName(className)) {
                            param.throwable = ClassNotFoundException(className)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: Failed to hook Class.forName: ${e.message}")
        }
    }

    /**
     * Hook ClassLoader.loadClass() to prevent detection
     * Optimized: Fast path with cache
     */
    private fun hookClassLoader(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val className = param.args[0] as? String ?: return
                        // Fast check with cache
                        if (isXposedClassName(className)) {
                            param.throwable = ClassNotFoundException(className)
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: Failed to hook ClassLoader: ${e.message}")
        }
    }

    /**
     * Hook stack trace methods to remove Xposed frames
     * Optimized: Only process if Xposed frames detected
     */
    private fun hookStackTrace(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook Thread.getStackTrace()
            XposedHelpers.findAndHookMethod(
                Thread::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val originalTrace = param.result as? Array<StackTraceElement> ?: return
                        // Quick scan first - avoid filter if no Xposed frames
                        if (!hasXposedFrames(originalTrace)) return

                        val cleanedTrace = originalTrace.filterNot { isXposedStackFrame(it) }.toTypedArray()
                        if (cleanedTrace.size != originalTrace.size) {
                            param.result = cleanedTrace
                        }
                    }
                }
            )

            // Hook Throwable.getStackTrace()
            XposedHelpers.findAndHookMethod(
                Throwable::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val originalTrace = param.result as? Array<StackTraceElement> ?: return
                        // Quick scan first
                        if (!hasXposedFrames(originalTrace)) return

                        val cleanedTrace = originalTrace.filterNot { isXposedStackFrame(it) }.toTypedArray()
                        if (cleanedTrace.size != originalTrace.size) {
                            param.result = cleanedTrace
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: Failed to hook stack trace: ${e.message}")
        }
    }

    // Quick check if array contains any Xposed frames (faster than filtering)
    private fun hasXposedFrames(trace: Array<StackTraceElement>): Boolean {
        for (element in trace) {
            val className = element.className
            if (className.contains("xposed", ignoreCase = true) ||
                className.contains("lsposed", ignoreCase = true)) {
                return true
            }
        }
        return false
    }

    private fun isXposedStackFrame(element: StackTraceElement): Boolean {
        val className = element.className
        return isXposedClassName(className) ||
                className.contains("xposed", ignoreCase = true) ||
                className.contains("lsposed", ignoreCase = true) ||
                className.contains("edxposed", ignoreCase = true)
    }

    /**
     * Hook Build class fields to hide suspicious tags
     * Simple static field replacement - no performance impact
     */
    private fun hookBuildFields(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Simply replace the static field value once at load time
            val tagsField = XposedHelpers.findField(Build::class.java, "TAGS")
            val currentTags = tagsField.get(null) as? String ?: return
            if (currentTags.contains("test-keys", ignoreCase = true)) {
                XposedHelpers.setStaticObjectField(
                    Build::class.java,
                    "TAGS",
                    currentTags.replace("test-keys", "release-keys", ignoreCase = true)
                )
            }
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: Failed to hook Build fields: ${e.message}")
        }
    }

    /**
     * Hook PackageManager to hide Xposed-related packages
     * Optimized: Only filter when result contains suspicious packages
     */
    private fun hookPackageManager(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader)

            // Hook getInstalledApplications
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getInstalledApplications",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val apps = param.result as? List<ApplicationInfo> ?: return
                        // Only filter if list is not empty and might contain suspicious apps
                        if (apps.size < 10) return // Skip if too few apps (likely filtered already)
                        param.result = apps.filterNot { app ->
                            SUSPICIOUS_PACKAGES.contains(app.packageName)
                        }
                    }
                }
            )

            // Hook getPackageInfo - throw exception for suspicious packages
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String ?: return
                        if (SUSPICIOUS_PACKAGES.contains(packageName)) {
                            val exceptionClass = XposedHelpers.findClass(
                                "android.content.pm.PackageManager" + "$" + "NameNotFoundException",
                                lpparam.classLoader
                            )
                            param.throwable = XposedHelpers.newInstance(exceptionClass, packageName) as Throwable
                        }
                    }
                }
            )

            // Hook getApplicationInfo - throw exception for suspicious packages
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getApplicationInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val packageName = param.args[0] as? String ?: return
                        if (SUSPICIOUS_PACKAGES.contains(packageName)) {
                            val exceptionClass = XposedHelpers.findClass(
                                "android.content.pm.PackageManager" + "$" + "NameNotFoundException",
                                lpparam.classLoader
                            )
                            param.throwable = XposedHelpers.newInstance(exceptionClass, packageName) as Throwable
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: Failed to hook PackageManager: ${e.message}")
        }
    }
}
