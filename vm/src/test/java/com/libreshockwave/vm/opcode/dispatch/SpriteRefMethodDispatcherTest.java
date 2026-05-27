package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.builtin.sprite.SpritePropertyProvider;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Execution(ExecutionMode.SAME_THREAD)
class SpriteRefMethodDispatcherTest {

    @AfterEach
    void clearProvider() {
        SpritePropertyProvider.clearProvider();
    }

    @Test
    void setMemberUsesSpriteMemberMethodWithoutCreatingBrokerState() {
        FakeSpriteProvider provider = new FakeSpriteProvider();
        SpritePropertyProvider.setProvider(provider);

        Datum result = SpriteRefMethodDispatcher.dispatch(
                7,
                "setMember",
                List.of(Datum.CastMemberRef.of(3, 11)));

        assertEquals(Datum.TRUE, result);
        assertEquals(Datum.CastMemberRef.of(3, 11), provider.members.get(7));
        assertTrue(provider.props.isEmpty());
    }

    @Test
    void getMemberReadsSpriteMemberProperty() {
        FakeSpriteProvider provider = new FakeSpriteProvider();
        provider.props.put("7:member", Datum.CastMemberRef.of(2, 5));
        SpritePropertyProvider.setProvider(provider);

        Datum result = SpriteRefMethodDispatcher.dispatch(7, "getMember", List.of());

        assertEquals(Datum.CastMemberRef.of(2, 5), result);
    }

    @Test
    void setCursorAndGetCursorUseSpriteProperties() {
        FakeSpriteProvider provider = new FakeSpriteProvider();
        SpritePropertyProvider.setProvider(provider);

        assertEquals(Datum.TRUE, SpriteRefMethodDispatcher.dispatch(7, "setCursor", List.of(Datum.of(280))));
        assertEquals(Datum.of(280), SpriteRefMethodDispatcher.dispatch(7, "getCursor", List.of()));
    }

    @Test
    void eventBrokerMethodsAreNotSynthesizedBySpriteRefs() {
        FakeSpriteProvider provider = new FakeSpriteProvider();
        SpritePropertyProvider.setProvider(provider);

        Datum result = SpriteRefMethodDispatcher.dispatch(
                7,
                "registerProcedure",
                List.of(Datum.symbol("mouseDown"), Datum.symbol("client"), Datum.symbol("mouseDown")));

        assertEquals(Datum.VOID, result);
        assertTrue(provider.props.isEmpty());
        assertTrue(provider.members.isEmpty());
    }

    private static final class FakeSpriteProvider implements SpritePropertyProvider {
        final Map<String, Datum> props = new LinkedHashMap<>();
        final Map<Integer, Datum> members = new LinkedHashMap<>();

        @Override
        public Datum getSpriteProp(int spriteNum, String propName) {
            return props.getOrDefault(spriteNum + ":" + propName.toLowerCase(), Datum.VOID);
        }

        @Override
        public boolean setSpriteProp(int spriteNum, String propName, Datum value) {
            props.put(spriteNum + ":" + propName.toLowerCase(), value);
            return true;
        }

        @Override
        public boolean setSpriteMember(int spriteNum, Datum value) {
            members.put(spriteNum, value);
            return true;
        }
    }
}
