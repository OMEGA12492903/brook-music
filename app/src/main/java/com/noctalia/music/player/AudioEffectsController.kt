package com.noctalia.music.player

import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EqPreset(val label: String, val gainsMb: IntArray) {
    FLAT("Plano", intArrayOf(0, 0, 0, 0, 0)),
    BASS("Bass Boost", intArrayOf(550, 350, 50, 100, 200)),
    PHONK("Phonk", intArrayOf(650, 400, -100, 250, 450)),
    ELECTRO("Electrónica", intArrayOf(450, 200, 0, 250, 450)),
    ROCK("Rock", intArrayOf(350, 150, -100, 250, 400)),
    VOCAL("Vocal", intArrayOf(-150, 100, 400, 350, 150)),
}

data class AudioFxState(
    val preset: EqPreset = EqPreset.FLAT,
    val bassBoostStrength: Int = 0, // 0..1000
    val normalizeVolume: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val attached: Boolean = false,
)

/**
 * Controlador de efectos de audio nativos de Android (Equalizer + BassBoost + LoudnessEnhancer)
 * conectado al `audioSessionId` de ExoPlayer. Totalmente seguro ante OEMs que restrinjan audiofx.
 */
class AudioEffectsController {

    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var currentSessionId: Int = 0

    private val _state = MutableStateFlow(AudioFxState())
    val state: StateFlow<AudioFxState> = _state.asStateFlow()

    @Synchronized
    fun attachToSession(audioSessionId: Int) {
        if (audioSessionId <= 0 || audioSessionId == currentSessionId) return
        release()
        currentSessionId = audioSessionId
        try {
            equalizer = Equalizer(0, audioSessionId).apply { enabled = true }
        } catch (_: Exception) {
            equalizer = null
        }
        try {
            bassBoost = BassBoost(0, audioSessionId).apply { enabled = true }
        } catch (_: Exception) {
            bassBoost = null
        }
        try {
            loudness = LoudnessEnhancer(audioSessionId).apply { enabled = _state.value.normalizeVolume }
        } catch (_: Exception) {
            loudness = null
        }
        _state.update { it.copy(attached = equalizer != null || bassBoost != null || loudness != null) }
        applyAll()
    }

    fun setPreset(preset: EqPreset) {
        _state.update { it.copy(preset = preset) }
        applyAll()
    }

    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _state.update { it.copy(bassBoostStrength = clamped) }
        applyAll()
    }

    fun setNormalizeVolume(enabled: Boolean) {
        _state.update { it.copy(normalizeVolume = enabled) }
        applyAll()
    }

    fun setSponsorBlock(enabled: Boolean) {
        _state.update { it.copy(sponsorBlockEnabled = enabled) }
    }

    @Synchronized
    private fun applyAll() {
        val st = _state.value
        equalizer?.let { eq ->
            try {
                val bands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val minLvl = range[0].toInt()
                val maxLvl = range[1].toInt()
                val gains = st.preset.gainsMb
                for (b in 0 until bands) {
                    val idx = ((b.toFloat() / bands.coerceAtLeast(1)) * gains.size).toInt().coerceIn(0, gains.lastIndex)
                    val target = gains[idx].coerceIn(minLvl, maxLvl).toShort()
                    eq.setBandLevel(b.toShort(), target)
                }
            } catch (_: Exception) {
            }
        }
        bassBoost?.let { bb ->
            try {
                if (bb.strengthSupported) {
                    bb.setStrength(st.bassBoostStrength.toShort())
                }
                bb.enabled = st.bassBoostStrength > 0
            } catch (_: Exception) {
            }
        }
        loudness?.let { ld ->
            try {
                ld.enabled = st.normalizeVolume
                if (st.normalizeVolume) {
                    // +250 mB (~+2.5 dB) nivelación suave sin distorsión
                    ld.setTargetGain(250)
                }
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { loudness?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
        loudness = null
        currentSessionId = 0
        _state.update { it.copy(attached = false) }
    }
}
