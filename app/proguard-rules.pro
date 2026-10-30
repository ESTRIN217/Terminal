# Add project specific ProGuard rules here.
# By default, the flags in this file are appended to flags specified
# in android-sdk/tools/proguard/proguard-android.txt
# You can edit the include path and order by changing the proguardFiles
# directive in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# commons-compress 1.27.x declares zstd-jni and brotli-dec as optional
# dependencies (Gradle does not pull them in). CompressorStreamFactory
# hard-references both, but the app never uses them (spec 023 formats
# exclude tar.zst/brotli; the rootfs is tar.gz), so the missing classes
# are harmless at runtime. Rules copied from the generated
# app/build/outputs/mapping/release/missing_rules.txt (fix for
# assembleRelease ahead of 3.1).
-dontwarn com.github.luben.zstd.ZstdInputStream
-dontwarn org.brotli.dec.BrotliInputStream

-dontobfuscate
#-renamesourcefileattribute SourceFile
#-keepattributes SourceFile,LineNumberTable
