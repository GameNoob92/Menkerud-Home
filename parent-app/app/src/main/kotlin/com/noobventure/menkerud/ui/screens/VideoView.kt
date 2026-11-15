package com.noobventure.menkerud.ui.screens

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import io.livekit.android.renderer.TextureViewRenderer
import io.livekit.android.room.Room
import io.livekit.android.room.track.VideoTrack
import livekit.org.webrtc.RendererCommon

/**
 * Shows one video track of [room]. `fill`: fill the whole area and cut what does not fit (one's own small picture);
 * otherwise the whole picture is shown with empty space around it (the picture from home, which is wider than a
 * phone held upright). LiveKit's own Compose components are not used: they pin an older SDK.
 */
@Composable
fun VideoView(room: Room, track: VideoTrack?, mirror: Boolean, modifier: Modifier = Modifier, fill: Boolean = false) {
    val context = LocalContext.current
    val view = remember(room) { TextureViewRenderer(context).also { room.initVideoRenderer(it) } }

    DisposableEffect(view, track) {
        track?.addRenderer(view)
        onDispose { track?.removeRenderer(view) }
    }
    DisposableEffect(view) {
        onDispose { view.release() }
    }
    AndroidView(
        factory = { view },
        modifier = modifier,
        update = {
            it.setMirror(mirror)
            it.setScalingType(if (fill) RendererCommon.ScalingType.SCALE_ASPECT_FILL else RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        }
    )
}
