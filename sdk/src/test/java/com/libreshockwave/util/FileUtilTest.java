package com.libreshockwave.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class FileUtilTest {

    @Test
    void castFallbacksPreserveAuthoredExtensionFirst() {
        assertArrayEquals(
                new String[] {
                        "https://cdn.example.test/movie/external_cast.cst",
                        "https://cdn.example.test/movie/external_cast.cct"
                },
                FileUtil.getUrlsWithFallbacks("https://cdn.example.test/movie/external_cast.cst"));

        assertArrayEquals(
                new String[] {
                        "https://assets.example.test/casts/shared.cct",
                        "https://assets.example.test/casts/shared.cst"
                },
                FileUtil.getUrlsWithFallbacks("https://assets.example.test/casts/shared.cct"));
    }
}
