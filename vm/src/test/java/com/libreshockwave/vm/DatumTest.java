package com.libreshockwave.vm;

import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

/**
 * Unit tests for Datum value types.
 */
class DatumTest {

    @Test
    void testIntegerValues() {
        Datum zero = Datum.of(0);
        Datum one = Datum.of(1);
        Datum negative = Datum.of(-42);

        assertTrue(zero.isInt());
        assertTrue(one.isInt());
        assertTrue(negative.isInt());

        assertEquals(0, zero.toInt());
        assertEquals(1, one.toInt());
        assertEquals(-42, negative.toInt());

        // Singleton optimization
        assertSame(Datum.ZERO, zero);
        assertSame(Datum.ONE, one);
        assertSame(Datum.of(255), Datum.of(255));
        assertSame(Datum.of(-128), Datum.of(-128));
        assertSame(Datum.of(10000), Datum.of(10000));
        assertNotSame(Datum.of(10001), Datum.of(10001));
    }

    @Test
    void testFloatValues() {
        Datum pi = Datum.of(3.14159);
        Datum negative = Datum.of(-2.5);

        assertTrue(pi.isFloat());
        assertTrue(negative.isFloat());

        assertEquals(3.14159, pi.toDouble(), 0.00001);
        assertEquals(-2.5, negative.toDouble(), 0.00001);

        // Director-style integer conversion rounds to nearest.
        assertEquals(3, pi.toInt());
        assertEquals(4, Datum.of(3.7).toInt());
        assertEquals(4, Datum.of(3.5).toInt());
        assertEquals(-3, negative.toInt());
        assertEquals(-1, Datum.of(-0.5).toInt());
    }

    @Test
    void testStringValues() {
        Datum hello = Datum.of("Hello World");
        Datum empty = Datum.of("");

        assertTrue(hello.isString());
        assertTrue(empty.isString());

        assertEquals("Hello World", hello.toStr());
        assertEquals("", empty.toStr());

        // Empty string singleton
        assertSame(Datum.EMPTY_STRING, empty);
        assertSame(Datum.of("A"), Datum.of("A"));
        assertSame(Datum.of("\0"), Datum.of("\0"));
        assertNotSame(Datum.of("\u0080"), Datum.of("\u0080"));
    }

    @Test
    void testSymbolValues() {
        Datum sym = Datum.symbol("mySymbol");

        assertTrue(sym.isSymbol());
        assertEquals("mySymbol", ((Datum.Symbol) sym).name());
        assertEquals("#mySymbol", sym.toString());
    }

    @Test
    void testListValues() {
        Datum list = Datum.list(Datum.of(1), Datum.of(2), Datum.of(3));

        assertTrue(list.isList());
        assertEquals(3, ((Datum.List) list).items().size());
        assertEquals("[1, 2, 3]", list.toString());
    }

    @Test
    void testPropListValues() {
        Datum propList = Datum.propList(Map.of("x", Datum.of(10), "y", Datum.of(20)));

        assertTrue(propList.isPropList());
        assertEquals(2, ((Datum.PropList) propList).size());
    }

    @Test
    void testPropListDuplicateKeys() {
        // Director's PropList supports duplicate keys (e.g. [#string: "user", #string: "pass"])
        Datum.PropList pl = new Datum.PropList();
        pl.add("string", Datum.of("username"), true);
        pl.add("string", Datum.of("password"), true);

        // Both entries must be preserved
        assertEquals(2, pl.size());

        // get() returns the first match
        assertEquals("username", pl.get("string").toStr());

        // Positional access returns each entry independently
        assertEquals("username", pl.getValue(0).toStr());
        assertEquals("password", pl.getValue(1).toStr());

        // Both keys are "string"
        assertEquals("string", pl.getKey(0));
        assertEquals("string", pl.getKey(1));

        // toString shows both entries
        assertEquals("[#string: \"username\", #string: \"password\"]", pl.toString());
    }

    @Test
    void testPropListDuplicateKeysViaPushPropList() {
        // Verify that the propList literal [#string: a, #string: b] preserves both entries
        Datum.PropList pl = new Datum.PropList();
        pl.add("short", Datum.of(1), true);
        pl.add("string", Datum.of("hello"), true);
        pl.add("short", Datum.of(2), true);  // duplicate key

        assertEquals(3, pl.size());
        // get returns first match
        assertEquals(1, pl.get("short").toInt());
        // positional access
        assertEquals(1, pl.getValue(0).toInt());
        assertEquals("hello", pl.getValue(1).toStr());
        assertEquals(2, pl.getValue(2).toInt());
    }

