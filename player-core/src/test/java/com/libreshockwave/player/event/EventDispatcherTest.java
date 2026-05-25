package com.libreshockwave.player.event;

import com.libreshockwave.player.behavior.BehaviorManager;
import com.libreshockwave.player.render.SpriteRegistry;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventDispatcherTest {

    @Test
    void brokerRegisteredKeyDownProcedureIsRecognizedAndDispatched() {
        Datum.ScriptInstance target = new Datum.ScriptInstance(200, new LinkedHashMap<>());
        RecordingVM vm = new RecordingVM(target);
        EventDispatcher dispatcher = new EventDispatcher(null, vm, new BehaviorManager(null));
        dispatcher.setSpriteRegistry(spriteRegistryWithBroker("keyDown"));

        assertTrue(dispatcher.spriteHasHandler(12, "keyDown"));

        dispatcher.dispatchSpriteEvent(12, "keyDown", List.of());

        assertEquals("call", vm.lastHandlerName);
        assertEquals(4, vm.lastArgs.size());
        assertEquals("eventProcBroker", vm.lastArgs.get(0).toKeyName());
        assertSame(target, vm.lastArgs.get(1));
        assertEquals("keyDown", vm.lastArgs.get(2).toKeyName());
        assertEquals("editable_field", vm.lastArgs.get(3).toKeyName());
    }

    private static SpriteRegistry spriteRegistryWithBroker(String eventName) {
        SpriteRegistry registry = new SpriteRegistry();
        SpriteState sprite = new SpriteState(12);
        Datum.ScriptInstance broker = new Datum.ScriptInstance(100, new LinkedHashMap<>());
        broker.properties().put("id", Datum.of("editable_field"));

        Datum.PropList procList = new Datum.PropList();
        procList.put(eventName, true, new Datum.List(List.of(
                Datum.symbol("eventProcBroker"),
                Datum.symbol("controller_object"))));
        broker.properties().put("pProcList", procList);

        sprite.setScriptInstanceList(List.of(broker));
        registry.getAll().put(12, sprite);
        return registry;
    }

    private static final class RecordingVM extends LingoVM {
        private final Datum.ScriptInstance resolvedTarget;
        private String lastHandlerName;
        private List<Datum> lastArgs = List.of();

        private RecordingVM(Datum.ScriptInstance resolvedTarget) {
            super(null);
            this.resolvedTarget = resolvedTarget;
        }

        @Override
        public Datum callHandler(String handlerName, List<Datum> args) {
            if ("getObject".equals(handlerName)) {
                assertEquals(1, args.size());
                assertEquals("controller_object", args.get(0).toKeyName());
                return resolvedTarget;
            }
            if ("call".equals(handlerName)) {
                lastHandlerName = handlerName;
                lastArgs = List.copyOf(args);
                assertInstanceOf(Datum.Symbol.class, args.get(0));
                return Datum.TRUE;
            }
            return Datum.VOID;
        }
    }
}
