package com.waveminer.waveminer.media;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public final class MediaTypes {

    public static final Set<String> VIDEO_EXTENSIONS = Set.of(
            "mp4", "mkv", "mov", "webm", "avi", "flv", "ts", "m4v");
    public static final Set<String> AUDIO_EXTENSIONS = Set.of(
            "mp3", "m4a", "wav", "aac", "flac", "opus", "ogg", "aiff");
    public static final Set<String> ALL_EXTENSIONS;

    static {
        Set<String> all = new HashSet<>(VIDEO_EXTENSIONS);
        all.addAll(AUDIO_EXTENSIONS);
        ALL_EXTENSIONS = Set.copyOf(all);
    }

    private MediaTypes() {}

    public static String extension(String filename) {
        if (filename == null) return "";
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) return "";
        return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    public static boolean isVideo(String filename) {
        return VIDEO_EXTENSIONS.contains(extension(filename));
    }

    public static boolean isMedia(String filename) {
        return ALL_EXTENSIONS.contains(extension(filename));
    }
}
