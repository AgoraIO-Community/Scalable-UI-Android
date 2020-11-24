package io.agora.scalableui

import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.agora.rtc.Constants
import io.agora.rtc.IRtcEngineEventHandler
import io.agora.rtc.RtcEngine
import io.agora.rtc.video.VideoCanvas
import io.agora.rtc.video.VideoEncoderConfiguration
import io.agora.scalableui.ui.RemoteViewAdapter
import java.util.concurrent.locks.ReentrantLock

class MainActivity : AppCompatActivity() {

    private var mRtcEngine: RtcEngine? = null
    private lateinit var remoteViewAdapter: RemoteViewAdapter
    private var uidList = ArrayList<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private val lock = ReentrantLock()

    companion object {
        private val LOG_TAG = MainActivity::class.java.simpleName

        private const val APP_ID = ""
        private const val CHANNEL = ""
        private const val TOKEN = ""

        private const val PERMISSION_CODE = 22
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (permissionsGranted()) {
            initializeApplication()
        } else {
            requestPermissions()
        }
    }

    private fun permissionsGranted(): Boolean {
        return ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                && ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(android.Manifest.permission.RECORD_AUDIO, android.Manifest.permission.CAMERA), PERMISSION_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        when (requestCode) {
            PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                    initializeApplication()
                } else {
                    finish()
                }
            }
        }
    }

    private fun initializeApplication() {
        // We first initialize our RtcEngine. This object will be used to call our Agora related methods
        try {
            mRtcEngine = RtcEngine.create(baseContext, APP_ID, object : IRtcEngineEventHandler() {
                override fun onUserJoined(uid: Int, elapsed: Int) {
                    // onUserJoined callback is called anytime a new remote user joins the channel
                    super.onUserJoined(uid, elapsed)

                    // We mute the stream by default so that it doesn't consume unnecessary bandwidth
                    mRtcEngine?.muteRemoteVideoStream(uid, true)

                    // We are using a lock since uidList is shared and there can be race conditions
                    lock.lock()
                    try {
                        // We are using uidList to keep track of the UIDs of the remote users
                        uidList.add(uid)
                    } finally {
                        lock.unlock()
                    }

                    // We are using the handler since UI Changes need to be run on the UI Thread
                    handler.post {

                        // When the the number of remote users grows to 4, we switch to the lower stream
                        // When this happens, we will now be using a lower resolution and bitrate to save on bandwidth
                        if (uidList.size == 4) {
                            mRtcEngine?.setRemoteDefaultVideoStreamType(Constants.VIDEO_STREAM_LOW)
                            Toast.makeText(
                                applicationContext,
                                "Fallback to Low Video Stream",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        // We notify our RecyclerView adapter that a new video stream is added
                        remoteViewAdapter.notifyItemInserted(uidList.size - 1)
                    }
                }

                override fun onUserOffline(uid: Int, reason: Int) {
                    // onUserOffline is called whenever a remote user leaves the channel
                    super.onUserOffline(uid, reason)

                    // We use toRemove to inform the RecyclerView of the index of item we are removing
                    val toRemove: Int

                    // We are using a lock since uidList is shared and there can be race conditions
                    lock.lock()
                    try {
                        // We are fetching the index of the item we are about to remove and then remove the item
                        toRemove = uidList.indexOf(uid)
                        uidList.remove(uid)
                    } finally {
                        lock.unlock()
                    }

                    // We are using the handler since UI Changes need to be run on the UI Thread
                    handler.post {
                        // We are using this to remove the remote video from being rendered on the SurfaceView
                        mRtcEngine?.setupRemoteVideo(VideoCanvas(null, VideoCanvas.RENDER_MODE_HIDDEN, uid))

                        // When the number of remote users shrinks to 3, we switch back to the higher stream
                        // When we have <= 3 remote users, the bandwidth savings are no longer necessary
                        if (uidList.size == 3) {
                            mRtcEngine?.setRemoteDefaultVideoStreamType(Constants.VIDEO_STREAM_HIGH)
                            Toast.makeText(
                                applicationContext,
                                "Go back to High Video Stream",
                                Toast.LENGTH_LONG
                            ).show()
                        }

                        // We notify our RecyclerView adapter that the video stream can be removed
                        remoteViewAdapter.notifyItemRemoved(toRemove)
                    }
                }
            })
        } catch (e: Exception) {
            Log.e(LOG_TAG, Log.getStackTraceString(e))
        }

        // By Default, Video is disabled. So we enable that
        mRtcEngine!!.enableVideo()

        // We set some standard configuration like resolution, FPS and bitrate of the video
        mRtcEngine!!.setVideoEncoderConfiguration(VideoEncoderConfiguration(VideoEncoderConfiguration.VD_640x360,
            VideoEncoderConfiguration.FRAME_RATE.FRAME_RATE_FPS_15,
            VideoEncoderConfiguration.STANDARD_BITRATE,
            VideoEncoderConfiguration.ORIENTATION_MODE.ORIENTATION_MODE_FIXED_PORTRAIT))

        // All videos will be rendered on a SurfaceView.
        // We are first fetching a reference to the FrameLayout we have defined in the XML
        // We are then using Agora to create a SurfaceView object
        // We are then adding this SurfaceView as a child to the FrameLayout
        // And then, we are passing the SurfaceView to Agora so that it can render our local video on it
        val localContainer = findViewById<FrameLayout>(R.id.local_container)
        val localFrame = RtcEngine.CreateRendererView(baseContext)
        localContainer.addView(localFrame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        mRtcEngine!!.setupLocalVideo(VideoCanvas(localFrame, VideoCanvas.RENDER_MODE_HIDDEN, 0))

        // We are calling this method to join the Agora Channel
        // We are passing in token. If it is empty, we are passing in null as the token
        mRtcEngine!!.joinChannel(if (TOKEN.isEmpty()) null else TOKEN, CHANNEL, "", 0)

        // This method allows us to fallback to low resolution and low bitrate
        mRtcEngine!!.enableDualStreamMode(true)

        // We are using GridLayoutManager to arrange the items of the RecyclerView in a grid pattern
        // We are setting span count as 2, so that are grid has 2 rows
        // We are setting the orientation of the layout as horizontal
        val remoteViewManager = GridLayoutManager(this, 2, GridLayoutManager.HORIZONTAL, false)

        // We are using our custom adapter here
        remoteViewAdapter = RemoteViewAdapter(uidList, mRtcEngine)

        // We are now referencing the RecyclerView in our XML and then setting the relevant parameters
        // We are setting hasFixedSize as true since the measurement of the individual items will not dynamically change
        findViewById<RecyclerView>(R.id.remote_container).apply {
            layoutManager = remoteViewManager
            adapter = remoteViewAdapter
            setHasFixedSize(true)
        }
    }

    override fun onDestroy() {
        super.onDestroy()

        // We are cleaning up all the resources Agora uses here
        mRtcEngine?.leaveChannel()
        handler.post { RtcEngine.destroy() }
        mRtcEngine = null
    }
}