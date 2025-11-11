# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# If your project uses WebView with JS, uncomment the following
# and specify the fully qualified class name to the JavaScript interface
# class:
#-keepclassmembers class fqcn.of.javascript.interface.for.webview {
#   public *;
#}

# Uncomment this to preserve the line number information for
# debugging stack traces.
#-keepattributes SourceFile,LineNumberTable

# If you keep the line number information, uncomment this to
# hide the original source file name.
#-renamesourcefileattribute SourceFile

# ViewModel
-keep class io.github.mwarevn.movingsimulation.ui.viewmodel.MainViewModel{*;}
-keepnames class io.github.mwarevn.movingsimulation.ui.viewmodel.MainViewModel.**

# Xposed
-keep class io.github.mwarevn.movingsimulation.xposed.Xshare{*;}
-keep class io.github.mwarevn.movingsimulation.xposed.HookEntry{*;}
-keep class de.robv.android.xposed.**{*;}
-keepnames class de.robv.android.xposed.**

# Retrofit - Keep all network models and services
-keep class io.github.mwarevn.movingsimulation.network.** { *; }
-keepnames class io.github.mwarevn.movingsimulation.network.**

# Retrofit generic signatures (fixes ClassCastException)
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes EnclosingMethod
-keepattributes InnerClasses

# Retrofit service interfaces
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Retrofit converter (Gson/Moshi)
-dontwarn okhttp3.**
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keep class okhttp3.** { *; }

# Gson specific rules
-keepclassmembers,allowobfuscation class * {
  @com.google.gson.annotations.SerializedName <fields>;
}
-keep,allowobfuscation,allowshrinking class com.google.gson.reflect.TypeToken
-keep,allowobfuscation,allowshrinking class * extends com.google.gson.reflect.TypeToken

# Keep data classes for network responses (critical for Retrofit deserialization)
-keep class io.github.mwarevn.movingsimulation.network.MapBoxDirectionsResponse { *; }
-keep class io.github.mwarevn.movingsimulation.network.MapBoxRoute { *; }
-keep class io.github.mwarevn.movingsimulation.network.MapBoxLeg { *; }
-keep class io.github.mwarevn.movingsimulation.network.OsrmRouteResponse { *; }
-keep class io.github.mwarevn.movingsimulation.network.OsrmRoute { *; }
-keep class io.github.mwarevn.movingsimulation.network.RoutingResult { *; }
-keep class io.github.mwarevn.movingsimulation.network.VehicleType { *; }

-repackageclasses
-allowaccessmodification
-overloadaggressively
