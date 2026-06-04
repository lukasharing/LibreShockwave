package com.libreshockwave.vm.builtin.string;

import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.util.LingoValueParser;

import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

/**
 * String-related builtin functions.
 */
public final class StringBuiltins {

    private StringBuiltins() {}

    public static void register(Map<String, BiFunction<LingoVM, List<Datum>, Datum>> builtins) {
        builtins.put("string", StringBuiltins::string);
        builtins.put("length", StringBuiltins::length);
        builtins.put("chars", StringBuiltins::chars);
        builtins.put("chartonum", StringBuiltins::charToNum);
        builtins.put("numtochar", StringBuiltins::numToChar);
        builtins.put("offset", StringBuiltins::offset);
        builtins.put("converttoproplist", StringBuiltins::convertToPropList);
        builtins.put("getstringvariable", StringBuiltins::getStringVariable);
        builtins.put("getpref", StringBuiltins::getPref);
        builtins.put("setpref", StringBuiltins::setPref);
    }

    private static Datum string(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.EMPTY_STRING;
        return Datum.of(args.get(0).toStr());
    }

    private static Datum length(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        Datum a = args.get(0);
        if (a instanceof Datum.Str s) {
            return Datum.of(s.value().length());
        } else if (a instanceof Datum.List l) {
            return Datum.of(l.items().size());
        } else if (a instanceof Datum.PropList p) {
            return Datum.of(p.size());
        }
        // For Symbol and other types, convert to string and return string length
        // (Lingo's length() is a string function that coerces its argument)
        return Datum.of(a.toStr().length());
    }

    private static Datum chars(LingoVM vm, List<Datum> args) {
        if (args.size() < 3) return Datum.EMPTY_STRING;
        String str = args.get(0).toStr();
        int start = args.get(1).toInt() - 1; // Lingo is 1-indexed
        int end = args.get(2).toInt();
        if (start < 0) start = 0;
        if (end > str.length()) end = str.length();
        if (start >= end) return Datum.EMPTY_STRING;
        return Datum.of(str.substring(start, end));
    }

    private static Datum charToNum(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.ZERO;
        String s = args.get(0).toStr();
        if (s.isEmpty()) return Datum.ZERO;
        return Datum.of((int) s.charAt(0));
    }

    private static Datum numToChar(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.EMPTY_STRING;
        int code = args.get(0).toInt();
        return Datum.of(String.valueOf((char) code));
    }

    /**
     * offset(substring, string)
     * Returns the 1-indexed position of substring in string, or 0 if not found.
     * Case-insensitive search using regionMatches to avoid toLowerCase() allocations.
     */
    private static Datum offset(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.ZERO;
        String substr = args.get(0).toStr();
        String str = args.get(1).toStr();
        if (substr.isEmpty()) return Datum.ZERO;

        // Case-insensitive search without creating temporary lowercase strings
        int sLen = substr.length();
        int limit = str.length() - sLen;
        for (int i = 0; i <= limit; i++) {
            if (str.regionMatches(true, i, substr, 0, sLen)) {
                return Datum.of(i + 1); // 1-indexed
            }
        }
        return Datum.ZERO;
    }

    private static Datum convertToPropList(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return new Datum.PropList();

        String source = args.get(0).toStr();
        String delimiter = ",";
        if (args.size() > 1 && !args.get(1).isVoid()) {
            delimiter = args.get(1).toStr();
            if (delimiter.isEmpty()) {
                delimiter = ",";
            }
        }

        Datum.PropList props = new Datum.PropList();
        int start = 0;
        while (start <= source.length()) {
            int end = findDelimiter(source, delimiter, start);
            if (end < 0) {
                end = source.length();
            }

            addPropertyItem(props, source.substring(start, end), vm);

            if (end == source.length()) {
                break;
            }
            start = end + delimiterWidth(source, delimiter, end);
        }
        return props;
    }

    private static int findDelimiter(String source, String delimiter, int start) {
        if ("\r".equals(delimiter)) {
            for (int i = start; i < source.length(); i++) {
                char ch = source.charAt(i);
                if (ch == '\r' || ch == '\n') {
                    return i;
                }
            }
            return -1;
        }
        return source.indexOf(delimiter, start);
    }

