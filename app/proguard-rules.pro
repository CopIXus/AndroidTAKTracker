# AndroidTAKTracker release rules.
#
# Strategy: let R8 remove unused code and resources but keep names. Several dependencies
# resolve classes by string (BouncyCastle JCE provider, Tink/protobuf-lite field access,
# the optional Headwind MDM reflection path), and an obfuscated build cannot be verified
# from a stack trace in the field. Size wins come from tree shaking, not renaming.
-dontobfuscate
-keepattributes *Annotation*, InnerClasses, Signature, Exceptions, SourceFile, LineNumberTable

# --- kotlinx.serialization (AppConfig tree, UpdateService models) ---------------------------
-dontnote kotlinx.serialization.**
-dontwarn kotlinx.serialization.**
-keepclassmembers class kotlinx.serialization.json.** { *** Companion; }
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.copix.androidtaktracker.**$$serializer { *; }
-keepclassmembers class com.copix.androidtaktracker.** { *** Companion; }
-keepclasseswithmembers class com.copix.androidtaktracker.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep @kotlinx.serialization.Serializable class com.copix.androidtaktracker.** { *; }

# --- BouncyCastle (Marti CSR enrollment swaps the Android BC stub provider) ----------------
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**
-dontwarn javax.naming.**

# --- androidx.security-crypto -> Tink -> protobuf-lite (EncryptedSharedPreferences) ---------
-keep class com.google.crypto.tink.** { *; }
-dontwarn com.google.crypto.tink.**
-keepclassmembers class * extends com.google.protobuf.GeneratedMessageLite { <fields>; }
-dontwarn com.google.protobuf.**

# --- OkHttp / Okio -----------------------------------------------------------------------------
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# --- Headwind MDM (AIDL stubs + optional reflection on com.hmdm.HeadwindMDM) -----------------
-keep class com.hmdm.** { *; }
-dontwarn com.hmdm.**

# --- Android components referenced from the manifest are kept by AGP; keep BuildConfig for
#     the version string surfaced in CoT takv and the updater. -----------------------------------
-keep class com.copix.androidtaktracker.BuildConfig { *; }
