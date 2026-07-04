package com.coach.anthropic;

import com.anthropic.client.AnthropicClient;
import com.anthropic.core.MultipartField;
import com.anthropic.models.beta.files.FileDeleteParams;
import com.anthropic.models.beta.files.FileMetadata;
import com.anthropic.models.beta.files.FileUploadParams;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;

/**
 * Anthropic SDK gateway for the beta Files API ({@code client.beta().files()}).
 */
@Component
public class SdkFileUploadGateway {

    private final AnthropicClient client;

    public SdkFileUploadGateway(AnthropicClient client) {
        this.client = client;
    }

    public UploadedFile upload(String filename, String mediaType, byte[] content) {
        MultipartField<InputStream> file = MultipartField.<InputStream>builder()
                .value(new ByteArrayInputStream(content))
                .filename(filename)
                .contentType(mediaType)
                .build();
        FileMetadata meta = client.beta().files().upload(FileUploadParams.builder()
                .file(file)
                .build());
        return new UploadedFile(meta.id(), mediaType, filename);
    }

    public void delete(String fileId) {
        client.beta().files().delete(FileDeleteParams.builder().fileId(fileId).build());
    }
}