    @Test
    void testPropListPutUpdatesFirstMatch() {
        Datum.PropList pl = new Datum.PropList();
        pl.add("a", Datum.of(1), true);
        pl.add("b", Datum.of(2), true);
        pl.add("a", Datum.of(3), true);

        // put updates FIRST match only
        pl.put("a", true, Datum.of(99));
        assertEquals(3, pl.size());
        assertEquals(99, pl.getValue(0).toInt());  // first "a" updated
        assertEquals(3, pl.getValue(2).toInt());    // second "a" unchanged
    }

    @Test
    void testPropListSymbolAndStringKeysUseFirstCompatiblePropertyInListOrder() {
        Datum.PropList pl = new Datum.PropList();
        pl.add("foo", Datum.of(1), true);
        pl.add("foo", Datum.of(2), false);

        assertEquals(1, pl.get(Datum.symbol("FOO")).toInt());
        assertEquals(1, pl.get(Datum.of("foo")).toInt());
        assertEquals(1, pl.findPos(Datum.of("foo")));

        pl.put(Datum.of("foo"), Datum.of(9));

        assertEquals(2, pl.size());
        assertTrue(pl.getKeyDatum(0) instanceof Datum.Symbol);
        assertEquals(9, pl.getValue(0).toInt());
        assertEquals(2, pl.getValue(1).toInt());
    }

    @Test
    void testPropListStringReadsFallBackToSymbolKeys() {
        Datum.PropList symbolOnly = new Datum.PropList();
        symbolOnly.add(Datum.symbol("top_up"), Datum.of(7));

        assertEquals(7, symbolOnly.get(Datum.of("top_up")).toInt());
        assertEquals(7, symbolOnly.getAPropOrDefault(Datum.of("top_up"), Datum.VOID).toInt());
        assertEquals(7, symbolOnly.getAtOrDefault(Datum.of("top_up"), Datum.VOID).toInt());
        assertEquals(1, symbolOnly.findPos(Datum.of("top_up")));
    }

    @Test
    void testPropListStringWritesUpdateCompatibleSymbolKeyWhenOnlySymbolExists() {
        Datum.PropList symbolOnly = new Datum.PropList();
        symbolOnly.add(Datum.symbol("top_up"), Datum.of(7));

        symbolOnly.put(Datum.of("top_up"), Datum.of(8));
        assertEquals(1, symbolOnly.size());
        assertTrue(symbolOnly.getKeyDatum(0) instanceof Datum.Symbol);
        assertEquals(8, symbolOnly.get(Datum.symbol("top_up")).toInt());
        assertEquals(8, symbolOnly.get(Datum.of("top_up")).toInt());
    }

    @Test
    void testPropListSymbolReadsCompatibleStringKeys() {
        Datum.PropList stringOnly = new Datum.PropList();
        stringOnly.add(Datum.of("left"), Datum.of(11));

        assertEquals(11, stringOnly.get(Datum.symbol("left")).toInt());
        assertEquals(11, stringOnly.getAPropOrDefault(Datum.symbol("left"), Datum.VOID).toInt());
        assertEquals(11, stringOnly.getAtOrDefault(Datum.symbol("left"), Datum.VOID).toInt());
        assertEquals(1, stringOnly.findPos(Datum.symbol("left")));
    }

    @Test
    void testPropListSetPropMatchesStringKeyWithSymbolQueryAndPreservesToken() {
        Datum.PropList pl = new Datum.PropList();
        pl.add("foo", Datum.of(1), false);

        assertTrue(pl.putExisting(Datum.symbol("foo"), Datum.of(7)));

        assertTrue(pl.getKeyDatum(0) instanceof Datum.Str);
        assertEquals("foo", pl.getKeyDatum(0).toStr());
        assertEquals(7, pl.getValue(0).toInt());
    }

    @Test
    void testPropListDoesNotAliasWindowTextColors() {
        Datum.PropList pl = new Datum.PropList();
        pl.add("color", Datum.of(0xEEEEEE), true);
        pl.add("bgColor", Datum.of(0x6794A7), true);

        assertNull(pl.get("txtColor", true));
        assertNull(pl.get("txtBgColor", true));
    }

