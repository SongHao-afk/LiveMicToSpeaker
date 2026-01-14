// MainActivity.kt
package com.example.flutter_application_3

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.*
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import androidx.annotation.NonNull
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
import io.flutter.plugin.common.MethodChannel
import kotlin.math.*

class MainActivity : FlutterActivity() {
  private val CHANNEL = "loopback"
  private val EVENTS = "loopback_events"
  private val TAG = "MainActivity"

  private lateinit var audioManager: AudioManager
  private val mainHandler = Handler(Looper.getMainLooper())

  private var thread: Thread? = null
  @Volatile private var running = false

  private var recorder: AudioRecord? = null
  private var player: AudioTrack? = null

  // AudioFX
  private var aec: AcousticEchoCanceler? = null
  private var agc: AutomaticGainControl? = null
  private var ns: NoiseSuppressor? = null

  // RMS sink
  @Volatile private var eventSink: EventChannel.EventSink? = null

  // Params from Flutter
  @Volatile private var eqEnabled: Boolean = true
  @Volatile private var outputGain: Double = 1.0
  @Volatile private var bandGains: DoubleArray =
    doubleArrayOf(1.0, 1.0, 1.0, 1.0, 1.0)

  // SCO wait
  private var scoReceiver: BroadcastReceiver? = null
  @Volatile private var pendingStartToken: Int = 0

  // ===== Hot route on wired plug/unplug (ADDED) =====
  private var deviceCallback: AudioDeviceCallback? = null
  @Volatile private var lastVoiceModeRequested: Boolean = false
  @Volatile private var lastVoicePath: Boolean = false
  @Volatile private var lastSampleRate: Int = 48000

  // ===== INPUT SELECT (ADDED) =====
  // false = ưu tiên mic điện thoại (built-in)
  // true  = ưu tiên mic tai nghe dây (wired headset mic)
  @Volatile private var preferWiredMic: Boolean = false

  // ✅ ADDED: boost cho mic tai nghe dây (vì thường rất nhỏ)
  // ===== PATCH: giảm default để bớt “karaoke/metallic” khi hãng đã có processing =====
  @Volatile private var headsetMicBoost: Double = 1.4 // ✅ PATCH (từ 2.2 -> 1.4)

  // ================== ✅ ADDED: Anti-feedback tuning for A2DP ==================
  // Khi output là loa Bluetooth A2DP + input là mic máy => rất dễ hú.
  // Không xoá route A2DP của bạn; chỉ thêm "feedback guard" + giới hạn gain khi A2DP.
  private val A2DP_SAFE_GAIN_CAP = 0.55     // cap outputGain khi A2DP để giảm loop gain
  private val FEEDBACK_RMS_THRESHOLD = 0.25 // RMS vượt ngưỡng này -> bóp gain nhanh
  private val FEEDBACK_RISE_THRESHOLD = 0.06// RMS tăng nhanh -> nghi hú -> bóp gain nhanh
  private val GUARD_MIN = 0.05             // không bóp xuống dưới mức này (tránh mất tiếng hoàn toàn)

  // ✅ ADDED: nếu feedback bùng mạnh -> chèn vài ms "mute" để PHÁ vòng lặp (half-duplex nhẹ)
  private val A2DP_MUTE_MS = 40            // 30-60ms thường đủ phá loop mà ít nghe thấy
  private val A2DP_HARD_MUTE_RMS = 0.55    // rms quá lớn -> hard mute ngắn
  // ===========================================================================

  override fun configureFlutterEngine(@NonNull flutterEngine: FlutterEngine) {
    super.configureFlutterEngine(flutterEngine)
    audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

    MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
      .setMethodCallHandler { call, result ->
        try {
          when (call.method) {
            "start" -> {
              val voiceMode = call.argument<Boolean>("voiceMode") ?: false
              startLoopback(voiceMode)
              result.success(null)
            }
            "stop" -> {
              stopLoopback()
              result.success(null)
            }
            "setParams" -> {
              val enabled = call.argument<Boolean>("eqEnabled") ?: true
              val outGain = (call.argument<Number>("outputGain") ?: 1.0).toDouble()
              val list = call.argument<List<Number>>("bandGains") ?: listOf(1, 1, 1, 1, 1)

              eqEnabled = enabled
              outputGain = outGain.coerceIn(0.0, 2.0)

              val arr = DoubleArray(5)
              for (i in 0 until 5) {
                val v = if (i < list.size) list[i].toDouble() else 1.0
                arr[i] = v.coerceIn(0.25, 3.0)
              }
              bandGains = arr
              result.success(null)
            }

            // ===== ADDED: choose input mic while wired plugged =====
            "setPreferWiredMic" -> {
              val v = call.argument<Boolean>("preferWiredMic") ?: false
              val boost = (call.argument<Number>("headsetBoost") ?: 2.2).toDouble()

              preferWiredMic = v
              headsetMicBoost = boost.coerceIn(1.0, 6.0)

              Log.d(TAG, "setPreferWiredMic=$preferWiredMic headsetBoost=$headsetMicBoost running=$running")

              // nếu đang chạy -> reroute luôn
              if (running) {
                handleRouteChanged()
              }
              result.success(null)
            }

            // ===== ADDED: query wired present to enable/disable button =====
            "isWiredPresent" -> {
              val wired = (findWiredOutput() != null || findWiredInput() != null)
              result.success(wired)
            }

            else -> result.notImplemented()
          }
        } catch (e: Exception) {
          result.error("ERR", e.message, null)
        }
      }

    EventChannel(flutterEngine.dartExecutor.binaryMessenger, EVENTS)
      .setStreamHandler(object : EventChannel.StreamHandler {
        override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
          eventSink = events
        }
        override fun onCancel(arguments: Any?) {
          eventSink = null
        }
      })

