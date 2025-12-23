import 'dart:async';
import 'dart:math';
import 'dart:typed_data';

import 'package:flutter/material.dart';
import 'package:audio_io/audio_io.dart';
import 'package:fftea/fftea.dart';
import 'package:permission_handler/permission_handler.dart';
import 'package:flutter_audio_output/flutter_audio_output.dart'; // <--- THÊM

class Homepage extends StatefulWidget {
  const Homepage({super.key});
  @override
  State<Homepage> createState() => _HomepageState();
}

class _HomepageState extends State<Homepage> {
  AudioIo? audio;
  StreamSubscription<List<double>>? micSub;

  bool running = false;
  bool eqEnabled = true;
  double volume = 0.0;
  int _lastUpdate = 0;

  // Trạng thái output
  bool usingBluetooth = false;
  bool switchingOutput = false;

  // EQ 5 băng tần
  double bassGain = 1.0;
  double lowMidGain = 1.0;
  double midGain = 1.0;
  double highMidGain = 1.0;
  double trebleGain = 1.0;

  final Map<String, List<double>> presets = {
    'Flat': [1, 1, 1, 1, 1],
    'Rock': [1.4, 1.2, 1.0, 1.3, 1.5],
    'Pop': [1.2, 1.1, 1.0, 1.0, 1.3],
    'Jazz': [1.1, 1.2, 1.3, 1.2, 1.1],
    'Heavy Metal': [1.5, 1.3, 1.0, 1.2, 1.4],
  };
  String currentPreset = 'Flat';

  static const int kSampleRate = 48000; // audio_io 48kHz

  @override
  void dispose() {
    stopLoop();
    super.dispose();
  }

  int _nextPow2(int x) {
    var n = 1;
    while (n < x) n <<= 1;
    return n;
  }

