package io.androdex.android.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AndrodexClientSecureTransportTest {
    @Test
    fun shouldApplyBridgeOutboundSeq_acceptsUnsequencedAndNewerPayloads() {
        assertTrue(
            shouldApplyBridgeOutboundSeq(
                incomingBridgeOutboundSeq = -1,
                lastAppliedBridgeOutboundSeq = 7,
            )
        )
        assertTrue(
            shouldApplyBridgeOutboundSeq(
                incomingBridgeOutboundSeq = 8,
                lastAppliedBridgeOutboundSeq = 7,
            )
        )
    }

    @Test
    fun shouldApplyBridgeOutboundSeq_rejectsReplayedPayloads() {
        assertFalse(
            shouldApplyBridgeOutboundSeq(
                incomingBridgeOutboundSeq = 7,
                lastAppliedBridgeOutboundSeq = 7,
            )
        )
        assertFalse(
            shouldApplyBridgeOutboundSeq(
                incomingBridgeOutboundSeq = 6,
                lastAppliedBridgeOutboundSeq = 7,
            )
        )
    }
}