    // ✅ ADDED: auto route when plug/unplug wired
    registerDeviceCallback()
  }

  // ===== Bluetooth detection =====

  // Loa bluetooth (A2DP)
  private fun findBtA2dpOutput(): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
  }

  // BT headset route (SCO)
  private fun findBtScoOutput(): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
  }

  private fun findBtScoInput(): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val ins = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    return ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
  }

  // ✅ CHỈ coi là “tai nghe BT” nếu có INPUT (mic)
  private fun isBtHeadsetWithMicConnected(): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
    val ins = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)

    val hasScoMic = ins.any { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
    val hasBleMic = ins.any { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET } // LE Audio headset mic (nếu có)

    return hasScoMic || hasBleMic
  }

  // ✅ Chọn output đúng theo mode
  private fun findBtOutputForPlayback(voicePath: Boolean): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)

    if (voicePath) {
      // Voice path: chỉ dùng SCO/BLE_HEADSET
      return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        ?: outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
    }

    // Media path: ưu tiên A2DP trước (TRÁNH SCO gây im tiếng)
    val a2dp = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP }
    if (a2dp != null) return a2dp

    // Các loại khác (tùy API/hãng)
    val hearingAid = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_HEARING_AID }
    if (hearingAid != null) return hearingAid

    // TYPE_BLE_SPEAKER có từ API mới hơn, check an toàn:
    if (Build.VERSION.SDK_INT >= 31) {
      val bleSpeaker = outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
      if (bleSpeaker != null) return bleSpeaker
    }

    // Cuối cùng mới thử BLE_HEADSET (nhưng chỉ khi không có gì khác)
    return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLE_HEADSET }
  }

  // ===== Start/Stop =====

  private fun startLoopback(voiceMode: Boolean) {
    stopLoopback()
    pendingStartToken++

    // ✅ ADDED: remember what user requested (needed for auto-reroute on unplug)
    lastVoiceModeRequested = voiceMode

    try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}

    // AUTO route
    if (!voiceMode) {
      safeStopSco()

      val a2dp = findBtA2dpOutput()
      val wiredOut = findWiredOutput()
      val wiredIn = findWiredInput()
      val wiredPresent = (wiredOut != null || wiredIn != null)

      // ✅ 1) Nếu là LOA BLUETOOTH A2DP => đi MEDIA path (ưu tiên, tránh chờ SCO không connect)
      // ✅ FIX (ADDED, không xoá): Anti-feedback + force VOICE_COMM sẽ chạy trong startEngine khi detect A2DP
      if (a2dp != null) {
        safeSetModeNormal()
        try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
        startEngine(sampleRate = 48000, voicePath = false, token = pendingStartToken)
        return
      }

      // ✅ 2) Nếu là BT HEADSET có MIC => bật SCO (nghe như tai nghe dây), với timeout fallback
      if (isBtHeadsetWithMicConnected()) {
        safeSetModeInCommunication()
        try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}

        val token = pendingStartToken
        val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
        var scoTimeout: Runnable? = null

        scoReceiver = object : BroadcastReceiver() {
          override fun onReceive(context: Context?, intent: Intent?) {
            val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: -1
            if (token != pendingStartToken) {
              try { unregisterReceiver(this) } catch (_: Exception) {}
              scoReceiver = null
              scoTimeout?.let { mainHandler.removeCallbacks(it) }
              return
            }

            if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
              try { audioManager.isBluetoothScoOn = true } catch (_: Exception) {}
              try { unregisterReceiver(this) } catch (_: Exception) {}
              scoReceiver = null
              scoTimeout?.let { mainHandler.removeCallbacks(it) }
              startEngine(sampleRate = 16000, voicePath = true, token = token)
            }
          }
        }

        try {
          registerReceiver(scoReceiver, filter)
          audioManager.startBluetoothSco()
          scoTimeout = Runnable {
            if (token == pendingStartToken) {
              try { scoReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
              scoReceiver = null
              safeStopSco()
              safeSetModeNormal()
              startEngine(sampleRate = 48000, voicePath = false, token = token)
            }
          }
          mainHandler.postDelayed(scoTimeout, 2000)
        } catch (_: Exception) {
          // fallback nếu SCO fail -> MEDIA
          try { scoReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
          scoReceiver = null
          safeStopSco()
          safeSetModeNormal()
          startEngine(sampleRate = 48000, voicePath = false, token = token)
        }
        return
      }

      // ✅ 3) Loa máy (không wired, không BT) => voice processing
      if (!wiredPresent) {
        safeSetModeInCommunication()
        try { audioManager.isSpeakerphoneOn = true } catch (_: Exception) {}
        startEngine(sampleRate = 16000, voicePath = true, token = pendingStartToken)
        return
      }

      // ✅ 4) Wired
      // ===== PATCH: wired nên để MODE_NORMAL cho mượt (tránh chain thoại gây sạn) =====
      safeSetModeNormal() // ✅ PATCH (đổi từ IN_COMMUNICATION -> NORMAL)
      try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
      startEngine(sampleRate = 48000, voicePath = false, token = pendingStartToken)
      return
    }

    // Manual SCO mode
    safeSetModeInCommunication()
    val token = pendingStartToken

    val filter = IntentFilter(AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED)
    var scoTimeout: Runnable? = null
    scoReceiver = object : BroadcastReceiver() {
      override fun onReceive(context: Context?, intent: Intent?) {
        val state = intent?.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, -1) ?: -1
        if (token != pendingStartToken) {
          try { unregisterReceiver(this) } catch (_: Exception) {}
          scoReceiver = null
          scoTimeout?.let { mainHandler.removeCallbacks(it) }
          return
        }

        if (state == AudioManager.SCO_AUDIO_STATE_CONNECTED) {
          try { audioManager.isBluetoothScoOn = true } catch (_: Exception) {}
          try { unregisterReceiver(this) } catch (_: Exception) {}
          scoReceiver = null
          scoTimeout?.let { mainHandler.removeCallbacks(it) }
          startEngine(sampleRate = 16000, voicePath = true, token = token)
        }
      }
    }

    try {
      registerReceiver(scoReceiver, filter)
      audioManager.startBluetoothSco()
      scoTimeout = Runnable {
        if (token == pendingStartToken) {
          try { scoReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
          scoReceiver = null
          safeStopSco()
          safeSetModeNormal()
          startEngine(sampleRate = 48000, voicePath = false, token = token)
        }
      }
      mainHandler.postDelayed(scoTimeout, 2000)
    } catch (_: Exception) {
      try { scoReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
      scoReceiver = null
      safeStopSco()
      safeSetModeNormal()
      startEngine(sampleRate = 48000, voicePath = false, token = token)
    }
  }

  private fun startEngine(sampleRate: Int, voicePath: Boolean, token: Int) {
    if (token != pendingStartToken) return

    // ✅ ADDED: remember current engine mode (used by auto-reroute)
    lastVoicePath = voicePath
    lastSampleRate = sampleRate

    val channelIn = AudioFormat.CHANNEL_IN_MONO
    val channelOut = AudioFormat.CHANNEL_OUT_MONO
    val encoding = AudioFormat.ENCODING_PCM_16BIT

    val effectiveSampleRate = if (voicePath) sampleRate else try {
      val prop = audioManager.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)
      prop?.toIntOrNull() ?: sampleRate
    } catch (_: Exception) { sampleRate }

    val recMin = AudioRecord.getMinBufferSize(effectiveSampleRate, channelIn, encoding)
    val playMin = AudioTrack.getMinBufferSize(effectiveSampleRate, channelOut, encoding)

    val wiredOut = findWiredOutput()
    val wiredIn = findWiredInput()

    // ✅ ADDED: đang thật sự dùng mic tai nghe?
    val usingWiredMic = (wiredIn != null && preferWiredMic)

    val wiredPresent = (!voicePath) && (wiredOut != null || wiredIn != null)

    // ===== PATCH: buffer non-voicePath tăng cho wired để tránh underrun -> hết sạn =====
    val isWiredOutNow = (!voicePath) && (wiredOut != null)

    // Use separate buffers for recorder/player so we can target lower playback latency for SCO
    val recTarget = if (voicePath) (effectiveSampleRate / 100) else (effectiveSampleRate / 50)

    val playTarget = when {
      voicePath -> (effectiveSampleRate / 100)
      isWiredOutNow -> (effectiveSampleRate / 20) // ✅ PATCH: wired mượt hơn
      else -> (effectiveSampleRate / 40)          // ✅ PATCH: media thường tăng nhẹ
    }

    val recBufSize = maxOf(recMin, recTarget)
    val playBufSize = maxOf(playMin, playTarget)

    Log.d(
      TAG,
      "startEngine requestedSampleRate=$sampleRate effectiveSampleRate=$effectiveSampleRate voicePath=$voicePath recMin=$recMin playMin=$playMin recBuf=$recBufSize playBuf=$playBufSize wiredOut=${wiredOut?.type ?: "null"}"
    )

    // ================== ✅ ADDED: detect A2DP output for anti-feedback ==================
    val isA2dpOutNow = (!voicePath) && (findBtA2dpOutput() != null) && (findWiredOutput() == null)
    if (isA2dpOutNow) {
      Log.w(TAG, "A2DP output active -> enable anti-feedback guard + cap gain")
    }
    // ================================================================================

    // ================== ✅ ADDED: FORCE VoiceCommunication source when A2DP + built-in mic ==================
    // Mục tiêu: cho Android cơ hội bật AEC/NS/AGC (nhiều máy chỉ chạy khi source=VOICE_COMMUNICATION)
    val isBuiltInMicNow = (!usingWiredMic) && (findBuiltInMic() != null)
    val forceVoiceCommForA2dp = isA2dpOutNow && isBuiltInMicNow
    if (forceVoiceCommForA2dp) {
      Log.w(TAG, "A2DP + built-in mic -> force VOICE_COMMUNICATION source + enable AEC/NS/AGC (best-effort)")
    }
    // =================================================================================

    if (!voicePath) {
      try {
        val a2 = findBtA2dpOutput()
        if (a2 != null) Log.w(TAG, "A2DP output detected — expect higher end-to-end latency due to Bluetooth A2DP stack")
      } catch (_: Exception) {}
    }

    // ===== PATCH: wired mic -> dùng VOICE_RECOGNITION để tránh “karaoke/metallic processing” =====
    val audioSource =
      if (voicePath || forceVoiceCommForA2dp) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION
      } else {
        if (usingWiredMic) MediaRecorder.AudioSource.VOICE_RECOGNITION // ✅ PATCH
        else MediaRecorder.AudioSource.MIC
      }

    recorder = AudioRecord.Builder()
      .setAudioSource(audioSource)
      .setAudioFormat(
        AudioFormat.Builder()
          .setEncoding(encoding)
          .setSampleRate(effectiveSampleRate)
          .setChannelMask(channelIn)
          .build()
      )
      .setBufferSizeInBytes(recBufSize)
      .apply {
        if (Build.VERSION.SDK_INT >= 29) {
          try {
            val perfField = AudioRecord::class.java.getField("PERFORMANCE_MODE_LOW_LATENCY")
            val perfMode = perfField.getInt(null)
            val m = this::class.java.getMethod("setPerformanceMode", Int::class.javaPrimitiveType)
            m.invoke(this, perfMode)
          } catch (_: Exception) {}
        }
      }
      .build()

    // ===== PATCH: Wired mic thì TẮT AEC/AGC/NS (chỉ bật cho voicePath) để tránh tiếng “vọng vọng karaoke” =====
    // ✅ FIX (ADDED): cũng bật FX khi forceVoiceCommForA2dp (best-effort với A2DP)
    if (voicePath || forceVoiceCommForA2dp) {
      try { aec = AcousticEchoCanceler.create(recorder?.audioSessionId ?: 0); aec?.enabled = true } catch (_: Exception) { aec = null }
      try { agc = AutomaticGainControl.create(recorder?.audioSessionId ?: 0); agc?.enabled = true } catch (_: Exception) { agc = null }
      try { ns  = NoiseSuppressor.create(recorder?.audioSessionId ?: 0); ns?.enabled  = true } catch (_: Exception) { ns = null }
    } else {
      try { aec?.release() } catch (_: Exception) {}
      aec = null
      try { agc?.release() } catch (_: Exception) {}
      agc = null
      try { ns?.release() } catch (_: Exception) {}
      ns = null
    }

    val attrs = AudioAttributes.Builder()
      .setUsage(if (voicePath) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_MEDIA)
      .setContentType(if (voicePath) AudioAttributes.CONTENT_TYPE_SPEECH else AudioAttributes.CONTENT_TYPE_MUSIC)
      .build()

    val format = AudioFormat.Builder()
      .setEncoding(encoding)
      .setSampleRate(effectiveSampleRate)
      .setChannelMask(channelOut)
      .build()

    player = AudioTrack.Builder()
      .setAudioAttributes(attrs)
      .setAudioFormat(format)
      .setTransferMode(AudioTrack.MODE_STREAM)
      .setBufferSizeInBytes(playBufSize)
      .apply {
        if (Build.VERSION.SDK_INT >= 29) {
          try {
            val perfField = AudioTrack::class.java.getField("PERFORMANCE_MODE_LOW_LATENCY")
            val perfMode = perfField.getInt(null)
            val m = this::class.java.getMethod("setPerformanceMode", Int::class.javaPrimitiveType)
            m.invoke(this, perfMode)
          } catch (_: Exception) {}
        }
      }
      .build()

    try { Log.d(TAG, "preferred output: " + (player?.preferredDevice?.type ?: "none")) } catch (_: Exception) {}

    // ✅ Ép route đúng theo voicePath
    routeToWiredIfPresent(recorder, player, wiredPresent, voicePath)

    try { player?.setVolume(1.0f) } catch (_: Exception) {}

    val eq = Eq5Band(effectiveSampleRate.toDouble())

    running = true
    thread = Thread {
      Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)

      val rec = recorder ?: return@Thread
      val out = player ?: return@Thread
      val buf = ShortArray(min(recBufSize, playBufSize) / 2)

      var lastEqEnabled = eqEnabled
      var lastGain = outputGain
      var lastBands = bandGains.copyOf()

      fun refreshEqIfChanged() {
        val en = eqEnabled
        val g = outputGain
        val b = bandGains

        var changed = false
        if (en != lastEqEnabled || g != lastGain) changed = true
        else for (i in 0 until 5) if (b[i] != lastBands[i]) { changed = true; break }

        if (changed) {
          lastEqEnabled = en
          lastGain = g
          lastBands = b.copyOf()
          val db = DoubleArray(5) { i ->
            val gi = lastBands[i].coerceAtLeast(0.0001)
            20.0 * ln(gi) / ln(10.0)
          }
          eq.updateGainsDb(db)
        }
      }

      var lastMeterTs = 0L

      // ===== PATCH: log underrunCount để bắt bệnh wired "sạn" =====
      var lastUnderrunTs = 0L

      // ================== ✅ ADDED: anti-feedback guard state ==================
      var guardGain = 1.0
      var lastRms = 0.0
      var lastGuardLogTs = 0L
      // ========================================================================

      rec.startRecording()
      out.play()

      while (running) {
        val n = rec.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
        if (n <= 0) continue

        refreshEqIfChanged()

        val en = lastEqEnabled
        val gRaw = lastGain

        // ✅ ADDED: mic boost riêng cho mic tai nghe
        val micBoost = if (usingWiredMic) headsetMicBoost else 1.0

        // ================== ✅ ADDED: cap gain when A2DP to reduce feedback ==================
        val g = if (isA2dpOutNow) min(gRaw, A2DP_SAFE_GAIN_CAP) else gRaw
        // ====================================================================================

        var sumSq = 0.0

        if (en) {
          for (i in 0 until n) {
            var x = buf[i].toDouble() / 32768.0
            x = eq.process(x)

            // ✅ FIX: chặn NaN/Infinity ngay sau EQ
            if (!x.isFinite()) x = 0.0

            x *= micBoost          // ✅ ADDED

            // ================== ✅ ADDED: apply feedback guard gain ==================
            x *= g * guardGain
            // =======================================================================

            x = softClip(x)

            val y = (x * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
            buf[i] = y
            val yf = y.toDouble() / 32768.0
            sumSq += yf * yf
          }
        } else {
          for (i in 0 until n) {
            var x = buf[i].toDouble() / 32768.0
            x *= micBoost          // ✅ ADDED

            // ================== ✅ ADDED: apply feedback guard gain ==================
            x *= g * guardGain
            // =======================================================================

            x = softClip(x)

            val y = (x * 32767.0).roundToInt().coerceIn(-32768, 32767).toShort()
            buf[i] = y
            val yf = y.toDouble() / 32768.0
            sumSq += yf * yf
          }
        }

        // ================== ✅ ADDED: update feedback guard + noise gate (A2DP only) ==================
        // Guard cập nhật theo RMS của buffer vừa xử lý:
        val rmsNow = sqrt(sumSq / n.toDouble()).coerceIn(0.0, 1.0)

        if (isA2dpOutNow) {
          val risingFast = (rmsNow - lastRms) > FEEDBACK_RISE_THRESHOLD
          val tooLoud = rmsNow > FEEDBACK_RMS_THRESHOLD

          if (tooLoud || risingFast) {
            // bóp nhanh để dập hú
            guardGain *= 0.75
            if (guardGain < GUARD_MIN) guardGain = GUARD_MIN
          } else {
            // hồi phục chậm
            guardGain += (1.0 - guardGain) * 0.002
          }

          // ✅ Noise Gate / Duck (half-duplex nhẹ): phá vòng lặp bằng cách mute cực ngắn
          if (rmsNow > A2DP_HARD_MUTE_RMS) {
            val muteSamples = min(n, (effectiveSampleRate * (A2DP_MUTE_MS / 1000.0)).toInt().coerceAtLeast(1))
            for (i in 0 until muteSamples) buf[i] = 0
          }

          val nowLog = System.currentTimeMillis()
          if (nowLog - lastGuardLogTs > 1000) {
            lastGuardLogTs = nowLog
            Log.w(TAG, "A2DP feedbackGuard rms=${"%.3f".format(rmsNow)} guardGain=${"%.3f".format(guardGain)} gCap=${"%.2f".format(g)} forceVoiceCommForA2dp=$forceVoiceCommForA2dp")
          }
        }

        lastRms = rmsNow
        // =============================================================================================

        val wrote = out.write(buf, 0, n)
        if (wrote < 0) Log.w(TAG, "AudioTrack write error $wrote")
        else if (wrote < n) Log.d(TAG, "AudioTrack partial write $wrote/$n")

        // ✅ PATCH: underrunCount (API24+). Nếu tăng liên tục => buffer chưa đủ.
        val now2 = System.currentTimeMillis()
        if (Build.VERSION.SDK_INT >= 24 && now2 - lastUnderrunTs > 1000) {
          lastUnderrunTs = now2
          try {
            Log.d(TAG, "underrunCount=${out.underrunCount} voicePath=$voicePath wiredOutNow=$isWiredOutNow")
          } catch (_: Exception) {}
        }

        val now = System.currentTimeMillis()
        if (now - lastMeterTs > 50) {
          lastMeterTs = now
          mainHandler.post { eventSink?.success(rmsNow) }
        }
      }

      try { rec.stop() } catch (_: Exception) {}
      try { out.stop() } catch (_: Exception) {}
    }.also { it.start() }
  }

  private fun stopLoopback() {
    pendingStartToken++

    running = false
    try { thread?.join(400) } catch (_: Exception) {}
    thread = null

    try { aec?.release() } catch (_: Exception) {}
    aec = null
    try { agc?.release() } catch (_: Exception) {}
    agc = null
    try { ns?.release() } catch (_: Exception) {}
    ns = null

    try { recorder?.release() } catch (_: Exception) {}
    recorder = null

    try { player?.pause() } catch (_: Exception) {}
    try { player?.flush() } catch (_: Exception) {}
    try { player?.release() } catch (_: Exception) {}
    player = null

    try { scoReceiver?.let { unregisterReceiver(it) } } catch (_: Exception) {}
    scoReceiver = null

    safeStopSco()
    safeSetModeNormal()
    try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
  }

  private fun safeStopSco() {
    try { audioManager.isBluetoothScoOn = false } catch (_: Exception) {}
    try { audioManager.stopBluetoothSco() } catch (_: Exception) {}
  }

  private fun safeSetModeNormal() {
    try { audioManager.mode = AudioManager.MODE_NORMAL } catch (_: Exception) {}
  }

  private fun safeSetModeInCommunication() {
    try { audioManager.mode = AudioManager.MODE_IN_COMMUNICATION } catch (_: Exception) {}
  }

  // ===== WIRED/USB ROUTE FIX =====

  private fun findWiredOutput(): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val outs = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
    return outs.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
      ?: outs.firstOrNull {
        it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
          it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
      }
  }

  private fun findWiredInput(): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val ins = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    return ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_USB_HEADSET }
      ?: ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET }
  }

  private fun findBuiltInMic(): AudioDeviceInfo? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
    val ins = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
    return ins.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
  }

  // ✅ thêm voicePath để route BT đúng mode
  private fun routeToWiredIfPresent(
    rec: AudioRecord?,
    out: AudioTrack?,
    wiredPresent: Boolean,
    voicePath: Boolean
  ) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

    val wiredOut = findWiredOutput()
    val wiredIn = findWiredInput()
    val builtInMic = findBuiltInMic()

    // Output: wired ưu tiên trước
    try {
      if (wiredOut != null && out != null) out.preferredDevice = wiredOut
    } catch (_: Exception) {}

    // Output: nếu không wired -> chọn BT output đúng theo voicePath
    try {
      if (out != null && wiredOut == null) {
        val btOut = findBtOutputForPlayback(voicePath)
        if (btOut != null) out.preferredDevice = btOut
      }
    } catch (_: Exception) {}

    // ===== INPUT SELECT (UPDATED, but only changed behavior) =====
    // Mặc định: ưu tiên built-in mic.
    // Khi user bật preferWiredMic: ưu tiên wired mic.
    try {
      if (rec != null) {
        val targetIn =
          if (preferWiredMic) (wiredIn ?: builtInMic)
          else (builtInMic ?: wiredIn)

        if (targetIn != null) rec.preferredDevice = targetIn
      }
    } catch (_: Exception) {}
  }

  // ✅ FIX: NaN-safe softClip
  private fun softClip(xIn: Double): Double {
    if (!xIn.isFinite()) return 0.0   // ✅ FIX
    var x = xIn
    if (x > 1.2) x = 1.2
    if (x < -1.2) x = -1.2
    val y = x - (x * x * x) / 3.0
    return y.coerceIn(-1.0, 1.0)
  }

  // ===================== ADDED BLOCK START =====================

  private fun registerDeviceCallback() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
    if (deviceCallback != null) return

    deviceCallback = object : AudioDeviceCallback() {
      override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
        handleRouteChanged()
      }

      override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
        handleRouteChanged()
      }
    }

    try {
      audioManager.registerAudioDeviceCallback(deviceCallback, mainHandler)
    } catch (_: Exception) {}
  }

  private fun handleRouteChanged() {
    if (!running) return
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return

    val wiredOut = findWiredOutput()
    val wiredIn = findWiredInput()
    val builtIn = findBuiltInMic()

    // CASE: cắm tai nghe dây/USB => ép output sang wired, input theo preferWiredMic
    if (wiredOut != null) {
      Log.d(TAG, "Wired detected -> auto route to wired (preferWiredMic=$preferWiredMic)")

      // ===== PATCH: wired route thì mode NORMAL cho mượt =====
      safeSetModeNormal() // ✅ PATCH
      try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}

      // hot reroute first
      try { player?.preferredDevice = wiredOut } catch (_: Exception) {}
      try {
        val targetIn =
          if (preferWiredMic) (wiredIn ?: builtIn)
          else (builtIn ?: wiredIn)
        if (targetIn != null) recorder?.preferredDevice = targetIn
      } catch (_: Exception) {}

      // restart engine to ensure the route actually takes effect on all devices
      restartEngineAuto(sampleRate = 48000, voicePath = false)
      return
    }

    // CASE: rút wired => restore auto route
    Log.d(TAG, "Wired not present -> restore auto route")

    // Nếu user bật voiceMode thủ công: ưu tiên voice path (SCO nếu có), không thì speaker voice processing
    if (lastVoiceModeRequested) {
      safeSetModeInCommunication()
      try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}

      if (isBtHeadsetWithMicConnected()) {
        restartEngineAuto(sampleRate = 16000, voicePath = true)
      } else {
        try { audioManager.isSpeakerphoneOn = true } catch (_: Exception) {}
        restartEngineAuto(sampleRate = 16000, voicePath = true)
      }
      return
    }

    // Auto mode: ưu tiên A2DP nếu có, không thì speaker voice processing
    val a2dp = findBtA2dpOutput()
    if (a2dp != null) {
      safeSetModeNormal()
      try { audioManager.isSpeakerphoneOn = false } catch (_: Exception) {}
      restartEngineAuto(sampleRate = 48000, voicePath = false)
    } else {
      safeSetModeInCommunication()
      try { audioManager.isSpeakerphoneOn = true } catch (_: Exception) {}
      restartEngineAuto(sampleRate = 16000, voicePath = true)
    }
  }

  private fun restartEngineAuto(sampleRate: Int, voicePath: Boolean) {
    if (voicePath == lastVoicePath && sampleRate == lastSampleRate) return

    val token = ++pendingStartToken

    // stop current engine quickly
    running = false

    // unblock read/write
    try { recorder?.stop() } catch (_: Exception) {}
    try { player?.pause() } catch (_: Exception) {}
    try { player?.flush() } catch (_: Exception) {}

    try { thread?.interrupt() } catch (_: Exception) {}
    try { thread?.join(300) } catch (_: Exception) {}
    thread = null

    // release FX
    try { aec?.release() } catch (_: Exception) {}
    aec = null
    try { agc?.release() } catch (_: Exception) {}
    agc = null
    try { ns?.release() } catch (_: Exception) {}
    ns = null

    // release audio objects
    try { recorder?.release() } catch (_: Exception) {}
    recorder = null
    try { player?.release() } catch (_: Exception) {}
    player = null

    // start new engine with new route/mode
    startEngine(sampleRate = sampleRate, voicePath = voicePath, token = token)
  }

  override fun onDestroy() {
    super.onDestroy()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      try { deviceCallback?.let { audioManager.unregisterAudioDeviceCallback(it) } } catch (_: Exception) {}
    }
    deviceCallback = null
  }

  // ===================== ADDED BLOCK END =====================
}

