package com.libreshockwave.vm.builtin.movie;

import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * Movie/input state builtins exposed as function calls in Lingo.
 */
public final class MovieBuiltins {

    private MovieBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("altdown", property("altDown"));
        builtins.put("clickloc", property("clickLoc"));
        builtins.put("clickon", property("clickOn"));
        builtins.put("commanddown", property("commandDown"));
        builtins.put("controldown", property("controlDown"));
        builtins.put("doubleclick", MovieBuiltins::doubleClick);
        builtins.put("frame", property("frame"));
        builtins.put("key", property("key"));
        builtins.put("keycode", property("keyCode"));
        builtins.put("keypressed", property("keyPressed"));
        builtins.put("mousedown", property("mouseDown"));
        builtins.put("mouseh", property("mouseH"));
        builtins.put("mouseloc", property("mouseLoc"));
        builtins.put("mouseup", property("mouseUp"));
        builtins.put("mousev", property("mouseV"));
        builtins.put("optiondown", property("optionDown"));
        builtins.put("rightmousedown", property("rightMouseDown"));
        builtins.put("rollover", MovieBuiltins::rollover);
        builtins.put("shiftdown", property("shiftDown"));
        builtins.put("getmonotonicmillis", MovieBuiltins::getMonotonicMillis);
        builtins.put("systemdate", MovieBuiltins::systemDate);
    }

    private static BiFunction<LingoVM, List<Datum>, Datum> property(String propName) {
        return (vm, args) -> movieProperty(propName);
    }

    private static Datum movieProperty(String propName) {
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        if (provider == null) {
            return Datum.VOID;
        }
        Datum value = provider.getMovieProp(propName);
        return value == null ? Datum.VOID : value;
    }

    private static Datum doubleClick(LingoVM vm, List<Datum> args) {
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        if (provider == null) {
            return Datum.FALSE;
        }
        Datum value = provider.getMovieProp("doubleClick");
        return value.isVoid() ? Datum.FALSE : value;
    }

    private static Datum rollover(LingoVM vm, List<Datum> args) {
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        if (provider == null) {
            return args.isEmpty() ? Datum.ZERO : Datum.FALSE;
        }
        Datum value = provider.getMovieProp("rollover");
        int rolloverChannel = value == null || value.isVoid() ? 0 : value.toInt();
        if (args.isEmpty()) {
            return Datum.of(rolloverChannel);
        }
        return Datum.of(rolloverChannel == spriteChannel(args.get(0)) ? 1 : 0);
    }

    private static int spriteChannel(Datum value) {
        if (value instanceof Datum.SpriteRef spriteRef) {
            return spriteRef.channelNum();
        }
        return value == null || value.isVoid() ? 0 : value.toInt();
    }

    private static Datum systemDate(LingoVM vm, List<Datum> args) {
        LocalDate now = LocalDate.now();
        Datum.PropList date = new Datum.PropList();
        date.add("day", Datum.of(now.getDayOfMonth()), true);
        date.add("month", Datum.of(now.getMonthValue()), true);
        date.add("year", Datum.of(now.getYear()), true);
        return date;
    }

    private static Datum getMonotonicMillis(LingoVM vm, List<Datum> args) {
        MoviePropertyProvider provider = MoviePropertyProvider.getProvider();
        if (provider == null) {
            return Datum.of((int) (System.currentTimeMillis() & 0x7fffffff));
        }
        Datum value = provider.getMovieProp("milliseconds");
        if (value == null || value.isVoid()) {
            return Datum.of((int) (System.currentTimeMillis() & 0x7fffffff));
        }
        return value;
    }
}
