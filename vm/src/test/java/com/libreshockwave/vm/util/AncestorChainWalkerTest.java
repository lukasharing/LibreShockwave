package com.libreshockwave.vm.util;

import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class AncestorChainWalkerTest {

    @Test
    void ancestorOnlyAcceptsScriptInstances() {
        Datum.ScriptInstance ancestor = new Datum.ScriptInstance(78, new LinkedHashMap<>());
        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        instance.properties().put(Datum.PROP_ANCESTOR, ancestor);

        AncestorChainWalker.setProperty(instance, "ancestor", Datum.VOID);
        assertSame(ancestor, instance.properties().get(Datum.PROP_ANCESTOR));

        AncestorChainWalker.setProperty(instance, "ancestor", Datum.of("not an ancestor"));
        assertSame(ancestor, instance.properties().get(Datum.PROP_ANCESTOR));
    }

    @Test
    void ancestorAssignmentIsCaseInsensitive() {
        Datum.ScriptInstance oldAncestor = new Datum.ScriptInstance(78, new LinkedHashMap<>());
        Datum.ScriptInstance newAncestor = new Datum.ScriptInstance(79, new LinkedHashMap<>());
        Datum.ScriptInstance instance = new Datum.ScriptInstance(77, new LinkedHashMap<>());
        instance.properties().put(Datum.PROP_ANCESTOR, oldAncestor);

        AncestorChainWalker.setProperty(instance, "Ancestor", newAncestor);

        assertSame(newAncestor, instance.properties().get(Datum.PROP_ANCESTOR));
    }

    @Test
    void scriptOwnerSelectsMatchingAncestorForParentScriptPropertyScope() {
        Datum.ScriptInstance base = new Datum.ScriptInstance(100, new LinkedHashMap<>());
        Datum.ScriptInstance active = new Datum.ScriptInstance(200, new LinkedHashMap<>());
        Datum.ScriptInstance queue = new Datum.ScriptInstance(300, new LinkedHashMap<>());
        queue.properties().put(Datum.PROP_ANCESTOR, active);
        active.properties().put(Datum.PROP_ANCESTOR, base);
        queue.properties().put("pAnimFrame", Datum.VOID);
        active.properties().put("pAnimFrame", Datum.of(0));

        Datum.ScriptInstance activeRoot = AncestorChainWalker.findScriptOwner(queue, script(200));
        Datum.ScriptInstance queueRoot = AncestorChainWalker.findScriptOwner(queue, script(300));

        assertSame(active, activeRoot);
        assertSame(queue, queueRoot);
        assertEquals(0, AncestorChainWalker.getProperty(activeRoot, "pAnimFrame").toInt());
        assertSame(Datum.VOID, AncestorChainWalker.getProperty(queueRoot, "pAnimFrame"));
        assertNull(AncestorChainWalker.findScriptOwner(queue, script(400)));
    }

    private static ScriptChunk script(int id) {
        return new ScriptChunk(
                null,
                new ChunkId(id),
                ScriptChunk.ScriptType.PARENT,
                0,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                new byte[0]);
    }
}
