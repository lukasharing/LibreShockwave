package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.Scope;
import com.libreshockwave.vm.builtin.BuiltinRegistry;
import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.opcode.ExecutionContext;
import com.libreshockwave.vm.support.NoOpCastLibProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.lang.reflect.Field;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
class ScriptInstanceMethodDispatcherTest {

    @Test
    void numericCloseThreadDefersDuringActiveHandler() throws Exception {
        LingoVM vm = new LingoVM(null);
        pushActiveScope(vm);

        assertTrue(ScriptInstanceMethodDispatcher.shouldDeferNumericCloseThread(
                vm,
                "closethread",
                List.of(Datum.of(11))));
        assertTrue(ScriptInstanceMethodDispatcher.shouldDeferNumericCloseThread(
                vm,
                "closethread",
                List.of(Datum.of(11.5))));
    }

    @Test
    void symbolicCloseThreadDoesNotDeferDuringActiveHandler() throws Exception {
        LingoVM vm = new LingoVM(null);
        pushActiveScope(vm);

        assertFalse(ScriptInstanceMethodDispatcher.shouldDeferNumericCloseThread(
                vm,
                "closethread",
                List.of(Datum.symbol("catalogue"))));
    }

    @Test
    void numericCloseThreadDoesNotDeferWhileFlushingDeferredTasks() throws Exception {
        LingoVM vm = new LingoVM(null);
        pushActiveScope(vm);
        setBooleanField(vm, "flushingDeferredTasks", true);

        assertFalse(ScriptInstanceMethodDispatcher.shouldDeferNumericCloseThread(
                vm,
                "closethread",
                List.of(Datum.of(11))));
    }

    @Test
    void numericCloseThreadDoesNotDeferOutsideActiveHandler() {
        LingoVM vm = new LingoVM(null);

        assertFalse(ScriptInstanceMethodDispatcher.shouldDeferNumericCloseThread(
                vm,
                "closethread",
                List.of(Datum.of(11))));
    }

    @Test
    void numericCloseThreadDispatchQueuesTickBoundaryTask() throws Exception {
        LingoVM vm = new LingoVM(null);
        pushActiveScope(vm);
        setCurrentVm(vm);

        try {
            Datum result = ScriptInstanceMethodDispatcher.dispatch(
                    null,
                    new Datum.ScriptInstance(77, new LinkedHashMap<>()),
                    "closeThread",
                    List.of(Datum.of(11)));

            assertTrue(result.isTruthy());
            assertEquals(0, getDequeSize(vm, "deferredScriptInstanceCalls"));
            assertEquals(1, getDequeSize(vm, "deferredTasks"));
        } finally {
            clearCurrentVm();
        }
    }

    @Test
    void explicitScriptHandlerRunsForInstanceMethod() {
        ScriptChunk.Handler handler = createTestHandler();
        ScriptChunk script = createTestScript(handler);
        Scope scope = new Scope(script, handler, List.of(), Datum.VOID);
        ExecutionContext ctx = new ExecutionContext(
                scope,
                handler.instructions().getFirst(),
                new BuiltinRegistry(),
                null,
                (ignoredScript, ignoredHandler, args, receiver) -> Datum.of("script:getmemnum"),
                ignoredName -> null,
                new ExecutionContext.GlobalAccessor() {
                    @Override
                    public Datum getGlobal(String name) {
                        return Datum.VOID;
                    }

                    @Override
                    public void setGlobal(String name, Datum value) {}
                },
                (name, args) -> Datum.VOID,
                ignored -> {},
                () -> "");

        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, new LinkedHashMap<>());

