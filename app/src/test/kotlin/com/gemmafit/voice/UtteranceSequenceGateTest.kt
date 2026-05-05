package com.gemmafit.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UtteranceSequenceGateTest {
    @Test
    fun complete_ignoresInterruptedUtterance() {
        val gate = UtteranceSequenceGate()
        val first = gate.nextId("normal")

        gate.interrupt()
        val critical = gate.nextId("critical")

        assertFalse(gate.complete(first))
        assertTrue(gate.complete(critical))
        assertFalse(gate.complete(critical))
    }
}
