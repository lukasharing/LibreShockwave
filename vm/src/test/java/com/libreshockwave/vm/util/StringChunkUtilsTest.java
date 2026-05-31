package com.libreshockwave.vm.util;

import com.libreshockwave.lingo.StringChunkType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringChunkUtilsTest {

    @Test
    void lineChunksTreatReturnEnterAndCrLfAsLineBreaksInOnePayload() {
        String mixed = "first\rsecond\nthird\r\nfourth";

        assertEquals(4, StringChunkUtils.countChunks(mixed, StringChunkType.LINE, ','));
        assertEquals("first", StringChunkUtils.getChunk(mixed, StringChunkType.LINE, 1, ','));
        assertEquals("second", StringChunkUtils.getChunk(mixed, StringChunkType.LINE, 2, ','));
        assertEquals("third", StringChunkUtils.getChunk(mixed, StringChunkType.LINE, 3, ','));
        assertEquals("fourth", StringChunkUtils.getChunk(mixed, StringChunkType.LINE, 4, ','));
    }

    @Test
    void lineChunksDoNotCreateBlankLineInsideCrLfPair() {
        String crlf = "first\r\nsecond\r\n";

        assertEquals(3, StringChunkUtils.countChunks(crlf, StringChunkType.LINE, ','));
        assertEquals("first", StringChunkUtils.getChunk(crlf, StringChunkType.LINE, 1, ','));
        assertEquals("second", StringChunkUtils.getChunk(crlf, StringChunkType.LINE, 2, ','));
        assertEquals("", StringChunkUtils.getChunk(crlf, StringChunkType.LINE, 3, ','));
    }

    @Test
    void lineChunksHideTrailingProtocolFieldTerminators() {
        String roomItems = "2147418201\twindow_skyscraper\towner\t:w=1,2 3,4 l=5,6 l\t\r\n\u0002";

        assertEquals(2, StringChunkUtils.countChunks(roomItems, StringChunkType.LINE, ','));
        assertEquals("2147418201\twindow_skyscraper\towner\t:w=1,2 3,4 l=5,6 l\t",
                StringChunkUtils.getChunk(roomItems, StringChunkType.LINE, 1, ','));
        assertEquals("", StringChunkUtils.getChunk(roomItems, StringChunkType.LINE, 2, ','));
    }

    @Test
    void lineChunksStripTerminatorFromFinalLineWithoutReturn() {
        String payload = "name=Black Two-Seater Sofa\u0002";

        assertEquals(1, StringChunkUtils.countChunks(payload, StringChunkType.LINE, ','));
        assertEquals("name=Black Two-Seater Sofa",
                StringChunkUtils.getChunk(payload, StringChunkType.LINE, 1, ','));
    }

    @Test
    void lineRangesPreserveOriginalSeparatorsInsideTheRange() {
        String mixed = "first\rsecond\nthird\r\nfourth";

        assertEquals("second\nthird", StringChunkUtils.getChunkRange(
                mixed, StringChunkType.LINE, 2, 3, ','));
    }

    @Test
    void returnDelimitedItemsTreatCrLfAsOneSeparatorInSplitPath() {
        String rows = "first\r\nsecond\nthird";

        assertEquals(List.of("first", "second", "third"),
                StringChunkUtils.splitIntoChunks(rows, StringChunkType.ITEM, '\r'));
    }
}
