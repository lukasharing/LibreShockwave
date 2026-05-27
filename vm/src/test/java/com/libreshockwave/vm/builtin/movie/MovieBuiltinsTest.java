package com.libreshockwave.vm.builtin.movie;

import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MovieBuiltinsTest {

    @Test
    void classicInputFunctionsReadMovieProperties() {
        LingoVM vm = new LingoVM(null);
        RecordingMovieProvider provider = new RecordingMovieProvider();
        provider.properties.put("mouseH", Datum.of(42));
        provider.properties.put("mouseV", Datum.of(84));
        provider.properties.put("mouseLoc", new Datum.Point(42, 84));
        provider.properties.put("clickOn", Datum.of(12));
        provider.properties.put("clickLoc", new Datum.Point(40, 80));
        provider.properties.put("keyCode", Datum.of(13));
        provider.properties.put("shiftDown", Datum.TRUE);
        provider.properties.put("optionDown", Datum.FALSE);
        provider.properties.put("controlDown", Datum.TRUE);

        MoviePropertyProvider.setProvider(provider);
        try {
            assertEquals(42, vm.callHandler("mouseH", List.of()).toInt());
            assertEquals(84, vm.callHandler("mouseV", List.of()).toInt());
            assertEquals(new Datum.Point(42, 84), vm.callHandler("mouseLoc", List.of()));
            assertEquals(12, vm.callHandler("clickOn", List.of()).toInt());
            assertEquals(new Datum.Point(40, 80), vm.callHandler("clickLoc", List.of()));
            assertEquals(13, vm.callHandler("keyCode", List.of()).toInt());
            assertEquals(1, vm.callHandler("shiftDown", List.of()).toInt());
            assertEquals(0, vm.callHandler("optionDown", List.of()).toInt());
            assertEquals(1, vm.callHandler("controlDown", List.of()).toInt());
        } finally {
            MoviePropertyProvider.clearProvider();
        }
    }

    @Test
    void rolloverReturnsCurrentChannelOrTestsSpriteArgument() {
        LingoVM vm = new LingoVM(null);
        RecordingMovieProvider provider = new RecordingMovieProvider();
        provider.properties.put("rollover", Datum.of(7));

        MoviePropertyProvider.setProvider(provider);
        try {
            assertEquals(7, vm.callHandler("rollover", List.of()).toInt());
            assertEquals(1, vm.callHandler("rollover", List.of(Datum.of(7))).toInt());
            assertEquals(1, vm.callHandler("rollover", List.of(Datum.SpriteRef.of(7))).toInt());
            assertEquals(0, vm.callHandler("rollover", List.of(Datum.of(8))).toInt());
        } finally {
            MoviePropertyProvider.clearProvider();
        }
    }

    private static final class RecordingMovieProvider implements MoviePropertyProvider {
        private final Map<String, Datum> properties = new HashMap<>();

        @Override
        public Datum getMovieProp(String propName) {
            for (Map.Entry<String, Datum> entry : properties.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(propName)) {
                    return entry.getValue();
                }
            }
            return Datum.VOID;
        }

        @Override
        public boolean setMovieProp(String propName, Datum value) {
            properties.put(propName, value);
            return true;
        }
    }
}