    private static int delimiterWidth(String source, String delimiter, int index) {
        if ("\r".equals(delimiter)
                && source.charAt(index) == '\r'
                && index + 1 < source.length()
                && source.charAt(index + 1) == '\n') {
            return 2;
        }
        return delimiter.length();
    }

    private static void addPropertyItem(Datum.PropList props, String rawItem, LingoVM vm) {
        String item = rawItem.trim();
        if (item.isEmpty()) {
            return;
        }

        int equals = item.indexOf('=');
        if (equals <= 0) {
            return;
        }

        String key = item.substring(0, equals).trim();
        String value = item.substring(equals + 1).trim();
        if (!key.isEmpty()) {
            props.putTyped(key, false, parsePropertyValue(value, vm));
        }
    }

    private static Datum parsePropertyValue(String value, LingoVM vm) {
        if (value == null || value.isEmpty()) {
            return Datum.EMPTY_STRING;
        }
        String trimmed = value.trim();
        if (shouldParseStructuredPropertyValue(trimmed)) {
            Datum parsed = LingoValueParser.parseWithPartial(trimmed, vm);
            if (parsed != null && !parsed.isVoid()) {
                return parsed;
            }
            if ("VOID".equalsIgnoreCase(trimmed)) {
                return Datum.VOID;
            }
            if ("EMPTY".equalsIgnoreCase(trimmed)) {
                return Datum.EMPTY_STRING;
            }
        }
        return Datum.of(value);
    }

    private static boolean shouldParseStructuredPropertyValue(String value) {
        if (value.isEmpty()) {
            return false;
        }
        if ((value.startsWith("[") && value.endsWith("]"))
                || (value.startsWith("\"") && value.endsWith("\""))
                || value.startsWith("#")
                || startsStructuredCall(value, "rgb")
                || startsStructuredCall(value, "color")
                || startsStructuredCall(value, "rect")
                || startsStructuredCall(value, "point")) {
            return true;
        }
        if ("TRUE".equalsIgnoreCase(value)
                || "FALSE".equalsIgnoreCase(value)
                || "VOID".equalsIgnoreCase(value)
                || "EMPTY".equalsIgnoreCase(value)) {
            return true;
        }
        return isPlainNumber(value);
    }

    private static boolean startsStructuredCall(String value, String name) {
        return value.regionMatches(true, 0, name + "(", 0, name.length() + 1)
                && value.endsWith(")");
    }

    private static boolean isPlainNumber(String value) {
        int start = 0;
        boolean seenDigit = false;
        boolean seenDot = false;
        if (value.startsWith("-") || value.startsWith("+")) {
            start = 1;
        }
        if (start >= value.length()) {
            return false;
        }
        for (int i = start; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (ch >= '0' && ch <= '9') {
                seenDigit = true;
                continue;
            }
            if (ch == '.' && !seenDot) {
                seenDot = true;
                continue;
            }
            return false;
        }
        return seenDigit;
    }

    private static Datum getStringVariable(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) {
            return Datum.EMPTY_STRING;
        }

        Datum value = vm.callHandler("getVariable", args);
        if (value.isVoid()) {
            return Datum.EMPTY_STRING;
        }

        String text = value.toStr();
        if (text.length() >= 2 && text.charAt(0) == '"' && text.charAt(text.length() - 1) == '"') {
            return Datum.of(text.substring(1, text.length() - 1));
        }
        return Datum.of(text);
    }

    private static Datum getPref(LingoVM vm, List<Datum> args) {
        if (args.isEmpty()) return Datum.VOID;
        String key = args.get(0).toStr();
        if (key.isEmpty()) return Datum.VOID;
        return vm.getPref(key);
    }

    private static Datum setPref(LingoVM vm, List<Datum> args) {
        if (args.size() < 2) return Datum.VOID;
        String key = args.get(0).toStr();
        if (key.isEmpty()) return Datum.VOID;
        return vm.setPref(key, args.get(1));
    }
}
