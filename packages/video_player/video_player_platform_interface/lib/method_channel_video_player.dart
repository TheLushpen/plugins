// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'dart:async';
import 'dart:ui';

import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:video_player_platform_interface/messages.g.dart';

import 'video_player_platform_interface.dart';

/// An implementation of [VideoPlayerPlatform] that uses method channels.
///
/// This is the default implementation, for compatibility with existing
/// third-party implementations. It is not used by other implementations in
/// this repository.
class MethodChannelVideoPlayer extends VideoPlayerPlatform {
  final VideoPlayerApi _api = VideoPlayerApi();

  @override
  Future<void> init() {
    return _api.initialize();
  }

  @override
  Future<void> dispose(int textureId) {
    return _api.dispose(TextureMessage(textureId: textureId));
  }

  @override
  Future<int?> create(DataSource dataSource, double left, double top,
      double width, double height) async {
    late final CreateMessage message;

    switch (dataSource.sourceType) {
      case DataSourceType.asset:
        message = CreateMessage(
          asset: dataSource.asset,
          packageName: dataSource.package,
          left: left,
          top: top,
          width: width,
          height: height,
        );
        break;
      case DataSourceType.network:
        message = CreateMessage(
          uri: dataSource.uri,
          formatHint: _videoFormatStringMap[dataSource.formatHint],
          httpHeaders: dataSource.httpHeaders,
          left: left,
          top: top,
          width: width,
          height: height,
        );
        break;
      case DataSourceType.contentUri:
      case DataSourceType.file:
        message = CreateMessage(
          uri: dataSource.uri,
          left: left,
          top: top,
          width: width,
          height: height,
        );
        break;
    }

    final TextureMessage response = await _api.create(message);
    return response.textureId;
  }

  @override
  Future<void> setLooping(int textureId, bool looping) {
    return _api
        .setLooping(LoopingMessage(textureId: textureId, isLooping: looping));
  }

  @override
  Future<void> play(int textureId) {
    return _api.play(TextureMessage(textureId: textureId));
  }

  @override
  Future<void> pause(int textureId) {
    return _api.pause(TextureMessage(textureId: textureId));
  }

  @override
  Future<void> setVolume(int textureId, double volume) {
    return _api.setVolume(VolumeMessage(textureId: textureId, volume: volume));
  }

  @override
  Future<void> setPlaybackSpeed(int textureId, double speed) {
    assert(speed > 0);

    return _api.setPlaybackSpeed(
        PlaybackSpeedMessage(textureId: textureId, speed: speed));
  }

  @override
  Future<void> seekTo(int textureId, Duration position) {
    return _api.seekTo(PositionMessage(
        textureId: textureId, position: position.inMilliseconds));
  }

  @override
  Future<Duration> getPosition(int textureId) async {
    final PositionMessage response =
        await _api.position(TextureMessage(textureId: textureId));
    return Duration(milliseconds: response.position);
  }

  @override
  Stream<VideoEvent> videoEventsFor(int textureId) {
    return _eventChannelFor(textureId)
        .receiveBroadcastStream()
        .map((dynamic event) {
      final Map<dynamic, dynamic> map = event as Map<dynamic, dynamic>;
      switch (map['event']) {
        case 'initialized':
          return VideoEvent(
            eventType: VideoEventType.initialized,
            duration: Duration(milliseconds: map['duration']! as int),
            size: Size((map['width'] as num?)?.toDouble() ?? 0.0,
                (map['height'] as num?)?.toDouble() ?? 0.0),
            rotationCorrection: map['rotationCorrection'] as int? ?? 0,
            pipEnable: map['pipEnable'] as bool? ?? false,
          );
        case 'completed':
          return VideoEvent(
            eventType: VideoEventType.completed,
          );
        case 'bufferingUpdate':
          final List<dynamic> values = map['values']! as List<dynamic>;

          return VideoEvent(
            buffered: values.map<DurationRange>(_toDurationRange).toList(),
            eventType: VideoEventType.bufferingUpdate,
          );
        case 'bufferingStart':
          return VideoEvent(eventType: VideoEventType.bufferingStart);
        case 'bufferingEnd':
          return VideoEvent(eventType: VideoEventType.bufferingEnd);
        case 'startingPiP':
          return VideoEvent(eventType: VideoEventType.startingPiP);
        case 'stoppedPiP':
          return VideoEvent(eventType: VideoEventType.stoppedPiP);
        case 'expandButtonTapPiP':
          return VideoEvent(eventType: VideoEventType.expandButtonTapPiP);
        case 'closeButtonTapPiP':
          return VideoEvent(eventType: VideoEventType.closeButtonTapPiP);
        default:
          return VideoEvent(eventType: VideoEventType.unknown);
      }
    });
  }

  @override
  Widget buildView(int textureId) {
    return Texture(textureId: textureId);
  }

  @override
  Future<void> setMixWithOthers(bool mixWithOthers) {
    return _api.setMixWithOthers(
      MixWithOthersMessage(mixWithOthers: mixWithOthers),
    );
  }

  @override
  Future<void> setPictureInPicture(int textureId, bool enabled) {
    return _api.setPictureInPicture(PictureInPictureMessage(
        textureId: textureId, enabled: enabled ? 1 : 0));
  }

  @override
  Future<void> showAirPlayMenu(int textureId) {
    return _api.showAirPlayMenu(TextureMessage(textureId: textureId));
  }

  EventChannel _eventChannelFor(int textureId) {
    return EventChannel('flutter.io/videoPlayer/videoEvents$textureId');
  }

  static const Map<VideoFormat, String> _videoFormatStringMap =
      <VideoFormat, String>{
    VideoFormat.ss: 'ss',
    VideoFormat.hls: 'hls',
    VideoFormat.dash: 'dash',
    VideoFormat.other: 'other',
  };

  DurationRange _toDurationRange(dynamic value) {
    final List<dynamic> pair = value as List<dynamic>;
    return DurationRange(
      Duration(milliseconds: pair[0]! as int),
      Duration(milliseconds: pair[1]! as int),
    );
  }
}
