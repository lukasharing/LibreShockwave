package com.libreshockwave.vm.builtin.data;

import com.libreshockwave.vm.builtin.BuiltinRegistry;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConstructorBuiltinsTest {

    @Test
    void rgbParsesHexStringColors() {
        Datum result = new BuiltinRegistry().invoke("rgb", null, List.of(Datum.of("#EEEEEE")));

        assertEquals(new Datum.Color(238, 238, 238), result);
    }

    @Test
    void rgbParsesHexSymbolColors() {
        Datum result = new BuiltinRegistry().invoke("rgb", null, List.of(Datum.symbol("EEEEEE")));

        assertEquals(new Datum.Color(238, 238, 238), result);
    }
}
