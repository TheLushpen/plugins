// Copyright 2013 The Flutter Authors. All rights reserved.
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

package io.flutter.plugins.videoplayer;

import static com.google.android.exoplayer2.Player.REPEAT_MODE_ALL;
import static com.google.android.exoplayer2.Player.REPEAT_MODE_OFF;

import android.app.Activity;
import android.app.PictureInPictureParams;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Build;
import android.util.Rational;
import android.view.Surface;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.PlaybackParameters;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.Player.Listener;
import com.google.android.exoplayer2.audio.AudioAttributes;
import com.google.android.exoplayer2.RendererCapabilities;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.source.dash.DashMediaSource;
import com.google.android.exoplayer2.source.dash.DefaultDashChunkSource;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.ui.TrackNameProvider;
import com.google.android.exoplayer2.ui.DefaultTrackNameProvider;
import com.google.android.exoplayer2.source.TrackGroup;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.smoothstreaming.DefaultSsChunkSource;
import com.google.android.exoplayer2.source.smoothstreaming.SsMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelector;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.MappingTrackSelector;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultDataSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.util.Util;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.flutter.plugin.common.EventChannel;
import io.flutter.view.TextureRegistry;

final class VideoPlayer {
    private static final String FORMAT_SS = "ss";
    private static final String FORMAT_DASH = "dash";
    private static final String FORMAT_HLS = "hls";
    private static final String FORMAT_OTHER = "other";

    private final QueuingEventSink eventSink = new QueuingEventSink();
    private final EventChannel eventChannel;

    private final ExoPlayer exoPlayer;
    private Surface surface;

    private final TextureRegistry.SurfaceTextureEntry textureEntry;
    private final VideoPlayerOptions options;

    private boolean isInitialized = false;

    private Rect bounds;
    private final Activity activity;
    private final DefaultTrackSelector trackSelector;

    VideoPlayer(
            EventChannel eventChannel,
            TextureRegistry.SurfaceTextureEntry textureEntry,
            String dataSource,
            String formatHint,
            Map<String, String> httpHeaders,
            VideoPlayerOptions options,
            Activity activity,
            Rect bounds) {
        this.eventChannel = eventChannel;
        this.textureEntry = textureEntry;
        this.options = options;
        this.activity = activity;
        this.bounds = bounds;

        trackSelector = new DefaultTrackSelector(activity);
        exoPlayer = new ExoPlayer.Builder(activity).setTrackSelector(trackSelector).build();

        Uri uri = Uri.parse(dataSource);
        DataSource.Factory dataSourceFactory;
        if (isHTTP(uri)) {
            DefaultHttpDataSource.Factory httpDataSourceFactory =
                    new DefaultHttpDataSource.Factory()
                            .setUserAgent("ExoPlayer")
                            .setAllowCrossProtocolRedirects(true);

            if (httpHeaders != null && !httpHeaders.isEmpty()) {
                httpDataSourceFactory.setDefaultRequestProperties(httpHeaders);
            }
            dataSourceFactory = httpDataSourceFactory;
        } else {
            dataSourceFactory = new DefaultDataSource.Factory(activity);
        }

        MediaSource mediaSource = buildMediaSource(uri, dataSourceFactory, formatHint, activity);
        exoPlayer.setMediaSource(mediaSource);
        exoPlayer.prepare();

        setupVideoPlayer(eventChannel, textureEntry);
    }

