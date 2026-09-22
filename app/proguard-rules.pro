# kotlinx.serialization ships its own R8 rules; nothing app-specific is needed for it.

# ML Kit finds its parts at run time: MlKitComponentDiscoveryService lists
# ComponentRegistrar classes in the manifest, and each registrar registers
# factories keyed by Class. R8's full mode merged and shrank those classes, so
# BarcodeScanning.getClient() looked up a key nobody had registered and crashed
# with a NullPointerException, in minified builds only (1.0.0 on a Xiaomi Mi 10T
# Pro, Android 12). Keep the registration machinery as it is.
-keep class * implements com.google.firebase.components.ComponentRegistrar { *; }
-keep class com.google.firebase.components.** { *; }
-keep class com.google.mlkit.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode_bundled.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_barcode.** { *; }
-keep class com.google.android.gms.internal.mlkit_vision_common.** { *; }
-keep class com.google.android.gms.internal.mlkit_common.** { *; }