        CastLibProvider.setProvider(new ScriptHandlerProvider(script, handler, false));
        try {
            Datum result = ScriptInstanceMethodDispatcher.dispatch(
                    ctx,
                    instance,
                    "getmemnum",
                    List.of(Datum.of("Object Base Class")));
            assertEquals("script:getmemnum", result.toStr());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void handlerPredicateWalksAncestorChain() {
        ScriptChunk.Handler handler = createTestHandler();
        ScriptChunk script = createTestScript(handler);
        Datum.ScriptInstance ancestor = new Datum.ScriptInstance(78, new LinkedHashMap<>());
        Datum.ScriptInstance child = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        child.properties().put(Datum.PROP_ANCESTOR, ancestor);

        CastLibProvider.setProvider(new NoOpCastLibProvider() {
            @Override
            public HandlerLocation findHandlerInScript(int scriptId, String handlerName) {
                if (scriptId == 78 && "handleEndCrypto".equalsIgnoreCase(handlerName)) {
                    return new HandlerLocation(1, script, handler, null);
                }
                return null;
            }
        });
        try {
            Datum result = ScriptInstanceMethodDispatcher.dispatch(
                    null,
                    child,
                    "handler",
                    List.of(Datum.symbol("handleEndCrypto")));

            assertTrue(result.isTruthy());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void nestedPropLookupFallsBackPastIncompleteAncestorShadow() {
        Datum.PropList incompleteProps = new Datum.PropList();
        incompleteProps.add("id", Datum.of("shadow"), true);
        Datum.PropList actualProps = new Datum.PropList();
        actualProps.add("bgColor", new Datum.Color(255, 255, 255), true);

        Datum.ScriptInstance ancestor = new Datum.ScriptInstance(78, new LinkedHashMap<>());
        ancestor.properties().put("pProps", actualProps);
        Datum.ScriptInstance child = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        child.properties().put("pProps", incompleteProps);
        child.properties().put(Datum.PROP_ANCESTOR, ancestor);

        Datum result = ScriptInstanceMethodDispatcher.dispatch(
                null, child, "getProp", List.of(Datum.symbol("pProps"), Datum.symbol("bgColor")));

        assertEquals(new Datum.Color(255, 255, 255), result);
    }

    @Test
    void nestedPropLookupUsesNumericPropertyKeyWhenIndexIsOutOfRange() {
        Datum.PropList props = new Datum.PropList();
        props.putTyped(Datum.of(2147418112), Datum.of("sandbox"));
        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        instance.properties().put("pObjects", props);

        Datum result = ScriptInstanceMethodDispatcher.dispatch(
                null, instance, "getProp", List.of(Datum.symbol("pObjects"), Datum.of(2147418112)));

        assertEquals("sandbox", result.toStr());
    }

    @Test
    void nestedPropLookupUsesNumericIndexWhenInRange() {
        Datum.PropList task = new Datum.PropList();
        task.put(Datum.symbol("uniqueid"), Datum.of("Timeout uid:1:100"));
        Datum.PropList itemList = new Datum.PropList();
        itemList.put(Datum.of("pwdhide100"), task);
        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        instance.properties().put("pItemList", itemList);

        Datum result = ScriptInstanceMethodDispatcher.dispatch(
                null, instance, "getProp", List.of(Datum.symbol("pItemList"), Datum.of(1)));

        assertTrue(result instanceof Datum.PropList);
        assertEquals("Timeout uid:1:100", ((Datum.PropList) result).get(Datum.symbol("uniqueid")).toStr());
    }

    @Test
    void nestedPropSetCreatesNumericPropertyKeyWhenIndexIsOutOfRange() {
        Datum.PropList props = new Datum.PropList();
        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        instance.properties().put("pObjects", props);

        ScriptInstanceMethodDispatcher.dispatch(
                null,
                instance,
                "setProp",
                List.of(Datum.symbol("pObjects"), Datum.of(2147418112), Datum.of("chair")));

        assertEquals(1, props.size());
        assertEquals("chair", props.getAProp(Datum.of(2147418112)).toStr());
    }

    @Test
    void nestedPropSetUpdatesAncestorOwnedContainer() {
        Datum.PropList objectList = new Datum.PropList();
        Datum.ScriptInstance ancestor = new Datum.ScriptInstance(78, new LinkedHashMap<>());
        ancestor.properties().put("pObjectList", objectList);

        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        instance.properties().put(Datum.PROP_ANCESTOR, ancestor);

        Datum.ScriptInstance roomInterface = new Datum.ScriptInstance(79, new LinkedHashMap<>());
        ScriptInstanceMethodDispatcher.dispatch(
                null,
                instance,
                "setProp",
                List.of(Datum.symbol("pObjectList"), Datum.symbol("room_interface"), roomInterface));

        assertEquals(roomInterface, objectList.get(Datum.symbol("room_interface")));
        assertFalse(instance.properties().containsKey("pObjectList"));
    }

    @Test
    void setAtWritesRegularScriptInstanceProperty() {
        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, new LinkedHashMap<>());

        Datum result = ScriptInstanceMethodDispatcher.dispatch(
                null,
                instance,
                "setAt",
                List.of(Datum.symbol("pFacadeId"), Datum.symbol("snowwar_loungesystem")));

        assertTrue(result.isVoid());
        assertTrue(instance.properties().get("pFacadeId") instanceof Datum.Symbol);
        assertEquals("snowwar_loungesystem", ((Datum.Symbol) instance.properties().get("pFacadeId")).name());
    }

    @Test
    void setAtUpdatesAncestorOwnedProperty() {
        LinkedHashMap<String, Datum> ancestorProps = new LinkedHashMap<>();
        ancestorProps.put("pFacadeId", Datum.symbol("old"));
        Datum.ScriptInstance ancestor = new Datum.ScriptInstance(78, ancestorProps);

        LinkedHashMap<String, Datum> childProps = new LinkedHashMap<>();
        childProps.put("ancestor", ancestor);
        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, childProps);

        ScriptInstanceMethodDispatcher.dispatch(
                null,
                instance,
                "setAt",
                List.of(Datum.symbol("pFacadeId"), Datum.symbol("snowwar_loungesystem")));

        assertTrue(ancestor.properties().get("pFacadeId") instanceof Datum.Symbol);
        assertEquals("snowwar_loungesystem", ((Datum.Symbol) ancestor.properties().get("pFacadeId")).name());
        assertFalse(instance.properties().containsKey("pFacadeId"));
    }

    @Test
    void countWithoutArgsCountsScriptInstanceProperties() {
        LinkedHashMap<String, Datum> props = new LinkedHashMap<>();
        props.put("a", Datum.of(1));
        props.put("b", Datum.of(2));
        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, props);

        Datum result = ScriptInstanceMethodDispatcher.dispatch(
                null,
                instance,
                "count",
                List.of());

        assertEquals(2, result.toInt());
    }

