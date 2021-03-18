package io.agora.scalableui.ui

import android.view.SurfaceView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.recyclerview.widget.RecyclerView
import io.agora.rtc.RtcEngine
import io.agora.rtc.video.VideoCanvas

class RemoteViewAdapter(private val uidList: ArrayList<Int>, private val mRtcEngine: RtcEngine?): RecyclerView.Adapter<RemoteViewAdapter.RemoteViewHolder>() {
    class RemoteViewHolder(val frame: FrameLayout): RecyclerView.ViewHolder(frame)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RemoteViewHolder {
        val remoteFrame = FrameLayout(parent.context)

        // The width of the FrameLayout is set to half the parent's width.
        // This is to make sure that the Grid has 2 columns
        remoteFrame.layoutParams = RecyclerView.LayoutParams(parent.measuredWidth / 2, RecyclerView.LayoutParams.MATCH_PARENT)
        return RemoteViewHolder(remoteFrame)
    }

    override fun onBindViewHolder(holder: RemoteViewHolder, position: Int) {

        // First we unmute the remote video stream so that Agora can start fetching the remote video feed
        // We have to do this since we mute the remote video in the onUserJoined callback to save on bandwidth
        mRtcEngine?.muteRemoteVideoStream(uidList[position], false)

        // CreateRendererView is used to create a SurfaceView object
        val surface = RtcEngine.CreateRendererView(holder.itemView.context)

        // We are tagging the SurfaceView object with the UID.
        // This keeps us from manually maintaining a mapping between the SurfaceView and UID
        // We'll see it used in the onViewRecycled method
        surface.tag = uidList[position]

        // We will now use Agora's setupRemoteVideo method to render the remote video stream on the SurfaceView
        mRtcEngine!!.setupRemoteVideo(VideoCanvas(surface, VideoCanvas.RENDER_MODE_HIDDEN, uidList[position]))

        // We'll add the SurfaceView as a child to the FrameLayout which is actually the ViewHolder in our RecyclerView
        holder.frame.addView(surface, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    override fun onViewRecycled(holder: RemoteViewHolder) {
        // We are calling this method when our view is removed from the RecyclerView Pool.
        // This allows us to save on bandwidth

        // We get the UID from the tag of the SurfaceView
        val uid = (holder.frame.getChildAt(0) as SurfaceView).tag as Int

        // We mute the remote video stream of the UID
        mRtcEngine?.muteRemoteVideoStream(uid, true)
    }

    override fun getItemCount() = uidList.size
}
