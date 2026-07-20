package com.coach.attach;

import com.coach.anthropic.AttachmentBlock;
import com.coach.anthropic.MimeType;
import static com.coach.anthropic.MimeType.fromValue;
import com.coach.anthropic.SdkFileUploadGateway;
import com.coach.anthropic.UploadedFile;
import com.coach.config.AppConfig;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Turns uploaded {@link MultipartFile}s into {@link AttachmentBlock}s: validates each,
 * expands any zip into its individual files (via {@link ZipExpander}), then uploads
 * every resulting file to the Anthropic Files API (via {@link FileUploadGateway}).
 *
 * <p>Validation runs to completion <em>before</em> any upload, so a rejected message
 * (unsupported type, oversize, too many files, or a bad zip entry) never leaves
 * orphaned uploads behind.
 */
@Component
public class AttachmentService {

    private static final Set<String> IMAGE_TYPES =
            Set.of("image/jpeg", "image/png", "image/gif", "image/webp");
    private static final String OCTET_STREAM = "application/octet-stream";
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern FORBIDDEN = Pattern.compile("[^A-Za-z0-9._ -]");

    private final SdkFileUploadGateway fileGateway;
    private final ZipExpander zipExpander;
    private final AppConfig config;

    public AttachmentService(SdkFileUploadGateway fileGateway, ZipExpander zipExpander, AppConfig config) {
        this.fileGateway = fileGateway;
        this.zipExpander = zipExpander;
        this.config = config;
    }

    /** Validate, expand zips, and upload; returns one {@link AttachmentBlock} per file. */
    public List<AttachmentBlock> process(List<MultipartFile> files) {
        if (files.isEmpty()) {
            return List.of();
        }
        AppConfig.Upload cfg = config.upload();

        // Phase 1 — validate and expand everything into ready-to-upload files.
        List<ExtractedFile> pending = new ArrayList<>();
        for (MultipartFile file : files) {
            if (file.isEmpty()) {
                continue;
            }
            String filename = file.getOriginalFilename();
            byte[] bytes = bytesOf(file);

            if (isZip(filename, file.getContentType())) {
                pending.addAll(zipExpander.expand(bytes));
                continue;
            }

            String mediaType = resolveType(filename, file.getContentType());
            if (mediaType == null || !cfg.allowedMimeTypes().contains(mediaType)) {
                throw new UploadException("unsupported file type: " + filename);
            }
            if (bytes.length > cfg.maxFileSizeBytes()) {
                throw new UploadException("file too large: " + filename);
            }
            pending.add(new ExtractedFile(filename, mediaType, bytes));
        }

        if (pending.size() > cfg.maxFilesPerMessage()) {
            throw new UploadException("too many files (max " + cfg.maxFilesPerMessage() + ")");
        }

        // Phase 2 — upload the validated files.
        List<AttachmentBlock> out = new ArrayList<>();
        for (ExtractedFile leaf : pending) {
            String filename = safeName(leaf.filename());
            UploadedFile uploaded = fileGateway.upload(filename, leaf.mediaType(), leaf.content());
            MimeType mimeType = fromValue(leaf.mediaType());
            out.add(new AttachmentBlock(uploaded.fileId(), mimeType, filename,
                    kindFor(leaf.mediaType())));
        }
        return out;
    }

    /**
     * Make a filename safe for the Anthropic Files API, which rejects any filename
     * containing a forbidden character (path separators, control characters and
     * assorted punctuation). Diacritics are folded to ASCII first ("Álgebra" →
     * "Algebra") to keep names readable, then anything outside a conservative
     * allowlist of letters, digits, dot, space, dash and underscore becomes '_'.
     */
    static String safeName(String name) {
        if (name == null) {
            return "file";
        }
        String ascii = DIACRITICS.matcher(Normalizer.normalize(name, Normalizer.Form.NFD)).replaceAll("");
        String safe = FORBIDDEN.matcher(ascii).replaceAll("_").strip();
        return safe.isEmpty() ? "file" : safe;
    }

    private static boolean isZip(String filename, String declaredType) {
        return "application/zip".equals(declaredType)
                || (filename != null && filename.toLowerCase(Locale.ROOT).endsWith(".zip"));
    }

    /** Prefer the declared content type, falling back to the extension when missing/generic. */
    private static String resolveType(String filename, String declaredType) {
        if (declaredType != null && !declaredType.isBlank() && !OCTET_STREAM.equals(declaredType)) {
            return declaredType;
        }
        return MediaTypes.byFilename(filename);
    }

    private static AttachmentBlock.Kind kindFor(String mediaType) {
        return mediaType != null && IMAGE_TYPES.contains(mediaType)
                ? AttachmentBlock.Kind.IMAGE
                : AttachmentBlock.Kind.DOCUMENT;
    }

    private static byte[] bytesOf(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
