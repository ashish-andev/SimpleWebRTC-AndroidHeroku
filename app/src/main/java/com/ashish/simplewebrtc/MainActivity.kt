package com.ashish.simplewebrtc

import android.Manifest
import android.content.Intent
import android.graphics.Point
import android.opengl.GLSurfaceView
import android.view.Window
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import org.json.JSONException
import org.webrtc.MediaStream
import org.webrtc.VideoRenderer
import org.webrtc.VideoRendererGui

class MainActivity : AppCompatActivity(), WebRtcClient.RtcListener {
    var scalingType: VideoRendererGui.ScalingType = VideoRendererGui.ScalingType.SCALE_ASPECT_FILL
    lateinit var vsv: GLSurfaceView
    lateinit var localRender: VideoRenderer.Callbacks
    lateinit var remoteRender: VideoRenderer.Callbacks
    var client: WebRtcClient? = null
    lateinit var mSocketAddress: String
    var callerId: String? = null
    protected var permissionChecker = PermissionChecker()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.addFlags(
            WindowManager.LayoutParams.FLAG_FULLSCREEN
                    or WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)


        setContentView(R.layout.activity_main)
        mSocketAddress = "https://simplewebrtctshish7017.herokuapp.com"

        vsv = findViewById(R.id.glview_call)
        vsv.preserveEGLContextOnPause = true
        vsv.keepScreenOn = true
        VideoRendererGui.setView(vsv) { init() }

        // local and remote render
        remoteRender = VideoRendererGui.create(
            REMOTE_X, REMOTE_Y,
            REMOTE_WIDTH, REMOTE_HEIGHT, scalingType, false
        )
        localRender = VideoRendererGui.create(
            LOCAL_X_CONNECTING, LOCAL_Y_CONNECTING,
            LOCAL_WIDTH_CONNECTING, LOCAL_HEIGHT_CONNECTING, scalingType, true
        )

        val intent = intent
        val action = intent.action

        if (Intent.ACTION_VIEW == action) {
            val segments = intent.data!!.pathSegments
            callerId = segments[0]
        }
        checkPermissions()
    }

    fun checkPermissions() {
        permissionChecker.verifyPermissions(
            this,
            RequiredPermissions,
            object : PermissionChecker.VerifyPermissionsCallback {

                override fun onPermissionAllGranted() {

                }

                override fun onPermissionDeny(permissions: Array<String>) {
                    Toast.makeText(this@MainActivity, "Please grant required permissions.", Toast.LENGTH_LONG).show()
                }
            })
    }

    fun init() {
        val displaySize = Point()
        windowManager.defaultDisplay.getSize(displaySize)
        val params = PeerConnectionParameters(
            true, false, displaySize.x, displaySize.y, 30, 1, VIDEO_CODEC_VP9, true, 1, AUDIO_CODEC_OPUS, true
        )

        client = WebRtcClient(this, mSocketAddress, params, VideoRendererGui.getEGLContext())
    }

    public override fun onPause() {
        super.onPause()
        vsv.onPause()
        if (client != null) {
            client!!.onPause()
        }
    }

    public override fun onResume() {
        super.onResume()
        vsv.onResume()
        if (client != null) {
            client!!.onResume()
        }
    }

    public override fun onDestroy() {
        if (client != null) {
            client!!.onDestroy()
        }
        super.onDestroy()
    }

    override fun onCallReady(callId: String) {
        if (callerId != null) {
            try {
                answer(callerId!!)
            } catch (e: JSONException) {
                e.printStackTrace()
            }

        } else {
            call(callId)
        }
    }

    @Throws(JSONException::class)
    fun answer(callerId: String) {
        client!!.sendMessage(callerId, "init", null!!)
        startCam()
    }

    fun call(callId: String) {
        startCam()
        /*Intent msg = new Intent(Intent.ACTION_SEND);
        msg.putExtra(Intent.EXTRA_TEXT, mSocketAddress + callId);
        msg.setType("text/plain");
        startActivityForResult(Intent.createChooser(msg, "Call someone :"), VIDEO_CALL_SENT);*/
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == VIDEO_CALL_SENT) {
            startCam()
        }
    }

    fun startCam() {
        // Camera settings
        if (PermissionChecker.hasPermissions(this, RequiredPermissions)) {
            client!!.start("android_test")
        }
    }

    override fun onStatusChanged(newStatus: String) {
        runOnUiThread { Toast.makeText(applicationContext, newStatus, Toast.LENGTH_SHORT).show() }
    }

    override fun onLocalStream(localStream: MediaStream) {
        localStream.videoTracks[0].addRenderer(VideoRenderer(localRender))
        VideoRendererGui.update(
            localRender,
            LOCAL_X_CONNECTING, LOCAL_Y_CONNECTING,
            LOCAL_WIDTH_CONNECTING, LOCAL_HEIGHT_CONNECTING,
            scalingType, false
        )
    }

    override fun onAddRemoteStream(remoteStream: MediaStream, endPoint: Int) {
        remoteStream.videoTracks[0].addRenderer(VideoRenderer(remoteRender))
        VideoRendererGui.update(
            remoteRender,
            REMOTE_X, REMOTE_Y,
            REMOTE_WIDTH, REMOTE_HEIGHT, scalingType, false
        )
        VideoRendererGui.update(
            localRender,
            LOCAL_X_CONNECTED, LOCAL_Y_CONNECTED,
            LOCAL_WIDTH_CONNECTED, LOCAL_HEIGHT_CONNECTED,
            scalingType, false
        )
    }

    override fun onRemoveRemoteStream(endPoint: Int) {
        VideoRendererGui.update(
            localRender,
            LOCAL_X_CONNECTING, LOCAL_Y_CONNECTING,
            LOCAL_WIDTH_CONNECTING, LOCAL_HEIGHT_CONNECTING,
            scalingType, false
        )
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        permissionChecker.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }

    companion object {
        val VIDEO_CALL_SENT = 666
        val VIDEO_CODEC_VP9 = "VP9"
        val AUDIO_CODEC_OPUS = "opus"
        // Local preview screen position before call is connected.
        val LOCAL_X_CONNECTING = 0
        val LOCAL_Y_CONNECTING = 0
        val LOCAL_WIDTH_CONNECTING = 100
        val LOCAL_HEIGHT_CONNECTING = 100
        // Local preview screen position after call is connected.
        val LOCAL_X_CONNECTED = 72
        val LOCAL_Y_CONNECTED = 72
        val LOCAL_WIDTH_CONNECTED = 25
        val LOCAL_HEIGHT_CONNECTED = 25
        // Remote video screen position
        val REMOTE_X = 0
        val REMOTE_Y = 0
        val REMOTE_WIDTH = 100
        val REMOTE_HEIGHT = 100

        val RequiredPermissions = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
    }
}