    @Test
    void countWithPropertyArgCountsResolvedPropertyValue() {
        LinkedHashMap<String, Datum> props = new LinkedHashMap<>();
        props.put("pData", Datum.list(Datum.of(1), Datum.of(2), Datum.of(3)));
        props.put("pName", Datum.of("r31"));
        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, props);

        Datum listCount = ScriptInstanceMethodDispatcher.dispatch(
                null,
                instance,
                "count",
                List.of(Datum.symbol("pData")));
        Datum stringCount = ScriptInstanceMethodDispatcher.dispatch(
                null,
                instance,
                "count",
                List.of(Datum.symbol("pName")));

        assertEquals(3, listCount.toInt());
        assertEquals(3, stringCount.toInt());
    }

    @Test
    void countWithPropertyArgWalksAncestorChain() {
        LinkedHashMap<String, Datum> ancestorProps = new LinkedHashMap<>();
        ancestorProps.put("pData", Datum.list(Datum.of(5), Datum.of(6)));
        Datum.ScriptInstance ancestor = new Datum.ScriptInstance(78, ancestorProps);

        LinkedHashMap<String, Datum> childProps = new LinkedHashMap<>();
        childProps.put("ancestor", ancestor);
        Datum.ScriptInstance child = new Datum.ScriptInstance(77, childProps);

        Datum result = ScriptInstanceMethodDispatcher.dispatch(
                null,
                child,
                "count",
                List.of(Datum.symbol("pData")));

        assertEquals(2, result.toInt());
    }

    @Test
    void prependedReceiverRemainsParamZeroWhenNamesAreUnavailableButArgCountIncludesMe() {
        ScriptChunk.Handler handler = createHandlerWithArgCount(4);
        ScriptChunk script = createTestScript(handler);
        Datum.ScriptInstance receiver = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        Datum object = new Datum.ScriptInstance(78, new LinkedHashMap<>());
        Datum message = Datum.of("Variable not found");
        Datum method = Datum.symbol("get");
        Scope scope = new Scope(script, handler, List.of(receiver, object, message, method), receiver);

        assertTrue(scope.getParam(0) == receiver);
        assertTrue(scope.getParam(1) == object);
        assertEquals("Variable not found", scope.getParam(2).toStr());
        assertEquals("get", scope.getParam(3).toKeyName());
    }

    @Test
    void prependedReceiverIsSkippedWhenNamesAreUnavailableAndArgCountExcludesMe() {
        ScriptChunk.Handler handler = createHandlerWithArgCount(3);
        ScriptChunk script = createTestScript(handler);
        Datum.ScriptInstance receiver = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        Datum object = new Datum.ScriptInstance(78, new LinkedHashMap<>());
        Datum message = Datum.of("Variable not found");
        Datum method = Datum.symbol("get");
        Scope scope = new Scope(script, handler, List.of(receiver, object, message, method), receiver);

        assertTrue(scope.getParam(0) == object);
        assertEquals("Variable not found", scope.getParam(1).toStr());
        assertEquals("get", scope.getParam(2).toKeyName());
    }

    @SuppressWarnings("unchecked")
    private static void pushActiveScope(LingoVM vm) throws Exception {
        Field callStackField = LingoVM.class.getDeclaredField("callStack");
        callStackField.setAccessible(true);
        ArrayDeque<Scope> callStack = (ArrayDeque<Scope>) callStackField.get(vm);
        ScriptChunk.Handler handler = createTestHandler();
        ScriptChunk script = createTestScript(handler);
        callStack.push(new Scope(script, handler, List.of(), Datum.VOID));
    }

    private static ScriptChunk.Handler createTestHandler() {
        return new ScriptChunk.Handler(
                1,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(new ScriptChunk.Handler.Instruction(0, Opcode.RET, 0, 0)),
                Map.of(0, 0));
    }

    private static ScriptChunk.Handler createHandlerWithArgCount(int argCount) {
        List<Integer> argNameIds = new java.util.ArrayList<>();
        for (int i = 0; i < argCount; i++) {
            argNameIds.add(100 + i);
        }
        return new ScriptChunk.Handler(
                1,
                0,
                0,
                0,
                argCount,
                0,
                0,
                0,
                argNameIds,
                List.of(),
                List.of(new ScriptChunk.Handler.Instruction(0, Opcode.RET, 0, 0)),
                Map.of(0, 0));
    }

    private static ScriptChunk createTestScript(ScriptChunk.Handler handler) {
        return new ScriptChunk(
                null,
                new ChunkId(1),
                ScriptChunk.ScriptType.PARENT,
                0,
                List.of(handler),
                List.of(),
                List.of(),
                List.of(),
                new byte[0]);
    }

    @SuppressWarnings("unchecked")
    private static int getDequeSize(LingoVM vm, String fieldName) throws Exception {
        Field field = LingoVM.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return ((Deque<Object>) field.get(vm)).size();
    }

    private static void setBooleanField(LingoVM vm, String fieldName, boolean value) throws Exception {
        Field field = LingoVM.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setBoolean(vm, value);
    }

    @SuppressWarnings("unchecked")
    private static void setCurrentVm(LingoVM vm) throws Exception {
        Field field = LingoVM.class.getDeclaredField("currentVm");
        field.setAccessible(true);
        field.set(null, vm);
    }

    @SuppressWarnings("unchecked")
    private static void clearCurrentVm() throws Exception {
        Field field = LingoVM.class.getDeclaredField("currentVm");
        field.setAccessible(true);
        field.set(null, null);
    }

    private static final class ScriptHandlerProvider extends NoOpCastLibProvider {
        private final ScriptChunk script;
        private final ScriptChunk.Handler handler;
        private final boolean exposeStableRegistryMembers;
        private final boolean exposeBootstrapScriptMembers;

        private ScriptHandlerProvider(
                ScriptChunk script,
                ScriptChunk.Handler handler,
                boolean exposeStableRegistryMembers) {
            this(script, handler, exposeStableRegistryMembers, false);
        }

        private ScriptHandlerProvider(
                ScriptChunk script,
                ScriptChunk.Handler handler,
                boolean exposeStableRegistryMembers,
                boolean exposeBootstrapScriptMembers) {
            this.script = script;
            this.handler = handler;
            this.exposeStableRegistryMembers = exposeStableRegistryMembers;
            this.exposeBootstrapScriptMembers = exposeBootstrapScriptMembers;
        }

        @Override
        public Datum getMember(int castLibNumber, int memberNumber) {
            return Datum.VOID;
        }

        @Override
        public Datum getMemberByName(int castLibNumber, String memberName) {
            return Datum.CastMemberRef.of(2, 74);
        }

        @Override
        public Datum getRegistryMemberByName(int castLibNumber, String memberName) {
            if (!exposeStableRegistryMembers) {
                return Datum.VOID;
            }
            return Datum.CastMemberRef.of(2, 74);
        }

        @Override
        public Datum getMemberProp(int castLibNumber, int memberNumber, String propName) {
            if ("type".equalsIgnoreCase(propName) && exposeBootstrapScriptMembers) {
                return Datum.symbol("script");
            }
            return Datum.VOID;
        }

        @Override
        public HandlerLocation findHandlerInScript(int scriptId, String handlerName) {
            if (scriptId == 77 && "getmemnum".equalsIgnoreCase(handlerName)) {
                return new HandlerLocation(1, script, handler, null);
            }
            return null;
        }
    }
}
