package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.datum.LingoException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropListMethodDispatcherTest {

    @Test
    void getAtSymbolKeyMatchesSymbolEntry() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("color", Datum.of(255), true);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(new Datum.Symbol("color")));

        assertEquals(255, result.toInt());
    }

    @Test
    void getAtStringKeyMatchesStringEntry() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("Color", Datum.of(255), false);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of("Color")));

        assertEquals(255, result.toInt());
    }

    @Test
    void getAtNumericStringDoesNotFallBackToNumericPropertyKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add(Datum.of(42), Datum.of("numeric"));

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of("42")));

        assertTrue(result.isVoid());
    }

    @Test
    void getAPropNumericKeyDoesNotFallBackToStringNumericPropertyKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("0", Datum.of("hello-listener"), false);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getAProp", List.of(Datum.of(0)));

        assertTrue(result.isVoid());
    }

    @Test
    void ilkMethodReturnsPropListTypeWhenStoredIlkExists() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("ilk", Datum.symbol("struct"), true);

        Datum result = PropListMethodDispatcher.dispatch(propList, "ilk", List.of());

        assertEquals("propList", result.toKeyName());
    }

    @Test
    void ilkMethodMatchesPropListAndListTypes() {
        Datum.PropList propList = new Datum.PropList();

        assertEquals(Datum.TRUE, PropListMethodDispatcher.dispatch(
                propList, "ilk", List.of(Datum.symbol("propList"))));
        assertEquals(Datum.TRUE, PropListMethodDispatcher.dispatch(
                propList, "ilk", List.of(Datum.symbol("list"))));
        assertEquals(Datum.FALSE, PropListMethodDispatcher.dispatch(
                propList, "ilk", List.of(Datum.symbol("struct"))));
    }

    @Test
    void getAtAndGetAPropStillReadStoredIlkProperty() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("ilk", Datum.symbol("struct"), true);

        Datum getAtResult = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.symbol("ilk")));
        Datum getAPropResult = PropListMethodDispatcher.dispatch(
                propList, "getAProp", List.of(Datum.symbol("ilk")));

        assertEquals("struct", getAtResult.toKeyName());
        assertEquals("struct", getAPropResult.toKeyName());
    }

    @Test
    void getAtIntegerKeepsPositionalBehaviorWithStringNumericKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("2", Datum.of("string-key"), false);
        propList.add("second", Datum.of("positional"), true);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of(2)));

        assertEquals("positional", result.toStr());
    }

    @Test
    void getAtIntegerDoesNotFallBackToNumericPropertyKeyWhenIndexIsOutOfRange() {
        Datum.PropList propList = new Datum.PropList();
        propList.add(Datum.of(42), Datum.of("numeric-key"));

        Datum getAtResult = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of(42)));
        Datum getaPropResult = PropListMethodDispatcher.dispatch(
                propList, "getAProp", List.of(Datum.of(42)));

        assertTrue(getAtResult.isVoid());
        assertEquals("numeric-key", getaPropResult.toStr());
    }

    @Test
    void getAtStringKeyIsCaseSensitive() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("room_interface", Datum.of(1), false);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of("Room_interface")));

        assertTrue(result.isVoid());
    }

    @Test
    void getAtSymbolKeyIsCaseInsensitive() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("room_interface", Datum.of(1), true);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.symbol("Room_interface")));

        assertEquals(1, result.toInt());
    }

    @Test
    void getAtStringAndSymbolKeysUseFirstCompatiblePropertyInListOrder() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("color", Datum.of(255), false);
        propList.add("top_up", Datum.of(9), true);

        Datum symbolResult = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(new Datum.Symbol("color")));
        Datum stringResult = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of("color")));
        Datum symbolFromStringResult = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of("top_up")));

        assertEquals(255, symbolResult.toInt());
        assertEquals(255, stringResult.toInt());
        assertEquals(9, symbolFromStringResult.toInt());
    }

    @Test
    void getValueSymbolKeyReadsCompatibleStringKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("name", Datum.of(42), false);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getValue", List.of(new Datum.Symbol("name")));

        assertEquals(42, result.toInt());
    }

    @Test
    void getValueStringKeyFallsBackToSymbolWhenExactKeyMissing() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("top_up", Datum.of(42), true);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getValue", List.of(Datum.of("top_up")));

        assertEquals(42, result.toInt());
    }

    @Test
    void findPosStringKeyUsesCompatibleSymbolKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("top_up", Datum.of(42), true);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "findPos", List.of(Datum.of("top_up")));

        assertEquals(1, result.toInt());
    }

    @Test
    void setPropStringKeyUpdatesCompatibleSymbolKeyWhenExactStringMissing() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("top_up", Datum.of(1), true);

        PropListMethodDispatcher.dispatch(
                propList, "setProp", List.of(Datum.of("top_up"), Datum.of(2)));

        assertEquals(1, propList.size());
        assertTrue(propList.getKeyDatum(0) instanceof Datum.Symbol);
        assertEquals(2, propList.getAtOrDefault(Datum.symbol("top_up"), Datum.VOID).toInt());
    }

    @Test
    void getAtReturnsFirstCompatibleDuplicateInPhysicalOrder() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("key", Datum.of(1), true);   // symbol #key
        propList.add("key", Datum.of(2), false);   // string "key"

        Datum symResult = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(new Datum.Symbol("key")));
        Datum strResult = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of("key")));

        assertEquals(1, symResult.toInt());
        assertEquals(1, strResult.toInt());
    }

    @Test
    void getPropAtPreservesStringKeys() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("1", Datum.of("Guest Rooms"), false);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getPropAt", List.of(Datum.of(1)));

        assertTrue(result instanceof Datum.Str);
        assertEquals("1", result.toStr());
    }

    @Test
    void getPropAtPreservesSymbolKeys() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("category", Datum.of("Guest Rooms"), true);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getPropAt", List.of(Datum.of(1)));

        assertTrue(result instanceof Datum.Symbol);
        assertEquals("category", result.toKeyName());
    }

    @Test
    void getOnePreservesStringKeyForDeletePropRoundTrip() {
        Datum.PropList propList = new Datum.PropList();
        Datum value = Datum.of("window-element");
        propList.add("move.button", value, false);

        Datum key = PropListMethodDispatcher.dispatch(
                propList, "getOne", List.of(value));
        assertTrue(key instanceof Datum.Str);
        assertEquals("move.button", key.toStr());

        PropListMethodDispatcher.dispatch(propList, "deleteProp", List.of(key));

        assertEquals(0, propList.size());
    }

    @Test
    void getOnePreservesSymbolKeyForDeletePropRoundTrip() {
        Datum.PropList propList = new Datum.PropList();
        Datum value = Datum.of("thread-object");
        propList.add("room_interface", value, true);

        Datum key = PropListMethodDispatcher.dispatch(
                propList, "getOne", List.of(value));
        assertTrue(key instanceof Datum.Symbol);
        assertEquals("room_interface", key.toKeyName());

        PropListMethodDispatcher.dispatch(propList, "deleteProp", List.of(key));

        assertEquals(0, propList.size());
    }

    @Test
    void getPosAndDeleteOneOperateOnPropertyListValues() {
        Datum.PropList propList = new Datum.PropList();
        Datum moveButton = Datum.of("move-button-element");
        propList.add("name", Datum.of("label"), true);
        propList.add("move.button", moveButton, false);

        Datum position = PropListMethodDispatcher.dispatch(
                propList, "getPos", List.of(moveButton));
        assertEquals(2, position.toInt());

        PropListMethodDispatcher.dispatch(propList, "deleteOne", List.of(moveButton));

        assertEquals(1, propList.size());
        assertEquals("name", propList.getKeyDatum(0).toKeyName());
    }

    @Test
    void setAPropAndGetPropAtPreservePointKeys() {
        Datum.PropList propList = new Datum.PropList();
        Datum.Point point = new Datum.Point(147, 69);

        PropListMethodDispatcher.dispatch(
                propList, "setAProp", List.of(point, Datum.of("prop-image")));

        Datum key = PropListMethodDispatcher.dispatch(
                propList, "getPropAt", List.of(Datum.of(1)));
        Datum value = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of(1)));
        Datum keyedValue = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(new Datum.Point(147, 69)));

        assertTrue(key instanceof Datum.Point);
        assertEquals(point, key);
        assertEquals("prop-image", value.toStr());
        assertEquals("prop-image", keyedValue.toStr());
    }

    @Test
    void setAPropUpdatesFirstDuplicateAndSetPropRequiresExistingProperty() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("door", Datum.of("first"), false);
        propList.add("door", Datum.of("second"), false);

        PropListMethodDispatcher.dispatch(
                propList, "setAProp", List.of(Datum.of("door"), Datum.of("updated")));

        assertEquals("updated", propList.getValue(0).toStr());
        assertEquals("second", propList.getValue(1).toStr());

        assertThrows(LingoException.class, () -> PropListMethodDispatcher.dispatch(
                propList, "setProp", List.of(Datum.of("missing"), Datum.of("value"))));
    }

    @Test
    void setAPropUpdatesFirstCompatibleSymbolOrStringKeyAndPreservesKeyToken() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("door", Datum.of("first"), false);
        propList.add("door", Datum.of("second"), true);

        PropListMethodDispatcher.dispatch(
                propList, "setAProp", List.of(Datum.symbol("door"), Datum.of("updated")));

        assertTrue(propList.getKeyDatum(0) instanceof Datum.Str);
        assertEquals("updated", propList.getValue(0).toStr());
        assertEquals("second", propList.getValue(1).toStr());
    }

    @Test
    void getAtIntegerPrefersPositionOverNumericPropertyKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("first", Datum.of("positional"), true);
        propList.add(Datum.of(1), Datum.of("numeric-key"));

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of(1)));

        assertEquals("positional", result.toStr());
    }

    @Test
    void setAtIntegerOutOfRangeRaisesScriptErrorForPropertyList() {
        Datum.PropList propList = new Datum.PropList();

        assertThrows(LingoException.class, () -> PropListMethodDispatcher.dispatch(
                propList, "setAt", List.of(Datum.of(42), Datum.of("roller"))));
        assertEquals(0, propList.size());
    }

    @Test
    void setAtIntegerInRangeKeepsPositionalBehaviorWhenNoNumericKeyExists() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("first", Datum.of("old"), true);

        PropListMethodDispatcher.dispatch(
                propList, "setAt", List.of(Datum.of(1), Datum.of("new")));

        assertEquals(1, propList.size());
        assertEquals("new", propList.getValue(0).toStr());
        assertTrue(PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of("1"))).isVoid());
    }

    @Test
    void methodSetAtWithSymbolKeyMatchesBracketAssignment() {
        Datum.PropList propList = new Datum.PropList();

        PropListMethodDispatcher.dispatch(
                propList, "setAt", List.of(Datum.symbol("room_interface"), Datum.of("object")));

        assertEquals(1, propList.size());
        assertTrue(propList.entries().getFirst().isSymbolKey());
        assertEquals("object", PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.symbol("room_interface"))).toStr());
    }

    @Test
    void methodSetAtAllowsSymbolThreadAndStringWindowIdsToCoexist() {
        Datum.PropList propList = new Datum.PropList();

        PropListMethodDispatcher.dispatch(
                propList, "setAt", List.of(Datum.symbol("room_interface"), Datum.of("thread")));
        PropListMethodDispatcher.dispatch(
                propList, "setAt", List.of(Datum.of("Room_interface"), Datum.of("window")));

        assertEquals(2, propList.size());
        assertEquals("thread", PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.symbol("room_interface"))).toStr());
        assertEquals("window", PropListMethodDispatcher.dispatch(
                propList, "getAt", List.of(Datum.of("Room_interface"))).toStr());
    }

    @Test
    void getPropAndFindPosPreservePointKeys() {
        Datum.PropList propList = new Datum.PropList();
        Datum.Point point = new Datum.Point(14, 149);

        PropListMethodDispatcher.dispatch(
                propList, "setAProp", List.of(point, Datum.of("wall-prop")));

        Datum keyedValue = PropListMethodDispatcher.dispatch(
                propList, "getAProp", List.of(new Datum.Point(14, 149)));
        Datum pos = PropListMethodDispatcher.dispatch(
                propList, "findPos", List.of(new Datum.Point(14, 149)));

        assertEquals("wall-prop", keyedValue.toStr());
        assertEquals(1, pos.toInt());
    }

    @Test
    void numericKeysDoNotCorruptPositionalMessageRegistration() {
        Datum.PropList messageMap = new Datum.PropList();
        messageMap.add(Datum.of(-1), Datum.symbol("handleDisconnect"));
        messageMap.add(Datum.of(0), Datum.symbol("handleHello"));
        messageMap.add(Datum.of(1), Datum.symbol("handleSecretKey"));
        messageMap.add(Datum.of(2), Datum.symbol("handleRights"));

        Datum.PropList listeners = new Datum.PropList();
        for (int i = 1; i <= messageMap.size(); i++) {
            Datum messageId = PropListMethodDispatcher.dispatch(
                    messageMap, "getPropAt", List.of(Datum.of(i)));
            Datum method = PropListMethodDispatcher.dispatch(
                    messageMap, "getAt", List.of(Datum.of(i)));
            PropListMethodDispatcher.dispatch(
                    listeners, "setAProp", List.of(messageId, method));
        }

        assertEquals("handleDisconnect", listeners.getAProp(Datum.of(-1)).toKeyName());
        assertEquals("handleHello", listeners.getAProp(Datum.of(0)).toKeyName());
        assertEquals("handleSecretKey", listeners.getAProp(Datum.of(1)).toKeyName());
        assertEquals("handleRights", listeners.getAProp(Datum.of(2)).toKeyName());
    }

    @Test
    void getValueEvaluatesStoredStringByKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("class.list", Datum.of("[Manager Template Class, Variable Container Class]"), false);

        Datum result = PropListMethodDispatcher.dispatch(
                propList, "getValue", List.of(Datum.of("class.list")));

        Datum.List list = (Datum.List) result;
        assertEquals("Manager Template Class", list.items().get(0).toStr());
        assertEquals("Variable Container Class", list.items().get(1).toStr());
    }

    @Test
    void getValueReturnsScalarValuesByKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("connection.info.id", Datum.symbol("info"), false);
        propList.add("client.textdata.utf8", Datum.of(1), false);

        Datum symbolResult = PropListMethodDispatcher.dispatch(
                propList, "getValue", List.of(Datum.of("connection.info.id")));
        Datum intResult = PropListMethodDispatcher.dispatch(
                propList, "getValue", List.of(Datum.of("client.textdata.utf8")));

        assertEquals("info", symbolResult.toKeyName());
        assertEquals(1, intResult.toInt());
    }

    @Test
    void deletePropRemovesFirstCompatibleSymbolOrStringKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("room_interface", Datum.of(1), true);   // symbol #room_interface
        propList.add("room_interface", Datum.of(2), false);  // string "room_interface"

        PropListMethodDispatcher.dispatch(
                propList, "deleteProp", List.of(Datum.of("room_interface")));

        assertEquals(1, propList.size());
        assertTrue(propList.entries().getFirst().keyDatum() instanceof Datum.Str);
        assertEquals(2, propList.entries().getFirst().value().toInt());
    }

    @Test
    void deletePropStringKeyRemovesCompatibleSymbolWhenTextMatchesExactly() {
        Datum.PropList propList = new Datum.PropList();
        propList.add("top_up", Datum.of(1), true);

        PropListMethodDispatcher.dispatch(
                propList, "deleteProp", List.of(Datum.of("top_up")));

        assertEquals(0, propList.size());
    }

    @Test
    void deletePropNumericStringDoesNotRemoveNumericPropertyKey() {
        Datum.PropList propList = new Datum.PropList();
        propList.add(Datum.of(42), Datum.of("numeric"));

        PropListMethodDispatcher.dispatch(
                propList, "deleteProp", List.of(Datum.of("42")));

        assertEquals(1, propList.size());
        assertEquals("numeric", propList.getValue(0).toStr());
    }
}
