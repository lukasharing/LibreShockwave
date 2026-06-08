package com.libreshockwave.vm.util;

import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class LingoValueParserTest {

    @Test
    void parsesQuotedKeyPropListWithEmptyNestedPropLists() {
        Datum parsed = LingoValueParser.parseWithPartial(
                "[\"a\": [:], \"b\": [:], \"c\": [#ink: 33]]",
                new LingoVM(null));

        assertInstanceOf(Datum.PropList.class, parsed);
        Datum.PropList props = (Datum.PropList) parsed;

        assertInstanceOf(Datum.PropList.class, props.get("a"));
        assertInstanceOf(Datum.PropList.class, props.get("b"));
        assertInstanceOf(Datum.PropList.class, props.get("c"));
        assertEquals(33, ((Datum.PropList) props.get("c")).get("ink").toInt());
    }

    @Test
    void parsesFurniturePropsWithQuotedLayerKeysAndSymbolProperties() {
        Datum parsed = LingoValueParser.parseWithPartial(
                "[\"a\": [#blend: 80], \"b\": [#ink: 33, #blend:0], \"c\": [#ink: 41], \"d\": [#ink: 36]]",
                new LingoVM(null));

        assertInstanceOf(Datum.PropList.class, parsed);
        Datum.PropList props = (Datum.PropList) parsed;

        assertFalse(props.entries().get(0).isSymbolKey(), "quoted layer names remain string keys");
        Datum.PropList layerB = assertInstanceOf(Datum.PropList.class, props.get("b", false));
        assertTrue(layerB.entries().get(0).isSymbolKey(), "#ink remains a symbol key");
        assertEquals(33, layerB.get("ink", true).toInt());
        assertEquals(0, layerB.get("blend", true).toInt());
    }

    @Test
    void parsesBareKeyNestedPropListsUsedByDynamicAssetData() {
        String source = "[\r" +
                "states:[1,2],\r" +
                "statestrings:[ \"off\", \"on\" ],\r" +
                "layers:[ \r" +
                "a:[ [ frames:[ 0 ] ] ], \r" +
                "b:[ [ frames:[ 0 ] ], [ loop:0, delay:2, frames:[ 1, 2, 3 ] ] ]\r" +
                "]\r" +
                "]";

        Datum parsed = LingoValueParser.parseWithPartial(source, new LingoVM(null));

        assertInstanceOf(Datum.PropList.class, parsed);
        Datum.PropList props = (Datum.PropList) parsed;

        assertInstanceOf(Datum.List.class, props.get("states"));
        assertEquals(2, ((Datum.List) props.get("states")).items().size());
        assertInstanceOf(Datum.PropList.class, props.get("layers"));

        Datum.PropList layers = (Datum.PropList) props.get("layers");
        assertInstanceOf(Datum.List.class, layers.get("b"));

        Datum.List bLayers = (Datum.List) layers.get("b");
        assertEquals(2, bLayers.items().size());
        assertInstanceOf(Datum.PropList.class, bLayers.items().get(1));

        Datum.PropList animatedLayer = (Datum.PropList) bLayers.items().get(1);
        assertEquals(0, animatedLayer.get("loop").toInt());
        assertEquals(2, animatedLayer.get("delay").toInt());
        assertInstanceOf(Datum.List.class, animatedLayer.get("frames"));
    }

    @Test
    void parsesFlatQuotedStringListsUsedBySystemPropsClassVariables() {
        Datum parsed = LingoValueParser.parseWithPartial(
                "[\"Manager Template Class\",\"Variable Container Class\"]",
                new LingoVM(null));

        assertInstanceOf(Datum.List.class, parsed);
        Datum.List list = (Datum.List) parsed;
        assertEquals(2, list.items().size());
        assertEquals("Manager Template Class", list.items().get(0).toStr());
        assertEquals("Variable Container Class", list.items().get(1).toStr());
    }

    @Test
    void parsesBareStringListsUsedBySystemPropsClassVariables() {
        Datum parsed = LingoValueParser.parseWithPartial(
                "[Manager Template Class, Variable Container Class]",
                new LingoVM(null));

        assertInstanceOf(Datum.List.class, parsed);
        Datum.List list = (Datum.List) parsed;
        assertEquals(2, list.items().size());
        assertEquals("Manager Template Class", list.items().get(0).toStr());
        assertEquals("Variable Container Class", list.items().get(1).toStr());
    }

    @Test
    void parsesAvailableBbcodeCompatibilityVariable() {
        Datum parsed = LingoValueParser.parseWithPartial(
                "[\"b\":[#style:#fontStyle,#default:[#bold]],\"i\":[#style:#fontStyle,#default:[#italic]]," +
                        "\"u\":[#style:#fontStyle,#default:[#underline]],\"color\":[#style:#color]," +
                        "\"c\":[#style:#color],\"size\":[#style:#fontSize],\"br\":[#replace:\"\\r\"]," +
                        "\"url\":[#style:#url]]",
                new LingoVM(null));

        Datum.PropList props = assertInstanceOf(Datum.PropList.class, parsed);

        assertFalse(props.entries().get(0).isSymbolKey(), "BBCode names are string keys");
        Datum.PropList color = assertInstanceOf(Datum.PropList.class, props.get("color", false));
        assertEquals("color", color.get("style", true).toKeyName());
        Datum.PropList bold = assertInstanceOf(Datum.PropList.class, props.get("b", false));
        Datum.List boldDefault = assertInstanceOf(Datum.List.class, bold.get("default", true));
        assertEquals("bold", boldDefault.items().get(0).toKeyName());
        Datum.PropList breakCode = assertInstanceOf(Datum.PropList.class, props.get("br", false));
        assertEquals("\r", breakCode.get("replace", true).toStr());
    }

    @Test
    void parsesRgbSingleNumberWithDirectorColorSemantics() {
        Datum.Color director238 = colorFromArgb(Datum.datumToArgb(Datum.of(238)));

        assertEquals(director238, LingoValueParser.parseWithPartial("rgb(238)", new LingoVM(null)));
        assertEquals(director238, LingoValueParser.parseWithPartial("rgb(\"238\")", new LingoVM(null)));
        assertEquals(new Datum.Color(0xEE, 0xEE, 0xEE),
                LingoValueParser.parseWithPartial("rgb(\"#EEEEEE\")", new LingoVM(null)));
        assertEquals(new Datum.Color(0x12, 0x34, 0x56),
                LingoValueParser.parseWithPartial("rgb(\"123456\")", new LingoVM(null)));
    }

    @Test
    void parsesFlatMixedLiteralListsWithoutRegexDependency() {
        Datum parsed = LingoValueParser.parseWithPartial(
                "[#core, 7, 3.5, \"Broker Manager Class\"]",
                new LingoVM(null));

        assertInstanceOf(Datum.List.class, parsed);
        Datum.List list = (Datum.List) parsed;
        assertEquals("core", list.items().get(0).toKeyName());
        assertEquals(7, list.items().get(1).toInt());
        assertEquals(3.5, list.items().get(2).toDouble(), 0.0001);
        assertEquals("Broker Manager Class", list.items().get(3).toStr());
    }

    private static Datum.Color colorFromArgb(int argb) {
        return new Datum.Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
    }
}
