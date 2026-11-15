# Rules for the shrunk builds (release, minified). LiveKit and Firebase bring their own.
# Add a rule only when a shrunk build is seen to break without it, and say here what broke.

# --- kotlinx.serialization ---
# The app's own @Serializable classes: the pairing kept on the phone (store/Credentials.kt) and the bodies of the
# requests and answers (api/HttpCallApi.kt). The library ships most of its rules itself; these are the rest of the
# canonical set from its README, as in the DrySpool project. A pairing that can be saved but not read back, in the
# shrunk build only, is what they guard against.
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault

# Keep the `Companion` object field of serializable classes.
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# Keep `serializer()` on companion objects (default and named) of serializable classes.
-if @kotlinx.serialization.Serializable class ** {
    static **$* *;
}
-keepclassmembers class <2>$<3> {
    kotlinx.serialization.KSerializer serializer(...);
}

# Keep `INSTANCE.serializer()` of serializable objects.
-if @kotlinx.serialization.Serializable class ** {
    public static ** INSTANCE;
}
-keepclassmembers class <1> {
    public static <1> INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
