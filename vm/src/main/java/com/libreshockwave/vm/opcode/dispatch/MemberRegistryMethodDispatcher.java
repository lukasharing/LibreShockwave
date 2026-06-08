package com.libreshockwave.vm.opcode.dispatch;

import com.libreshockwave.id.SlotId;
import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.util.AncestorChainWalker;

import java.util.IdentityHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

public final class MemberRegistryMethodDispatcher {

    static final DispatchResult NOT_HANDLED = new DispatchResult(false, Datum.VOID);
    private static final String MEMBER_ALIAS_INDEX = "memberalias.index";
    private static final Map<Datum.ScriptInstance, Map<AliasSource, RememberedAliasText>> persistentAliasTextByRegistry =
            new IdentityHashMap<>();

    private record AliasSource(int castLibNumber, String fieldKey) {}

    private record RememberedAliasText(String text, Map<String, Integer> importedAliases) {}

    private record MemberSlot(int castLibNumber, int memberNumber) {}

    private MemberRegistryMethodDispatcher() {}

    static DispatchResult prefill(Datum.ScriptInstance instance, String methodName, List<Datum> args) {
        if (methodName == null || methodName.isEmpty()) {
            return NOT_HANDLED;
        }

        switch (LingoVM.normalizeLookupName(methodName)) {
            case "getmemnum", "exists", "memberexists", "getmember" -> {
                Datum.PropList registry = getRegistry(instance);
                if (registry != null) {
                    resolveRegisteredMemberSlot(instance, registry, args, true);
                }
                return NOT_HANDLED;
            }
            default -> {
                return NOT_HANDLED;
            }
        }
    }

    static DispatchResult dispatch(Datum.ScriptInstance instance, String methodName, List<Datum> args) {
        if (methodName == null || methodName.isEmpty()) {
            return NOT_HANDLED;
        }

        return dispatchNormalized(instance, LingoVM.normalizeLookupName(methodName), args);
    }

    static DispatchResult prefillNormalized(Datum.ScriptInstance instance, String method, List<Datum> args) {
        if (method == null || method.isEmpty()) {
            return NOT_HANDLED;
        }

        switch (method) {
            case "getmemnum", "exists", "memberexists", "getmember" -> {
                Datum.PropList registry = getRegistry(instance);
                if (registry != null) {
                    resolveRegisteredMemberSlot(instance, registry, args, true);
                }
                return NOT_HANDLED;
            }
            default -> {
                return NOT_HANDLED;
            }
        }
    }

    static DispatchResult dispatchNormalized(Datum.ScriptInstance instance, String method, List<Datum> args) {
        if (method == null || method.isEmpty()) {
            return NOT_HANDLED;
        }

        return switch (method) {
            case "getmemnum", "exists", "memberexists", "getmember", "updatemember", "readaliasindexesfromfield" -> {
                Datum.PropList registry = getRegistry(instance);
                if (registry == null) {
                    yield NOT_HANDLED;
                }
                yield switch (method) {
                    case "getmemnum" -> new DispatchResult(true, Datum.of(resolveRegisteredMemberSlot(instance, registry, args, true)));
                    case "exists", "memberexists" -> new DispatchResult(
                            true,
                            Math.abs(resolveRegisteredMemberSlot(instance, registry, args, true)) > 0 ? Datum.TRUE : Datum.FALSE);
                    case "getmember" -> new DispatchResult(true, resolveRegisteredMember(instance, registry, args));
                    case "updatemember" -> new DispatchResult(true, updateRegisteredMember(instance, registry, args));
                    case "readaliasindexesfromfield" -> dispatchReadAliasIndexesFromField(instance, registry, args);
                    default -> NOT_HANDLED;
                };
            }
            default -> NOT_HANDLED;
        };
    }

