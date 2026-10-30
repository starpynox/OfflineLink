package com.offlinelink.protocol

enum class MessageType {
    PING,
    PONG,
    PEER_HELLO,
    PEER_VERIFY,
    TEXT,
    ACK,
    SOS,
    GROUP_TEXT,
    FILE_INIT,
    FILE_CHUNK,
    FILE_ACK,
    ROUTING_ADVERT,
    ROUTING_FETCH,
    ROUTING_ACK
}
