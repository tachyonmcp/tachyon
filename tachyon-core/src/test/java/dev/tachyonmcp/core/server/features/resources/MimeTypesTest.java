/* Copyright (c) 2026 Konstantin Pavlov/IT Staff and contributors. */
package dev.tachyonmcp.core.server.features.resources;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class MimeTypesTest {

    @ParameterizedTest
    @CsvSource({
        "SKILL.md,text/markdown",
        "notes.MARKDOWN,text/markdown",
        "readme.txt,text/plain",
        "extract.py,text/x-python",
        "run.sh,text/plain",
        "manifest.json,application/json",
        "app.js,text/javascript",
        "app.mjs,text/javascript",
        "config.yaml,application/yaml",
        "config.yml,application/yaml",
        "pyproject.toml,application/toml",
        "data.csv,text/csv",
        "page.html,text/html",
        "page.htm,text/html",
        "logo.png,image/png",
        "photo.jpg,image/jpeg",
        "photo.jpeg,image/jpeg",
        "anim.gif,image/gif",
        "icon.svg,image/svg+xml",
        "banner.webp,image/webp",
        "archive.zip,application/zip",
        "noextension,application/octet-stream"
    })
    void guessesMimeTypeFromExtension(String fileName, String expected) {
        assertThat(MimeTypes.guess(fileName)).isEqualTo(expected);
    }

    @ParameterizedTest
    @CsvSource({
        "text/plain,true",
        "text/markdown,true",
        "application/json,true",
        "application/yaml,true",
        "application/toml,true",
        "application/octet-stream,false",
        "image/png,false"
    })
    void classifiesTextualMimeTypes(String mimeType, boolean expected) {
        assertThat(MimeTypes.isText(mimeType)).isEqualTo(expected);
    }
}
