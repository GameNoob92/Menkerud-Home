package com.noobventure.menkerud.call

import android.content.Context
import android.util.Log
import com.noobventure.menkerud.api.LiveKitTicket
import io.livekit.android.LiveKit
import io.livekit.android.events.RoomEvent
import io.livekit.android.events.collect
import io.livekit.android.room.Room
import io.livekit.android.room.track.LocalVideoTrack
import io.livekit.android.room.track.Track
import io.livekit.android.room.track.VideoTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * One stay in one LiveKit room, with camera and microphone. Sound needs no setting here: LiveKit picks a Bluetooth
 * headset, then a wired one, then the loudspeaker, then the earpiece – the loudspeaker when nothing is plugged in.
 */
class LiveKitCallSession(context: Context) : CallSession {
    val room: Room = LiveKit.create(context.applicationContext)

    private val signals = RoomSignals()
    private val said = Channel<SessionEvent>(Channel.UNLIMITED)     // kept until the controller reads them
    override val events: Flow<SessionEvent> = said.receiveAsFlow()

    private val _remoteVideo = MutableStateFlow<VideoTrack?>(null)
    /** The other side's picture, when there is one. */
    val remoteVideo: StateFlow<VideoTrack?> = _remoteVideo.asStateFlow()

    private val _localVideo = MutableStateFlow<VideoTrack?>(null)
    /** One's own picture, once the camera is on. */
    val localVideo: StateFlow<VideoTrack?> = _localVideo.asStateFlow()

    private val _microphoneOn = MutableStateFlow(false)
    val microphoneOn: StateFlow<Boolean> = _microphoneOn.asStateFlow()

    private val _cameraOn = MutableStateFlow(false)
    val cameraOn: StateFlow<Boolean> = _cameraOn.asStateFlow()

    private val _frontCamera = MutableStateFlow(true)
    /** Whether the camera in use is the one on the front: its picture is shown mirrored, the back camera's is not. */
    val frontCamera: StateFlow<Boolean> = _frontCamera.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var closed = false

    init {
        scope.launch {
            room.events.collect { event ->
                when (event) {
                    is RoomEvent.ParticipantConnected -> say(signals.joined())
                    is RoomEvent.ParticipantDisconnected -> say(signals.left(room.remoteParticipants.size))
                    is RoomEvent.Disconnected -> say(signals.disconnected())
                    is RoomEvent.TrackSubscribed -> (event.track as? VideoTrack)?.let { _remoteVideo.value = it }
                    is RoomEvent.TrackUnsubscribed -> if (event.track === _remoteVideo.value) _remoteVideo.value = null
                    else -> Unit
                }
            }
        }
    }

    override suspend fun connect(ticket: LiveKitTicket) {
        room.connect(ticket.url, ticket.token)
        say(signals.connected(room.remoteParticipants.size))
        if (_remoteVideo.value == null) _remoteVideo.value = pictureAlreadyThere()
        // Without a camera, or without a microphone, the call still goes on with what there is.
        setCamera(true)
        setMicrophone(true)
    }

    suspend fun setMicrophone(on: Boolean) {
        if (attempt("microphone") { room.localParticipant.setMicrophoneEnabled(on) }) _microphoneOn.value = on
    }

    suspend fun setCamera(on: Boolean) {
        if (!attempt("camera") { room.localParticipant.setCameraEnabled(on) }) return
        _cameraOn.value = on
        if (on) _localVideo.value = cameraTrack()
    }

    fun switchCamera() {
        val track = cameraTrack() ?: return
        track.switchCamera()
        _frontCamera.value = !_frontCamera.value
    }

    override suspend fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        _remoteVideo.value = null
        _localVideo.value = null
        room.disconnect()
        room.release()                  // lets go of camera and microphone
    }

    private fun say(event: SessionEvent?) {
        if (event != null) said.trySend(event)
    }

    private fun cameraTrack(): LocalVideoTrack? =
        room.localParticipant.getTrackPublication(Track.Source.CAMERA)?.track as? LocalVideoTrack

    private fun pictureAlreadyThere(): VideoTrack? =
        room.remoteParticipants.values.firstNotNullOfOrNull { other ->
            other.videoTrackPublications.firstNotNullOfOrNull { (_, track) -> track as? VideoTrack }
        }

    private suspend fun attempt(what: String, block: suspend () -> Boolean): Boolean = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what: " + e.javaClass.simpleName)
        false
    }

    private companion object {
        const val TAG = "MenkerudHome"
    }
}
