package com.libreshockwave.vm.builtin.data;

import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.BuiltinRegistry;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ListBuiltinsTest {

    @Test
    void globalGetOneReturnsPropertyKeyForPropertyLists() {
        BuiltinRegistry builtins = new BuiltinRegistry();
        LingoVM vm = new LingoVM(null);
        Datum.PropList propList = new Datum.PropList();
        Datum buttonElement = Datum.of("move-button-element");
        propList.add("move.button", buttonElement, false);

        Datum key = builtins.invoke("getOne", vm, List.of(propList, buttonElement));

        assertTrue(key instanceof Datum.Str);
        assertEquals("move.button", key.toStr());

        builtins.invoke("deleteProp", vm, List.of(propList, key));

        assertEquals(0, propList.size());
    }

    @Test
    void globalDeletePropStringKeyRemovesCompatibleSymbolWhenExactStringMissing() {
        BuiltinRegistry builtins = new BuiltinRegistry();
        LingoVM vm = new LingoVM(null);
        Datum.PropList propList = new Datum.PropList();
        propList.add("top_up", Datum.of(1), true);

        builtins.invoke("deleteProp", vm, List.of(propList, Datum.of("top_up")));

        assertEquals(0, propList.size());
    }

    @Test
    void globalGetPosAndDeleteOneOperateOnPropertyListValues() {
        BuiltinRegistry builtins = new BuiltinRegistry();
        LingoVM vm = new LingoVM(null);
        Datum.PropList propList = new Datum.PropList();
        Datum buttonElement = Datum.of("pick-button-element");
        propList.add("name", Datum.of("label"), true);
        propList.add("pick.button", buttonElement, false);

        Datum position = builtins.invoke("getPos", vm, List.of(propList, buttonElement));
        assertEquals(2, position.toInt());

        builtins.invoke("deleteOne", vm, List.of(propList, buttonElement));

        assertEquals(1, propList.size());
        assertEquals("name", propList.getKeyDatum(0).toKeyName());
    }
}