    @Test
    void testPropListDoesNotAliasFixedLineSpaceToLineHeight() {
        Datum.PropList pl = new Datum.PropList();
        pl.add("lineHeight", Datum.of(10), true);

        assertNull(pl.get("fixedLineSpace"));
        assertNull(pl.get("fixedLineSpace", true));
    }

    @Test
    void testLingoEqualsCrossType() {
        // Director: #foo = "foo" is TRUE (case-insensitive)
        assertTrue(Datum.symbol("info").lingoEquals(Datum.of("info")));
        assertTrue(Datum.symbol("Info").lingoEquals(Datum.of("info")));
        assertTrue(Datum.of("INFO").lingoEquals(Datum.symbol("info")));

        // Director: VOID = 0 is TRUE
        assertTrue(Datum.VOID.lingoEquals(Datum.ZERO));
        assertTrue(Datum.ZERO.lingoEquals(Datum.VOID));

        // Director: 1 = 1.0 is TRUE
        assertTrue(Datum.of(1).lingoEquals(Datum.of(1.0)));

        // Director authored code commonly compares a numeric zero sentinel with an empty string.
        assertTrue(Datum.ZERO.lingoEquals(Datum.EMPTY_STRING));
        assertTrue(Datum.EMPTY_STRING.lingoEquals(Datum.ZERO));

        // Numeric strings compare as numbers, but arbitrary text does not collapse to zero.
        assertTrue(Datum.of(7).lingoEquals(Datum.of("7")));
        assertTrue(Datum.of("7.5").lingoEquals(Datum.of(7.5)));
        assertFalse(Datum.ZERO.lingoEquals(Datum.of("furniture")));

        // Not equal
        assertFalse(Datum.symbol("foo").lingoEquals(Datum.of("bar")));
        assertFalse(Datum.of(1).lingoEquals(Datum.of(2)));
    }

