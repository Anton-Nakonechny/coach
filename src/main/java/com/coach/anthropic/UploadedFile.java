package com.coach.anthropic;

/** Result of uploading one file to the Anthropic Files API. */
public record UploadedFile(String fileId, String mediaType, String filename) { }
