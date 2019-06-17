package com.ashish.simplewebrtc

import android.opengl.EGLContext
import android.util.Log
import com.github.nkzawa.emitter.Emitter
import com.github.nkzawa.socketio.client.IO
import com.github.nkzawa.socketio.client.Socket
import org.json.JSONException
import org.json.JSONObject
import org.webrtc.*

import java.net.URISyntaxException
import java.util.HashMap
import java.util.LinkedList

class WebRtcClient(
    var mListener: RtcListener,
    host: String,
    var pcParams: PeerConnectionParameters,
    mEGLcontext: EGLContext
) {
    var endPoints = BooleanArray(MAX_PEER)
    var factory: PeerConnectionFactory
    var peers = HashMap<String, Peer>()
    var iceServers = LinkedList<PeerConnection.IceServer>()
    var pcConstraints = MediaConstraints()
    lateinit var localMS: MediaStream
    var videoSource: VideoSource? = null
    lateinit var client: Socket

    val videoCapturer: VideoCapturer
        get() {
            val frontCameraDeviceName = VideoCapturerAndroid.getNameOfFrontFacingDevice()
            return VideoCapturerAndroid.create(frontCameraDeviceName)
        }

    /**
     * Implement this interface to be notified of events.
     */
    interface RtcListener {
        fun onCallReady(callId: String)

        fun onStatusChanged(newStatus: String)

        fun onLocalStream(localStream: MediaStream)

        fun onAddRemoteStream(remoteStream: MediaStream, endPoint: Int)

        fun onRemoveRemoteStream(endPoint: Int)
    }

    interface Command {
        @Throws(JSONException::class)
        fun execute(peerId: String, payload: JSONObject?)
    }

    inner class CreateOfferCommand : Command {
        @Throws(JSONException::class)
        override fun execute(peerId: String, payload: JSONObject?) {
            Log.d(TAG, "CreateOfferCommand")
            val peer = peers[peerId]
            peer!!.pc.createOffer(peer, pcConstraints)
        }
    }

    inner class CreateAnswerCommand : Command {
        @Throws(JSONException::class)
        override fun execute(peerId: String, payload: JSONObject?) {
            Log.d(TAG, "CreateAnswerCommand")
            val peer = peers[peerId]
            val sdp = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(payload!!.getString("type")),
                payload.getString("sdp")
            )
            peer!!.pc.setRemoteDescription(peer, sdp)
            peer.pc.createAnswer(peer, pcConstraints)
        }
    }

    inner class SetRemoteSDPCommand : Command {
        @Throws(JSONException::class)
        override fun execute(peerId: String, payload: JSONObject?) {
            Log.d(TAG, "SetRemoteSDPCommand")
            val peer = peers[peerId]
            val sdp = SessionDescription(
                SessionDescription.Type.fromCanonicalForm(payload!!.getString("type")),
                payload.getString("sdp")
            )
            peer!!.pc.setRemoteDescription(peer, sdp)
        }
    }

    inner class AddIceCandidateCommand : Command {
        @Throws(JSONException::class)
        override fun execute(peerId: String, payload: JSONObject?) {
            Log.d(TAG, "AddIceCandidateCommand")
            val pc = peers[peerId]!!.pc
            if (pc.remoteDescription != null) {
                val candidate = IceCandidate(
                    payload!!.getString("id"),
                    payload.getInt("label"),
                    payload.getString("candidate")
                )
                pc.addIceCandidate(candidate)
            }
        }
    }

    /**
     * Send a message through the signaling server
     *
     * @param to      id of recipient
     * @param type    type of message
     * @param payload payload of message
     * @throws JSONException
     */
    @Throws(JSONException::class)
    fun sendMessage(to: String, type: String, payload: JSONObject) {
        val message = JSONObject()
        message.put("to", to)
        message.put("type", type)
        message.put("payload", payload)
        client.emit("message", message)
    }

    inner class MessageHandler {
        lateinit var commandMap: HashMap<String, Command>

        var onMessage: Emitter.Listener = Emitter.Listener { args ->
            val data = args[0] as JSONObject
            try {
                val from = data.getString("from")
                val type = data.getString("type")
                var payload: JSONObject? = null
                if (type != "init") {
                    payload = data.getJSONObject("payload")
                }
                // if peer is unknown, try to add him
                if (!peers.containsKey(from)) {
                    // if MAX_PEER is reach, ignore the call
                    val endPoint = findEndPoint()
                    if (endPoint != MAX_PEER) {
                        val peer = addPeer(from, endPoint)
                        peer.pc.addStream(localMS)
                        commandMap[type]!!.execute(from, payload)
                    }
                } else {
                    commandMap[type]!!.execute(from, payload)
                }
            } catch (e: JSONException) {
                e.printStackTrace()
            }
        }

        var onId: Emitter.Listener = Emitter.Listener { args ->
            val id = args[0] as String
            mListener.onCallReady(id)
        }

        init {
            this.commandMap = HashMap()
            commandMap["init"] = CreateOfferCommand()
            commandMap["offer"] = CreateAnswerCommand()
            commandMap["answer"] = SetRemoteSDPCommand()
            commandMap["candidate"] = AddIceCandidateCommand()
        }
    }

    inner class Peer(var id: String, var endPoint: Int) : SdpObserver, PeerConnection.Observer {
        var pc: PeerConnection

        override fun onCreateSuccess(sdp: SessionDescription) {
            // TODO: modify sdp to use pcParams prefered codecs
            try {
                val payload = JSONObject()
                payload.put("type", sdp.type.canonicalForm())
                payload.put("sdp", sdp.description)
                sendMessage(id, sdp.type.canonicalForm(), payload)
                pc.setLocalDescription(this@Peer, sdp)
            } catch (e: JSONException) {
                e.printStackTrace()
            }

        }

        override fun onSetSuccess() {}

        override fun onCreateFailure(s: String) {}

        override fun onSetFailure(s: String) {}

        override fun onSignalingChange(signalingState: PeerConnection.SignalingState) {}

        override fun onIceConnectionChange(iceConnectionState: PeerConnection.IceConnectionState) {
            if (iceConnectionState == PeerConnection.IceConnectionState.DISCONNECTED) {
                removePeer(id)
                mListener.onStatusChanged("DISCONNECTED")
            }
        }

        override fun onIceGatheringChange(iceGatheringState: PeerConnection.IceGatheringState) {}

        override fun onIceCandidate(candidate: IceCandidate) {
            try {
                val payload = JSONObject()
                payload.put("label", candidate.sdpMLineIndex)
                payload.put("id", candidate.sdpMid)
                payload.put("candidate", candidate.sdp)
                sendMessage(id, "candidate", payload)
            } catch (e: JSONException) {
                e.printStackTrace()
            }

        }

        override fun onAddStream(mediaStream: MediaStream) {
            Log.d(TAG, "onAddStream " + mediaStream.label())
            // remote streams are displayed from 1 to MAX_PEER (0 is localStream)
            mListener.onAddRemoteStream(mediaStream, endPoint + 1)
        }

        override fun onRemoveStream(mediaStream: MediaStream) {
            Log.d(TAG, "onRemoveStream " + mediaStream.label())
            removePeer(id)
        }

        override fun onDataChannel(dataChannel: DataChannel) {}

        override fun onRenegotiationNeeded() {

        }

        init {
            Log.d(TAG, "new Peer: $id $endPoint")
            this.pc = factory.createPeerConnection(iceServers, pcConstraints, this)

            pc.addStream(localMS) //, new MediaConstraints()

            mListener.onStatusChanged("CONNECTING")
        }
    }

    fun addPeer(id: String, endPoint: Int): Peer {
        val peer = Peer(id, endPoint)
        peers[id] = peer

        endPoints[endPoint] = true
        return peer
    }

    fun removePeer(id: String) {
        val peer = peers[id]
        mListener.onRemoveRemoteStream(peer!!.endPoint)
        peer.pc.close()
        peers.remove(peer.id)
        endPoints[peer.endPoint] = false
    }

    init {
        PeerConnectionFactory.initializeAndroidGlobals(
            mListener, true, true,
            pcParams.videoCodecHwAcceleration, mEGLcontext
        )
        factory = PeerConnectionFactory()
        val messageHandler = MessageHandler()

        try {
            client = IO.socket(host)
        } catch (e: URISyntaxException) {
            e.printStackTrace()
        }

        client.on("id", messageHandler.onId)
        client.on("message", messageHandler.onMessage)
        client.connect()

        iceServers.add(PeerConnection.IceServer("stun:23.21.150.121"))
        iceServers.add(PeerConnection.IceServer("stun:stun.l.google.com:19302"))

        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        pcConstraints.mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
        pcConstraints.optional.add(MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"))
    }

    /**
     * Call this method in Activity.onPause()
     */
    fun onPause() {
        if (videoSource != null) videoSource!!.stop()
    }

    /**
     * Call this method in Activity.onResume()
     */
    fun onResume() {
        if (videoSource != null) videoSource!!.restart()
    }

    /**
     * Call this method in Activity.onDestroy()
     */
    fun onDestroy() {
        for (peer in peers.values) {
            peer.pc.dispose()
        }
        if (videoSource != null) {
            videoSource!!.dispose()
        }
        factory.dispose()
        client.disconnect()
        client.close()
    }

    fun findEndPoint(): Int {
        for (i in 0 until MAX_PEER) if (!endPoints[i]) return i
        return MAX_PEER
    }

    /**
     * Start the client.
     *
     *
     * Set up the local stream and notify the signaling server.
     * Call this method after onCallReady.
     *
     * @param name client name
     */
    fun start(name: String) {
        setCamera()
        try {
            val message = JSONObject()
            message.put("name", name)
            client.emit("readyToStream", message)
        } catch (e: JSONException) {
            e.printStackTrace()
        }

    }

    fun setCamera() {
        localMS = factory.createLocalMediaStream("ARDAMS")
        if (pcParams.videoCallEnabled) {
            val videoConstraints = MediaConstraints()
            videoConstraints.mandatory.add(
                MediaConstraints.KeyValuePair(
                    "maxHeight",
                    Integer.toString(pcParams.videoHeight)
                )
            )
            videoConstraints.mandatory.add(
                MediaConstraints.KeyValuePair(
                    "maxWidth",
                    Integer.toString(pcParams.videoWidth)
                )
            )
            videoConstraints.mandatory.add(
                MediaConstraints.KeyValuePair(
                    "maxFrameRate",
                    Integer.toString(pcParams.videoFps)
                )
            )
            videoConstraints.mandatory.add(
                MediaConstraints.KeyValuePair(
                    "minFrameRate",
                    Integer.toString(pcParams.videoFps)
                )
            )

            videoSource = factory.createVideoSource(videoCapturer, videoConstraints)
            localMS.addTrack(factory.createVideoTrack("ARDAMSv0", videoSource!!))
        }

        val audioSource = factory.createAudioSource(MediaConstraints())
        localMS.addTrack(factory.createAudioTrack("ARDAMSa0", audioSource))

        mListener.onLocalStream(localMS)
    }

    companion object {
        val TAG = WebRtcClient::class.java.canonicalName
        val MAX_PEER = 2
    }
}