// ===== BIQUAD EQ =====

private class Biquad {
  private var b0 = 1.0
  private var b1 = 0.0
  private var b2 = 0.0
  private var a1 = 0.0
  private var a2 = 0.0
  private var z1 = 0.0
  private var z2 = 0.0

  fun process(x: Double): Double {
    val y = b0 * x + z1
    z1 = b1 * x - a1 * y + z2
    z2 = b2 * x - a2 * y
    return y
  }

  private fun setNorm(
    b0u: Double, b1u: Double, b2u: Double,
    a0u: Double, a1u: Double, a2u: Double
  ) {
    val inv = 1.0 / a0u
    b0 = b0u * inv
    b1 = b1u * inv
    b2 = b2u * inv
    a1 = a1u * inv
    a2 = a2u * inv
  }

  fun setPeaking(fs: Double, f0: Double, q: Double, gainDb: Double) {
    val A = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * Math.PI * (f0 / fs)
    val cw = cos(w0)
    val sw = sin(w0)
    val alpha = sw / (2.0 * q)

    val b0u = 1.0 + alpha * A
    val b1u = -2.0 * cw
    val b2u = 1.0 - alpha * A
    val a0u = 1.0 + alpha / A
    val a1u = -2.0 * cw
    val a2u = 1.0 - alpha / A
    setNorm(b0u, b1u, b2u, a0u, a1u, a2u)
  }

