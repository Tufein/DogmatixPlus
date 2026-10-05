package com.cortinadev.dogmatix.util

import com.cortinadev.dogmatix.util.TagClassifier.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

class TagClassifierTest {

    @Test fun `regions are recognised before the language pattern`() {
        assertEquals(Kind.REGION, TagClassifier.kindOf("USA"))
        assertEquals(Kind.REGION, TagClassifier.kindOf("Europe"))
        assertEquals(Kind.REGION, TagClassifier.kindOf("UK"))
        assertEquals(Kind.REGION, TagClassifier.kindOf("World"))
    }

    @Test fun `two and three letter codes are languages`() {
        assertEquals(Kind.LANGUAGE, TagClassifier.kindOf("En"))
        assertEquals(Kind.LANGUAGE, TagClassifier.kindOf("FR"))
        assertEquals(Kind.LANGUAGE, TagClassifier.kindOf("deu"))
    }

    @Test fun `revisions and versions`() {
        assertEquals(Kind.REVISION, TagClassifier.kindOf("Rev 1"))
        assertEquals(Kind.REVISION, TagClassifier.kindOf("Rev A"))
        assertEquals(Kind.REVISION, TagClassifier.kindOf("REV1"))
        assertEquals(Kind.REVISION, TagClassifier.kindOf("v1.1"))
        assertEquals(Kind.REVISION, TagClassifier.kindOf("V2"))
        assertEquals(Kind.REVISION, TagClassifier.kindOf("Version 2"))
    }

    @Test fun `release kinds and video standards`() {
        assertEquals(Kind.RELEASE, TagClassifier.kindOf("Beta"))
        assertEquals(Kind.RELEASE, TagClassifier.kindOf("Beta 2"))
        assertEquals(Kind.RELEASE, TagClassifier.kindOf("Proto"))
        assertEquals(Kind.RELEASE, TagClassifier.kindOf("Demo"))
        assertEquals(Kind.RELEASE, TagClassifier.kindOf("Hack"))
        assertEquals(Kind.VIDEO, TagClassifier.kindOf("PAL"))
        assertEquals(Kind.VIDEO, TagClassifier.kindOf("NTSC-J"))
    }

    @Test fun `anything else is other`() {
        assertEquals(Kind.OTHER, TagClassifier.kindOf("Disc 1"))
        assertEquals(Kind.OTHER, TagClassifier.kindOf("Limited Edition"))
        assertEquals(Kind.OTHER, TagClassifier.kindOf(""))
        assertEquals(Kind.OTHER, TagClassifier.kindOf("  "))
    }
}
