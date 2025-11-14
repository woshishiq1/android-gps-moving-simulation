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

    // SAFE: Use strict exact matching, not contains()
    // contains() might block legitimate classes like "com.example.exposed"
    private fun isXposedClassName(className: String): Boolean {
        return classNameCache.getOrPut(className) {
            // Use exact match from whitelist
            XPOSED_CLASS_NAMES.contains(className) ||
            // Or strict startsWith for packages
            XPOSED_CLASS_NAMES.any { className.startsWith("$it.") }
        }
    }

    fun initAntiDetection(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Read user preferences for each hook
            val settings = XshareAntiDetection()

            // TIER 1: SAFE hooks (recommended - enabled by default)
            if (settings.isHookIsFromMockProvider) {
                hookIsFromMockProvider(lpparam)
            }
            if (settings.isHookMockLocationCheck) {
                hookMockLocationCheck(lpparam)
            }
            if (settings.isHookBuildFields) {
                hookBuildFields(lpparam)
            }
            if (settings.isHookStackTrace) {
                hookStackTrace(lpparam)
            }

            // TIER 2: MODERATE risk hooks (safe implementation - enabled by default)
            if (settings.isHookPackageManagerSafe) {
                hookPackageManagerSafe(lpparam)
            }
            if (settings.isHookClassLoaderSafe) {
                hookClassLoaderSafe(lpparam)
            }
            if (settings.isHookApplicationInfo) {
                hookApplicationInfoSafe(lpparam)
            }
            if (settings.isHookSystemProperties) {
                hookSystemPropertiesSafe(lpparam)
            }

            // TIER 3: RISKY hooks (aggressive - disabled by default)
            if (settings.isHookClassForName) {
                hookClassForName(lpparam)
            }
            if (settings.isHookClassLoader) {
                hookClassLoader(lpparam)
            }
            if (settings.isHookPackageManager) {
                hookPackageManager(lpparam)
            }
            if (settings.isHookNativeLibrary) {
                hookNativeLibrary(lpparam)
            }
            if (settings.isHookMapView) {
                hookMapView(lpparam)
            }

        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection init failed: ${e.message}")
        }
    }

    /**
     * Hook Class.forName() to prevent detection
     * ✅ WORKING VERSION: Per-app hook installed AFTER ClassLoader creation
     *
     * Solution verified from LSPosed architecture:
     * - Hook is installed PER-APP in LoadedApk.createClassLoader callback
     * - Uses app's ClassLoader context (not global Zygote)
     * - Install at Application.attach() - early but safe
     *
     * Why this works without bootloop:
     * 1. Hooks are per-process, not global
     * 2. Installed AFTER app's ClassLoader is ready
     * 3. System server and Zygote are explicitly skipped
     * 4. Each app gets its own hook instance (isolated)
     */
    private fun hookClassForName(lpparam: XC_LoadPackage.LoadPackageParam) {
        // CRITICAL: Skip system server
        if (lpparam.packageName == "android") {
            return
        }

        // Skip critical apps
        val criticalApps = setOf(
            "com.android.systemui",
            "com.android.phone",
            "com.android.settings"
        )
        if (criticalApps.contains(lpparam.packageName)) {
            return
        }

        try {
            // Hook Application.attach() - this is PER-APP, not global
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    @Volatile
                    private var hooked = false

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (hooked) return
                        hooked = true

                        try {
                            XposedBridge.log("AntiDetection: Installing Class.forName hook for ${lpparam.packageName}")
                            installClassForNameHook(lpparam)
                        } catch (e: Throwable) {
                            XposedBridge.log("AntiDetection: Failed to install Class.forName hook: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: Failed to hook Application.attach: ${e.message}")
        }
    }

    /**
     * Install Class.forName hook PER-APP (safe, no bootloop)
     * Called from Application.attach() - app-specific context
     *
     * Why this is safe:
     * - Hook is scoped to THIS app's process only
     * - Installed after app's ClassLoader is created
     * - System classes are whitelisted
     * - Errors are caught and logged without crashing
     */
    private fun installClassForNameHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Whitelist of EXACT Xposed class names to block
            val blockedClasses = setOf(
                "de.robv.android.xposed.XposedBridge",
                "de.robv.android.xposed.XposedHelpers",
                "de.robv.android.xposed.XC_MethodHook",
                "de.robv.android.xposed.XC_MethodReplacement",
                "de.robv.android.xposed.IXposedHookLoadPackage",
                "de.robv.android.xposed.IXposedHookInitPackageResources",
                "de.robv.android.xposed.IXposedHookZygoteInit",
                "de.robv.android.xposed.callbacks.XC_LoadPackage",
                "de.robv.android.xposed.callbacks.XCallback",
                "io.github.libxposed.api.XposedInterface",
                "io.github.libxposed.api.XposedModule",
                "io.github.libxposed.api.XposedModuleInterface",
                "org.lsposed.lspd.core.Startup",
                "org.lsposed.lspd.impl.LSPosedContext",
                "org.lsposed.lspd.service.LSPosedService",
                "org.lsposed.lspd.models.Module"
            )

            // 🔴 STRONG WHITELIST: NEVER block these (100% bootloop prevention)
            val systemClassPrefixes = setOf(
                "android.",
                "androidx.",
                "com.android.",
                "java.",
                "javax.",
                "dalvik.",
                "libcore.",
                "org.apache.",
                "org.json.",
                "org.xml.",
                "org.xmlpull.",
                "kotlin.",
                "kotlinx.",
                "com.google.android."
            )

            // 🔴 SAFETY: Track failed blocks and error count
            val failedBlocks = mutableSetOf<String>()
            var hookErrorCount = 0
            val MAX_ERRORS = 10

            // Hook Class.forName(String, boolean, ClassLoader) - 3-arg version
            XposedHelpers.findAndHookMethod(
                Class::class.java,
                "forName",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                ClassLoader::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            // 🔴 FAIL-SAFE: Stop blocking if too many errors
                            if (hookErrorCount >= MAX_ERRORS) {
                                return
                            }

                            val className = param.args[0] as? String ?: return

                            // Skip if known to cause issues
                            if (failedBlocks.contains(className)) {
                                return
                            }

                            // 🔴 WHITELIST: Allow ALL system classes (never block)
                            for (prefix in systemClassPrefixes) {
                                if (className.startsWith(prefix)) {
                                    return
                                }
                            }

                            // EXACT match check
                            if (blockedClasses.contains(className)) {
                                param.throwable = ClassNotFoundException(className)
                                return
                            }

                            // PREFIX check for Xposed packages
                            if (className.startsWith("de.robv.android.xposed.") ||
                                className.startsWith("org.lsposed.lspd.") ||
                                className.startsWith("io.github.libxposed.")) {

                                // Inner class safety check
                                if (className.contains("$")) {
                                    val outerClass = className.substringBefore("$")
                                    if (!outerClass.startsWith("de.robv.android.xposed.") &&
                                        !outerClass.startsWith("org.lsposed.lspd.") &&
                                        !outerClass.startsWith("io.github.libxposed.")) {
                                        return // Outer is safe, allow inner
                                    }
                                }

                                param.throwable = ClassNotFoundException(className)
                            }

                        } catch (t: Throwable) {
                            // 🔴 SAFETY: Track errors and auto-whitelist
                            hookErrorCount++
                            val className = param.args[0] as? String
                            if (className != null) {
                                failedBlocks.add(className)
                                if (hookErrorCount < MAX_ERRORS) {
                                    XposedBridge.log("AntiDetection: Error blocking $className (${hookErrorCount}/${MAX_ERRORS})")
                                }
                            }
                        }
                    }
                }
            )

            // Hook Class.forName(String) - 1-arg version
            XposedHelpers.findAndHookMethod(
                Class::class.java,
                "forName",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            // 🔴 FAIL-SAFE: Stop if too many errors
                            if (hookErrorCount >= MAX_ERRORS) {
                                return
                            }

                            val className = param.args[0] as? String ?: return

                            if (failedBlocks.contains(className)) {
                                return
                            }

                            // 🔴 WHITELIST: Allow system classes
                            for (prefix in systemClassPrefixes) {
                                if (className.startsWith(prefix)) {
                                    return
                                }
                            }

                            // Same logic as 3-arg version
                            if (blockedClasses.contains(className)) {
                                param.throwable = ClassNotFoundException(className)
                                return
                            }

                            if (className.startsWith("de.robv.android.xposed.") ||
                                className.startsWith("org.lsposed.lspd.") ||
                                className.startsWith("io.github.libxposed.")) {

                                if (className.contains("$")) {
                                    val outerClass = className.substringBefore("$")
                                    if (!outerClass.startsWith("de.robv.android.xposed.") &&
                                        !outerClass.startsWith("org.lsposed.lspd.") &&
                                        !outerClass.startsWith("io.github.libxposed.")) {
                                        return
                                    }
                                }

                                param.throwable = ClassNotFoundException(className)
                            }

                        } catch (t: Throwable) {
                            hookErrorCount++
                            val className = param.args[0] as? String
                            if (className != null) {
                                failedBlocks.add(className)
                            }
                        }
                    }
                }
            )

            XposedBridge.log("AntiDetection: ✅ Class.forName() hooks installed (IMMEDIATE mode with safety checks)")

        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: ❌ Failed to install immediate hooks: ${e.message}")
        }
    }

    /**
     * Hook ClassLoader.loadClass() to prevent detection
     * ✅ WORKING VERSION: Per-app hook installed AFTER ClassLoader creation
     *
     * Same safe approach as Class.forName hook
     */
    private fun hookClassLoader(lpparam: XC_LoadPackage.LoadPackageParam) {
        // CRITICAL: Skip system server
        if (lpparam.packageName == "android") {
            return
        }

        // Skip critical apps
        val criticalApps = setOf(
            "com.android.systemui",
            "com.android.phone",
            "com.android.settings"
        )
        if (criticalApps.contains(lpparam.packageName)) {
            return
        }

        try {
            // Hook Application.attach() - this is PER-APP, not global
            XposedHelpers.findAndHookMethod(
                "android.app.Application",
                lpparam.classLoader,
                "attach",
                android.content.Context::class.java,
                object : XC_MethodHook() {
                    @Volatile
                    private var hooked = false

                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (hooked) return
                        hooked = true

                        try {
                            XposedBridge.log("AntiDetection: Installing ClassLoader hook for ${lpparam.packageName}")
                            installClassLoaderHook(lpparam)
                        } catch (e: Throwable) {
                            XposedBridge.log("AntiDetection: Failed to install ClassLoader hook: ${e.message}")
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: Failed to hook Application.attach: ${e.message}")
        }
    }

    /**
     * Install ClassLoader hook PER-APP (safe, no bootloop)
     * Called from Application.attach() - app-specific context
     */
    private fun installClassLoaderHook(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Whitelist of exact Xposed classes to block
            val blockedClasses = setOf(
                "de.robv.android.xposed.XposedBridge",
                "de.robv.android.xposed.XposedHelpers",
                "de.robv.android.xposed.XC_MethodHook",
                "de.robv.android.xposed.XC_MethodReplacement",
                "io.github.libxposed.api.XposedInterface",
                "io.github.libxposed.api.XposedModule",
                "org.lsposed.lspd.core.Startup",
                "org.lsposed.lspd.impl.LSPosedContext"
            )

            // 🔴 STRONG WHITELIST: NEVER block these (100% bootloop prevention)
            val systemClassPrefixes = setOf(
                "android.",
                "androidx.",
                "com.android.",
                "java.",
                "javax.",
                "dalvik.",
                "libcore.",
                "kotlin.",
                "kotlinx.",
                "com.google.android."
            )

            // 🔴 SAFETY: Track failed blocks and error count
            val failedBlocks = mutableSetOf<String>()
            var hookErrorCount = 0
            val MAX_ERRORS = 10

            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            // 🔴 FAIL-SAFE: Stop blocking if too many errors
                            if (hookErrorCount >= MAX_ERRORS) {
                                return
                            }

                            val className = param.args[0] as? String ?: return

                            // Skip known critical classes
                            if (failedBlocks.contains(className)) {
                                return
                            }

                            // 🔴 WHITELIST: Allow ALL system classes
                            for (prefix in systemClassPrefixes) {
                                if (className.startsWith(prefix)) {
                                    return // Allow system classes
                                }
                            }

                            // EXACT match check
                            if (blockedClasses.contains(className)) {
                                param.throwable = ClassNotFoundException(className)
                                return
                            }

                            // PREFIX check for Xposed packages
                            if (className.startsWith("de.robv.android.xposed.") ||
                                className.startsWith("org.lsposed.lspd.") ||
                                className.startsWith("io.github.libxposed.")) {

                                // Inner class safety
                                if (className.contains("$")) {
                                    val outerClass = className.substringBefore("$")
                                    if (!outerClass.startsWith("de.robv.android.xposed.") &&
                                        !outerClass.startsWith("org.lsposed.lspd.") &&
                                        !outerClass.startsWith("io.github.libxposed.")) {
                                        return // Outer is safe, allow inner
                                    }
                                }

                                param.throwable = ClassNotFoundException(className)
                            }

                        } catch (t: Throwable) {
                            // 🔴 SAFETY: Track errors and auto-whitelist
                            hookErrorCount++
                            val className = param.args[0] as? String
                            if (className != null) {
                                failedBlocks.add(className)
                                if (hookErrorCount < MAX_ERRORS) {
                                    XposedBridge.log("AntiDetection: ClassLoader error blocking $className (${hookErrorCount}/${MAX_ERRORS})")
                                }
                            }
                        }
                    }
                }
            )

            XposedBridge.log("AntiDetection: ✅ ClassLoader hook installed (IMMEDIATE mode with safety checks)")

        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: ❌ Failed to install ClassLoader hook: ${e.message}")
        }
    }

    /**
     * Hook stack trace methods to remove Xposed frames
     * Optimized: Only process if Xposed frames detected
     * LIGHTWEIGHT: Only runs when stack trace is accessed
     */
    private fun hookStackTrace(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook Thread.getStackTrace()
            XposedHelpers.findAndHookMethod(
                Thread::class.java,
                "getStackTrace",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val originalTrace = param.result as? Array<StackTraceElement> ?: return

                            // Fast path: If trace is small or no Xposed frames, skip
                            if (originalTrace.size < 5) return
                            if (!hasXposedFrames(originalTrace)) return

                            val cleanedTrace = originalTrace.filterNot { isXposedStackFrame(it) }.toTypedArray()
                            if (cleanedTrace.size != originalTrace.size) {
                                param.result = cleanedTrace
                            }
                        } catch (t: Throwable) {
                            // Silently fail - don't break stack traces
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
                        try {
                            val originalTrace = param.result as? Array<StackTraceElement> ?: return

                            // Fast path
                            if (originalTrace.size < 5) return
                            if (!hasXposedFrames(originalTrace)) return

                            val cleanedTrace = originalTrace.filterNot { isXposedStackFrame(it) }.toTypedArray()
                            if (cleanedTrace.size != originalTrace.size) {
                                param.result = cleanedTrace
                            }
                        } catch (t: Throwable) {
                            // Silently fail
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Don't log - this is not critical
        }
    }

    // Quick check if array contains any Xposed frames (faster than filtering)
    private fun hasXposedFrames(trace: Array<StackTraceElement>): Boolean {
        for (element in trace) {
            val className = element.className
            // SAFE: Check for Xposed/LSPosed in package structure
            if (className.startsWith("de.robv.android.xposed.") ||
                className.startsWith("io.github.libxposed.") ||
                className.startsWith("org.lsposed.") ||
                className.contains(".xposed.") ||
                className.contains(".lsposed.")) {
                return true
            }
        }
        return false
    }

    private fun isXposedStackFrame(element: StackTraceElement): Boolean {
        val className = element.className
        // SAFE: More specific checks
        return className.startsWith("de.robv.android.xposed.") ||
                className.startsWith("io.github.libxposed.") ||
                className.startsWith("org.lsposed.") ||
                className.startsWith("org.meowcat.edxposed.") ||
                className.contains(".xposed.") ||
                className.contains(".lsposed.")
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

    /**
     * Hook Runtime.load/loadLibrary to hide suspicious native libraries
     * Some apps check for Xposed/LSPosed native libraries
     */
    private fun hookNativeLibrary(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val suspiciousLibs = setOf(
                "xposed",
                "lsposed",
                "edxposed",
                "riru"
            )

            // Hook Runtime.load
            XposedHelpers.findAndHookMethod(
                Runtime::class.java,
                "load",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val libPath = param.args[0] as? String ?: return
                            if (suspiciousLibs.any { libPath.contains(it, ignoreCase = true) }) {
                                param.throwable = UnsatisfiedLinkError("Library not found: $libPath")
                            }
                        } catch (t: Throwable) {
                            // Silently fail
                        }
                    }
                }
            )

            // Hook Runtime.loadLibrary
            XposedHelpers.findAndHookMethod(
                Runtime::class.java,
                "loadLibrary",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val libName = param.args[0] as? String ?: return
                            if (suspiciousLibs.any { libName.contains(it, ignoreCase = true) }) {
                                param.throwable = UnsatisfiedLinkError("Library not found: $libName")
                            }
                        } catch (t: Throwable) {
                            // Silently fail
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: Failed to hook native library: ${e.message}")
        }
    }

    /**
     * Hook Map-related classes to prevent location-based detection
     * Some banking apps detect mock location via Google Maps API
     */
    private fun hookMapView(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Try to hook GoogleMap.isMyLocationEnabled
            val googleMapClass = XposedHelpers.findClassIfExists(
                "com.google.android.gms.maps.GoogleMap",
                lpparam.classLoader
            ) ?: return

            XposedHelpers.findAndHookMethod(
                googleMapClass,
                "isMyLocationEnabled",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        // Always return false to hide that location is being mocked
                        param.result = false
                    }
                }
            )
        } catch (e: Throwable) {
            // This is optional, don't log error
        }
    }

    /**
     * Hook Location.isFromMockProvider() - CRITICAL for anti-detection
     * Many apps check this to detect fake GPS
     */
    private fun hookIsFromMockProvider(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val locationClass = XposedHelpers.findClass(
                "android.location.Location",
                lpparam.classLoader
            )

            // Hook isFromMockProvider() to always return false
            XposedHelpers.findAndHookMethod(
                locationClass,
                "isFromMockProvider",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        // Always return false - location is NOT from mock provider
                        param.result = false
                    }
                }
            )

            // Also hook isMock() if it exists (some ROMs have this)
            try {
                XposedHelpers.findAndHookMethod(
                    locationClass,
                    "isMock",
                    object : XC_MethodHook() {
                        override fun beforeHookedMethod(param: MethodHookParam) {
                            param.result = false
                        }
                    }
                )
            } catch (e: Throwable) {
                // Method may not exist, that's fine
            }
        } catch (e: Throwable) {
            XposedBridge.log("AntiDetection: Failed to hook isFromMockProvider: ${e.message}")
        }
    }

    /**
     * Hook debug-related checks
     * Some apps refuse to run if debuggable
     */
    private fun hookDebugCheck(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook ApplicationInfo to hide debuggable flag
            val appInfoClass = XposedHelpers.findClass(
                "android.content.pm.ApplicationInfo",
                lpparam.classLoader
            )

            // Hook field access to flags
            XposedHelpers.findAndHookMethod(
                appInfoClass,
                "getField",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (param.args[0] == "flags") {
                            try {
                                val flags = param.result as? Int ?: return
                                // Remove FLAG_DEBUGGABLE (0x2)
                                val FLAG_DEBUGGABLE = 0x2
                                param.result = flags and FLAG_DEBUGGABLE.inv()
                            } catch (e: Throwable) {
                                // Silently fail
                            }
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // This is optional hook
        }
    }

    /**
     * Hook Settings.Secure to hide mock location settings
     * Some apps check Settings.Secure.ALLOW_MOCK_LOCATION
     * LIGHTWEIGHT: Only intercepts specific keys
     */
    private fun hookMockLocationCheck(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val settingsSecureClass = XposedHelpers.findClass(
                "android.provider.Settings\$Secure",
                lpparam.classLoader
            )

            // Hook getString() to hide mock location setting
            XposedHelpers.findAndHookMethod(
                settingsSecureClass,
                "getString",
                android.content.ContentResolver::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val name = param.args[1] as? String ?: return

                            // Fast path: Only intercept mock location keys
                            if (name == "mock_location" || name == "allow_mock_location") {
                                param.result = "0" // Mock location is disabled
                            }
                        } catch (t: Throwable) {
                            // Silently fail
                        }
                    }
                }
            )

            // Hook getInt() as well
            XposedHelpers.findAndHookMethod(
                settingsSecureClass,
                "getInt",
                android.content.ContentResolver::class.java,
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val name = param.args[1] as? String ?: return

                            // Fast path
                            if (name == "mock_location" || name == "allow_mock_location") {
                                param.result = 0 // Mock location is disabled
                            }
                        } catch (t: Throwable) {
                            // Silently fail
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Don't log - not critical
        }
    }

    /**
     * SAFE version of PackageManager hook
     * Only hides LSPosed-related packages, doesn't block other apps
     */
    private fun hookPackageManagerSafe(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val pmClass = XposedHelpers.findClass("android.app.ApplicationPackageManager", lpparam.classLoader)

            // Hook getInstalledApplications - SAFE: Only filter, don't throw
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getInstalledApplications",
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val apps = param.result as? List<ApplicationInfo> ?: return

                            // SAFE: Only filter if list looks valid
                            if (apps.isEmpty()) return

                            // Filter out LSPosed packages
                            param.result = apps.filterNot { app ->
                                SUSPICIOUS_PACKAGES.contains(app.packageName)
                            }
                        } catch (t: Throwable) {
                            // SAFE: If error, return original result
                        }
                    }
                }
            )

            // Hook getPackageInfo - SAFE: Only throw for LSPosed packages
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getPackageInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val packageName = param.args[0] as? String ?: return

                            // SAFE: Only block LSPosed packages
                            if (SUSPICIOUS_PACKAGES.contains(packageName)) {
                                val exceptionClass = XposedHelpers.findClass(
                                    "android.content.pm.PackageManager\$NameNotFoundException",
                                    lpparam.classLoader
                                )
                                param.throwable = XposedHelpers.newInstance(exceptionClass, packageName) as Throwable
                            }
                        } catch (t: Throwable) {
                            // SAFE: If error, let original method run
                        }
                    }
                }
            )

            // Hook getApplicationInfo - SAFE: Only throw for LSPosed packages
            XposedHelpers.findAndHookMethod(
                pmClass,
                "getApplicationInfo",
                String::class.java,
                Int::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val packageName = param.args[0] as? String ?: return

                            // SAFE: Only block LSPosed packages
                            if (SUSPICIOUS_PACKAGES.contains(packageName)) {
                                val exceptionClass = XposedHelpers.findClass(
                                    "android.content.pm.PackageManager\$NameNotFoundException",
                                    lpparam.classLoader
                                )
                                param.throwable = XposedHelpers.newInstance(exceptionClass, packageName) as Throwable
                            }
                        } catch (t: Throwable) {
                            // SAFE: If error, let original method run
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Don't crash if PackageManager hook fails
        }
    }

    /**
     * SAFE version of ClassLoader hook
     * Only blocks Xposed classes, uses whitelist to prevent blocking important classes
     */
    private fun hookClassLoaderSafe(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // SAFE: Only hook ClassLoader.loadClass, not Class.forName
            // Class.forName is used during app initialization and blocking it causes freeze
            XposedHelpers.findAndHookMethod(
                ClassLoader::class.java,
                "loadClass",
                String::class.java,
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        try {
                            val className = param.args[0] as? String ?: return

                            // SAFE: Use strict whitelist - only block exact Xposed classes
                            // Don't use contains() which might block legitimate classes
                            if (XPOSED_CLASS_NAMES.contains(className)) {
                                param.throwable = ClassNotFoundException(className)
                            }
                        } catch (t: Throwable) {
                            // SAFE: If error, let original method run
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Don't crash if ClassLoader hook fails
        }
    }

    /**
     * SAFE version of ApplicationInfo hook
     * Hide debuggable flags that banking apps check
     */
    private fun hookApplicationInfoSafe(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            // Hook ApplicationInfo.flags to hide debuggable flag
            val appInfoClass = XposedHelpers.findClass(
                "android.content.pm.ApplicationInfo",
                lpparam.classLoader
            )

            // Hook when banking app gets its own ApplicationInfo
            XposedHelpers.findAndHookMethod(
                "android.app.ContextImpl",
                lpparam.classLoader,
                "getApplicationInfo",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val appInfo = param.result as? ApplicationInfo ?: return

                            // SAFE: Remove debuggable flags
                            val FLAG_DEBUGGABLE = 0x2
                            appInfo.flags = appInfo.flags and FLAG_DEBUGGABLE.inv()
                        } catch (t: Throwable) {
                            // SAFE: If error, return original
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Don't crash if hook fails
        }
    }

    /**
     * SAFE version of System Properties hook
     * Banking apps check ro.debuggable and other system properties
     */
    private fun hookSystemPropertiesSafe(lpparam: XC_LoadPackage.LoadPackageParam) {
        try {
            val systemPropertiesClass = XposedHelpers.findClass(
                "android.os.SystemProperties",
                lpparam.classLoader
            )

            // Hook SystemProperties.get(String key)
            XposedHelpers.findAndHookMethod(
                systemPropertiesClass,
                "get",
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val key = param.args[0] as? String ?: return

                            // SAFE: Only modify specific security-related properties
                            when (key) {
                                "ro.debuggable" -> param.result = "0"
                                "ro.secure" -> param.result = "1"
                                "ro.build.type" -> param.result = "user"
                                "ro.build.tags" -> {
                                    val current = param.result as? String
                                    if (current?.contains("test-keys") == true) {
                                        param.result = current.replace("test-keys", "release-keys")
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            // SAFE: If error, return original value
                        }
                    }
                }
            )

            // Hook SystemProperties.get(String key, String def)
            XposedHelpers.findAndHookMethod(
                systemPropertiesClass,
                "get",
                String::class.java,
                String::class.java,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val key = param.args[0] as? String ?: return

                            // SAFE: Only modify specific properties
                            when (key) {
                                "ro.debuggable" -> param.result = "0"
                                "ro.secure" -> param.result = "1"
                                "ro.build.type" -> param.result = "user"
                                "ro.build.tags" -> {
                                    val current = param.result as? String
                                    if (current?.contains("test-keys") == true) {
                                        param.result = current.replace("test-keys", "release-keys")
                                    }
                                }
                            }
                        } catch (t: Throwable) {
                            // SAFE: If error, return original
                        }
                    }
                }
            )

            // Hook SystemProperties.getBoolean(String key, boolean def)
            XposedHelpers.findAndHookMethod(
                systemPropertiesClass,
                "getBoolean",
                String::class.java,
                Boolean::class.javaPrimitiveType,
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        try {
                            val key = param.args[0] as? String ?: return

                            // SAFE: Only modify debuggable check
                            if (key == "ro.debuggable") {
                                param.result = false
                            }
                        } catch (t: Throwable) {
                            // SAFE: If error, return original
                        }
                    }
                }
            )
        } catch (e: Throwable) {
            // Don't crash if SystemProperties hook fails
        }
    }
}
