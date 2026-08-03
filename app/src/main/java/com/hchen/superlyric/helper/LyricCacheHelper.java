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
 */
package com.hchen.superlyric.helper;

import android.content.Context;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.hchen.hooktool.log.XposedLog;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * 通用歌词磁盘缓存助手。
 * <p>
 * Spotify 与网易云特性共享的基建：按提供者命名空间独立存储接口原始 JSON，
 * 不与既有提供者（LyricProvider）共用目录。
 * <p>
 * 路径：{@code cacheDir/SuperLyric/lyric/{provider}/{key}.json}
 * <p>
 * Spotify 传入 {@code key = {locale}/{id}}（locale 段用于区分不同语言的歌词）；
 * 网易云传入 {@code key = {id}}。key 会被清洗，仅允许 {@code [A-Za-z0-9._-]}
 * 与单层 {@code '/'} 分隔，杜绝路径穿越。
 *
 * @author 焕晨HChen
 */
public final class LyricCacheHelper {
    private static final String TAG = "LyricCacheHelper";
    private static final String CACHE_ROOT = "SuperLyric" + File.separator + "lyric";

    private LyricCacheHelper() {
    }

    /**
     * 将接口原始 JSON 写入磁盘缓存。
     *
     * @param provider 提供者命名空间（如 Spotify / Netease），须为纯名称
     * @param key      缓存键（可为 {locale}/{id} 或纯 id）
     * @param json     接口返回的原始 JSON 字符串
     */
    public static void put(@NonNull Context context, @NonNull String provider, @NonNull String key, @NonNull String json) {
        File file = getFile(context, provider, key);
        if (file == null) return;
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return;
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(json.getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException e) {
            XposedLog.logW(TAG, "Failed to write lyric cache: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 读取磁盘缓存，未命中返回 {@code null}。
     */
    @Nullable
    public static String get(@NonNull Context context, @NonNull String provider, @NonNull String key) {
        File file = getFile(context, provider, key);
        if (file == null || !file.exists() || !file.isFile()) return null;
        try (FileInputStream fis = new FileInputStream(file)) {
            byte[] bytes = new byte[(int) file.length()];
            int read = fis.read(bytes);
            if (read != bytes.length) {
                return new String(bytes, 0, Math.max(read, 0), StandardCharsets.UTF_8);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            XposedLog.logW(TAG, "Failed to read lyric cache: " + file.getAbsolutePath(), e);
            return null;
        }
    }

    /**
     * 计算缓存文件路径；provider / key 非法时返回 {@code null}。
     */
    @Nullable
    private static File getFile(@NonNull Context context, @NonNull String provider, @NonNull String key) {
        String providerDir = sanitizeSegment(provider);
        if (providerDir == null) return null;

        String keyPath = sanitizeKey(key);
        if (keyPath == null) return null;

        return new File(new File(context.getCacheDir(), CACHE_ROOT), providerDir + File.separator + keyPath + ".json");
    }

    /**
     * 清洗单个路径段：仅保留 {@code [A-Za-z0-9._-]}，非法返回 {@code null}。
     */
    @Nullable
    private static String sanitizeSegment(@NonNull String segment) {
        if (TextUtils.isEmpty(segment)) return null;
        StringBuilder sb = new StringBuilder(segment.length());
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-') {
                sb.append(c);
            } else {
                return null;
            }
        }
        return sb.toString();
    }

    /**
     * 清洗 key：允许 {@code [A-Za-z0-9._-]} 与单层 {@code '/'} 分隔；
     * 拒绝空段、{@code ..} 与首尾 {@code '/'}。
     */
    @Nullable
    private static String sanitizeKey(@NonNull String key) {
        if (TextUtils.isEmpty(key)) return null;
        String[] segments = key.split("/");
        StringBuilder sb = new StringBuilder();
        for (String segment : segments) {
            String cleaned = sanitizeSegment(segment);
            if (cleaned == null || cleaned.equals(".") || cleaned.equals("..")) return null;
            if (sb.length() > 0) sb.append(File.separatorChar);
            sb.append(cleaned);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }

    /**
     * 获取当前默认语言的 locale 段（如 zh-CN），用于 Spotify 缓存键。
     */
    @NonNull
    public static String currentLocaleTag() {
        return Locale.getDefault().toLanguageTag();
    }
}
