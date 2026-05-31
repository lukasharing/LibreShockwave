package com.libreshockwave.vm.opcode;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.Scope;
import com.libreshockwave.vm.builtin.BuiltinRegistry;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PropertyOpcodesTheBuiltinTest {

    @Test
    void theBuiltinWithArgumentsInvokesDirectorCoercionBuiltins() {
        ExecutionContext ctx = contextWithBuiltins();

        Datum stringValue = PropertyOpcodes.resolveTheBuiltin(
                "string", new Datum.ArgList(List.of(Datum.of(42))), ctx);
        Datum integerValue = PropertyOpcodes.resolveTheBuiltin(
                "integer", new Datum.ArgList(List.of(Datum.of(3.75))), ctx);

        assertEquals("42", stringValue.toStr());
        assertEquals(4, integerValue.toInt());
    }

    @Test
    void theBuiltinWithSingleArgumentReadsObjectProperty() {
        ExecutionContext ctx = contextWithBuiltins();
        Datum.ScriptInstance instance = new Datum.ScriptInstance(
                1, Map.of("id", Datum.of("roller-7")));

        Datum value = PropertyOpcodes.resolveTheBuiltin(
                "id", new Datum.ArgList(List.of(instance)), ctx);

        assertEquals("roller-7", value.toStr());
    }

    @Test
    void theBuiltinWithoutArgumentsInvokesDirectorBuiltins() {
        ExecutionContext ctx = contextWithBuiltins();

        Datum value = PropertyOpcodes.resolveTheBuiltin(
                "systemDate", new Datum.ArgList(List.of()), ctx);

        Datum.PropList date = (Datum.PropList) value;
        assertEquals(false, date.get("day").isVoid());
        assertEquals(false, date.get("month").isVoid());
        assertEquals(false, date.get("year").isVoid());
    }

    private static ExecutionContext contextWithBuiltins() {
        ScriptChunk.Handler.Instruction instruction =
                new ScriptChunk.Handler.Instruction(0, Opcode.THE_BUILTIN, 0, 0);
        ScriptChunk.Handler handler = new ScriptChunk.Handler(
                0, 0, 0, 0, 0, 0, 0, 0,
                List.of(), List.of(), List.of(instruction), Map.of(0, 0));
        ScriptChunk script = new ScriptChunk(
                null,
                new ChunkId(1),
                ScriptChunk.ScriptType.MOVIE_SCRIPT,
                0,
                List.of(handler),
                List.of(),
                List.of(),
                List.of(),
                new byte[0]);
        Scope scope = new Scope(script, handler, List.of(), Datum.VOID);
        BuiltinRegistry builtins = new BuiltinRegistry();
        return new ExecutionContext(
                scope,
                instruction,
                builtins,
                null,
                (s, h, args, receiver) -> Datum.VOID,
                name -> null,
                new ExecutionContext.GlobalAccessor() {
                    @Override
                    public Datum getGlobal(String name) {
                        return Datum.VOID;
                    }

                    @Override
                    public void setGlobal(String name, Datum value) {
                    }
                },
                (name, args) -> builtins.invoke(name, null, args),
                errorState -> {
                },
                () -> "(test)");
    }
}
