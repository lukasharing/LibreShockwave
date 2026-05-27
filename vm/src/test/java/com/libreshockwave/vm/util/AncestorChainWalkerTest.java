package com.libreshockwave.vm.util;

import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;

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
}
