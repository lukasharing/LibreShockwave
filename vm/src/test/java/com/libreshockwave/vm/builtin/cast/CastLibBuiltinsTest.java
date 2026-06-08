package com.libreshockwave.vm.builtin.cast;

import com.libreshockwave.vm.builtin.BuiltinRegistry;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.support.NoOpCastLibProvider;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CastLibBuiltinsTest {

    @Test
    void removeMemberDispatchesToCastProviderSlot() {
        int[] removed = {0, 0};
        CastLibProvider.setProvider(new NoOpCastLibProvider() {
            @Override
            public boolean removeMember(int castLibNumber, int memberNumber) {
                removed[0] = castLibNumber;
                removed[1] = memberNumber;
                return true;
            }
        });
        try {
            Datum result = new BuiltinRegistry().invoke("removeMember", null,
                    List.of(Datum.CastMemberRef.of(7, 42)));

            assertEquals(1, result.toInt());
            assertEquals(7, removed[0]);
            assertEquals(42, removed[1]);
        } finally {
            CastLibProvider.clearProvider();
        }
    }
}
