package org.medianexus.music.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class LicencesTest {
    @Test
    fun aParagraphWrappedForAComputerIsOneParagraphOnAPhone() {
        val text = "PREAMBLE\nThe goals of the Open Font License (OFL) are to stimulate worldwide\ndevelopment of collaborative font projects.\n\n" +
            "-----------------------\nSIL OPEN FONT LICENSE\n\n1) Neither the Font Software nor any of\nits individual components.\n2) Original or Modified Versions"
        assertEquals(
            "PREAMBLE\nThe goals of the Open Font License (OFL) are to stimulate worldwide development of collaborative font projects.\n\n" +
                "-----------------------\nSIL OPEN FONT LICENSE\n\n1) Neither the Font Software nor any of its individual components.\n2) Original or Modified Versions",
            reflowed(text),
        )
    }
}