  fun setLowShelf(fs: Double, f0: Double, slope: Double, gainDb: Double) {
    val A = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * Math.PI * (f0 / fs)
    val cw = cos(w0)
    val sw = sin(w0)
    val sqrtA = sqrt(A)
    val alpha = sw / 2.0 * sqrt((A + 1.0 / A) * (1.0 / slope - 1.0) + 2.0)

    val b0u = A * ((A + 1) - (A - 1) * cw + 2 * sqrtA * alpha)
    val b1u = 2 * A * ((A - 1) - (A + 1) * cw)
    val b2u = A * ((A + 1) - (A - 1) * cw - 2 * sqrtA * alpha)
    val a0u = (A + 1) + (A - 1) * cw + 2 * sqrtA * alpha
    val a1u = -2 * ((A - 1) + (A + 1) * cw)
    val a2u = (A + 1) + (A - 1) * cw - 2 * sqrtA * alpha
    setNorm(b0u, b1u, b2u, a0u, a1u, a2u)
  }

  fun setHighShelf(fs: Double, f0: Double, slope: Double, gainDb: Double) {
    val A = 10.0.pow(gainDb / 40.0)
    val w0 = 2.0 * Math.PI * (f0 / fs)
    val cw = cos(w0)
    val sw = sin(w0)
    val sqrtA = sqrt(A)
    val alpha = sw / 2.0 * sqrt((A + 1.0 / A) * (1.0 / slope - 1.0) + 2.0)

    val b0u = A * ((A + 1) + (A - 1) * cw + 2 * sqrtA * alpha)
    val b1u = -2 * A * ((A - 1) + (A + 1) * cw)
    val b2u = A * ((A + 1) + (A - 1) * cw - 2 * sqrtA * alpha)
    val a0u = (A + 1) - (A - 1) * cw + 2 * sqrtA * alpha
    val a1u = 2 * ((A - 1) - (A + 1) * cw)
    val a2u = (A + 1) - (A - 1) * cw - 2 * sqrtA * alpha
    setNorm(b0u, b1u, b2u, a0u, a1u, a2u)
  }
}

private class Eq5Band(private val fs: Double) {
  private val low = Biquad()
  private val m1 = Biquad()
  private val m2 = Biquad()
  private val m3 = Biquad()
  private val high = Biquad()

  fun updateGainsDb(db: DoubleArray) {
    low.setLowShelf(fs, 60.0, 1.0, db[0])
    m1.setPeaking(fs, 230.0, 1.0, db[1])
    m2.setPeaking(fs, 910.0, 1.0, db[2])
    m3.setPeaking(fs, 3600.0, 1.0, db[3])

    // ✅ FIX 1: treble không được vượt Nyquist (tránh NaN/unstable)
    val nyq = fs * 0.5
    val fHigh = min(14000.0, nyq * 0.90)   // 90% Nyquist cho an toàn
    high.setHighShelf(fs, fHigh, 1.0, db[4])
  }

  fun process(x: Double): Double {
    var y = x
    y = low.process(y)
    y = m1.process(y)
    y = m2.process(y)
    y = m3.process(y)
    y = high.process(y)
    return y
  }
}
