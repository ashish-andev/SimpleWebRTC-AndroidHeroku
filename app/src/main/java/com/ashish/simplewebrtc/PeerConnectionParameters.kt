package com.ashish.simplewebrtc

class PeerConnectionParameters(
    val videoCallEnabled: Boolean, val loopback: Boolean,
    val videoWidth: Int, val videoHeight: Int, val videoFps: Int, val videoStartBitrate: Int,
    val videoCodec: String, val videoCodecHwAcceleration: Boolean,
    val audioStartBitrate: Int, val audioCodec: String,
    val cpuOveruseDetection: Boolean
)