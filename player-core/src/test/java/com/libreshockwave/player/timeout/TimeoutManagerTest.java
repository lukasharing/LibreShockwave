package com.libreshockwave.player.timeout;

import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeoutManagerTest {

    @Test
    void regularTimeoutProcessingDoesNotFireOneMillisecondTimeoutImmediately() {
        TimeoutManager manager = new TimeoutManager();
        RecordingVM vm = new RecordingVM();
        long now = System.currentTimeMillis();

        manager.createTimeout("refreshDisplay", 1, "refreshDisplay", Datum.VOID);
        manager.processTimeouts(vm, now);

        assertEquals(0, vm.callCount);
    }

    @Test
    void inputPumpFiresOneMillisecondTimeoutBeforeNextQueuedInputEvent() {
        TimeoutManager manager = new TimeoutManager();
        RecordingVM vm = new RecordingVM();
        long now = System.currentTimeMillis();

        manager.createTimeout("refreshDisplay", 1, "refreshDisplay", Datum.VOID);
        manager.processInputEventTimeouts(vm, now);

        assertEquals(1, vm.callCount);
        assertEquals("refreshDisplay", vm.lastHandlerName);
    }

    private static final class RecordingVM extends LingoVM {
        private int callCount;
        private String lastHandlerName;

        private RecordingVM() {
            super(null);
        }

        @Override
        public Datum callHandler(String handlerName, List<Datum> args) {
            callCount++;
            lastHandlerName = handlerName;
            return Datum.TRUE;
        }
    }
}
