# kotlinx.serialization ships the rules its generated serializers need, and every
# serializer here is reached through generated code (`Song.serializer()`), not looked up by
# name, so nothing more is kept for it.
#
# The parts of the app that are found by name rather than called: none yet. When the
# playback service and the car's content provider arrive, the manifest keeps them.
