package com.noctalia.music.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.noctalia.music.data.DeviceAudioProfile
import com.noctalia.music.data.LibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class EqPreset(val label: String, val gainsMb: IntArray) {
    FLAT("Plano", intArrayOf(0, 0, 0, 0, 0)),
    BASS("Bass Boost", intArrayOf(550, 350, 50, 100, 200)),
    URBANO("Urbano / Reggaetón", intArrayOf(500, 250, 50, 200, 150)),
    HIPHOP("Hip-Hop / Rap", intArrayOf(550, 300, -50, 150, 200)),
    PHONK("Phonk", intArrayOf(650, 400, -100, 250, 450)),
    ELECTRO("Electrónica", intArrayOf(450, 200, 0, 250, 450)),
    POP("Pop", intArrayOf(150, 250, 100, 250, 200)),
    ROCK("Rock", intArrayOf(350, 150, -100, 250, 400)),
    METAL("Metal", intArrayOf(450, 150, -250, 350, 450)),
    ACOUSTIC("Acústico / Indie", intArrayOf(100, 200, 250, 300, 200)),
    VOCAL("Vocal", intArrayOf(-150, 100, 400, 350, 150)),
    LOFI("Lo-Fi / Chill", intArrayOf(300, 200, 50, -150, -350)),
    CLASSICAL("Clásica / OST", intArrayOf(200, 100, -50, 250, 400)),
    NIGHT("Modo Noche", intArrayOf(-400, -150, 350, 300, -200)),
    CAR("Coche / Altavoz", intArrayOf(300, 100, 150, 300, 350)),
    CUSTOM("Personalizado", intArrayOf(0, 0, 0, 0, 0)),
}

data class AudioFxState(
    val preset: EqPreset = EqPreset.FLAT,
    val bandFreqLabels: List<String> = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz"),
    val bandLevelsMb: List<Int> = listOf(0, 0, 0, 0, 0),
    val minBandMb: Int = -1500,
    val maxBandMb: Int = 1500,
    val bassBoostStrength: Int = 0, // 0..1000
    val normalizeVolume: Boolean = true,
    val sponsorBlockEnabled: Boolean = true,
    val skipSilenceEnabled: Boolean = false,
    val smoothFadeEnabled: Boolean = true,
    val crossfadeSec: Int = 0, // 0..10
    val playbackSpeed: Float = 1.0f, // 0.5..2.0
    val playbackPitch: Float = 1.0f, // 0.6..1.4
    val activeOutputKey: String = "speaker",
    val activeOutputLabel: String = "Altavoz del teléfono",
    val activeOutputIsBluetooth: Boolean = false,
    val perDeviceProfilesEnabled: Boolean = true,
    val savedProfileCount: Int = 0,
    val attached: Boolean = false,
)

/**
 * Controlador de efectos de audio nativos de Android (Ecualizador manual de 5 bandas + Presets +
 * BassBoost + LoudnessEnhancer) con perfiles automáticos por dispositivo Bluetooth / Cable / Altavoz
 * usando `AudioManager.AudioDeviceCallback` (0 permisos peligrosos de Bluetooth requeridos).
 */
