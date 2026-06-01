package com.ntzb.myradio.ui

import android.app.Application
import android.content.Context
import android.media.AudioManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.ntzb.myradio.data.LikesRepository
import com.ntzb.myradio.data.PlaybackSnapshot
import com.ntzb.myradio.data.StationRepository
import com.ntzb.myradio.model.Station
import com.ntzb.myradio.player.PlayerController
import com.ntzb.myradio.widget.WidgetUpdater
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

data class UiState(
    val stations: List<Station> = emptyList(),
    val likedIds: Set<String> = emptySet(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentStationId: String? = null,
    val stationName: String = "",
    val song: String = "",
    val snapshotSong: String = "",   // from PlaybackSnapshot (covers Kan ACRCloud polling)
    val volume: Float = 1f
) {
    val liked: List<Station> get() = stations.filter { it.id in likedIds }
    val current: Station? get() = stations.firstOrNull { it.id == currentStationId }
    /** Song to display: controller's ICY song, else the snapshot (Kan polled) song. */
    val displaySong: String get() = song.ifBlank { snapshotSong }
}

class RadioViewModel(app: Application) : AndroidViewModel(app) {

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var player: Player? = null

    private val listener = object : Player.Listener {
        override fun onEvents(p: Player, events: Player.Events) = syncFrom(p)
    }

    init {
        viewModelScope.launch {
            _state.update { it.copy(stations = StationRepository.loadStations(getApplication())) }
        }
        viewModelScope.launch {
            LikesRepository.likedIds(getApplication()).collect { ids ->
                _state.update { it.copy(likedIds = ids) }
            }
        }
        viewModelScope.launch {
            PlaybackSnapshot.flow(getApplication()).collect { np ->
                _state.update { it.copy(snapshotSong = np.song) }
            }
        }
        viewModelScope.launch {
            val c = PlayerController.get(getApplication())
            player = c
            c.addListener(listener)
            syncFrom(c)
        }
        _state.update { it.copy(volume = currentSystemVolume()) }
        // Refresh any placed widget from current state when the app opens.
        viewModelScope.launch { WidgetUpdater.pushAll(getApplication()) }
    }

    private fun audioManager() =
        getApplication<Application>().getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun currentSystemVolume(): Float {
        val am = audioManager()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / max
    }

    private fun syncFrom(p: Player) {
        val md = p.mediaMetadata
        val name = md.station?.toString() ?: md.title?.toString().orEmpty()
        val song = md.title?.toString()?.takeIf { it.isNotBlank() && it != name }.orEmpty()
        _state.update {
            it.copy(
                isPlaying = p.isPlaying,
                isBuffering = p.playbackState == Player.STATE_BUFFERING,
                currentStationId = p.currentMediaItem?.mediaId,
                stationName = name,
                song = song
                // volume is system media volume, managed separately (not the player's gain)
            )
        }
    }

    fun play(station: Station) = viewModelScope.launch {
        PlayerController.playStation(getApplication(), station)
    }

    fun togglePlayPause() = viewModelScope.launch {
        PlayerController.togglePlayPause(getApplication())
    }

    fun stop() = viewModelScope.launch { PlayerController.stop(getApplication()) }

    fun toggleLike(id: String) = viewModelScope.launch {
        LikesRepository.toggle(getApplication(), id)
        WidgetUpdater.pushAll(getApplication())   // add/remove the station in the widget grid
    }

    fun setVolume(v: Float) {
        val vol = v.coerceIn(0f, 1f)
        val am = audioManager()
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        am.setStreamVolume(AudioManager.STREAM_MUSIC, (vol * max).roundToInt(), 0)
        _state.update { it.copy(volume = vol) }
    }

    override fun onCleared() {
        player?.removeListener(listener)
        super.onCleared()
    }
}
