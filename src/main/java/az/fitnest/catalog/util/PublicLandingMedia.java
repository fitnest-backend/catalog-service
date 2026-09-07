package az.fitnest.catalog.util;

import java.util.regex.Pattern;

public final class PublicLandingMedia {

    public static final String PUBLIC_MEDIA_PATH = "/api/v1/public/landing/media/";
    private static final Pattern FILE_ID = Pattern.compile("^[1-9][0-9]{0,31}$");
    private static final Pattern FILE_ID_IN_PATH = Pattern.compile(
            "(?:/api/v1)?/(?:media/stream|public/landing/media)/([1-9][0-9]{0,31})(?:[/?].*)?$");

    private PublicLandingMedia() {
    }

    public static boolean isSafeFileId(String fileId) {
        return fileId != null && FILE_ID.matcher(fileId).matches();
    }

    public static String extractFileId(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        String trimmed = url.trim();
        java.util.regex.Matcher path = FILE_ID_IN_PATH.matcher(trimmed);
        if (path.find()) {
            return path.group(1);
        }
        int slash = trimmed.lastIndexOf('/');
        String candidate = slash >= 0 ? trimmed.substring(slash + 1) : trimmed;
        int query = candidate.indexOf('?');
        if (query >= 0) {
            candidate = candidate.substring(0, query);
        }
        return isSafeFileId(candidate) ? candidate : null;
    }

    /**
     * Rewrites stored media to the public landing stream path.
     * Returns null when the value is not a numeric file id so private storage URLs never leak.
     */
    public static String toPublicUrl(String url) {
        String fileId = extractFileId(url);
        if (fileId == null) {
            return null;
        }
        return PUBLIC_MEDIA_PATH + fileId;
    }
}