    @SuppressWarnings("unchecked")
    ArrayList<String> getAudios() {
        MappingTrackSelector.MappedTrackInfo mappedTrackInfo =
                trackSelector.getCurrentMappedTrackInfo();

        ArrayList<String> audios = new ArrayList<>();

        if (mappedTrackInfo == null) {
            return audios;
        }

        for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
            if (mappedTrackInfo.getRendererType(i) != C.TRACK_TYPE_AUDIO) {
                continue;
            }

            TrackGroupArray trackGroupArray = mappedTrackInfo.getTrackGroups(i);
            for (int j = 0; j < trackGroupArray.length; j++) {
                TrackGroup group = trackGroupArray.get(j);

                for (int k = 0; k < group.length; k++) {
                    // Проверка на поддержку трека
                    if (mappedTrackInfo.getTrackSupport(i, j, k) == C.FORMAT_HANDLED) {
                        // Формируем название трека на основе информации из Format
                        Format format = group.getFormat(k);
                        String trackName = getTrackName(format);
                        audios.add(trackName);
                    }
                }
            }
        }
        return audios;
    }

    void setAudio(String audioName) {
        MappingTrackSelector.MappedTrackInfo mappedTrackInfo =
                trackSelector.getCurrentMappedTrackInfo();

        if (mappedTrackInfo == null) {
            return;
        }

        for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
            if (mappedTrackInfo.getRendererType(i) != C.TRACK_TYPE_AUDIO) {
                continue;
            }

            TrackGroupArray trackGroupArray = mappedTrackInfo.getTrackGroups(i);
            for (int j = 0; j < trackGroupArray.length; j++) {
                TrackGroup group = trackGroupArray.get(j);

                for (int k = 0; k < group.length; k++) {
                    // Получаем имя трека для сравнения
                    Format format = group.getFormat(k);
                    String trackName = getTrackName(format);

                    if (trackName.equals(audioName)) {
                        DefaultTrackSelector.ParametersBuilder builder = trackSelector.getParameters().buildUpon();

                        // Очищаем предыдущие оверрайды для текущего рендера
                        builder.clearSelectionOverride(i, trackGroupArray);

                        // Включаем рендерер, если он был отключен
                        builder.setRendererDisabled(i, false);

                        // Устанавливаем новый оверрайд выбора трека (преобразуем список в int[])
                        int[] tracks = {k};  // Прямо используем массив int[]
                        builder.setSelectionOverride(i, trackGroupArray, new DefaultTrackSelector.SelectionOverride(j, tracks));

                        // Применяем параметры
                        trackSelector.setParameters(builder);
                        return;
                    }
                }
            }
        }
    }

    void setAudioByIndex(int index) {
        MappingTrackSelector.MappedTrackInfo mappedTrackInfo =
                trackSelector.getCurrentMappedTrackInfo();

        if (mappedTrackInfo == null) {
            return;
        }

        int audioIndex = 0;

        for (int i = 0; i < mappedTrackInfo.getRendererCount(); i++) {
            if (mappedTrackInfo.getRendererType(i) != C.TRACK_TYPE_AUDIO) {
                continue;
            }

            TrackGroupArray trackGroupArray = mappedTrackInfo.getTrackGroups(i);
            for (int j = 0; j < trackGroupArray.length; j++) {
                TrackGroup group = trackGroupArray.get(j);

                for (int k = 0; k < group.length; k++) {
                    if (audioIndex == index) {
                        DefaultTrackSelector.ParametersBuilder builder = trackSelector.getParameters().buildUpon();

                        // Очищаем предыдущие оверрайды для текущего рендера
                        builder.clearSelectionOverride(i, trackGroupArray);

                        // Включаем рендерер, если он был отключен
                        builder.setRendererDisabled(i, false);

                        // Устанавливаем новый оверрайд выбора трека (преобразуем список в int[])
                        int[] tracks = {k};  // Прямо используем массив int[]
                        builder.setSelectionOverride(i, trackGroupArray, new DefaultTrackSelector.SelectionOverride(j, tracks));

                        // Применяем параметры
                        trackSelector.setParameters(builder);
                        return;
                    }
                    audioIndex++;
                }
            }
        }
    }


    /**
     * Пример метода для генерации названия трека на основе Format.
     */
    private String getTrackName(Format format) {
        if (format.language != null) {
            return format.language;  // Используем язык трека как его имя
        } else if (format.sampleMimeType != null) {
            return format.sampleMimeType;  // Используем MIME тип, если язык не доступен
        } else {
            return "Unknown";  // Если никакой информации нет
        }
    }


    private static boolean isHTTP(Uri uri) {
        if (uri == null || uri.getScheme() == null) {
            return false;
        }
        String scheme = uri.getScheme();
        return scheme.equals("http") || scheme.equals("https");
    }

    private MediaSource buildMediaSource(
            Uri uri, DataSource.Factory mediaDataSourceFactory, String formatHint, Context context) {

        int type;
        if (formatHint == null) {
            type = Util.inferContentType(uri.getLastPathSegment());
        } else {
            switch (formatHint) {
                case FORMAT_SS:
                    type = C.TYPE_SS;
                    break;
                case FORMAT_DASH:
                    type = C.TYPE_DASH;
                    break;
                case FORMAT_HLS:
                    type = C.TYPE_HLS;
                    break;
                case FORMAT_OTHER:
                    type = C.TYPE_OTHER;
                    break;
                default:
                    type = -1;
                    break;
            }
        }
        switch (type) {
            case C.TYPE_SS:
                return new SsMediaSource.Factory(
                        new DefaultSsChunkSource.Factory(mediaDataSourceFactory),
                        new DefaultDataSource.Factory(context, mediaDataSourceFactory))
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_DASH:
                return new DashMediaSource.Factory(
                        new DefaultDashChunkSource.Factory(mediaDataSourceFactory),
                        new DefaultDataSource.Factory(context, mediaDataSourceFactory))
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_HLS:
                return new HlsMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            case C.TYPE_OTHER:
                return new ProgressiveMediaSource.Factory(mediaDataSourceFactory)
                        .createMediaSource(MediaItem.fromUri(uri));
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    private void setupVideoPlayer(
            EventChannel eventChannel, TextureRegistry.SurfaceTextureEntry textureEntry) {
        eventChannel.setStreamHandler(
                new EventChannel.StreamHandler() {
                    @Override
                    public void onListen(Object o, EventChannel.EventSink sink) {
                        eventSink.setDelegate(sink);
                    }

                    @Override
                    public void onCancel(Object o) {
                        eventSink.setDelegate(null);
                    }
                });

        surface = new Surface(textureEntry.surfaceTexture());
        exoPlayer.setVideoSurface(surface);
        setAudioAttributes(exoPlayer, options.mixWithOthers);

        exoPlayer.addListener(
                new Listener() {
                    private boolean isBuffering = false;

                    public void setBuffering(boolean buffering) {
                        if (isBuffering != buffering) {
                            isBuffering = buffering;
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", isBuffering ? "bufferingStart" : "bufferingEnd");
                            eventSink.success(event);
                        }
                    }

                    @Override
                    public void onPlaybackStateChanged(final int playbackState) {
                        if (playbackState == Player.STATE_BUFFERING) {
                            setBuffering(true);
                            sendBufferingUpdate();
                        } else if (playbackState == Player.STATE_READY) {
                            if (!isInitialized) {
                                isInitialized = true;
                                sendInitialized();
                            }
                        } else if (playbackState == Player.STATE_ENDED) {
                            Map<String, Object> event = new HashMap<>();
                            event.put("event", "completed");
                            eventSink.success(event);
                        }

                        if (playbackState != Player.STATE_BUFFERING) {
                            setBuffering(false);
                        }
                    }

                    @Override
                    public void onPlayerError(@NonNull PlaybackException error) {
                        setBuffering(false);
                        eventSink.error("VideoError", "Video player had error " + error, null);
                    }
                });

        VideoPlayerPlugin.onPictureInPictureModeChanged = (isInPictureInPictureMode, newConfig) -> {
            Map<String, Object> event = new HashMap<>();
            event.put("event", isInPictureInPictureMode ? "startingPiP" : "stoppedPiP");
            eventSink.success(event);
        };
    }

    void sendBufferingUpdate() {
        Map<String, Object> event = new HashMap<>();
        event.put("event", "bufferingUpdate");
        List<? extends Number> range = Arrays.asList(0, exoPlayer.getBufferedPosition());
        // iOS supports a list of buffered ranges, so here is a list with a single range.
        event.put("values", Collections.singletonList(range));
        eventSink.success(event);
    }

    private static void setAudioAttributes(ExoPlayer exoPlayer, boolean isMixMode) {
        exoPlayer.setAudioAttributes(
                new AudioAttributes.Builder().setContentType(C.CONTENT_TYPE_MOVIE).build(), !isMixMode);
    }

    void play() {
        exoPlayer.setPlayWhenReady(true);
    }

    void pause() {
        exoPlayer.setPlayWhenReady(false);
    }

    void setLooping(boolean value) {
        exoPlayer.setRepeatMode(value ? REPEAT_MODE_ALL : REPEAT_MODE_OFF);
    }

    void setVolume(double value) {
        float bracketedValue = (float) Math.max(0.0, Math.min(1.0, value));
        exoPlayer.setVolume(bracketedValue);
    }

    void setPlaybackSpeed(double value) {
        // We do not need to consider pitch and skipSilence for now as we do not handle them and
        // therefore never diverge from the default values.
        final PlaybackParameters playbackParameters = new PlaybackParameters(((float) value));

        exoPlayer.setPlaybackParameters(playbackParameters);
    }

    void seekTo(int location) {
        exoPlayer.seekTo(location);
    }

    long getPosition() {
        return exoPlayer.getCurrentPosition();
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private void sendInitialized() {
        if (isInitialized) {
            Map<String, Object> event = new HashMap<>();
            event.put("event", "initialized");
            event.put("duration", exoPlayer.getDuration());

            if (exoPlayer.getVideoFormat() != null) {
                Format videoFormat = exoPlayer.getVideoFormat();
                int width = videoFormat.width;
                int height = videoFormat.height;
                int rotationDegrees = videoFormat.rotationDegrees;
                // Switch the width/height if video was taken in portrait mode
                if (rotationDegrees == 90 || rotationDegrees == 270) {
                    width = exoPlayer.getVideoFormat().height;
                    height = exoPlayer.getVideoFormat().width;
                }

                boolean enable = setupPictureInPictureProperties(width, height);

                event.put("width", width);
                event.put("height", height);
                event.put("pipEnable", enable);
            }

            eventSink.success(event);
        }
    }


    private boolean setupPictureInPictureProperties(int width, int height) {
        updateBounds(width, height);

        try {
            if (activity != null && activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    PictureInPictureParams params = getPictureInPictureParams();

                    activity.setPictureInPictureParams(params);

                    return true;
                }
            }
        } catch (Exception error) {
            error.printStackTrace();
        }

        return false;
    }

    private void updateBounds(int width, int height) {
        float aspectRatio = (float) width / (float) height;

        int newHeight = (int) (bounds.width() / aspectRatio);
        int diff = (bounds.height() - newHeight) / 2;

        bounds = new Rect(bounds.left, bounds.top + diff,
                bounds.right, bounds.bottom - diff);
    }

    @SuppressWarnings("deprecation")
    public void setPictureInPicture() {
        try {
            if (activity != null && activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        PictureInPictureParams params = getPictureInPictureParams();

                        activity.enterPictureInPictureMode(params);
                    } else {
                        activity.enterPictureInPictureMode();
                    }
                }
            }
        } catch (Exception error) {
            error.printStackTrace();
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.O)
    private PictureInPictureParams getPictureInPictureParams() {
        PictureInPictureParams.Builder params = new PictureInPictureParams.Builder()
                .setAspectRatio(new Rational(bounds.width(), bounds.height()))
                .setSourceRectHint(bounds);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            params.setAutoEnterEnabled(true);
            params.setSeamlessResizeEnabled(false);
        }

        return params.build();
    }

    private void removePictureInPictureOptions() {
        if (activity != null && activity.getPackageManager().hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                activity.setPictureInPictureParams(new PictureInPictureParams.Builder()
                        .setAutoEnterEnabled(false)
                        .build());

            }
        }
    }

    void dispose() {
        removePictureInPictureOptions();

        if (isInitialized) {
            exoPlayer.stop();
        }
        textureEntry.release();
        eventChannel.setStreamHandler(null);
        if (surface != null) {
            surface.release();
        }
        if (exoPlayer != null) {
            exoPlayer.release();
        }
    }
}
