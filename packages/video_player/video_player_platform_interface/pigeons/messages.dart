// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

import 'package:pigeon/pigeon.dart';

@ConfigurePigeon(PigeonOptions(
  dartOut: 'lib/messages.g.dart',
  dartTestOut: 'test/test.dart',
))
class TextureMessage {
  TextureMessage(this.textureId);
  int textureId;
}

class LoopingMessage {
  LoopingMessage(this.textureId, this.isLooping);
  int textureId;
  bool isLooping;
}

class VolumeMessage {
  VolumeMessage(this.textureId, this.volume);
  int textureId;
  double volume;
}

class PlaybackSpeedMessage {
  PlaybackSpeedMessage(this.textureId, this.speed);
  int textureId;
  double speed;
}

class PositionMessage {
  PositionMessage(this.textureId, this.position);
  int textureId;
  int position;
}

class CreateMessage {
  CreateMessage(this.left, this.top, this.width, this.height);

  String? asset;
  String? uri;
  String? packageName;
  String? formatHint;
  Map<String?, String?>? httpHeaders;
  final double left;
  final double top;
  final double width;
  final double height;
}

class MixWithOthersMessage {
  MixWithOthersMessage(this.mixWithOthers);
  bool mixWithOthers;
}

class PictureInPictureMessage {
  PictureInPictureMessage({
    required this.textureId,
    required this.enabled,
    required this.left,
    required this.top,
    required this.width,
    required this.height,
  });

  final int textureId;
  final int enabled;
  final double left;
  final double top;
  final double width;
  final double height;
}

@HostApi(dartHostTestHandler: 'TestHostVideoPlayerApi')
abstract class VideoPlayerApi {
  void initialize();
  TextureMessage create(CreateMessage msg);
  void dispose(TextureMessage msg);
  void setLooping(LoopingMessage msg);
  void setVolume(VolumeMessage msg);
  void setPlaybackSpeed(PlaybackSpeedMessage msg);
  void play(TextureMessage msg);
  PositionMessage position(TextureMessage msg);
  void seekTo(PositionMessage msg);
  void pause(TextureMessage msg);
  void setMixWithOthers(MixWithOthersMessage msg);
  void setPictureInPicture(PictureInPictureMessage arg);
  void showAirPlayMenu(TextureMessage msg);
}