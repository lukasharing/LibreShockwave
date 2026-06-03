package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.datum.LingoException;
import com.libreshockwave.vm.util.LingoValueParser;

import java.util.List;

/**
 * Handles method calls on property lists.
 * Uses equalsIgnoreCase to avoid toLowerCase String allocation on the hot count path.
 */
public final class PropListMethodDispatcher {

    private PropListMethodDispatcher() {}

    public static Datum dispatch(Datum.PropList propList, String methodName, List<Datum> args) {
        // Fast path for most common operations (no allocation)
        if ("count".equalsIgnoreCase(methodName)) {
            // count(propList) -> number of entries
            // count(propList, #prop) -> count of the sub-property value (list.prop.count)
            if (!args.isEmpty()) {
                Datum sub = propList.getOrDefault(args.get(0).toKeyName(), Datum.VOID);
                if (sub instanceof Datum.List subList) return Datum.of(subList.items().size());
                if (sub instanceof Datum.PropList subProp) return Datum.of(subProp.size());
                return Datum.ZERO;
            }
            return Datum.of(propList.size());
        }

        return switch (methodName.toLowerCase()) {
            case "getprop", "getpropref", "getaprop", "getproperty" -> {
                if (args.isEmpty()) yield Datum.VOID;
                Datum value = propList.getAPropOrDefault(args.get(0), Datum.VOID);
                // getProp(propList, #prop, index) -> propList.prop[index]
                if (args.size() >= 2 && value instanceof Datum.List subList) {
                    int index = args.get(1).toInt() - 1; // 1-indexed
                    if (index >= 0 && index < subList.items().size()) {
                        yield subList.items().get(index);
                    }
                    yield Datum.VOID;
                }
                yield value;
            }
            case "setaprop" -> {
                if (args.size() < 2) yield Datum.VOID;
                Datum keyDatum = args.get(0);
                propList.put(keyDatum, args.get(1));
                yield Datum.VOID;
            }
            case "setprop" -> {
                if (args.size() < 2) yield Datum.VOID;
                Datum keyDatum = args.get(0);
                if (!propList.putExisting(keyDatum, args.get(1))) {
                    throw new LingoException("Property not found: " + keyDatum.toKeyName());
                }
                yield Datum.VOID;
            }
            case "addprop" -> {
                if (args.size() < 2) yield Datum.VOID;
                Datum keyDatum = args.get(0);
                // addProp always appends -> allows duplicate keys
                propList.add(keyDatum, args.get(1));
                yield Datum.VOID;
            }
            case "getat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                yield propList.getAtOrDefault(args.get(0), Datum.VOID);
            }
            case "getvalue" -> {
                if (args.isEmpty()) yield Datum.VOID;
                Datum value = propList.getValueByKeyOrIndexOrDefault(args.get(0), Datum.VOID);
                if (value == null || value.isVoid()) {
                    yield Datum.VOID;
                }
                yield evaluateStoredValue(value);
            }
            case "setat" -> {
                if (args.size() < 2) yield Datum.VOID;
                Datum keyOrIndex = args.get(0);
                Datum value = args.get(1);
                if (keyOrIndex instanceof Datum.Int || keyOrIndex instanceof Datum.Float) {
                    int index = keyOrIndex.toInt() - 1;
                    if (index >= 0 && index < propList.size()) {
                        propList.setValue(index, value);
                        yield Datum.VOID;
                    } else {
                        throw new LingoException("setAt index out of range: " + keyOrIndex.toInt());
                    }
                }
                // Bytecode for property-list bracket assignment is emitted as an
                // object-method setAt call: set pList[#key] to value. Keep the
                // global setAt(propList, key, value) strict in ListBuiltins; this
                // method path must preserve Director's bracket-assignment behavior.
                propList.putTyped(keyOrIndex, value);
                yield Datum.VOID;
            }
            case "getone" -> {
                // getOne(propList, value) - find the property NAME where the value matches
                // Returns the original key token or 0 if not found.
                if (args.isEmpty()) yield Datum.ZERO;
                Datum searchValue = args.get(0);
                for (Datum.PropEntry entry : propList.entries()) {
                    if (entry.value().lingoEquals(searchValue)) {
                        yield entry.keyDatum().deepCopy();
                    }
                }
                yield Datum.ZERO;
            }
            case "deleteprop" -> {
                if (args.isEmpty()) yield Datum.VOID;
                Datum keyDatum = args.get(0);
                removeTypedKey(propList, keyDatum);
                yield Datum.VOID;
            }
            case "findpos" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int pos = propList.findPos(args.get(0));
                yield pos > 0 ? Datum.of(pos) : Datum.VOID;
            }
            case "getpropat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt() - 1;
                if (index >= 0 && index < propList.size()) {
                    yield propList.getKeyDatum(index);
                }
                yield Datum.VOID;
            }
            case "deleteat" -> {
                if (args.isEmpty()) yield Datum.VOID;
                int index = args.get(0).toInt() - 1;
                if (index >= 0 && index < propList.size()) {
                    propList.removeAt(index);
                }
                yield Datum.VOID;
            }
            case "getlast" -> propList.isEmpty() ? Datum.VOID : propList.getValue(propList.size() - 1);
            case "getfirst" -> propList.isEmpty() ? Datum.VOID : propList.getValue(0);
            case "duplicate" ->
                    // Deep copy: Director's duplicate() creates independent copies of nested structures.
                    propList.deepCopy();
            default -> Datum.VOID;
        };
    }

    private static void removeTypedKey(Datum.PropList propList, Datum keyDatum) {
        if (keyDatum instanceof Datum.Symbol symbol) {
            propList.remove(symbol.name(), true);
        } else if (keyDatum instanceof Datum.Str string) {
            propList.remove(string.value(), false);
        } else {
            propList.remove(keyDatum);
        }
    }

    private static Datum evaluateStoredValue(Datum value) {
        if (value instanceof Datum.FieldText fieldText) {
            return LingoValueParser.parseWithPartial(fieldText.value(), LingoVM.getCurrentVM());
        }
        if (value.isString()) {
            Datum parsed = LingoValueParser.parseWithPartial(value.toStr(), LingoVM.getCurrentVM());
            return parsed != null && !parsed.isVoid() ? parsed : value;
        }
        return value;
    }
}
