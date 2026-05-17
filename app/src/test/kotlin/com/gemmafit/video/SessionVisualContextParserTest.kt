package com.gemmafit.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVisualContextParserTest {
    @Test
    fun parsesKeyValueSidecarOutput() {
        val context = SessionVisualContextParser.parse(
            "env=outdoor;support=chair;person=visible;overlay_readable=true;limited=false",
        )

        assertEquals(SessionVisualContext.ENV_OUTDOOR, context.env)
        assertEquals(SessionVisualContext.SUPPORT_CHAIR, context.support)
        assertEquals(SessionVisualContext.PERSON_VISIBLE, context.person)
        assertEquals(true, context.overlayReadable)
        assertEquals(false, context.limited)
        assertTrue(context.available)
        assertEquals(
            listOf(
                SessionVisualContext.REF_ENV,
                SessionVisualContext.REF_SUPPORT,
                SessionVisualContext.REF_PERSON,
                SessionVisualContext.REF_OVERLAY,
                SessionVisualContext.REF_LIMITED,
            ),
            context.evidenceRefs,
        )
    }

    @Test
    fun parsesJsonObjectEmbeddedInModelOutput() {
        val context = SessionVisualContextParser.parse(
            """result: {"env":"indoor","support":"none","person":"multiple","overlay_readable":false,"limited":true}""",
        )

        assertEquals(SessionVisualContext.ENV_INDOOR, context.env)
        assertEquals(SessionVisualContext.SUPPORT_NONE, context.support)
        assertEquals(SessionVisualContext.PERSON_MULTIPLE, context.person)
        assertEquals(false, context.overlayReadable)
        assertEquals(true, context.limited)
        assertTrue(context.evidenceRefs.contains(SessionVisualContext.REF_LIMITED))
    }

    @Test
    fun unknownOutputDoesNotBecomeAvailable() {
        val context = SessionVisualContextParser.parse("env=garage;support=maybe;person=blurred")

        assertEquals(SessionVisualContext.ENV_UNKNOWN, context.env)
        assertEquals(SessionVisualContext.SUPPORT_UNKNOWN, context.support)
        assertEquals(SessionVisualContext.PERSON_UNKNOWN, context.person)
        assertEquals(null, context.overlayReadable)
        assertEquals(null, context.limited)
        assertTrue(context.evidenceRefs.isEmpty())
        assertEquals(false, context.available)
    }

    @Test
    fun pipeEchoDoesNotOverclaimContradictoryChoices() {
        val context = SessionVisualContextParser.parse(
            "env=outdoor|unknown;support=chair|none|unknown;" +
                "person=visible|not_visible|multiple|unknown;overlay_readable=true|false;limited=false",
        )

        assertEquals(SessionVisualContext.ENV_OUTDOOR, context.env)
        assertEquals(SessionVisualContext.SUPPORT_UNKNOWN, context.support)
        assertEquals(SessionVisualContext.PERSON_UNKNOWN, context.person)
        assertEquals(null, context.overlayReadable)
        assertEquals(false, context.limited)
    }
}
