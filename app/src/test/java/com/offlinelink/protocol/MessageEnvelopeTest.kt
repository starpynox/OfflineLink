package com.offlinelink.protocol

import org.junit.Assert.*
import org.junit.Test
import java.util.UUID

class MessageEnvelopeTest {

    @Test
    fun testSerializationAndDeserialization() {
        val original = MessageEnvelope(
            version = 1,
            type = MessageType.TEXT,
            messageId = UUID.randomUUID().toString(),
            senderId = "NODE-AAA",
            recipientId = "NODE-BBB",
            priority = MessageEnvelope.PRIORITY_NORMAL,
            hopLimit = 4,
            hopCount = 1,
            payloadLength = 42,
            encryptedPayloadBase64 = "dGVzdC1lbmNyeXB0ZWQtc3RyaW5n"
        )

        val json = original.toJson()
        val deserialized = MessageEnvelope.fromJson(json)

        assertNotNull(deserialized)
        assertEquals(original.messageId, deserialized!!.messageId)
        assertEquals(original.senderId, deserialized.senderId)
        assertEquals(original.recipientId, deserialized.recipientId)
        assertEquals(original.type, deserialized.type)
        assertEquals(original.hopCount, deserialized.hopCount)
        assertEquals(original.hopLimit, deserialized.hopLimit)
        assertEquals(original.encryptedPayloadBase64, deserialized.encryptedPayloadBase64)
    }

    @Test
    fun testHopIncrement() {
        val envelope = MessageEnvelope(
            type = MessageType.TEXT,
            senderId = "A",
            recipientId = "C",
            hopLimit = 3,
            hopCount = 0
        )

        assertTrue(envelope.canHop())

        val hop1 = envelope.withIncrementedHop()
        assertEquals(1, hop1.hopCount)
        assertTrue(hop1.canHop())

        val hop2 = hop1.withIncrementedHop()
        assertEquals(2, hop2.hopCount)
        assertTrue(hop2.canHop())

        val hop3 = hop2.withIncrementedHop()
        assertEquals(3, hop3.hopCount)
        assertFalse(hop3.canHop()) // Hop limit reached
    }

    @Test
    fun testExpirationDetection() {
        val now = System.currentTimeMillis()
        val expiredEnvelope = MessageEnvelope(
            type = MessageType.TEXT,
            senderId = "A",
            recipientId = "B",
            timestamp = now - 5000,
            expiryTimestamp = now - 1000
        )
        assertTrue(expiredEnvelope.isExpired(now))

        val validEnvelope = MessageEnvelope(
            type = MessageType.TEXT,
            senderId = "A",
            recipientId = "B",
            timestamp = now,
            expiryTimestamp = now + 60000
        )
        assertFalse(validEnvelope.isExpired(now))
    }

    @Test
    fun testInvalidEnvelopeRejected() {
        val blankIdEnvelope = MessageEnvelope(
            type = MessageType.TEXT,
            messageId = "",
            senderId = "A",
            recipientId = "B"
        )
        assertFalse(blankIdEnvelope.isValid())

        val excessiveHopEnvelope = MessageEnvelope(
            type = MessageType.TEXT,
            senderId = "A",
            recipientId = "B",
            hopLimit = 2,
            hopCount = 5
        )
        assertFalse(excessiveHopEnvelope.isValid())
    }
}
