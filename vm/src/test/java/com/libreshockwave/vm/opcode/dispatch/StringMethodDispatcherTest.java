package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.movie.MoviePropertyProvider;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.util.LingoValueParser;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class StringMethodDispatcherTest {

    @Test
    void emulatesVariableContainerDumpForSystemPropsClassEntries() {
        String systemProps = ""
                + "#system\r"
                + "#props\r"
                + "#index\r"
                + "\r"
                + "object.manager.class      = [\"Object Manager Class\"]\r"
                + "variable.manager.class    = [\"Manager Template Class\",\"Variable Container Class\"]\r"
                + "broker.manager.class      = [\"Broker Manager Class\"]\r"
                + "thread.instance.class     = [\"Thread Instance Class\"]";

        MoviePropertyProvider.ItemDelimiterCache._char = '\r';

        Map<String, String> dumped = new LinkedHashMap<>();
        int pairCount = countChunk(systemProps, "item");
        for (int i = 1; i <= pairCount; i++) {
            String pair = getChunk(systemProps, "item", i, i);
            if (pair.isEmpty()) {
                continue;
            }
            String normalizedPair = normalizeWords(pair);
            if (normalizedPair.isEmpty()) {
                continue;
            }
            String firstChar = getChunk(getChunk(normalizedPair, "word", 1, 1), "char", 1, 1);
            if ("#".equals(firstChar)) {
                continue;
            }

            MoviePropertyProvider.ItemDelimiterCache._char = '=';
            String prop = normalizeWords(getChunk(normalizedPair, "item", 1, 1));
            String value = normalizeWords(getChunk(normalizedPair, "item", 2, countChunk(normalizedPair, "item")));
            dumped.put(prop, value);
            MoviePropertyProvider.ItemDelimiterCache._char = '\r';
        }

        assertEquals("[\"Object Manager Class\"]", dumped.get("object.manager.class"));
        assertEquals("[\"Manager Template Class\",\"Variable Container Class\"]",
                dumped.get("variable.manager.class"));
        assertEquals("[\"Broker Manager Class\"]", dumped.get("broker.manager.class"));
        assertEquals("[\"Thread Instance Class\"]", dumped.get("thread.instance.class"));
    }

    @Test
    void returnItemDelimiterAcceptsLfConfigLines() {
        String configText = ""
                + "cast.entry.1=interface_assets\n"
                + "cast.entry.2=patch_assets\n"
                + "client.window.title=Director Runtime";

        MoviePropertyProvider.ItemDelimiterCache._char = '\r';

        assertEquals(3, countChunk(configText, "item"));
        assertEquals("cast.entry.1=interface_assets", getChunk(configText, "item", 1, 1));
        assertEquals("cast.entry.2=patch_assets", getChunk(configText, "item", 2, 2));
        assertEquals("client.window.title=Director Runtime", getChunk(configText, "item", 3, 3));
    }

    @Test
    void returnItemDelimiterTreatsCrLfAsOneBreak() {
        String configText = "cast.entry.1=interface_assets\r\ncast.entry.2=patch_assets";

        MoviePropertyProvider.ItemDelimiterCache._char = '\r';

        assertEquals(2, countChunk(configText, "item"));
        assertEquals("cast.entry.1=interface_assets", getChunk(configText, "item", 1, 1));
        assertEquals("cast.entry.2=patch_assets", getChunk(configText, "item", 2, 2));
    }

    @Test
    void parsesSingleStringClassListsUsedBySystemProps() {
        Datum parsed = LingoValueParser.parseWithPartial("[\"Broker Manager Class\"]", new LingoVM(null));

        assertInstanceOf(Datum.List.class, parsed);
        Datum.List list = (Datum.List) parsed;
        assertEquals(1, list.items().size());
        assertEquals("Broker Manager Class", list.items().get(0).toStr());
    }

    private static int countChunk(String value, String chunkType) {
        return StringMethodDispatcher.dispatch(
                Datum.of(value),
                "count",
                List.of(Datum.symbol(chunkType))).toInt();
    }

    private static String getChunk(String value, String chunkType, int start, int end) {
        return StringMethodDispatcher.dispatch(
                Datum.of(value),
                "getProp",
                List.of(Datum.symbol(chunkType), Datum.of(start), Datum.of(end))).toStr();
    }

    private static String normalizeWords(String value) {
        int wordCount = countChunk(value, "word");
        if (wordCount <= 0) {
            return "";
        }
        return getChunk(value, "word", 1, wordCount);
    }
}
