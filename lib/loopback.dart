import 'dart:async';
import 'package:flutter/services.dart';

class LoopbackParams {
  final bool eqEnabled;
  final double outputGain; // linear
  final List<double> bandGains; // length=5 linear (0.5..1.5)

  const LoopbackParams({
    required this.eqEnabled,
    required this.outputGain,
    required this.bandGains,
  });

  Map<String, dynamic> toMap() => {
    'eqEnabled': eqEnabled,
    'outputGain': outputGain,
    'bandGains': bandGains,
  };
}

class Loopback {
  static const MethodChannel _ch = MethodChannel('loopback');
  static const EventChannel _ev = EventChannel('loopback_events');

  static Stream<double>? _rmsStream;

  static Future<void> start({bool voiceMode = false}) =>
      _ch.invokeMethod('start', {'voiceMode': voiceMode});

  static Future<void> stop() => _ch.invokeMethod('stop');

  static Future<void> setParams(LoopbackParams p) =>
      _ch.invokeMethod('setParams', p.toMap());

  // ✅ ADDED: chọn mic tai nghe dây hay mic máy
  // ✅ UPDATED: thêm headsetBoost (tăng gain riêng cho mic tai nghe dây)
  static Future<void> setPreferWiredMic(
    bool preferWiredMic, {
    double headsetBoost = 2.2,
  }) => _ch.invokeMethod('setPreferWiredMic', {
    'preferWiredMic': preferWiredMic,
    'headsetBoost': headsetBoost,
  });

  // ✅ ADDED: hỏi native xem hiện đang cắm tai nghe dây/USB không
  static Future<bool> isWiredPresent() async {
    final v = await _ch.invokeMethod('isWiredPresent');
    return v == true;
  }

  static Stream<double> rmsStream() {
    _rmsStream ??= _ev.receiveBroadcastStream().map(
      (e) => (e is num) ? e.toDouble() : 0.0,
    );
    return _rmsStream!;
  }
}