  // ================== START / STOP MIC ==================
  Future<void> startLoop() async {
    final status = await Permission.microphone.request();
    if (!status.isGranted) {
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('⚠️ Cần quyền Micro để hoạt động')),
        );
      }
      return;
    }

    await stopLoop();

    audio = AudioIo();
    try {
      await audio!.requestLatency(AudioIoLatency.Realtime);
      await audio!.start();
    } catch (e, st) {
      debugPrint('Audio start error: $e\n$st');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text('❌ Không thể khởi động thiết bị âm thanh: $e'),
          ),
        );
      }
      audio = null;
      return;
    }

    micSub = audio!.input.listen(
      (samples) {
        if (samples.isEmpty) return;

        // RMS meter
        final rms = sqrt(
          samples.fold(0.0, (a, b) => a + b * b) / samples.length,
        );
        final now = DateTime.now().millisecondsSinceEpoch;
        if (now - _lastUpdate > 200) {
          _lastUpdate = now;
          if (mounted) {
            setState(() => volume = rms);
          }
        }

        // EQ off -> pass through
        if (!eqEnabled) {
          audio?.output.add(samples);
          return;
        }

        final inLen = samples.length;
        final n = _nextPow2(inLen);
        final input = Float64List(n)..setRange(0, inLen, samples);

        final fft = FFT(n);
        final spec = fft.realFft(input);

        for (int i = 0; i < spec.length; i++) {
          final hz = i * kSampleRate / n;
          double gain;
          if (hz < 200) {
            gain = bassGain;
          } else if (hz < 800) {
            gain = lowMidGain;
          } else if (hz < 2000) {
            gain = midGain;
          } else if (hz < 6000) {
            gain = highMidGain;
          } else {
            gain = trebleGain;
          }

          final c = spec[i];
          spec[i] = Float64x2(c.x * gain, c.y * gain);
        }

        final processed = fft.realInverseFft(spec);

        for (int i = 0; i < processed.length; i++) {
          final v = processed[i];
          processed[i] = v > 1.0 ? 1.0 : (v < -1.0 ? -1.0 : v); // clamp -1..1
        }

        // Cắt lại đúng độ dài rồi phát
        audio?.output.add(processed.sublist(0, inLen));
      },
      onError: (e) => debugPrint('Mic error: $e'),
      cancelOnError: false,
    );

    if (mounted) setState(() => running = true);
  }

  Future<void> stopLoop() async {
    try {
      await micSub?.cancel();
      micSub = null;
      if (audio != null) {
        await audio!.stop();
        audio = null;
      }
      if (mounted) {
        setState(() {
          running = false;
          volume = 0.0;
        });
      }
    } catch (e) {
      debugPrint('Stop error: $e');
    }
  }

  // ================== EQ UI ==================
  void _applyPreset(String name) {
    final v = presets[name]!;
    setState(() {
      currentPreset = name;
      bassGain = v[0];
      lowMidGain = v[1];
      midGain = v[2];
      highMidGain = v[3];
      trebleGain = v[4];
    });
  }

  Widget _band(String label, double value, ValueChanged<double> onChanged) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Text(
          label,
          style: const TextStyle(color: Colors.white70, fontSize: 12),
        ),
        RotatedBox(
          quarterTurns: -1,
          child: Slider(
            value: value,
            onChanged: onChanged,
            min: 0.5,
            max: 1.5,
            divisions: 10,
            activeColor: Colors.greenAccent,
          ),
        ),
      ],
    );
  }

  // ================== BLUETOOTH OUTPUT ==================
  Future<void> _toggleBluetooth(bool value) async {
    if (switchingOutput) return;
    setState(() => switchingOutput = true);

    bool ok = false;
    String deviceName = '';

    try {
      if (value) {
        ok = await FlutterAudioOutput.changeToBluetooth();
        deviceName = 'Bluetooth';
      } else {
        // quay lại loa ngoài (speaker)
        ok = await FlutterAudioOutput.changeToSpeaker();
        deviceName = 'Speaker';
      }
    } catch (e) {
      debugPrint('Switch output error: $e');
      ok = false;
    }

    if (mounted) {
      setState(() {
        switchingOutput = false;
        if (ok) {
          usingBluetooth = value;
        }
      });

      ScaffoldMessenger.of(context).showSnackBar(
        SnackBar(
          content: Text(
            ok
                ? '✅ Đã chuyển âm thanh sang $deviceName'
                : '❌ Không chuyển được sang $deviceName',
          ),
          duration: const Duration(seconds: 2),
        ),
      );
    }
  }

  @override
  Widget build(BuildContext context) {
    final level = (volume * 100).clamp(0, 100).toStringAsFixed(1);

    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        title: const Text('🎤 Live Mic Equalizer'),
        backgroundColor: Colors.grey[900],
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            // VU meter
            AnimatedContainer(
              duration: const Duration(milliseconds: 100),
              width: 220,
              height: 20,
              decoration: BoxDecoration(
                borderRadius: BorderRadius.circular(10),
                color: Colors.grey[800],
              ),
              child: Align(
                alignment: Alignment.centerLeft,
                child: Container(
                  width: (2 * volume * 100).clamp(0, 220),
                  decoration: BoxDecoration(
                    borderRadius: BorderRadius.circular(10),
                    color: Colors.greenAccent,
                  ),
                ),
              ),
            ),
            const SizedBox(height: 8),
            Text(
              'Âm lượng: $level%',
              style: const TextStyle(color: Colors.white70),
            ),
            const SizedBox(height: 20),

            // EQ switch
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text(
                  'Custom Equalizer',
                  style: TextStyle(color: Colors.white70),
                ),
                Switch(
                  value: eqEnabled,
                  activeColor: Colors.greenAccent,
                  onChanged: (v) => setState(() => eqEnabled = v),
                ),
              ],
            ),
            const SizedBox(height: 8),

            // OUTPUT: SPEAKER / BLUETOOTH
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                const Text(
                  'Output: Bluetooth',
                  style: TextStyle(color: Colors.white70),
                ),
                const SizedBox(width: 8),
                Switch(
                  value: usingBluetooth,
                  activeColor: Colors.lightBlueAccent,
                  onChanged: switchingOutput ? null : _toggleBluetooth,
                ),
              ],
            ),
            const SizedBox(height: 8),

            // EQ sliders
            Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                _band('60Hz', bassGain, (v) => setState(() => bassGain = v)),
                _band(
                  '230Hz',
                  lowMidGain,
                  (v) => setState(() => lowMidGain = v),
                ),
                _band('910Hz', midGain, (v) => setState(() => midGain = v)),
                _band(
                  '3600Hz',
                  highMidGain,
                  (v) => setState(() => highMidGain = v),
                ),
                _band(
                  '14000Hz',
                  trebleGain,
                  (v) => setState(() => trebleGain = v),
                ),
              ],
            ),
            const SizedBox(height: 15),

            DropdownButton<String>(
              value: currentPreset,
              dropdownColor: Colors.grey[900],
              style: const TextStyle(color: Colors.white),
              onChanged: (val) => _applyPreset(val!),
              items: presets.keys
                  .map((n) => DropdownMenuItem(value: n, child: Text(n)))
                  .toList(),
            ),
            const SizedBox(height: 20),

            ElevatedButton(
              onPressed: running ? stopLoop : startLoop,
              style: ElevatedButton.styleFrom(
                backgroundColor: running ? Colors.red : Colors.green,
                padding: const EdgeInsets.symmetric(
                  horizontal: 32,
                  vertical: 16,
                ),
              ),
              child: Text(
                running ? 'DỪNG' : 'BẬT MICRO',
                style: const TextStyle(
                  fontSize: 18,
                  fontWeight: FontWeight.bold,
                ),
              ),
            ),
            const SizedBox(height: 10),
            const Text(
              '💡 Dùng tai nghe để tránh hú\n⚡ EQ 5 băng tần realtime (48 kHz)',
              style: TextStyle(color: Colors.white38, fontSize: 12),
              textAlign: TextAlign.center,
            ),
          ],
        ),
      ),
    );
  }
}