    @Test
    void testListGetOneWithSymbolStringCrossType() {
        // Simulates connection manager's exists() check:
        // pItemList contains string "info", searching for symbol #Info
        Datum.List list = (Datum.List) Datum.list(Datum.of("info"), Datum.of("mus"));
        Datum target = Datum.symbol("Info");

        // getOne should find the match using lingoEquals
        boolean found = false;
        for (int i = 0; i < list.items().size(); i++) {
            if (list.items().get(i).lingoEquals(target)) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Symbol #Info should match string \"info\" in list search");
    }

    @Test
    void testPropListKeepsHashPrefixedStringKeysSeparateFromSymbols() {
        Datum.PropList pl = new Datum.PropList();
        pl.putTyped("#info", false, Datum.of("connection"));

        assertNull(pl.get("info", true));
        assertEquals(0, pl.findPos("info"));
        assertFalse(pl.containsKey("info"));
        assertEquals("connection", pl.get("#info", false).toStr());
    }

    @Test
    void testPropListStringSymbolCompatibilityKeepsStringCaseSensitive() {
        Datum.PropList pl = new Datum.PropList();
        pl.add(Datum.symbol("room_interface"), Datum.of("thread"));
        pl.putTyped("Room_interface", false, Datum.of("window"));

        assertEquals("thread", pl.get(Datum.of("room_interface")).toStr());
        assertEquals("thread", pl.get(Datum.symbol("room_interface")).toStr());
        assertEquals("window", pl.get(Datum.of("Room_interface")).toStr());
        assertEquals("window", pl.get("Room_interface").toStr());
    }

    @Test
    void testTruthiness() {
        // Falsy values
        assertFalse(Datum.VOID.isTruthy());
        assertFalse(Datum.ZERO.isTruthy());
        assertFalse(Datum.of(0.0).isTruthy());
        assertFalse(Datum.EMPTY_STRING.isTruthy());

        // Truthy values
        assertTrue(Datum.ONE.isTruthy());
        assertTrue(Datum.of(42).isTruthy());
        assertTrue(Datum.of(-1).isTruthy());
        assertTrue(Datum.of(0.001).isTruthy());
        assertTrue(Datum.of("hello").isTruthy());
        assertTrue(Datum.list().isTruthy());
    }

    @Test
    void testTypeCoercion() {
        // String to number
        assertEquals(42, Datum.of("42").toInt());
        assertEquals(3.14, Datum.of("3.14").toDouble(), 0.001);

        // Invalid string to number
        assertEquals(0, Datum.of("abc").toInt());
        assertEquals(0.0, Datum.of("abc").toDouble(), 0.001);

        // Number to string
        assertEquals("42", Datum.of(42).toStr());
        assertEquals("3.14", Datum.of(3.14).toStr());
    }

    @Test
    void testPointAndRect() {
        Datum point = new Datum.Point(100, 200);
        Datum rect = new Datum.Rect(0, 0, 640, 480);

        assertEquals("point(100, 200)", point.toString());
        assertEquals("rect(0, 0, 640, 480)", rect.toString());
    }

    @Test
    void testSpriteAndCastMemberRef() {
        Datum sprite = Datum.SpriteRef.of(5);
        Datum member = Datum.CastMemberRef.of(1, 10);

        assertEquals("sprite(5)", sprite.toString());
        assertEquals("member(10, 1)", member.toString());
        assertEquals(5.0, sprite.toDouble(), 0.001);
    }

    @Test
    void testCastLibAndColorDoubleCoercion() {
        Datum castLib = Datum.CastLibRef.of(7);
        Datum color = new Datum.Color(0x12, 0x34, 0x56);

        assertEquals(7.0, castLib.toDouble(), 0.001);
        assertEquals(0x123456, color.toDouble(), 0.001);
    }

    @Test
    void stringHexColorsConvertToArgb() {
        assertEquals(0xFFFFFFFF, Datum.datumToArgb(Datum.of("#FFFFFF")));
        assertEquals(0xFF1234AB, Datum.datumToArgb(Datum.of("1234AB")));
        assertEquals(0xFFFFFFFF, Datum.datumToArgb(Datum.of("\"#FFFFFF\"")));
        assertEquals(0xFF000000, Datum.datumToArgb(Datum.of("'#000000'")));
    }

    @Test
    void rgbBuiltinAcceptsQuotedHexColorStrings() {
        LingoVM vm = new LingoVM(null);

        assertEquals(new Datum.Color(0xFC, 0xFC, 0xFC),
                vm.callHandler("rgb", List.of(Datum.of("\"#FCFCFC\""))));
        assertEquals(new Datum.Color(0xEE, 0xEE, 0xEE),
                vm.callHandler("rgb", List.of(Datum.of("'#EEEEEE'"))));
    }

    @Test
    void rgbBuiltinTreatsSingleSmallNumbersAsDirectorColorValues() {
        LingoVM vm = new LingoVM(null);

        Datum.Color director238 = colorFromArgb(Datum.datumToArgb(Datum.of(238)));

        assertEquals(director238, vm.callHandler("rgb", List.of(Datum.of(238))));
        assertEquals(director238, vm.callHandler("rgb", List.of(Datum.of("238"))));
        assertEquals(new Datum.Color(0x12, 0x34, 0x56),
                vm.callHandler("rgb", List.of(Datum.of(0x123456))));
        assertEquals(new Datum.Color(0x12, 0x34, 0x56),
                vm.callHandler("rgb", List.of(Datum.of("123456"))));
    }

    @Test
    void colorBuiltinSupportsDirectorRgbColorSpaceSyntax() {
        LingoVM vm = new LingoVM(null);

        assertEquals(new Datum.Color(238, 238, 238),
                vm.callHandler("color", List.of(Datum.symbol("rgb"), Datum.of(238), Datum.of(238), Datum.of(238))));
        assertEquals(new Datum.Color(150, 150, 150),
                vm.callHandler("color", List.of(Datum.symbol("rgb"), Datum.of(150), Datum.of(150), Datum.of(150))));
        assertEquals(new Datum.PaletteIndexColor(238),
                vm.callHandler("color", List.of(Datum.symbol("paletteIndex"), Datum.of(238))));
    }

    private static Datum.Color colorFromArgb(int argb) {
        return new Datum.Color((argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF);
    }

    @Test
    void testNumericOperations() {
        Datum a = Datum.of(10);
        Datum b = Datum.of(3);

        // These would be done by the VM, but we can test the coercion
        assertEquals(10, a.toInt());
        assertEquals(3, b.toInt());
        assertEquals(10.0, a.toDouble(), 0.001);
        assertEquals(3.0, b.toDouble(), 0.001);
    }
}
