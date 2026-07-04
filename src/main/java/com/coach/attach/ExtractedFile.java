package com.coach.attach;

/** A file ready to upload: its display name, resolved MIME type, and raw bytes. */
record ExtractedFile(String filename, String mediaType, byte[] content) { }
