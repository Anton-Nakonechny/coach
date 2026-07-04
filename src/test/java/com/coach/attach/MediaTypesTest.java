package com.coach.attach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

class MediaTypesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "photo.png,   image/png",
            "photo.jpg,   image/jpeg",
            "photo.jpeg,  image/jpeg",
            "photo.gif,   image/gif",
            "photo.webp,  image/webp",
            "report.pdf,  application/pdf",
            "notes.txt,   text/plain",
            "readme.md,   text/markdown",
            "data.csv,    text/csv",
            "config.json, application/json",
            "schema.xml,  application/xml",
            "bundle.zip,  application/zip",
    })
    void knownExtensionResolvesToMimeType(String filename, String expected) {
        assertThat(MediaTypes.byFilename(filename)).isEqualTo(expected);
    }

    @Test
    void caseInsensitive() {
        assertThat(MediaTypes.byFilename("PHOTO.PNG")).isEqualTo("image/png");
        assertThat(MediaTypes.byFilename("Report.PDF")).isEqualTo("application/pdf");
    }

    @Test
    void nullFilenameReturnsNull() {
        assertThat(MediaTypes.byFilename(null)).isNull();
    }

    @Test
    void noExtensionReturnsNull() {
        assertThat(MediaTypes.byFilename("Makefile")).isNull();
    }

    @Test
    void unknownExtensionReturnsNull() {
        assertThat(MediaTypes.byFilename("file.xyz")).isNull();
        assertThat(MediaTypes.byFilename("data.unknown")).isNull();
    }

    @Test
    void trailingDotReturnsNull() {
        assertThat(MediaTypes.byFilename("file.")).isNull();
    }
}
