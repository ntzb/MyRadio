package com.ntzb.myradio.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.ntzb.myradio.data.LikesRepository
import com.ntzb.myradio.data.StationRepository
import com.ntzb.myradio.model.Station
import com.ntzb.myradio.player.PlayerController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UiState(
    val stations: List<Station> = emptyList(),
    val likedIds: Set<String> = emptySet(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val currentStationId: String? = null,
    val stationName: String = "",
    val song: String = "",
    val volume: Float = 1f
) {
    val liked: List<Station> get() = stations.filter { it.id in likedIds }
    val current: Station? get() = stations.firstOrNull { it.id == currentStationId }
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
            val c = PlayerController.get(getApplication())
            player = c
            c.addListener(listener)
            syncFrom(c)
        }
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
                song = song,
                volume = p.volume
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
    }

    fun setVolume(v: Float) {
        player?.volume = v.coerceIn(0f, 1f)
        _state.update { it.copy(volume = v.coerceIn(0f, 1f)) }
    }

    override fun onCleared() {
        player?.removeListener(listener)
        super.onCleared()
    }
}