    private static Datum updateRegisteredMember(
            Datum.ScriptInstance instance,
            Datum.PropList registry,
            List<Datum> args) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return Datum.ZERO;
        }
        int slotValue = resolveRegisteredMemberSlot(instance, registry, args, true);
        MemberSlot slot = memberSlotFromValue(provider, slotValue);
        if (slot == null) {
            return Datum.ZERO;
        }
        return provider.updateMember(slot.castLibNumber(), slot.memberNumber()) ? Datum.TRUE : Datum.ZERO;
    }

    private static MemberSlot memberSlotFromValue(CastLibProvider provider, int slotValue) {
        if (provider == null || slotValue == 0) {
            return null;
        }
        int absValue = Math.abs(slotValue);
        SlotId slotId = new SlotId(absValue);
        if (slotId.castLib() >= 1 && slotId.member() >= 1) {
            return new MemberSlot(slotId.castLib(), slotId.member());
        }
        int castCount = provider.getCastLibCount();
        for (int castLib = 1; castLib <= castCount; castLib++) {
            if (provider.memberExists(castLib, absValue)) {
                return new MemberSlot(castLib, absValue);
            }
        }
        return null;
    }

    private static DispatchResult dispatchReadAliasIndexesFromField(
            Datum.ScriptInstance instance,
            Datum.PropList registry,
            List<Datum> args) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null || args.size() < 2) {
            return new DispatchResult(true, Datum.ZERO);
        }

        Datum fieldArg = args.get(0);
        Object fieldIdentifier = fieldArg instanceof Datum.Int i ? i.value() : fieldArg.toStr();
        int castLibNumber = args.get(1).toInt();
        Datum fieldDatum = provider.getFieldDatum(fieldIdentifier, castLibNumber);
        if (fieldDatum == null || fieldDatum.isVoid()) {
            return new DispatchResult(true, Datum.ZERO);
        }

        AliasSource source = new AliasSource(castLibNumber, aliasFieldKey(fieldArg));
        removeAliasesFromSource(instance, registry, source);
        Map<String, Integer> importedAliases = new java.util.LinkedHashMap<>();
        int imported = applyAliasMappings(registry, fieldDatum.toStr(),
                targetName -> resolveAliasTargetMemberNumber(registry, targetName, castLibNumber),
                importedAliases);
        rememberAliasText(instance, source, fieldDatum.toStr(), importedAliases);
        return new DispatchResult(true, Datum.of(imported));
    }

    private static String aliasFieldKey(Datum fieldArg) {
        if (fieldArg instanceof Datum.Int i) {
            return "member:" + i.value();
        }
        return "name:" + fieldArg.toStr().toLowerCase(Locale.ROOT);
    }

    private static Datum.PropList getRegistry(Datum.ScriptInstance instance) {
        if (instance == null) {
            return null;
        }
        Datum registryDatum = AncestorChainWalker.getProperty(instance, "pAllMemNumList");
        return registryDatum instanceof Datum.PropList registry ? registry : null;
    }

    private static int resolveRegisteredMemberSlot(
            Datum.ScriptInstance instance,
            Datum.PropList registry,
            List<Datum> args,
            boolean allowDefinitionBootstrapLookup) {
        if (registry == null || args == null || args.isEmpty()) {
            return 0;
        }

        Datum memberIdentifier = args.get(0);
        if (memberIdentifier == null || memberIdentifier.isVoid()) {
            return 0;
        }

        if (memberIdentifier.isInt() || memberIdentifier.isFloat()) {
            return memberIdentifier.toInt();
        }

        String memberName = memberIdentifier.toStr();
        if (memberName.isEmpty()) {
            return 0;
        }

        Datum registered = registry.get(memberName);
        if (registered != null && !registered.isVoid()) {
            int registeredSlot = registered.toInt();
            int normalizedSlot = normalizeRegisteredRegistrySlot(memberName, registeredSlot);
            if (normalizedSlot != 0) {
                if (normalizedSlot != registeredSlot) {
                    registry.putTyped(memberName, false, Datum.of(normalizedSlot));
                }
                return normalizedSlot;
            }
            registry.remove(memberName);
        }

        int rememberedAliasSlot = resolveRememberedAliasSlot(instance, registry, memberName);
        if (rememberedAliasSlot != 0) {
            registry.putTyped(memberName, false, Datum.of(rememberedAliasSlot));
            return rememberedAliasSlot;
        }

        int resolvedSlot = resolveMemberSlotByName(memberName, allowDefinitionBootstrapLookup);
        if (resolvedSlot > 0) {
            registry.putTyped(memberName, false, Datum.of(resolvedSlot));
        }
        return resolvedSlot;
    }

    private static Datum resolveRegisteredMember(
            Datum.ScriptInstance instance,
            Datum.PropList registry,
            List<Datum> args) {
        int slotValue = resolveRegisteredMemberSlot(instance, registry, args, true);
        if (slotValue == 0) {
            return Datum.VOID;
        }

        int normalizedSlot = Math.abs(slotValue);
        LingoVM vm = LingoVM.getCurrentVM();
        if (vm != null) {
            return vm.callBuiltin("member", List.of(Datum.of(normalizedSlot)));
        }

        SlotId slotId = new SlotId(normalizedSlot);
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return Datum.CastMemberRef.of(slotId.castLib(), slotId.member());
        }
        return provider.getMember(slotId.castLib(), slotId.member());
    }

    private static int resolveMemberSlotByName(String memberName, boolean allowDefinitionBootstrapLookup) {
        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null || memberName == null || memberName.isEmpty()) {
            return 0;
        }

        Datum memberRef = provider.getRegistryMemberByName(0, memberName);
        if (memberRef instanceof Datum.CastMemberRef cmr && cmr.castLibNum() >= 1 && cmr.memberNum() >= 1) {
            return SlotId.of(cmr.castLibNum(), cmr.memberNum()).value();
        }

        if (!allowDefinitionBootstrapLookup) {
            return 0;
        }

        Datum bootstrapRef = provider.getMemberByName(0, memberName);
        if (!(bootstrapRef instanceof Datum.CastMemberRef cmr) || cmr.castLibNum() < 1 || cmr.memberNum() < 1) {
            return 0;
        }
        boolean registryVisible = provider.isRegistryVisibleMember(cmr.castLibNum(), cmr.memberNum());
        if (!registryVisible && !isBootstrapDefinitionMember(provider, cmr.castLibNum(), cmr.memberNum())) {
            return 0;
        }
        return SlotId.of(cmr.castLibNum(), cmr.memberNum()).value();
    }

    private static boolean isBootstrapDefinitionMember(CastLibProvider provider, int castLibNumber, int memberNumber) {
        if (provider == null || castLibNumber <= 0 || memberNumber <= 0) {
            return false;
        }

        Datum type = provider.getMemberProp(castLibNumber, memberNumber, "type");
        if (!(type instanceof Datum.Symbol symbol)) {
            return false;
        }
        String typeName = symbol.name();
        return "script".equalsIgnoreCase(typeName)
                || "field".equalsIgnoreCase(typeName);
    }

    private static boolean isRegisteredRegistrySlotLive(int slotValue) {
        return normalizeRegisteredRegistrySlot(null, slotValue) != 0;
    }

    private static int normalizeRegisteredRegistrySlot(String memberName, int slotValue) {
        if (slotValue == 0) {
            return 0;
        }

        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return slotValue;
        }

        int absValue = Math.abs(slotValue);
        int sign = slotValue < 0 ? -1 : 1;
        SlotId slotId = new SlotId(absValue);
        if (slotId.castLib() >= 1 && slotId.member() >= 1) {
            if (provider.isRegistryVisibleMember(slotId.castLib(), slotId.member())) {
                return sign * absValue;
            }
            return 0;
        }

        int namedSlot = resolveRawRegistrySlotByName(provider, memberName, absValue);
        if (namedSlot != 0) {
            return sign * namedSlot;
        }

        int resolvedRawSlot = provider.resolveRawRegistryMemberSlot(memberName, absValue);
        if (resolvedRawSlot != 0) {
            return sign * resolvedRawSlot;
        }

        return slotValue;
    }

    private static int resolveRawRegistrySlotByName(CastLibProvider provider, String memberName, int memberNumber) {
        if (provider == null || memberName == null || memberName.isEmpty() || memberNumber <= 0) {
            return 0;
        }
        Datum ref = provider.getRegistryMemberByName(0, memberName);
        boolean registryResolved = ref instanceof Datum.CastMemberRef;
        if (!(ref instanceof Datum.CastMemberRef cmr)) {
            ref = provider.getMemberByName(0, memberName);
        }
        if (!(ref instanceof Datum.CastMemberRef cmr)
                || cmr.castLibNum() < 1
                || cmr.memberNum() != memberNumber) {
            return 0;
        }
        if (!provider.memberExists(cmr.castLibNum(), cmr.memberNum())) {
            return 0;
        }
        if (!registryResolved
                && !provider.isRegistryVisibleMember(cmr.castLibNum(), cmr.memberNum())
                && !isBootstrapDefinitionMember(provider, cmr.castLibNum(), cmr.memberNum())) {
            return 0;
        }
        return SlotId.of(cmr.castLibNum(), cmr.memberNum()).value();
    }

    private static int resolveRememberedAliasSlot(
            Datum.ScriptInstance instance,
            Datum.PropList registry,
            String memberName) {
        if (instance == null || registry == null || memberName == null || memberName.isEmpty()) {
            return 0;
        }

        Map<AliasSource, RememberedAliasText> aliasTexts = persistentAliasTextByRegistry.get(instance);
        if (aliasTexts == null || aliasTexts.isEmpty()) {
            return 0;
        }
        for (var entry : aliasTexts.entrySet()) {
            AliasSource source = entry.getKey();
            RememberedAliasText remembered = entry.getValue();
            int resolved = resolveAliasSlot(remembered.text(), memberName,
                    targetName -> resolveAliasTargetMemberNumber(registry, targetName, source.castLibNumber()));
            if (resolved != 0) {
                return resolved;
            }
        }
        return 0;
    }

    public static int reapplyPersistentAliases(int castLibNumber) {
        if (castLibNumber <= 0) {
            return 0;
        }
        int imported = 0;
        for (var entry : persistentAliasTextByRegistry.entrySet()) {
            imported += reapplyAliasesForRegistryOwner(entry.getKey(), entry.getValue(), castLibNumber);
        }
        return imported;
    }

    public static int reapplyAllPersistentAliases() {
        int imported = 0;
        for (var entry : persistentAliasTextByRegistry.entrySet()) {
            imported += reapplyAliasesForRegistryOwner(entry.getKey(), entry.getValue(), 0);
        }
        return imported;
    }

    static void clearRememberedAliases() {
        persistentAliasTextByRegistry.clear();
    }

    static int applyAliasMappings(Datum.PropList registry, String aliasText, Function<String, Integer> resolver) {
        return applyAliasMappings(registry, aliasText, resolver, null);
    }

    private static int applyAliasMappings(Datum.PropList registry, String aliasText,
                                          Function<String, Integer> resolver,
                                          Map<String, Integer> importedAliases) {
        if (registry == null || aliasText == null || aliasText.isEmpty() || resolver == null) {
            return 0;
        }

        int imported = 0;
        for (String rawLine : splitLines(aliasText)) {
            AliasLine aliasLine = parseAliasLine(rawLine);
            if (aliasLine == null || aliasLine.targetName().isEmpty()) {
                continue;
            }
            int resolvedNumber = resolver.apply(aliasLine.targetName());
            if (resolvedNumber <= 0) {
                continue;
            }
            int aliasSlot = aliasLine.mirrored() ? -resolvedNumber : resolvedNumber;
            registry.putTyped(
                    aliasLine.aliasName(),
                    false,
                    Datum.of(aliasSlot));
            if (importedAliases != null) {
                importedAliases.put(aliasLine.aliasName(), aliasSlot);
            }
            imported++;
        }
        return imported;
    }

    private static int resolveAliasSlot(String aliasText, String requestedAlias, Function<String, Integer> resolver) {
        if (aliasText == null || aliasText.isEmpty() || requestedAlias == null || requestedAlias.isEmpty() || resolver == null) {
            return 0;
        }

        for (String rawLine : splitLines(aliasText)) {
            AliasLine aliasLine = parseAliasLine(rawLine);
            if (aliasLine == null || !aliasLine.aliasName().equalsIgnoreCase(requestedAlias)) {
                continue;
            }
            if (aliasLine.targetName().isEmpty()) {
                return 0;
            }
            int resolvedNumber = resolver.apply(aliasLine.targetName());
            if (resolvedNumber <= 0) {
                return 0;
            }
            return aliasLine.mirrored() ? -resolvedNumber : resolvedNumber;
        }
        return 0;
    }

    private static void rememberAliasText(Datum.ScriptInstance instance, AliasSource source,
                                          String aliasText, Map<String, Integer> importedAliases) {
        if (instance == null || source == null || source.castLibNumber() <= 0
                || aliasText == null || aliasText.isEmpty()) {
            return;
        }
        Map<AliasSource, RememberedAliasText> aliasTexts = persistentAliasTextByRegistry.get(instance);
        if (aliasTexts == null) {
            aliasTexts = new java.util.LinkedHashMap<>();
            persistentAliasTextByRegistry.put(instance, aliasTexts);
        }
        aliasTexts.put(source, new RememberedAliasText(aliasText,
                importedAliases != null ? new java.util.LinkedHashMap<>(importedAliases) : Map.of()));
    }

    private static void removeAliasesFromSource(Datum.ScriptInstance instance,
                                                Datum.PropList registry,
                                                AliasSource source) {
        if (instance == null || registry == null || source == null) {
            return;
        }
        Map<AliasSource, RememberedAliasText> aliasesBySource = persistentAliasTextByRegistry.get(instance);
        if (aliasesBySource == null) {
            return;
        }
        RememberedAliasText remembered = aliasesBySource.get(source);
        if (remembered == null || remembered.importedAliases().isEmpty()) {
            return;
        }
        for (var alias : remembered.importedAliases().entrySet()) {
            Datum current = registry.get(alias.getKey(), false);
            if (current != null && !current.isVoid() && current.toInt() == alias.getValue()) {
                registry.remove(alias.getKey(), false);
            }
        }
    }

    private static List<String> splitLines(String text) {
        ArrayList<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch != '\r' && ch != '\n') {
                continue;
            }
            lines.add(text.substring(start, i));
            if (ch == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                i++;
            }
            start = i + 1;
        }
        lines.add(text.substring(start));
        return lines;
    }

    private static int reapplyAliasesForRegistryOwner(
            Datum.ScriptInstance registryOwner,
            Map<AliasSource, RememberedAliasText> aliasesBySource,
            int onlyCastLibNumber) {
        if (registryOwner == null || aliasesBySource == null || aliasesBySource.isEmpty()) {
            return 0;
        }

        Datum registryDatum = AncestorChainWalker.getProperty(registryOwner, "pAllMemNumList");
        if (!(registryDatum instanceof Datum.PropList registry)) {
            return 0;
        }

        int imported = 0;
        for (var aliasEntry : aliasesBySource.entrySet()) {
            AliasSource source = aliasEntry.getKey();
            if (onlyCastLibNumber > 0 && source.castLibNumber() != onlyCastLibNumber) {
                continue;
            }
            removeAliasesFromSource(registryOwner, registry, source);
            String aliasText = aliasEntry.getValue().text();
            if (aliasText == null || aliasText.isEmpty()) {
                continue;
            }
            Map<String, Integer> importedAliases = new java.util.LinkedHashMap<>();
            imported += applyAliasMappings(
                    registry,
                    aliasText,
                    targetName -> resolveAliasTargetMemberNumber(registry, targetName, source.castLibNumber()),
                    importedAliases);
            aliasesBySource.put(source, new RememberedAliasText(aliasText, importedAliases));
        }
        return imported;
    }

    private static AliasLine parseAliasLine(String rawLine) {
        if (rawLine == null || rawLine.length() <= 2) {
            return null;
        }

        int delimiter = rawLine.indexOf('=');
        if (delimiter <= 0 || delimiter >= rawLine.length() - 1) {
            return null;
        }

        String aliasName = rawLine.substring(0, delimiter);
        String targetName = rawLine.substring(delimiter + 1);
        if (aliasName.isEmpty() || targetName.isEmpty()) {
            return null;
        }

        boolean mirrored = targetName.charAt(targetName.length() - 1) == '*';
        if (mirrored) {
            targetName = targetName.substring(0, targetName.length() - 1);
        }

        return new AliasLine(aliasName, targetName, mirrored);
    }

    private static int resolveTargetMemberNumber(Datum.PropList registry, String targetName) {
        return resolveTargetMemberNumber(registry, targetName, 0);
    }

    private static int resolveAliasTargetMemberNumber(Datum.PropList registry, String targetName, int sourceCastLibNumber) {
        Datum existing = registry.get(targetName, false);
        if (existing != null && !existing.isVoid()) {
            int existingSlot = existing.toInt();
            int normalizedSlot = Math.abs(normalizeRegisteredRegistrySlot(targetName, existingSlot));
            if (normalizedSlot != 0) {
                if (normalizedSlot != Math.abs(existingSlot)) {
                    registry.putTyped(targetName, false, Datum.of(normalizedSlot));
                }
                return normalizedSlot;
            }
            registry.remove(targetName, false);
        }
        return resolveTargetMemberNumber(registry, targetName, sourceCastLibNumber);
    }

    private static int resolveTargetMemberNumber(Datum.PropList registry, String targetName, int sourceCastLibNumber) {
        Datum existing = registry.get(targetName);
        if (existing != null && !existing.isVoid()) {
            int existingSlot = existing.toInt();
            int normalizedSlot = Math.abs(normalizeRegisteredRegistrySlot(targetName, existingSlot));
            if (normalizedSlot != 0) {
                if (normalizedSlot != Math.abs(existingSlot)) {
                    registry.putTyped(targetName, false, Datum.of(normalizedSlot));
                }
                return normalizedSlot;
            }
            registry.remove(targetName);
        }

        CastLibProvider provider = CastLibProvider.getProvider();
        if (provider == null) {
            return 0;
        }

        Datum memberRef = sourceCastLibNumber > 0
                ? provider.getRegistryMemberByName(sourceCastLibNumber, targetName)
                : Datum.VOID;
        if (!(memberRef instanceof Datum.CastMemberRef)) {
            memberRef = provider.getRegistryMemberByName(0, targetName);
        }
        if (!(memberRef instanceof Datum.CastMemberRef cmr)
                || cmr.castLibNum() < 1
                || cmr.memberNum() < 1) {
            return 0;
        }

        int slotValue = SlotId.of(cmr.castLibNum(), cmr.memberNum()).value();
        if (sourceCastLibNumber <= 0 && !isRegisteredRegistrySlotLive(slotValue)) {
            return 0;
        }
        if (sourceCastLibNumber > 0 && !provider.isRegistryVisibleMember(cmr.castLibNum(), cmr.memberNum())) {
            return 0;
        }

        registry.putTyped(targetName, false, Datum.of(slotValue));
        return slotValue;
    }

    private record AliasLine(String aliasName, String targetName, boolean mirrored) {}

    record DispatchResult(boolean handled, Datum value) {}
}
