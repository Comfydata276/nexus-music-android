package org.medianexus.music.downloads

import androidx.media3.test.utils.robolectric.ShadowMediaCodecConfig

/**
 * The audio decoders every Android phone has, registered with the system's list of codecs as
 * Robolectric stands in for it (Media3's own test rule): AAC, FLAC, MP3, Opus and Vorbis.
 * **Not ALAC**, which no version of Android has shipped a decoder for. A test whose check
 * asks the system for a decoder (as the real one does) takes this as a rule.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
fun phoneDecoders(): ShadowMediaCodecConfig = ShadowMediaCodecConfig.withCodecs(
    listOf(
        ShadowMediaCodecConfig.CODEC_INFO_AAC,
        ShadowMediaCodecConfig.CODEC_INFO_FLAC,
        ShadowMediaCodecConfig.CODEC_INFO_MPEG,
        ShadowMediaCodecConfig.CODEC_INFO_OPUS,
        ShadowMediaCodecConfig.CODEC_INFO_VORBIS,
    ),
    emptyList(),
)