class AudioEffectsController(
    private val context: Context,
    private val library: LibraryRepository,
) {
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var loudness: LoudnessEnhancer? = null
    private var currentSessionId: Int = 0

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile
    private var pendingProfilePersist: Boolean = false
    @Volatile
    private var pendingCrossfadePersist: Boolean = false
    private val persistProfileRunnable = Runnable {
        pendingProfilePersist = false
        persistActiveProfileIfEnabled()
    }
    private val persistCrossfadeRunnable = Runnable {
        pendingCrossfadePersist = false
        library.updateCrossfadeSec(_state.value.crossfadeSec)
    }

    private val _state = MutableStateFlow(
        run {
            val s = library.state.value.settings
            AudioFxState(
                skipSilenceEnabled = s.skipSilence,
                smoothFadeEnabled = s.smoothFade,
                crossfadeSec = s.crossfadeSec.coerceIn(0, 10),
                perDeviceProfilesEnabled = s.perDeviceAudioProfiles,
                savedProfileCount = s.audioProfiles.size,
            )
        }
    )
    val state: StateFlow<AudioFxState> = _state.asStateFlow()

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refreshActiveAudioOutput(applyProfile = true)
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refreshActiveAudioOutput(applyProfile = true)
        }
    }

    init {
        refreshActiveAudioOutput(applyProfile = true)
        try {
            audioManager?.registerAudioDeviceCallback(deviceCallback, mainHandler)
        } catch (_: Exception) {
        }
    }

    private fun detectActiveOutput(): Triple<String, String, Boolean> {
        val devices = try {
            audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS).orEmpty()
        } catch (_: Exception) {
            emptyArray()
        }
        // Prioridad: Bluetooth A2DP/BLE > Auriculares cable/USB > Altavoz integrado
        val bt = devices.firstOrNull { d ->
            d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    (d.type == AudioDeviceInfo.TYPE_BLE_HEADSET || d.type == AudioDeviceInfo.TYPE_BLE_SPEAKER)) ||
                d.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
        if (bt != null) {
            val rawName = bt.productName?.toString()?.trim().orEmpty().ifBlank { "Bluetooth" }
            val safeKey = "bt_" + rawName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(40).ifEmpty { "device" }
            return Triple(safeKey, "Bluetooth · ${rawName.take(28)}", true)
        }

        val wired = devices.firstOrNull { d ->
            d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_USB_DEVICE ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES
        }
        if (wired != null) {
            val rawName = wired.productName?.toString()?.trim().orEmpty()
            val label = if (rawName.isNotBlank() && !rawName.equals(Build.MODEL, ignoreCase = true)) {
                "Cable / USB · ${rawName.take(24)}"
            } else {
                "Auriculares por cable / USB"
            }
            val safeKey = "wired_" + rawName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').take(32).ifEmpty { "headphones" }
            return Triple(safeKey, label, false)
        }

        return Triple("speaker", "Altavoz del teléfono", false)
    }

    fun refreshActiveAudioOutput(applyProfile: Boolean = true) {
        val (key, label, isBt) = detectActiveOutput()
        val prevKey = _state.value.activeOutputKey
        // Guardar cualquier cambio pendiente (debounce 400ms) en el dispositivo ANTERIOR antes de cambiar de salida
        if (key != prevKey && pendingProfilePersist) {
            flushPendingPersists()
        }
        val settings = library.state.value.settings
        _state.update {
            it.copy(
                activeOutputKey = key,
                activeOutputLabel = label,
                activeOutputIsBluetooth = isBt,
                savedProfileCount = settings.audioProfiles.size,
            )
        }
        if (applyProfile && _state.value.perDeviceProfilesEnabled && (key != prevKey || !_state.value.attached)) {
            val saved = settings.audioProfiles[key]
            if (saved != null) {
                loadProfile(saved)
            }
        }
    }

    private fun loadProfile(profile: DeviceAudioProfile) {
        val preset = EqPreset.entries.firstOrNull { it.name == profile.presetName } ?: EqPreset.FLAT
        val curBandCount = _state.value.bandLevelsMb.size.coerceAtLeast(5)
        val minMb = _state.value.minBandMb
        val maxMb = _state.value.maxBandMb
        val bands = if (preset == EqPreset.CUSTOM) {
            List(curBandCount) { i -> profile.bandLevelsMb.getOrElse(i) { 0 }.coerceIn(minMb, maxMb) }
        } else {
            mapPresetToBands(preset, curBandCount, minMb, maxMb)
        }
        _state.update {
            it.copy(
                preset = preset,
                bandLevelsMb = bands,
                bassBoostStrength = profile.bassBoost.coerceIn(0, 1000),
                normalizeVolume = profile.normalizeVolume,
            )
        }
        applyAll()
    }

    private fun persistActiveProfileIfEnabled() {
        val st = _state.value
        if (!st.perDeviceProfilesEnabled) return
        library.saveDeviceAudioProfile(
            deviceKey = st.activeOutputKey,
            profile = DeviceAudioProfile(
                presetName = st.preset.name,
                bandLevelsMb = st.bandLevelsMb,
                bassBoost = st.bassBoostStrength,
                normalizeVolume = st.normalizeVolume,
            ),
        )
        _state.update { it.copy(savedProfileCount = library.state.value.settings.audioProfiles.size) }
    }

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

        // Leer frecuencias reales de las bandas del hardware Android (normalmente 5 bandas)
        equalizer?.let { eq ->
            try {
                val count = eq.numberOfBands.toInt().coerceIn(3, 10)
                val range = eq.bandLevelRange
                val minLvl = range[0].toInt().coerceIn(-2000, -300)
                val maxLvl = range[1].toInt().coerceIn(300, 2000)
                val labels = List(count) { b ->
                    val hz = eq.getCenterFreq(b.toShort()) / 1000
                    if (hz >= 1000) {
                        val khz = hz / 1000f
                        if (khz == khz.toInt().toFloat()) "${khz.toInt()}kHz" else "%.1fkHz".format(khz)
                    } else {
                        "${hz}Hz"
                    }
                }
                val cur = _state.value
                val bands = if (cur.bandLevelsMb.size == count) {
                    cur.bandLevelsMb.map { it.coerceIn(minLvl, maxLvl) }
                } else {
                    mapPresetToBands(cur.preset, count, minLvl, maxLvl)
                }
                _state.update {
                    it.copy(
                        bandFreqLabels = labels,
                        bandLevelsMb = bands,
                        minBandMb = minLvl,
                        maxBandMb = maxLvl,
                    )
                }
            } catch (_: Exception) {
            }
        }

        _state.update { it.copy(attached = equalizer != null || bassBoost != null || loudness != null) }
        applyAll()
    }

    private fun scheduleProfilePersist() {
        if (!_state.value.perDeviceProfilesEnabled) return
        pendingProfilePersist = true
        mainHandler.removeCallbacks(persistProfileRunnable)
        mainHandler.postDelayed(persistProfileRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun scheduleCrossfadePersist() {
        pendingCrossfadePersist = true
        mainHandler.removeCallbacks(persistCrossfadeRunnable)
        mainHandler.postDelayed(persistCrossfadeRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun flushPendingPersists() {
        mainHandler.removeCallbacks(persistProfileRunnable)
        mainHandler.removeCallbacks(persistCrossfadeRunnable)
        if (pendingProfilePersist) {
            pendingProfilePersist = false
            persistActiveProfileIfEnabled()
        }
        if (pendingCrossfadePersist) {
            pendingCrossfadePersist = false
            library.updateCrossfadeSec(_state.value.crossfadeSec)
        }
    }

    fun setPreset(preset: EqPreset) {
        val st = _state.value
        val nextBands = if (preset == EqPreset.CUSTOM) {
            st.bandLevelsMb
        } else {
            mapPresetToBands(preset, st.bandLevelsMb.size.coerceAtLeast(5), st.minBandMb, st.maxBandMb)
        }
        _state.update { it.copy(preset = preset, bandLevelsMb = nextBands) }
        applyAll()
        mainHandler.removeCallbacks(persistProfileRunnable)
        pendingProfilePersist = false
        persistActiveProfileIfEnabled()
    }

    fun setBandLevel(bandIndex: Int, levelMb: Int) {
        val st = _state.value
        if (bandIndex !in st.bandLevelsMb.indices) return
        val clamped = levelMb.coerceIn(st.minBandMb, st.maxBandMb)
        val nextBands = st.bandLevelsMb.toMutableList().apply { this[bandIndex] = clamped }
        _state.update { it.copy(preset = EqPreset.CUSTOM, bandLevelsMb = nextBands) }
        applyAll()
        scheduleProfilePersist()
    }

    fun resetEqualizerBands() {
        val st = _state.value
        val zeros = List(st.bandLevelsMb.size.coerceAtLeast(5)) { 0 }
        _state.update { it.copy(preset = EqPreset.FLAT, bandLevelsMb = zeros, bassBoostStrength = 0) }
        applyAll()
        mainHandler.removeCallbacks(persistProfileRunnable)
        pendingProfilePersist = false
        persistActiveProfileIfEnabled()
    }

    fun setBassBoost(strength: Int) {
        val clamped = strength.coerceIn(0, 1000)
        _state.update { it.copy(bassBoostStrength = clamped) }
        applyAll()
        scheduleProfilePersist()
    }

    fun setNormalizeVolume(enabled: Boolean) {
        _state.update { it.copy(normalizeVolume = enabled) }
        applyAll()
        mainHandler.removeCallbacks(persistProfileRunnable)
        pendingProfilePersist = false
        persistActiveProfileIfEnabled()
    }

    fun setPerDeviceProfilesEnabled(enabled: Boolean) {
        library.updatePerDeviceAudioProfiles(enabled)
        _state.update { it.copy(perDeviceProfilesEnabled = enabled) }
        if (enabled) {
            val saved = library.state.value.settings.audioProfiles[_state.value.activeOutputKey]
            if (saved != null) loadProfile(saved) else persistActiveProfileIfEnabled()
        }
    }

    fun setCrossfadeSec(sec: Int) {
        val clamped = sec.coerceIn(0, 10)
        _state.update { it.copy(crossfadeSec = clamped) }
        scheduleCrossfadePersist()
    }

    fun setSponsorBlock(enabled: Boolean) {
        _state.update { it.copy(sponsorBlockEnabled = enabled) }
    }

    fun setSkipSilence(enabled: Boolean) {
        _state.update { it.copy(skipSilenceEnabled = enabled) }
    }

    fun setSmoothFade(enabled: Boolean) {
        _state.update { it.copy(smoothFadeEnabled = enabled) }
    }

    fun setSpeedAndPitch(speed: Float, pitch: Float) {
        val s = speed.coerceIn(0.5f, 2.0f)
        val p = pitch.coerceIn(0.6f, 1.4f)
        _state.update { it.copy(playbackSpeed = s, playbackPitch = p) }
    }

    private fun mapPresetToBands(preset: EqPreset, count: Int, minLvl: Int, maxLvl: Int): List<Int> {
        val gains = preset.gainsMb
        return List(count) { b ->
            val idx = ((b.toFloat() / count.coerceAtLeast(1)) * gains.size).toInt().coerceIn(0, gains.lastIndex)
            gains[idx].coerceIn(minLvl, maxLvl)
        }
    }

    @Synchronized
    private fun applyAll() {
        val st = _state.value
        equalizer?.let { eq ->
            try {
                val hasActiveEq = st.bandLevelsMb.any { it != 0 }
                val bands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val minLvl = range[0].toInt()
                val maxLvl = range[1].toInt()
                for (b in 0 until bands) {
                    val target = st.bandLevelsMb.getOrElse(b) { 0 }.coerceIn(minLvl, maxLvl).toShort()
                    eq.setBandLevel(b.toShort(), target)
                }
                // Desactivar el DSP del ecualizador cuando todas las bandas están en 0 dB (Plano)
                eq.enabled = hasActiveEq
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
                    ld.setTargetGain(250)
                }
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun release() {
        flushPendingPersists()
        try { equalizer?.release() } catch (_: Exception) {}
        try { bassBoost?.release() } catch (_: Exception) {}
        try { loudness?.release() } catch (_: Exception) {}
        equalizer = null
        bassBoost = null
        loudness = null
        currentSessionId = 0
        _state.update { it.copy(attached = false) }
    }

    companion object {
        private const val PERSIST_DEBOUNCE_MS = 400L
    }
}
