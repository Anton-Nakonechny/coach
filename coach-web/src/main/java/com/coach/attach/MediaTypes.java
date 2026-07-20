package com.coach.attach;

import java.net.URLConnection;
import java.util.Locale;
import java.util.Map;

/**
 * Maps a filename's extension to a MIME type. Used for zip entries (which carry no
 * declared content type) and as a fallback when a multipart part's declared type is
 * missing or generic ({@code application/octet-stream}).
 *
 * <p>Resolution delegates to the JDK's built-in table ({@link URLConnection#guessContentTypeFromName})
 * and supplements it with three types the JDK does not reliably know.
 */
final class MediaTypes {

    private static final Map<String, String> SUPPLEMENT = Map.of(
            "webp", "image/webp",
            "md",   "text/markdown",
            "csv",  "text/csv");

    private MediaTypes() { }

    /** Resolved MIME type for a filename, or {@code null} if the extension is unknown. */
    static String byFilename(String filename) {
        if (filename == null) {
            return null;
        }
        int dot = filename.lastIndexOf('.');
        if (dot < 0 || dot == filename.length() - 1) {
            return null;
        }
        String ext = filename.substring(dot + 1).toLowerCase(Locale.ROOT);
        String type = URLConnection.guessContentTypeFromName("f." + ext);
        if (type != null && !type.equals("application/octet-stream")) {
            return type;
        }
        return SUPPLEMENT.get(ext);
    }
}
