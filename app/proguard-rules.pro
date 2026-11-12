# Mantener el código propio (puentes JavaScript, entidades Room, servicios).
-keep class com.audiovault.browser.** { *; }
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface, *Annotation*, Signature, InnerClasses, EnclosingMethod
