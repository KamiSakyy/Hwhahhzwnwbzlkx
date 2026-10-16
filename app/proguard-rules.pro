# R8 / ProGuard configuration for Tuman.
# The app uses no reflection and no third-party libraries, so real shrinking and
# obfuscation are safe here (no blanket -keep of our own package).

-renamesourcefileattribute Tuman
-repackageclasses ''
-allowaccessmodification
-optimizationpasses 5
-dontusemixedcaseclassnames
-verbose

# --- Components referenced from AndroidManifest.xml (kept by AGP too, listed
# --- explicitly so a misconfigured aapt rule set can never strip them) ---
-keep class com.tuman.free.MainActivity
-keep class com.tuman.free.AppsActivity
-keep class com.tuman.free.BootReceiver
-keep class com.tuman.free.TunnelTile
-keep class com.tuman.free.vpn.TunnelVpnService

# --- Custom views inflated from XML need the (Context, AttributeSet) ctor ---
-keep public class * extends android.view.View {
    public <init>(android.content.Context);
    public <init>(android.content.Context, android.util.AttributeSet);
    public <init>(android.content.Context, android.util.AttributeSet, int);
    public void set*(...);
}

# Keep line numbers + source file mapping so crash reports stay readable via
# the mapping.txt artifact produced by the CI job.
-keepattributes SourceFile,LineNumberTable

-dontwarn java.lang.invoke.**
-dontwarn javax.annotation.**
