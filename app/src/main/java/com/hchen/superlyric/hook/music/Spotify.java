/*
 * This file is part of SuperLyric.

 * SuperLyric is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.

 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2025-2026 HChenX

 * Inspired from LyricProvider/spotify-music (https://github.com/tomakino/LyricProvider)
 * Copyright 2026 Proify, Tomakino — Apache License 2.0
 */
package com.hchen.superlyric.hook.music;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import com.hchen.hooktool.hook.AbsHook;
import com.hchen.processor.HookThis;
import com.hchen.superlyric.helper.LyricCacheHelper;
import com.hchen.superlyric.helper.SpotifyHelper;
import com.hchen.superlyric.helper.SpotifyHelper.SpotifyLine;
import com.hchen.superlyric.hook.AbsPublisher;
import com.hchen.superlyricapi.SuperLyricData;
import com.hchen.superlyricapi.SuperLyricLine;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Spotify 歌词提供者。
 * <p>
 * Hook 宿主系统媒体会话（MediaSession.setPlaybackState / setMetadata）拿到音轨标识
 * 与播放状态；hook 宿主网络栈（okhttp3.Headers）捕获会话头；调用 Spotify color-lyrics
 * 私有接口拉取歌词，按「位置插值 + Handler 轮询」推进当前行发布；音译放入翻译槽位。
 * <p>
 * 磁盘缓存命中不触发网络请求；404 视为无歌词（不发任何数据）；
 * 广告 / 无法解析音轨标识 → {@code sendStop()} 清空显示。
 * <p>
 * Inspired from LyricProvider/spotify-music.
 *
 * @author 焕晨HChen
 */
@HookThis(targetPackage = "com.spotify.music")
public final class Spotify extends AbsPublisher {
    private static final long LOOP_INTERVAL_MS = 200L;

    private Context appContext;
    private Handler lyricHandler;

    // 播放状态（setPlaybackState 写入，轮询线程读取）
    private volatile int playbackStateValue = PlaybackState.STATE_NONE;
    private volatile long lastPosition = 0L;
    private volatile long lastPositionUpdateTime = 0L;
    private volatile float speed = 0f;

    // 音轨标识与元数据
    private volatile String currentTrackId;
    private volatile String currentTitle = "";
    private volatile String currentArtist = "";
    private final Map<String, TrackInfo> trackInfoCache = new ConcurrentHashMap<>();

    // 歌词数据（apply 时整体替换）
    private volatile List<SpotifyLine> lyricList = Collections.emptyList();
    private volatile String lyricTrackId;
    private volatile int lastShownIndex = -1;

    // 轮询推进
    private volatile boolean isRunning = false;

    // 异步拉取（缓存线程池：快速切歌时新请求不被慢的旧请求阻塞，
    // 过期结果由 applyLyrics 的 trackId 校验丢弃）
    private final ExecutorService downloadExecutor = Executors.newCachedThreadPool();
    private final Set<String> downloadingIds = ConcurrentHashMap.newKeySet();

    private static final class TrackInfo {
        final String id;
        final String title;
        final String artist;
        final long duration;

        TrackInfo(String id, String title, String artist, long duration) {
            this.id = id;
            this.title = title;
            this.artist = artist;
            this.duration = duration;
        }
    }

    @Override
    protected void onPackageReady(@NonNull XposedModuleInterface.PackageReadyParam param) {
        // 轮询线程
        HandlerThread lyricThread = new HandlerThread("SpotifyLyricThread");
        lyricThread.start();
        lyricHandler = new Handler(lyricThread.getLooper());

        hookPlaybackState();
        hookMetadata();
        hookSessionHeaders();

        logI(TAG, "Spotify hooks loaded (package: " + param.getPackageName() + ")");
    }

    @Override
    protected void onApplicationCreated(@NonNull Context context) {
        super.onApplicationCreated(context);
        appContext = context.getApplicationContext();
    }

    // ------------------------------ MediaSession hooks ------------------------------

    private void hookPlaybackState() {
        hookMethod("android.media.session.MediaSession",
            "setPlaybackState",
            "android.media.session.PlaybackState",
            new AbsHook() {
                @Override
                public void after() {
                    Object arg = getArg(0);
                    if (arg instanceof PlaybackState state) {
                        onPlaybackStateChanged(state);
                    }
                }
            }
        );
    }

    private void hookMetadata() {
        hookMethod("android.media.session.MediaSession",
            "setMetadata",
            "android.media.MediaMetadata",
            new AbsHook() {
                @Override
                public void after() {
                    Object arg = getArg(0);
                    if (arg instanceof MediaMetadata metadata) {
                        onMetadataChanged(metadata);
                    }
                }
            }
        );
    }

