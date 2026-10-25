# AutoVybz R8 keep rules. Everything else is covered by proguard-android-optimize.txt,
# AAPT's generated rules (manifest components, custom views in layouts) and the
# consumer rules shipped inside the AndroidX / Compose / coroutines libraries.

# JNI: the native bridges (NativeEngine, HrirConvolver) are exported from C/C++ by
# mangled symbol name (Java_com_..._nativeCreate etc.), so the declaring classes and
# the native methods themselves must never be renamed.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Enum constants are a storage format: settings and presets persist them via
# Enum.name and read them back with valueOf(...) (settings, preset files/JSON shared
# between builds). Keep every enum's member names so stored data stays readable.
# (proguard-android-optimize.txt keeps values()/valueOf() but not the constant names.)
-keepclassmembers enum * {
    *;
}
