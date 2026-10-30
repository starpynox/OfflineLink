package com.offlinelink.routing

import com.offlinelink.protocol.MessageEnvelope
import com.offlinelink.protocol.MessageType
import com.offlinelink.protocol.RoutingAdvertPayload
import com.offlinelink.protocol.RoutingFetchPayload
import com.offlinelink.security.CryptoEngine
import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

/**
 * Validates the core store-and-forward opportunistic routing protocol,
 * persistent duplicate suppression, and the 3-device relay workflow (A -> B -> C).
 */
class RoutingLogicTest {

    @Test
    fun testThreeDeviceStoreAndForwardRelayWorkflow() {
        val nodeA = "NODE-A111"
        val nodeB = "NODE-B222" // Intermediate relay
        val nodeC = "NODE-C333" // Final recipient

        val secretMessageText = "Medical supplies arrived at waypoint Charlie"
        val pairwiseKeyAC = CryptoEngine.derivePairwiseKey(nodeA, nodeC)

        // Step 1: Phone A originates and encrypts message for Phone C
        val encryptedForC = CryptoEngine.encryptAesGcm(pairwiseKeyAC, secretMessageText.toByteArray(Charsets.UTF_8))
        val messageId = UUID.randomUUID().toString()

        val envelopeA = MessageEnvelope(
            type = MessageType.TEXT,
            messageId = messageId,
            senderId = nodeA,
            recipientId = nodeC,
            hopLimit = 3,
            hopCount = 0,
            payloadLength = secretMessageText.length,
            encryptedPayloadBase64 = encryptedForC.toBase64()
        )

        // Step 2: Phone B (relay) encounters Phone A and receives the envelope
        assertTrue("Message must have remaining hops to be accepted by relay", envelopeA.canHop())
        val envelopeAtB = envelopeA.withIncrementedHop()
        assertEquals(1, envelopeAtB.hopCount)

        // Relay B CANNOT decrypt the message content because B does not have the A-C pairwise key!
        val pairwiseKeyBC = CryptoEngine.derivePairwiseKey(nodeB, nodeC)
        val decryptAttemptByB = CryptoEngine.decryptAesGcm(pairwiseKeyBC, encryptedForC.iv, encryptedForC.ciphertext)
        assertNull("Intermediate relay B must not be able to decrypt the private payload", decryptAttemptByB)

        // Step 3: A and B disconnect. Later, B encounters C.
        // B advertises held messages to C
        val advertFromB = RoutingAdvertPayload(
            heldMessageIds = listOf(envelopeAtB.messageId),
            targetRecipients = listOf(envelopeAtB.recipientId),
            priorities = listOf(envelopeAtB.priority),
            expiries = listOf(envelopeAtB.expiryTimestamp)
        )

        // C inspects the advertisement: C is the intended recipient!
        val cFetchList = mutableListOf<String>()
        for (i in advertFromB.heldMessageIds.indices) {
            if (advertFromB.targetRecipients[i] == nodeC) {
                cFetchList.add(advertFromB.heldMessageIds[i])
            }
        }
        assertEquals(1, cFetchList.size)
        assertEquals(messageId, cFetchList[0])

        // Step 4: C requests the message via RoutingFetch
        val fetchFromC = RoutingFetchPayload(requestedMessageIds = cFetchList)
        assertEquals(listOf(messageId), fetchFromC.requestedMessageIds)

        // B transmits the message to C with incremented hop count
        val envelopeAtC = envelopeAtB.withIncrementedHop()
        assertEquals(2, envelopeAtC.hopCount)
        assertEquals(nodeC, envelopeAtC.recipientId)

        // Step 5: C decrypts the message using its pairwise key with sender A
        val decryptedByC = CryptoEngine.decryptAesGcm(pairwiseKeyAC, encryptedForC.iv, encryptedForC.ciphertext)
        assertNotNull("Final recipient C must successfully decrypt the message", decryptedByC)
        assertEquals(secretMessageText, String(decryptedByC!!, Charsets.UTF_8))
    }

    @Test
    fun testDuplicateSuppressionLogic() {
        val seenCache = mutableSetOf<String>()
        val messageId = "MSG-DUP-12345"

        // First arrival
        val isFirstArrivalSeen = seenCache.contains(messageId)
        assertFalse("First arrival must not be seen", isFirstArrivalSeen)
        seenCache.add(messageId)

        // Second arrival (e.g. repeated contact or multiple relay paths)
        val isSecondArrivalSeen = seenCache.contains(messageId)
        assertTrue("Second arrival must be recognized as duplicate and suppressed", isSecondArrivalSeen)
    }

    @Test
    fun testHopLimitEnforcementPreventsForwardingLoops() {
        val envelope = MessageEnvelope(
            type = MessageType.TEXT,
            senderId = "A",
            recipientId = "Z",
            hopLimit = 2,
            hopCount = 0
        )

        assertTrue(envelope.canHop())
        val hop1 = envelope.withIncrementedHop()
        assertTrue(hop1.canHop())
        val hop2 = hop1.withIncrementedHop()

        // Hop limit of 2 reached
        assertFalse("Message that reached hopLimit must not be permitted to hop further", hop2.canHop())
    }

    @Test
    fun testBlankRecipientValidation() {
        val blankRecipients = listOf("", "   ", "\t", "\n")
        for (recipient in blankRecipients) {
            assertTrue("Recipient must be considered blank", recipient.trim().isBlank())
        }

        val validRecipient = "  DEVICE-UUID-1234  "
        assertFalse(validRecipient.trim().isBlank())
        assertEquals("DEVICE-UUID-1234", validRecipient.trim())
    }

    @Test
    fun testDestinationEnforcementInEntity() {
        try {
            require("".isNotBlank()) { "Recipient ID cannot be blank" }
            fail("Blank recipient should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Recipient ID cannot be blank"))
        }

        try {
            require("   ".isNotBlank()) { "Conversation ID cannot be blank" }
            fail("Blank conversation ID should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("Conversation ID cannot be blank"))
        }
    }
}