    private void onPlaybackStateChanged(@NonNull PlaybackState state) {
        playbackStateValue = state.getState();
        lastPosition = state.getPosition();
        speed = state.getPlaybackSpeed();
        lastPositionUpdateTime = SystemClock.elapsedRealtime();

        logD(TAG, "PlaybackState: state=" + playbackStateValue + ", position=" + lastPosition + ", speed=" + speed);

        switch (playbackStateValue) {
            case PlaybackState.STATE_PLAYING:
                startLoop();
                break;
            case PlaybackState.STATE_STOPPED:
                sendStop();
                stopLoop();
                break;
            default:
                // 暂停/缓冲等：停表保留最后一行
                stopLoop();
                break;
        }
    }

    private void onMetadataChanged(@NonNull MediaMetadata metadata) {
        String id = extractTrackId(metadata);
        if (id == null) {
            // 广告 / 无法解析音轨标识 → sendStop 清空（Apple 范式）
            logD(TAG, "setMetadata: no valid track id, treat as ad/unknown, sendStop");
            sendStop();
            stopLoop();
            return;
        }

        if (id.equals(currentTrackId)) {
            logD(TAG, "setMetadata: same track, ignore: " + id);
            return;
        }

        // 切歌：清空旧歌词与状态，避免残留上一首
        sendStop();
        stopLoop();
        lyricList = Collections.emptyList();
        lyricTrackId = null;
        lastShownIndex = -1;

        currentTrackId = id;
        TrackInfo info = trackInfoCache.computeIfAbsent(id, k -> {
            String title = metadata.getString(MediaMetadata.METADATA_KEY_TITLE);
            String artist = metadata.getString(MediaMetadata.METADATA_KEY_ARTIST);
            long duration = metadata.getLong(MediaMetadata.METADATA_KEY_DURATION);
            return new TrackInfo(id, title, artist, duration);
        });
        currentTitle = info.title != null ? info.title : "";
        currentArtist = info.artist != null ? info.artist : "";
        logD(TAG, "Track changed: id=" + id + ", title=" + currentTitle + ", artist=" + currentArtist);

        fetchLyricsForTrack(id);
    }

    /**
     * 从 MEDIA_ID 剥 {@code spotify:track:} 前缀取音轨标识；
     * 无前缀 / 为空 → 非歌曲内容（广告），返回 null。
     */
    private String extractTrackId(@NonNull MediaMetadata metadata) {
        String mediaId = metadata.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
        if (mediaId == null) return null;
        String stripped = mediaId.startsWith("spotify:track:")
            ? mediaId.substring("spotify:track:".length())
            : "";
        return stripped.isBlank() ? null : stripped;
    }

    // ------------------------------ 拉取与缓存 ------------------------------

    private void fetchLyricsForTrack(@NonNull String id) {
        // 独立命名空间：cacheDir/SuperLyric/lyric/Spotify/{locale}/{id}.json
        String cacheKey = LyricCacheHelper.currentLocaleTag() + "/" + id;

        String cached = appContext != null ? LyricCacheHelper.get(appContext, "Spotify", cacheKey) : null;
        if (cached != null) {
            logD(TAG, "Lyric cache hit for " + id + ", no network request");
            applyLyrics(id, cached);
            return;
        }

        if (!downloadingIds.add(id)) {
            logD(TAG, "Lyric already downloading for " + id);
            return;
        }

        downloadExecutor.execute(() -> {
            try {
                String json = SpotifyHelper.fetchLyric(id);
                logD(TAG, "Lyric fetched for " + id + ", json length=" + json.length());
                applyLyrics(id, json);
                if (appContext != null) {
                    LyricCacheHelper.put(appContext, "Spotify", cacheKey, json);
                    logD(TAG, "Lyric cache written: Spotify/" + cacheKey + ".json");
                }
            } catch (SpotifyHelper.NoFoundLyricException e) {
                logD(TAG, "No lyric found (404) for " + id + ", keep blank");
            } catch (Exception e) {
                logE(TAG, "Failed to fetch lyric for " + id, e);
            } finally {
                downloadingIds.remove(id);
            }
        });
    }

    private void applyLyrics(@NonNull String id, @NonNull String json) {
        // 竞态防护：异步拉取完成时校验当前音轨
        if (!id.equals(currentTrackId)) {
            logD(TAG, "Stale lyric response for " + id + ", current=" + currentTrackId + ", discard");
            return;
        }

        List<SpotifyLine> lines = SpotifyHelper.parseLyrics(json);
        if (lines == null || lines.isEmpty()) {
            logD(TAG, "No lyric lines for " + id + ", keep blank");
            return;
        }

        lyricList = lines;
        lyricTrackId = id;
        lastShownIndex = -1;
        logD(TAG, "Lyrics ready for " + id + ", lines=" + lines.size() + ", first=" + lines.get(0).text);

        if (playbackStateValue == PlaybackState.STATE_PLAYING) {
            startLoop();
        }
    }

