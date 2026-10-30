# SQLCipher calls Java classes from native code (JNI): keep them as they are.
-keep class net.zetetic.database.** { *; }
-dontwarn net.zetetic.database.**
