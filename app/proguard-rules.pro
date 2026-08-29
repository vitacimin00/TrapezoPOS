# Trapezo POS R8 / ProGuard rules.
#
# Deliberately minimal. Blanket rules such as
#     -keep class com.trapezo.** { *; }
# are NOT used: they would defeat the point of shrinking and obfuscation.
#
# Room, Compose, CameraX and ML Kit all ship their own consumer rules via AAR metadata, so no
# manual keeps are required for them. Add an entry here ONLY when an actual release failure
# proves it is needed, and say which failure it fixes.

# Keep line numbers so a production crash report stays diagnosable, while still stripping
# the original source file name.
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
