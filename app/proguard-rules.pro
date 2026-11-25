# kotlinx.serialization ships the rules its generated serializers need, and every
# serializer here is reached through generated code (`Song.serializer()`), not looked up by
# name, so nothing more is kept for it.
#
# The parts of the app that are found by name rather than called: none yet. When the
# playback service and the car's content provider arrive, the manifest keeps them.

# The shared core (native/, `core/Core.kt`): the library finds its functions by the names
# they have in Kotlin, and makes the exception it throws by name.
-keepclasseswithmembers,includedescriptorclasses class org.medianexus.music.core.Core { native <methods>; }
-keep class org.medianexus.music.core.CoreException { <init>(java.lang.String); }

# The library's requests: the shared library calls up into this object by these names
# (native/src/wire.rs), which nothing in Kotlin calls.
-keep class org.medianexus.music.core.CoreHttp {
    byte[] send(byte[]);
    byte[] text(long);
    byte[] read(long, int);
    void close(long);
    boolean pause(long, long);
    void kept(long, byte[]);
}