    // ------------------------------ 当前行推进 ------------------------------

    private synchronized void startLoop() {
        if (isRunning) return;
        isRunning = true;
        lyricHandler.post(lyricLoop);
    }

    private synchronized void stopLoop() {
        isRunning = false;
    }

    private final Runnable lyricLoop = new Runnable() {
        @Override
        public void run() {
            if (!isRunning) return;
            try {
                if (playbackStateValue != PlaybackState.STATE_PLAYING) {
                    isRunning = false;
                    return;
                }
                if (lyricList.isEmpty() || !Objects.equals(lyricTrackId, currentTrackId)) {
                    isRunning = false;
                    return;
                }

                long estimated = estimatePosition();
                int index = findLineIndex(estimated);
                if (index >= 0 && index != lastShownIndex) {
                    lastShownIndex = index;
                    sendCurrentLine(lyricList.get(index));
                }
            } catch (Throwable t) {
                logE(TAG, "Lyric loop error", t);
            }
            if (isRunning) {
                lyricHandler.postDelayed(lyricLoop, LOOP_INTERVAL_MS);
            }
        }
    };

    /**
     * 位置插值：上次采样位置 + 速度 × 流逝时间。
     */
    private long estimatePosition() {
        long elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTime;
        return lastPosition + (long) (elapsed * speed);
    }

    /**
     * 顺序查找 startTime ≤ 位置 < endTime 的行。
     */
    private int findLineIndex(long position) {
        List<SpotifyLine> lines = lyricList;
        for (int i = 0; i < lines.size(); i++) {
            SpotifyLine line = lines.get(i);
            if (position >= line.startTimeMs && position < line.endTimeMs) {
                return i;
            }
        }
        return -1;
    }

    private void sendCurrentLine(@NonNull SpotifyLine line) {
        SuperLyricData data = new SuperLyricData()
            .setTitle(currentTitle)
            .setArtist(currentArtist)
            .setLyric(new SuperLyricLine(line.text, line.startTimeMs, line.endTimeMs));
        if (line.transliteratedWords != null && !line.transliteratedWords.trim().isEmpty()) {
            data.setTranslation(new SuperLyricLine(line.transliteratedWords));
        }
        sendLyric(data);
        logD(TAG, "sendLyric: [" + currentTitle + " - " + currentArtist + "] " + line.text);
    }

    // ------------------------------ 会话头捕获 ------------------------------

    private void hookSessionHeaders() {
        boolean constructorHooked = false;
        try {
            Class<?> headersClass = findClass("okhttp3.Headers");
            logD(TAG, "Session headers target: class=" + headersClass.getName()
                + ", classloader=" + headersClass.getClassLoader());
            hookAllConstructor(headersClass, new AbsHook() {
                @Override
                public void after() {
                    Object[] args = getArgs();
                    if (args != null && args.length > 0 && args[0] instanceof String[]) {
                        captureNamesAndValues((String[]) args[0]);
                    }
                }
            });
            constructorHooked = true;
            logI(TAG, "Session headers: okhttp3.Headers constructor hooked");
        } catch (Throwable t) {
            logW(TAG, "Session headers: constructor hook unsupported, fallback to Headers$Builder.add", t);
        }

        // 写入漏斗 fallback：无论构造器 hook 是否生效都挂上
        try {
            hookMethod("okhttp3.Headers$Builder",
                "add",
                String.class, String.class,
                new AbsHook() {
                    @Override
                    public void after() {
                        Object name = getArg(0);
                        Object value = getArg(1);
                        if (name instanceof String && value instanceof String) {
                            captureHeader((String) name, (String) value);
                        }
                    }
                }
            );
            logI(TAG, "Session headers: okhttp3.Headers$Builder.add hooked (fallback funnel)");
        } catch (Throwable t) {
            logW(TAG, "Session headers: Headers$Builder.add hook failed", t);
        }

        logD(TAG, "Session headers hook result: constructor=" + constructorHooked + ", fallback=attached");
    }

    private void captureNamesAndValues(@NonNull String[] namesAndValues) {
        for (int i = 0; i + 1 < namesAndValues.length; i += 2) {
            captureHeader(namesAndValues[i], namesAndValues[i + 1]);
        }
    }

    private void captureHeader(@NonNull String name, @NonNull String value) {
        String lower = name.toLowerCase(Locale.ENGLISH);
        if (SpotifyHelper.isKeyRequired(lower)) {
            SpotifyHelper.setHeader(lower, value);
        }
    }
}
