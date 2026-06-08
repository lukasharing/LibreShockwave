package com.libreshockwave.player.cast;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.KeyTableChunk;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.id.SlotId;
import com.libreshockwave.util.FileUtil;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.DebugConfig;
import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.LingoVM;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Manages cast libraries for the player.
 * Provides access to cast libraries and their members for Lingo scripts.
 * Cast libraries are lazily loaded when first accessed via castLib().
 */
public class CastLibManager implements CastLibProvider {

    private static final String DYNAMIC_PALETTE_DUPLICATE_SUFFIX = " Duplicate";
    private static final String COMPACT_DYNAMIC_PALETTE_DUPLICATE_SUFFIX = "Duplicate";

    private final DirectorFile file;
    private final Map<Integer, CastLib> castLibs = new LinkedHashMap<>();
    private final Map<Integer, Map<String, HandlerLocation>> handlerLookupCache = new HashMap<>();
    private final Map<String, HandlerLocation> globalHandlerLookupCache = new HashMap<>();
    private final Map<String, Datum> memberByNameLookupCache = new HashMap<>();
    private final Map<String, Datum> registryMemberByNameLookupCache = new HashMap<>();
    private final Map<Integer, List<String>> scriptPropertyNamesCache = new HashMap<>();
    private boolean initialized = false;

    // Callback for cast data loading: when Lingo sets castLib.fileName, this is called
    // with (castLibNumber, fileName). Can load data synchronously (JVM) or queue for
    // async delivery (WASM).
    private final BiConsumer<Integer, String> castDataRequestCallback;
    private java.util.function.IntConsumer registryChangeCallback;

    public CastLibManager(DirectorFile file, BiConsumer<Integer, String> castDataRequestCallback) {
        this.file = file;
        this.castDataRequestCallback = castDataRequestCallback;
    }

    public void setRegistryChangeCallback(java.util.function.IntConsumer registryChangeCallback) {
        this.registryChangeCallback = registryChangeCallback;
    }

    /**
     * Initialize cast library references from the DirectorFile.
     * This creates CastLib objects but doesn't load their members yet.
     * Uses CastListChunk as the primary source for cast library info.
     */
    private void ensureInitialized() {
        if (initialized || file == null) {
            return;
        }

        initialized = true;

        CastListChunk castList = file.getCastList();
        List<CastChunk> casts = file.getCasts();
        KeyTableChunk keyTable = file.getKeyTable();

        String basePath = file.getBasePath();

        if (castList != null && !castList.entries().isEmpty()) {
            boolean[] assignedCastChunks = new boolean[casts.size()];
            // Use CastListChunk entries as the source
            for (int i = 0; i < castList.entries().size(); i++) {
                CastListChunk.CastListEntry listEntry = castList.entries().get(i);

                // Cast lib number is 1-based index
                int castLibNumber = i + 1;

                CastChunk castChunk = findCastChunkForListEntry(casts, assignedCastChunks, listEntry, keyTable);
                boolean isExternal = listEntry.path() != null && !listEntry.path().isEmpty();

                CastLib castLib = new CastLib(castLibNumber, castChunk, listEntry);

                // Set preload mode from cast list entry
                castLib.setPreloadMode(listEntry.preloadSettings());

                // Set base path for external cast loading
                castLib.setBasePath(basePath);

                // For internal casts (no external fileName), set the source file directly
                if (!isExternal) {
                    castLib.setSourceFile(file);
                }

                castLibs.put(castLibNumber, castLib);
                traceCastLoad("init cast=" + castLibNumber
                        + " name=" + safe(castLib.getName())
                        + " file=" + safe(castLib.getFileName())
                        + " external=" + isExternal
                        + " preload=" + castLib.getPreloadMode()
                        + " castChunk=" + (castChunk != null));
            }
        } else if (!casts.isEmpty()) {
            // Fallback: use CastChunks directly if no cast list
            for (int i = 0; i < casts.size(); i++) {
                int castLibNumber = i + 1;
                CastLib castLib = new CastLib(castLibNumber, casts.get(i), null);
                castLib.setBasePath(basePath);
                castLib.setSourceFile(file);
                castLibs.put(castLibNumber, castLib);
                traceCastLoad("init-fallback cast=" + castLibNumber
                        + " name=" + safe(castLib.getName())
                        + " file=" + safe(castLib.getFileName()));
            }
        }
    }

    private static CastChunk findCastChunkForListEntry(
            List<CastChunk> casts,
            boolean[] assignedCastChunks,
            CastListChunk.CastListEntry listEntry,
            KeyTableChunk keyTable) {
        if (casts == null || casts.isEmpty() || listEntry == null) {
            return null;
        }

        int resourceMappedIndex = findCastChunkIndexByResourceId(casts, listEntry.id(), keyTable);
        if (resourceMappedIndex >= 0 && !assignedCastChunks[resourceMappedIndex]) {
            assignedCastChunks[resourceMappedIndex] = true;
            return casts.get(resourceMappedIndex);
        }

        int expectedMemberCount = listEntry.memberCount();
        for (int i = 0; i < casts.size(); i++) {
            if (assignedCastChunks[i]) {
                continue;
            }
            CastChunk cast = casts.get(i);
            if (cast != null && cast.memberIds().size() == expectedMemberCount) {
                assignedCastChunks[i] = true;
                return cast;
            }
        }
        return null;
    }

    private static int findCastChunkIndexByResourceId(List<CastChunk> casts, int castResourceId, KeyTableChunk keyTable) {
        if (casts == null || keyTable == null || castResourceId <= 0) {
            return -1;
        }
        KeyTableChunk.KeyTableEntry castMappingEntry =
                keyTable.findEntry(new ChunkId(castResourceId), ChunkType.CASp.getFourCC());
        if (castMappingEntry == null) {
            return -1;
        }
        ChunkId mappingResourceId = castMappingEntry.sectionId();
        for (int i = 0; i < casts.size(); i++) {
            CastChunk cast = casts.get(i);
            if (cast != null && mappingResourceId.equals(cast.id())) {
                return i;
            }
        }
        return -1;
    }

    /**
     * Get a cast library by number, loading it if necessary.
     */
    public CastLib getCastLib(int castLibNumber) {
        ensureInitialized();

        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib != null && !castLib.isLoaded()) {
            traceCastLoad("lazy-load-request cast=" + castLibNumber
                    + " name=" + safe(castLib.getName())
                    + " file=" + safe(castLib.getFileName())
                    + " state=" + castLib.getState());
            castLib.load();
        }
        return castLib;
    }

    /**
     * Get a cast library by name, loading it if necessary.
     */
    public CastLib getCastLibByNameInternal(String name) {
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            if (castLib.getName().equalsIgnoreCase(name)) {
                if (!castLib.isLoaded()) {
                    castLib.load();
                }
                return castLib;
            }
        }

        // Check for "Internal" as default name for cast 1
        if ("internal".equalsIgnoreCase(name)) {
            return getCastLib(1);
        }

        return null;
    }

    @Override
    public int getCastLibByNumber(int castLibNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        return castLib != null ? castLib.getNumber() : -1;
    }

    @Override
    public int getCastLibByName(String name) {
        CastLib castLib = getCastLibByNameInternal(name);
        return castLib != null ? castLib.getNumber() : -1;
    }

    @Override
    public Datum getCastLibProp(int castLibNumber, String propName) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return Datum.VOID;
        }
        return castLib.getProp(propName);
    }

    @Override
    public boolean setCastLibProp(int castLibNumber, String propName, Datum value) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return false;
        }

        String normalizedPropName = propName == null ? "" : propName.toLowerCase(Locale.ROOT);
        String oldName = castLib.getName();
        String oldFileName = castLib.getFileName();
        boolean wasRegistryVisible = isRegistryVisibleCast(castLib);

        boolean result = castLib.setProp(propName, value);
        if (result && ("name".equals(normalizedPropName) || "filename".equals(normalizedPropName))) {
            traceCastLoad("setProp cast=" + castLibNumber
                    + " prop=" + normalizedPropName
                    + " oldName=" + safe(oldName)
                    + " newName=" + safe(castLib.getName())
                    + " oldFile=" + safe(oldFileName)
                    + " newFile=" + safe(castLib.getFileName())
                    + " registryVisible=" + isRegistryVisibleCast(castLib));
        }

        if (result && ("name".equals(normalizedPropName) || "filename".equals(normalizedPropName))) {
            boolean registryChanged = !Objects.equals(oldName, castLib.getName())
                    || !Objects.equals(oldFileName, castLib.getFileName())
                    || wasRegistryVisible != isRegistryVisibleCast(castLib);
            if (registryChanged) {
                notifyRegistryChanged(castLibNumber);
            }
        }

        // When authored Lingo sets castLib.fileName to a newly downloaded URL, reload
        // the cast from cached data to match Director's automatic reload behavior.
        // In real Director, setting castLib.fileName triggers an automatic reload.
        if (result && "filename".equals(normalizedPropName)) {
            tryLoadCastFromCache(castLibNumber, value.toStr());
        }

        return result;
    }

    private void notifyRegistryChanged(int castLibNumber) {
        clearHandlerLookupCache();
        if (registryChangeCallback != null) {
            registryChangeCallback.accept(castLibNumber);
        }
    }

    private void tryLoadCastFromCache(int castLibNumber, String newFileName) {
        if (newFileName == null || newFileName.isEmpty()) return;

        clearHandlerLookupCache();
        markPendingExternalLoad(castLibNumber, newFileName);
        traceCastLoad("fileName-request cast=" + castLibNumber
                + " file=" + safe(newFileName)
                + " cacheHit=" + (getCachedExternalData(newFileName) != null));

        // Player provides a callback that checks its internal caches safely
        // before delegating to system-specific async logic.
        if (castDataRequestCallback != null) {
            castDataRequestCallback.accept(castLibNumber, newFileName);
        }
    }

    @Override
    public Datum getMember(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            // Return reference anyway - will be invalid
            return Datum.CastMemberRef.of(castLibNumber, memberNumber);
        }

        // Validate member exists
        CastMemberChunk member = castLib.findMemberByNumber(memberNumber);
        if (member == null) {
            // Return reference anyway - member may not exist but reference is valid syntax
            return Datum.CastMemberRef.of(castLibNumber, memberNumber);
        }
        return Datum.CastMemberRef.of(castLibNumber, memberNumber);
    }

    @Override
    public boolean memberExists(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return false;
        }
        boolean exists = castLib.findMemberByNumber(memberNumber) != null
                || castLib.getCachedMember(memberNumber) != null;
        return exists;
    }

    @Override
    public boolean isRegistryVisibleMember(int castLibNumber, int memberNumber) {
        if (memberNumber <= 0) {
            return false;
        }
        CastLib castLib = getCastLib(castLibNumber);
        if (!isRegistryVisibleCast(castLib)) {
            return false;
        }
        CastMember cached = castLib.getCachedMember(memberNumber);
        return castLib.findMemberByNumber(memberNumber) != null
                || (cached != null && !cached.isReusableDynamicSlot());
    }

    @Override
    public int resolveRawRegistryMemberSlot(String registryName, int memberNumber) {
        ensureInitialized();
        if (memberNumber <= 0) {
            return 0;
        }

        int exactNameSlot = 0;
        int definitionSlot = 0;
        int exactNameCount = 0;
        int definitionCount = 0;

        for (CastLib castLib : castLibs.values()) {
            CastLib searchable = castForMemberLookup(castLib);
            if (searchable == null || !isRegistryVisibleCast(searchable)) {
                continue;
            }
            if (searchable.isExternal() && !searchable.isLoaded()) {
                continue;
            }

            CastMemberChunk chunk = searchable.findMemberByNumber(memberNumber);
            CastMember cached = searchable.getCachedMember(memberNumber);
            if (chunk == null && cached == null) {
                continue;
            }

            int slotValue = SlotId.of(searchable.getNumber(), memberNumber).value();
            String actualName = rawRegistryMemberName(chunk, cached);
            if (sameMemberName(registryName, actualName)) {
                exactNameSlot = slotValue;
                exactNameCount++;
                continue;
            }

            if (isRegistryDefinitionMember(chunk, cached)) {
                definitionSlot = slotValue;
                definitionCount++;
            }
        }

        if (exactNameCount == 1) {
            return exactNameSlot;
        }
        if (definitionCount == 1) {
            return definitionSlot;
        }
        return 0;
    }

    private static boolean sameMemberName(String expected, String actual) {
        return expected != null
                && actual != null
                && !expected.isEmpty()
                && !actual.isEmpty()
                && expected.equalsIgnoreCase(actual);
    }

    private static String rawRegistryMemberName(CastMemberChunk chunk, CastMember cached) {
        if (chunk != null && chunk.name() != null && !chunk.name().isEmpty()) {
            return chunk.name();
        }
        return cached != null ? cached.getName() : null;
    }

    private static boolean isRegistryDefinitionMember(CastMemberChunk chunk, CastMember cached) {
        MemberType type = chunk != null ? chunk.memberType() : cached != null ? cached.getMemberType() : MemberType.NULL;
        if (type == MemberType.TEXT || type == MemberType.BUTTON || type == MemberType.RICH_TEXT || type == MemberType.SCRIPT) {
            return true;
        }
        return chunk != null && chunk.isTextXtra();
    }

    @Override
    public Datum getMemberByName(int castLibNumber, String memberName) {
        ensureInitialized();
        memberName = unquoteLingoStringLiteral(memberName);
        String cacheKey = memberByNameCacheKey(castLibNumber, memberName);
        if (memberByNameLookupCache.containsKey(cacheKey)) {
            return memberByNameLookupCache.get(cacheKey);
        }
        Datum result = Datum.VOID;
        if (castLibNumber > 0) {
            result = getMemberByNameInCast(getCastLib(castLibNumber), memberName);
        } else {
            for (CastLib castLib : castLibs.values()) {
                MemberNameCandidate found = getMemberByNameCandidate(castForMemberLookup(castLib), memberName);
                if (found == null) {
                    continue;
                }
                result = found.ref();
                break;
            }
        }

        memberByNameLookupCache.put(cacheKey, result);
        return result;
    }

    @Override
    public Datum getScriptMemberByName(int castLibNumber, String memberName) {
        ensureInitialized();
        memberName = unquoteLingoStringLiteral(memberName);
        if (castLibNumber > 0) {
            return getScriptMemberByNameInCast(getCastLib(castLibNumber), memberName);
        }

        for (CastLib castLib : castLibs.values()) {
            CastLib loadedCast = castForGlobalLookup(castLib);
            if (!isRegistryVisibleCast(loadedCast)) {
                continue;
            }
            Datum found = getScriptMemberByNameInCast(loadedCast, memberName);
            if (!found.isVoid()) {
                return found;
            }
        }

        for (CastLib castLib : castLibs.values()) {
            Datum found = getScriptMemberByNameInCast(castForGlobalLookup(castLib), memberName);
            if (!found.isVoid()) {
                return found;
            }
        }

        return Datum.VOID;
    }

    @Override
    public Datum getRegistryMemberByName(int castLibNumber, String memberName) {
        ensureInitialized();
        memberName = unquoteLingoStringLiteral(memberName);
        String cacheKey = memberByNameCacheKey(castLibNumber, memberName);
        if (registryMemberByNameLookupCache.containsKey(cacheKey)) {
            return registryMemberByNameLookupCache.get(cacheKey);
        }
        Datum result = Datum.VOID;
        if (castLibNumber > 0) {
            CastLib castLib = getCastLib(castLibNumber);
            if (!isRegistryVisibleCast(castLib)) {
                registryMemberByNameLookupCache.put(cacheKey, result);
                return result;
            }
            result = getRegistryMemberByNameInCast(castLib, memberName);
        } else {
            for (CastLib castLib : castLibs.values()) {
                CastLib loadedCast = castForMemberLookup(castLib);
                if (!isRegistryVisibleCast(loadedCast)) {
                    continue;
                }
                Datum found = getRegistryMemberByNameInCast(loadedCast, memberName);
                if (!found.isVoid()) {
                    result = found;
                    break;
                }
            }
        }
        registryMemberByNameLookupCache.put(cacheKey, result);
        return result;
    }

    private static String memberByNameCacheKey(int castLibNumber, String memberName) {
        return castLibNumber + ":" + LingoVM.normalizeLookupName(memberName);
    }

    private static String unquoteLingoStringLiteral(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        if (value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private CastLib castForGlobalLookup(CastLib castLib) {
        if (castLib == null) {
            return null;
        }
        if (castLib.isLoaded()) {
            return castLib;
        }
        if (castLib.isExternal()) {
            return null;
        }
        return getCastLib(castLib.getNumber());
    }

    private CastLib castForMemberLookup(CastLib castLib) {
        if (castLib == null) {
            return null;
        }
        if (castLib.isLoaded()) {
            return castLib;
        }
        if (castLib.isExternal()) {
            return castLib;
        }
        return getCastLib(castLib.getNumber());
    }

    @Override
    public int getCastLibCount() {
        ensureInitialized();
        return castLibs.size();
    }

    @Override
    public int getMemberCount(int castLibNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return 0;
        }
        return castLib.getMemberCount();
    }

    @Override
    public Datum getMemberProp(int castLibNumber, int memberNumber, String propName) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            // Return defaults for invalid cast lib
            return CastLib.getInvalidMemberProp(propName);
        }
        Datum value = castLib.getMemberProp(memberNumber, propName);
        if (!"text".equalsIgnoreCase(propName) || !(value instanceof Datum.Str text) || !text.value().isEmpty()) {
            return value;
        }
        CastMember member = castLib.getMember(memberNumber);
        String resolvedText = resolveFieldText(member);
        return resolvedText.isEmpty() ? value : Datum.of(resolvedText);
    }

    @Override
    public boolean setMemberProp(int castLibNumber, int memberNumber, String propName, Datum value) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return false;
        }
        boolean result = castLib.setMemberProp(memberNumber, propName, value);
        if (result && "name".equalsIgnoreCase(propName)) {
            clearHandlerLookupCache();
        }
        return result;
    }

    @Override
    public boolean updateMember(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null || memberNumber <= 0) {
            return false;
        }
        CastMember cached = castLib.getCachedMember(memberNumber);
        if (cached == null) {
            return castLib.findMemberByNumber(memberNumber) != null;
        }
        if (!cached.isRuntimeDynamic()) {
            return true;
        }
        cached.erase();
        clearHandlerLookupCache();
        return true;
    }

    @Override
    public boolean removeMember(int castLibNumber, int memberNumber) {
        return updateMember(castLibNumber, memberNumber);
    }

    @Override
    public Datum getMemberTextRangeProp(int castLibNumber, int memberNumber,
                                        String chunkType, int start, int end,
                                        String propName) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return Datum.VOID;
        }
        return castLib.getMemberTextRangeProp(memberNumber, chunkType, start, end, propName);
    }

    @Override
    public boolean setMemberTextRangeProp(int castLibNumber, int memberNumber,
                                          String chunkType, int start, int end,
                                          String propName, Datum value) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return false;
        }
        return castLib.setMemberTextRangeProp(memberNumber, chunkType, start, end, propName, value);
    }

    @Override
    public boolean importFileIntoMember(int castLibNumber, int memberNumber, String url, Datum options) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return false;
        }
        CastMember member = castLib.getMember(memberNumber);
        if (member == null) {
            return false;
        }
        byte[] data = getCachedDownloadedData(url);
        if (data == null || data.length == 0) {
            return false;
        }
        if (member.getMemberType() == MemberType.TEXT || member.getMemberType() == MemberType.BUTTON) {
            member.setDynamicText(new String(data, StandardCharsets.UTF_8));
            return true;
        }
        Bitmap bitmap = decodeImportedImage(data);
        if (bitmap == null) {
            return false;
        }
        return member.setProp("image", new Datum.ImageRef(bitmap));
    }

    private static Bitmap decodeImportedImage(byte[] data) {
        if (data.length < 12
                || data[0] != 'L' || data[1] != 'S' || data[2] != 'W' || data[3] != 'I') {
            return null;
        }
        int width = readU32BE(data, 4);
        int height = readU32BE(data, 8);
        if (width <= 0 || height <= 0) {
            return null;
        }
        int pixelBytes = width * height * 4;
        if (pixelBytes / 4 != width * height || data.length < 12 + pixelBytes) {
            return null;
        }
        Bitmap bitmap = new Bitmap(width, height, 32);
        boolean hasAlpha = false;
        int offset = 12;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int r = data[offset++] & 0xFF;
                int g = data[offset++] & 0xFF;
                int b = data[offset++] & 0xFF;
                int a = data[offset++] & 0xFF;
                if (a < 255) {
                    hasAlpha = true;
                }
                bitmap.setPixel(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        if (hasAlpha) {
            bitmap.setNativeAlpha(true);
        }
        return bitmap;
    }

    private static int readU32BE(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    @Override
    public Datum callMemberMethod(int castLibNumber, int memberNumber,
                                   String methodName, java.util.List<Datum> args) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return Datum.VOID;
        }
        CastMember member = castLib.getMember(memberNumber);
        if (member == null) {
            return Datum.VOID;
        }
        if ("duplicate".equalsIgnoreCase(methodName)) {
            return duplicateMember(castLib, member, args);
        }
        return member.callMethod(methodName, args);
    }

    private Datum duplicateMember(CastLib sourceCastLib, CastMember sourceMember, java.util.List<Datum> args) {
        if (sourceCastLib == null || sourceMember == null) {
            return Datum.VOID;
        }

        Datum.CastMemberRef targetRef;
        if (args == null || args.isEmpty()) {
            CastMember targetMember = sourceCastLib.createDynamicMember(sourceMember.getMemberType().getName());
            if (targetMember == null) {
                return Datum.VOID;
            }
            Datum createdRef = Datum.CastMemberRef.of(sourceCastLib.getNumber(), targetMember.getMemberNumber());
            if (!(createdRef instanceof Datum.CastMemberRef cmr)) {
                return Datum.VOID;
            }
            targetRef = cmr;
        } else {
            targetRef = resolveDuplicateTargetRef(sourceCastLib.getNumber(), args.get(0));
            if (targetRef == null) {
                return sourceMember.callMethod("duplicate", args);
            }
        }

        CastLib targetCastLib = getCastLib(targetRef.castLibNum());
        if (targetCastLib == null) {
            return Datum.VOID;
        }
        CastMember targetMember = targetCastLib.getMember(targetRef.memberNum());
        if (targetMember == null) {
            targetMember = targetCastLib.createDynamicMemberAt(
                    targetRef.memberNum(), sourceMember.getMemberType());
            if (targetMember != null) {
                clearHandlerLookupCache();
            }
        }
        if (targetMember == null) {
            return Datum.VOID;
        }

        if (targetMember.copyMediaFrom(sourceMember)) {
            return targetRef;
        }

        // Some legacy movies call duplicate() on an empty receiver while passing
        // the real source member as the argument. Keep that compatibility after
        // trying Director's documented source->target direction first.
        if (args != null && !args.isEmpty()) {
            CastMember argumentMember = targetCastLib.getMember(targetRef.memberNum());
            if (argumentMember != null && sourceMember.copyMediaFrom(argumentMember)) {
                return Datum.CastMemberRef.of(sourceMember.getCastLibNumber(), sourceMember.getMemberNumber());
            }
        }
        return Datum.VOID;
    }

    private static Datum.CastMemberRef resolveDuplicateTargetRef(int sourceCastLibNumber, Datum targetArg) {
        if (targetArg instanceof Datum.CastMemberRef cmr) {
            return cmr;
        }
        if (targetArg == null || (!targetArg.isInt() && !targetArg.isFloat())) {
            return null;
        }

        int slotValue = targetArg.toInt();
        SlotId slotId = new SlotId(slotValue);
        if (slotId.castLib() >= 1 && slotId.member() >= 1) {
            Datum decodedRef = Datum.CastMemberRef.of(slotId.castLib(), slotId.member());
            if (decodedRef instanceof Datum.CastMemberRef cmr) {
                return cmr;
            }
        }
        if (sourceCastLibNumber >= 1 && slotValue >= 1) {
            Datum fallbackRef = Datum.CastMemberRef.of(sourceCastLibNumber, slotValue);
            if (fallbackRef instanceof Datum.CastMemberRef cmr) {
                return cmr;
            }
        }
        return null;
    }

    /**
     * Get a property for an invalid member reference.
     */

    /**
     * Get a cast member chunk directly.
     */
    public CastMemberChunk getCastMember(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLibForMemberLookup(castLibNumber);
        if (castLib == null) {
            return null;
        }
        return castLib.findMemberByNumber(memberNumber);
    }

    private CastLib getCastLibForMemberLookup(int castLibNumber) {
        ensureInitialized();

        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return null;
        }

        if (castLib.isExternal() && !castLib.isFetched()) {
            requestExternalCastData(castLib);
            if (!castLib.isFetched()) {
                return castLib.isLoaded() ? castLib : null;
            }
        }

        if (!castLib.isLoaded()) {
            castLib.load();
        }
        return castLib;
    }

    private void requestExternalCastData(CastLib castLib) {
        if (castLib == null || !castLib.isExternal() || castLib.isFetching()) {
            return;
        }
        String fileName = castLib.getFileName();
        if (fileName == null || fileName.isEmpty()) {
            return;
        }
        castLib.markFetching();
        if (castDataRequestCallback != null) {
            castDataRequestCallback.accept(castLib.getNumber(), fileName);
        }
    }

    /**
     * Get the palette from a palette cast member.
     * Searches by member number within a specific cast lib, then all cast libs.
     */
    @Override
    public Palette getMemberPalette(int castLibNumber, int memberNumber) {
        // Try specified cast lib first
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib != null && castLib.isLoaded()) {
            CastMember dynamicMember = castLib.getMember(memberNumber);
            if (dynamicMember != null) {
                Palette dynamicPalette = dynamicMember.getPaletteData();
                if (dynamicPalette != null) {
                    return dynamicPalette;
                }
                Palette duplicateFallback = resolveDynamicPaletteDuplicate(dynamicMember);
                if (duplicateFallback != null) {
                    return duplicateFallback;
                }
            }
            CastMemberChunk chunk = castLib.findMemberByNumber(memberNumber);
            if (chunk != null && chunk.file() != null) {
                return chunk.file().resolvePaletteByMemberNumberExact(memberNumber);
            }
        }
        // Fallback: search all cast libs
        for (CastLib cl : castLibs.values()) {
            if (!cl.isLoaded()) continue;
            CastMember dynamicMember = cl.getMember(memberNumber);
            if (dynamicMember != null) {
                Palette dynamicPalette = dynamicMember.getPaletteData();
                if (dynamicPalette != null) {
                    return dynamicPalette;
                }
                Palette duplicateFallback = resolveDynamicPaletteDuplicate(dynamicMember);
                if (duplicateFallback != null) {
                    return duplicateFallback;
                }
            }
            CastMemberChunk chunk = cl.findMemberByNumber(memberNumber);
            if (chunk != null && chunk.file() != null) {
                return chunk.file().resolvePaletteByMemberNumberExact(memberNumber);
            }
        }
        return null;
    }

    /**
     * Get a dynamic CastMember object (created at runtime via new(#type, castLib)).
     * Used by the renderer when CastMemberChunk lookup fails for dynamically created members.
     */
    public CastMember getDynamicMember(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return null;
        }
        return castLib.getMember(memberNumber);
    }

    /**
     * Resolve any runtime CastMember wrapper by cast/member number.
     * Unlike getDynamicMember(), this also works for file-backed members.
     */
    public CastMember resolveMember(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return null;
        }
        return castLib.getMember(memberNumber);
    }

    /**
     * Find a CastMember object by name across all loaded cast libraries.
     * Searches visible cast libraries in order and lets each cast resolve the
     * first matching visible slot, whether file-backed or runtime-created.
     * Returns null if not found.
     */
    public CastMember findCastMemberByName(String name) {
        ensureInitialized();

        // First pass uses runtime cast state, including renamed and dynamic members.
        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) continue;
            CastMember member = castLib.getMemberByName(name);
            if (member != null) {
                return member;
            }
        }

        // Search file members — return from CastLib.getMember() to get a full CastMember
        for (CastLib castLib : castLibs.values()) {
            CastLib searchable = castForMemberLookup(castLib);
            if (searchable == null || !searchable.isLoaded()) {
                continue;
            }
            CastMemberChunk chunk = searchable.findMemberByName(name);
            if (chunk != null) {
                int memberNumber = searchable.getMemberNumber(chunk);
                CastMember member = searchable.getMember(memberNumber);
                if (member != null) {
                    return member;
                }
            }
        }

        return null;
    }

    /**
     * Get a cast member chunk by name.
     */
    public CastMemberChunk getCastMemberByName(String name) {
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            CastLib searchable = castForMemberLookup(castLib);
            if (searchable == null || !searchable.isLoaded()) {
                continue;
            }
            CastMemberChunk member = searchable.findMemberByName(name);
            if (member != null) {
                return member;
            }
        }
        return null;
    }

    /**
     * Find the runtime CastMember for a given CastMemberChunk.
     * Searches all cast libraries for the chunk by its chunk ID, then returns the
     * runtime CastMember wrapper. Used by SpriteBaker to get Lingo-set properties
     * for score-placed sprites that only have a CastMemberChunk.
     */
    public CastMember findRuntimeMember(CastMemberChunk target) {
        if (target == null) return null;
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) continue;
            int memberNum = castLib.getMemberNumber(target);
            if (memberNum >= 0) {
                return castLib.getMember(memberNum);
            }
        }
        return null;
    }

    /**
     * Get all loaded cast libraries.
     */
    public Map<Integer, CastLib> getCastLibs() {
        ensureInitialized();
        return castLibs;
    }

    @Override
    public String getCastLibName(int castLibNumber) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return null;
        }
        return castLib.getName();
    }

    @Override
    public String getCastLibFileName(int castLibNumber) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return null;
        }
        return castLib.getFileName();
    }

    @Override
    public boolean fetchCastLib(int castLibNumber) {
        // External cast fetching is now handled by preloadNetThing
        // This method returns true if the cast is already fetched
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return false;
        }
        return castLib.isFetched();
    }

    @Override
    public boolean isCastLibExternal(int castLibNumber) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return false;
        }
        return castLib.isExternal();
    }

    @Override
    public void preloadCasts(int mode) {
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            int preloadMode = castLib.getPreloadMode();

            // mode 1 = AfterFrameOne, mode 2 = BeforeFrameOne (MovieLoaded)
            if (preloadMode == mode) {
                // External casts must be fetched via preloadNetThing first
                if (castLib.isFetched() && !castLib.isLoaded()) {
                    castLib.load();
                }
            }
        }
    }

    // Raw download cache. This is intentionally separate from CastLib state:
    // fetching bytes does not install those bytes into every cast slot with a
    // matching file name. Director exposes a cast's new members only when that
    // specific cast slot is preloaded or when authored Lingo sets castLib.fileName.
    private final Object externalCastCacheLock = new Object();
    private final Map<String, byte[]> castDataCache = new HashMap<>();
    private final Map<Integer, String> pendingExternalLoads = new HashMap<>();

    /**
     * Cache raw external cast data by base name for later reuse.
     */
    public void cacheExternalData(String url, byte[] data) {
        synchronized (externalCastCacheLock) {
            for (String key : downloadCacheKeys(url)) {
                castDataCache.put(key, data);
            }
        }
        traceCastLoad("cacheExternalData url=" + safe(url)
                + " bytes=" + (data != null ? data.length : 0)
                + " requestedSlots=" + getRequestedExternalCastSlots(url)
                + " registrySlots=" + getRegistryVisibleExternalCastSlots(url));
        for (CastLib castLib : findCastLibsByUrl(url)) {
            if (isRequestedExternalLoad(castLib, url)) {
                castLib.cacheFetchedExternalData(data);
            }
        }
    }

    /**
     * Look up cached raw cast data by base name.
     */
    public byte[] getCachedExternalData(String baseName) {
        synchronized (externalCastCacheLock) {
            for (String key : downloadCacheKeys(baseName)) {
                byte[] data = castDataCache.get(key);
                if (data != null) {
                    return data;
                }
            }
        }
        return null;
    }

    /**
     * Look up cached downloaded bytes by URL or file name.
     * Used by importFileInto for external image payloads as well as cast loading.
     */
    public byte[] getCachedDownloadedData(String url) {
        synchronized (externalCastCacheLock) {
            for (String key : downloadCacheKeys(url)) {
                byte[] data = castDataCache.get(key);
                if (data != null) {
                    return data;
                }
            }
        }
        return null;
    }

    private static Set<String> downloadCacheKeys(String url) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (url == null || url.isEmpty()) {
            return keys;
        }
        String normalizedUrl = normalizedDownloadCacheKey(url);
        if (normalizedUrl != null && !normalizedUrl.isEmpty()) {
            keys.add(normalizedUrl);
        }
        if (hasQuery(url)) {
            return keys;
        }
        String fileName = FileUtil.getFileName(url);
        if (fileName == null || fileName.isEmpty()) {
            return keys;
        }
        if (isAmbiguousCacheFileName(fileName)) {
            return keys;
        }
        keys.add(fileName.toLowerCase(Locale.ROOT));
        String baseName = FileUtil.getFileNameWithoutExtension(fileName);
        if (baseName != null && !baseName.isEmpty()) {
            keys.add(baseName.toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    private static boolean isAmbiguousCacheFileName(String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return true;
        }
        String clean = fileName;
        int query = clean.indexOf('?');
        if (query >= 0) {
            clean = clean.substring(0, query);
        }
        return clean.matches("\\d+");
    }

    private static String normalizedDownloadCacheKey(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        if (url.startsWith("http://") || url.startsWith("https://")) {
            try {
                URI uri = URI.create(url);
                String scheme = uri.getScheme();
                String host = uri.getHost();
                String path = uri.getPath();
                if (scheme == null || host == null || path == null || path.isEmpty()) {
                    return null;
                }
                int port = uri.getPort();
                String authority = port >= 0 ? host + ":" + port : host;
                String query = uri.getRawQuery();
                String key = scheme + "://" + authority + path;
                if (query != null && !query.isEmpty()) {
                    key = key + "?" + query;
                }
                return key.toLowerCase(Locale.ROOT);
            } catch (Exception ignored) {
                return null;
            }
        }
        return url.toLowerCase(Locale.ROOT);
    }

    private static boolean hasQuery(String url) {
        return url != null && url.indexOf('?') >= 0;
    }

    public void clearPendingExternalLoad(int castLibNumber) {
        synchronized (externalCastCacheLock) {
            pendingExternalLoads.remove(castLibNumber);
        }
    }

    /**
     * Release raw download buffers for external casts that are already parsed.
     * The parsed DirectorFile remains attached to each CastLib, so authored
     * members/scripts used by rendering stay available.
     */
    public int releaseLoadedExternalData() {
        ensureInitialized();
        int released = 0;
        for (CastLib castLib : castLibs.values()) {
            if (castLib.releaseFetchedExternalDataIfLoaded()) {
                released++;
            }
        }
        return released;
    }

    private void markPendingExternalLoad(int castLibNumber, String fileName) {
        synchronized (externalCastCacheLock) {
            pendingExternalLoads.put(castLibNumber, normalizedBaseName(fileName));
        }
    }

    /**
     * Set external cast data from preloadNetThing.
     * @param castLibNumber The cast library number
     * @param data The raw file data
     * @return true if parsing was successful
     */
    public boolean setExternalCastData(int castLibNumber, byte[] data) {
        return setExternalCastData(castLibNumber, data, null);
    }

    public boolean setExternalCastData(int castLibNumber, byte[] data, DirectorFile parsedSource) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return false;
        }
        if (castLib.isLoaded() && castLib.hasFetchedExternalData(data)) {
            return true;
        }
        DirectorFile reusableSource = parsedSource != null
                ? parsedSource
                : findReusableExternalSource(castLibNumber, data);
        traceCastLoad("setExternalCastData cast=" + castLibNumber
                + " file=" + safe(castLib.getFileName())
                + " bytes=" + (data != null ? data.length : 0)
                + " reuse=" + (reusableSource != null));
        boolean loaded = castLib.setExternalData(data, reusableSource);
        if (loaded) {
            clearPendingExternalLoad(castLibNumber);
            traceCastLoad("setExternalCastData-done cast=" + castLibNumber
                    + " file=" + safe(castLib.getFileName()));
        } else {
            traceCastLoad("setExternalCastData-failed cast=" + castLibNumber
                    + " file=" + safe(castLib.getFileName()));
        }
        return loaded;
    }

    private DirectorFile findReusableExternalSource(int targetCastLibNumber, byte[] data) {
        if (data == null || data.length == 0) {
            return null;
        }
        for (CastLib other : castLibs.values()) {
            if (other.getNumber() == targetCastLibNumber) {
                continue;
            }
            if (!other.isLoaded() || !other.hasFetchedExternalData(data)) {
                continue;
            }
            DirectorFile source = other.getSourceFile();
            if (source != null) {
                return source;
            }
        }
        return null;
    }

    public boolean hasLoadedExternalCastData(int castLibNumber, byte[] data) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        return castLib != null && castLib.isLoaded() && castLib.hasFetchedExternalData(data);
    }

    public java.util.List<Integer> getRequestedExternalCastSlots(String url) {
        ensureInitialized();

        java.util.List<Integer> slots = new java.util.ArrayList<>();
        
        for (CastLib castLib : findCastLibsByUrl(url)) {
            if (isRequestedExternalLoad(castLib, url)) {
                slots.add(castLib.getNumber());
            }
        }
        return slots;
    }

    /**
     * Return cast slots that may be hydrated from a completed external-cast
     * download. A raw download alone is not enough to install a cast: the bytes
     * must belong to a cast slot that is actively waiting for this source.
     * Generic preloadNetThing downloads remain raw/status-only until authored
     * code binds them to a cast slot or Director preloads that specific slot.
     */
    public java.util.List<Integer> getHydratableExternalCastSlots(String url) {
        ensureInitialized();
        return java.util.List.copyOf(getRequestedExternalCastSlots(url));
    }

    public boolean shouldHydrateDownloadedExternalCast(String url) {
        return !getHydratableExternalCastSlots(url).isEmpty();
    }

    public boolean isPendingExternalLoad(int castLibNumber, String url) {
        ensureInitialized();
        String baseName = normalizedBaseName(url);
        if (baseName.isEmpty()) {
            return false;
        }
        String pending;
        synchronized (externalCastCacheLock) {
            pending = pendingExternalLoads.get(castLibNumber);
        }
        return baseName.equals(pending);
    }

    /**
     * Returns true when a downloaded external cast URL belongs to one stable cast
     * namespace authored by the movie. Ambiguous shared files, such as placeholder
     * casts reused across many slots, stay status-only unless a specific slot is
     * actively waiting for them.
     */
    public boolean hasRegistryVisibleExternalCastBinding(String url) {
        ensureInitialized();
        return registryVisibleExternalCastBindingCount(url) == 1;
    }

    public java.util.List<Integer> getRegistryVisibleExternalCastSlots(String url) {
        ensureInitialized();
        java.util.List<Integer> slots = new java.util.ArrayList<>();
        java.util.List<CastLib> matches = findCastLibsByUrl(url);
        for (CastLib castLib : matches) {
            if (isRegistryVisibleCast(castLib)) {
                slots.add(castLib.getNumber());
            }
        }
        return slots.size() == 1 ? slots : java.util.List.of();
    }

    private int registryVisibleExternalCastBindingCount(String url) {
        int count = 0;
        for (CastLib castLib : findCastLibsByUrl(url)) {
            if (isRegistryVisibleCast(castLib)) {
                count++;
            }
        }
        return count;
    }

    private boolean isRequestedExternalLoad(CastLib castLib, String url) {
        if (castLib == null) {
            return false;
        }
        String baseName = normalizedBaseName(url);
        if (baseName.isEmpty()) {
            return false;
        }
        String pending;
        synchronized (externalCastCacheLock) {
            pending = pendingExternalLoads.get(castLib.getNumber());
        }
        return castLib.isFetching() || baseName.equals(pending);
    }

    private static void traceCastLoad(String message) {
        if (!DebugConfig.isDebugPlaybackEnabled()) {
            return;
        }
        System.out.println("[CastLoad] " + message);
    }

    private static String safe(String value) {
        if (value == null) {
            return "<null>";
        }
        String sanitized = value.replace('\n', ' ').replace('\r', ' ');
        if (sanitized.length() > 160) {
            return sanitized.substring(0, 160) + "...";
        }
        return '"' + sanitized + '"';
    }

    private java.util.List<CastLib> findCastLibsByUrl(String url) {
        String fileNameNoExt = normalizedBaseName(url);
        java.util.List<CastLib> result = new java.util.ArrayList<>();
        if (fileNameNoExt.isEmpty()) {
            return result;
        }

        for (CastLib castLib : castLibs.values()) {
            String castPath = castLib.getFileName();
            if (castPath == null || castPath.isEmpty()) continue;

            String castFileNoExt = normalizedBaseName(castPath);
            if (castFileNoExt.equals(fileNameNoExt)) {
                result.add(castLib);
            }
        }
        return result;
    }

    private static String normalizedBaseName(String path) {
        String fileName = FileUtil.getFileName(path);
        if (fileName == null || fileName.isEmpty()) {
            return "";
        }
        String baseName = FileUtil.getFileNameWithoutExtension(fileName);
        return baseName != null ? baseName.toLowerCase(Locale.ROOT) : "";
    }

    private static Datum getMemberByNameInCast(CastLib castLib, String memberName) {
        MemberNameCandidate candidate = getMemberByNameCandidate(castLib, memberName);
        return candidate != null ? candidate.ref() : Datum.VOID;
    }

    private record MemberNameCandidate(Datum ref) {}

    private static Datum getRegistryMemberByNameInCast(CastLib castLib, String memberName) {
        MemberNameCandidate candidate = getRegistryMemberByNameCandidate(castLib, memberName);
        return candidate != null ? candidate.ref() : Datum.VOID;
    }

    private static MemberNameCandidate getRegistryMemberByNameCandidate(CastLib castLib, String memberName) {
        if (castLib == null || memberName == null || memberName.isEmpty()) {
            return null;
        }
        if (!castLib.isLoaded()) {
            CastMember dynamic = castLib.findCachedMemberByNameExact(memberName);
            if (dynamic != null) {
                return new MemberNameCandidate(
                        Datum.CastMemberRef.of(castLib.getNumber(), dynamic.getMemberNumber()));
            }
            return null;
        }
        CastMember member = castLib.getRegistryMemberByName(memberName);
        if (member != null) {
            return new MemberNameCandidate(
                    Datum.CastMemberRef.of(castLib.getNumber(), member.getMemberNumber()));
        }
        return null;
    }

    private static MemberNameCandidate getMemberByNameCandidate(CastLib castLib, String memberName) {
        if (castLib == null || memberName == null || memberName.isEmpty()) {
            return null;
        }
        if (!castLib.isLoaded()) {
            CastMember dynamic = castLib.findCachedMemberByNameExact(memberName);
            if (dynamic != null) {
                return new MemberNameCandidate(
                        Datum.CastMemberRef.of(castLib.getNumber(), dynamic.getMemberNumber()));
            }
            return null;
        }
        CastMember member = castLib.getMemberByName(memberName);
        if (member != null) {
            return new MemberNameCandidate(
                    Datum.CastMemberRef.of(castLib.getNumber(), member.getMemberNumber()));
        }
        return null;
    }

    private static Datum getScriptMemberByNameInCast(CastLib castLib, String memberName) {
        if (castLib == null || memberName == null || memberName.isEmpty()) {
            return Datum.VOID;
        }
        if (!castLib.isLoaded()) {
            castLib.load();
        }
        for (var entry : castLib.getMemberChunks().entrySet()) {
            CastMemberChunk member = entry.getValue();
            if (member == null || member.name() == null
                    || !member.name().equalsIgnoreCase(memberName)) {
                continue;
            }
            int memberNumber = entry.getKey();
            if (castLib.getScript(memberNumber) != null) {
                return Datum.CastMemberRef.of(castLib.getNumber(), memberNumber);
            }
        }
        return Datum.VOID;
    }

    private static boolean isRegistryVisibleCast(CastLib castLib) {
        return castLib != null && castLib.usesStableRegistryBinding();
    }

    @Override
    public Datum createMember(int castLibNumber, String memberType) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return Datum.VOID;
        }
        CastMember member = castLib.createDynamicMember(memberType);
        if (member == null) {
            return Datum.VOID;
        }
        clearHandlerLookupCache();
        return Datum.CastMemberRef.of(castLibNumber, member.getMemberNumber());
    }

    @Override
    public boolean isRuntimeDynamicMember(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return false;
        }
        CastMember member = castLib.getMember(memberNumber);
        return member != null && member.isRuntimeDynamic();
    }

    @Override
    public com.libreshockwave.bitmap.Palette resolvePaletteByName(String name) {
        ensureInitialized();
        com.libreshockwave.bitmap.Palette exact = resolvePaletteByExactName(name);
        if (exact != null) {
            return exact;
        }

        String baseName = dynamicPaletteDuplicateBaseName(name);
        return baseName != null ? resolvePaletteByExactName(baseName) : null;
    }

    private com.libreshockwave.bitmap.Palette resolvePaletteByExactName(String name) {
        com.libreshockwave.bitmap.Palette firstMatch = null;

        for (CastLib castLib : castLibs.values()) {
            com.libreshockwave.chunks.CastMemberChunk chunk = castLib.findMemberByName(name);
            if (chunk != null && chunk.file() != null) {
                int memberNum = castLib.getMemberNumber(chunk);
                com.libreshockwave.bitmap.Palette pal =
                        memberNum > 0 ? chunk.file().resolvePaletteByMemberNumberExact(memberNum) : null;
                if (pal != null) {
                    if (firstMatch == null) {
                        firstMatch = pal;
                    }
                }
            }
            CastMember dynamic = castLib.getMemberByName(name);
            if (dynamic != null) {
                com.libreshockwave.bitmap.Palette pal = dynamic.getPaletteData();
                if (pal == null) {
                    pal = resolveDynamicPaletteDuplicate(dynamic);
                }
                if (pal != null) {
                    if (firstMatch == null) {
                        firstMatch = pal;
                    }
                }
            }
        }
        return firstMatch;
    }

    @Override
    public com.libreshockwave.bitmap.Palette resolvePaletteByMember(int castLibNum, int memberNum) {
        ensureInitialized();
        CastLib castLib = getCastLib(castLibNum);
        if (castLib == null) {
            return null;
        }
        CastMember dynamicMember = castLib.getMember(memberNum);
        if (dynamicMember != null) {
            com.libreshockwave.bitmap.Palette dynamicPalette = dynamicMember.getPaletteData();
            if (dynamicPalette != null) {
                return dynamicPalette;
            }
            com.libreshockwave.bitmap.Palette duplicateFallback = resolveDynamicPaletteDuplicate(dynamicMember);
            if (duplicateFallback != null) {
                return duplicateFallback;
            }
        }
        com.libreshockwave.chunks.CastMemberChunk chunk = castLib.findMemberByNumber(memberNum);
        if (chunk != null && chunk.file() != null) {
            return chunk.file().resolvePaletteByMemberNumberExact(memberNum);
        }
        return null;
    }

    private com.libreshockwave.bitmap.Palette resolveDynamicPaletteDuplicate(CastMember dynamicMember) {
        if (dynamicMember == null) {
            return null;
        }
        String baseName = dynamicPaletteDuplicateBaseName(dynamicMember.getName());
        if (baseName == null) {
            return null;
        }
        com.libreshockwave.bitmap.Palette sourcePalette = resolvePaletteByExactName(baseName);
        if (sourcePalette == null) {
            return null;
        }
        com.libreshockwave.bitmap.Palette copiedPalette = copyPalette(sourcePalette);
        dynamicMember.setPaletteData(copiedPalette);
        return copiedPalette;
    }

    private static String dynamicPaletteDuplicateBaseName(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        String suffix = null;
        if (lower.endsWith(DYNAMIC_PALETTE_DUPLICATE_SUFFIX.toLowerCase(Locale.ROOT))) {
            suffix = DYNAMIC_PALETTE_DUPLICATE_SUFFIX;
        } else if (lower.endsWith(COMPACT_DYNAMIC_PALETTE_DUPLICATE_SUFFIX.toLowerCase(Locale.ROOT))) {
            suffix = COMPACT_DYNAMIC_PALETTE_DUPLICATE_SUFFIX;
        }
        if (suffix == null) {
            return null;
        }
        String baseName = normalized.substring(0, normalized.length() - suffix.length()).trim();
        return baseName.isEmpty() ? null : baseName;
    }

    private static com.libreshockwave.bitmap.Palette copyPalette(com.libreshockwave.bitmap.Palette palette) {
        int[] colors = new int[palette.size()];
        for (int i = 0; i < colors.length; i++) {
            colors[i] = palette.getColor(i);
        }
        return new com.libreshockwave.bitmap.Palette(colors, palette.getName());
    }

    @Override
    public String getFieldValue(Object memberNameOrNum, int castId) {
        ensureInitialized();
        CastMember member = resolveFieldMember(memberNameOrNum, castId);
        if (member != null) {
            return resolveFieldText(member);
        }
        return "";
    }

    @Override
    public Datum getFieldDatum(Object memberNameOrNum, int castId) {
        ensureInitialized();
        CastMember member = resolveFieldMember(memberNameOrNum, castId);
        if (member == null) {
            return Datum.EMPTY_STRING;
        }
        return new Datum.FieldText(resolveFieldText(member), member.getCastLibNumber(), member.getMemberNumber());
    }

    @Override
    public Datum getFieldParsedValue(int castLibNumber, int memberNumber, com.libreshockwave.vm.LingoVM vm) {
        ensureInitialized();
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return Datum.VOID;
        }
        CastMember member = castLib.getMember(memberNumber);
        if (member == null) {
            return Datum.VOID;
        }
        String resolvedText = resolveFieldText(member);
        String memberText = member.getTextContent();
        if (!resolvedText.equals(memberText)) {
            return com.libreshockwave.vm.util.LingoValueParser.parseWithPartial(resolvedText, vm);
        }
        return member.getParsedTextValue(vm);
    }

    @Override
    public void setFieldValue(Object memberNameOrNum, int castId, String value) {
        ensureInitialized();
        CastMember member = resolveFieldMember(memberNameOrNum, castId);
        if (member != null) {
            member.setDynamicText(value);
        }
    }

    private CastMember resolveFieldMember(Object memberNameOrNum, int castId) {
        CastMember member = null;

        if (memberNameOrNum instanceof String name) {
            if (castId > 0) {
                CastLib castLib = getCastLib(castId);
                if (castLib != null) {
                    member = castLib.getMemberByName(name);
                }
            } else {
                for (CastLib castLib : castLibs.values()) {
                    if (!castLib.isLoaded()) {
                        castLib.load();
                    }
                    member = castLib.getMemberByName(name);
                    if (member != null) {
                        break;
                    }
                }
            }
        } else if (memberNameOrNum instanceof Integer num) {
            int effectiveCastId;
            int effectiveMemberNum;
            if (Math.abs(num) > 65535) {
                int slotValue = Math.abs(num);
                effectiveCastId = slotValue >> 16;
                effectiveMemberNum = slotValue & 0xFFFF;
            } else {
                effectiveMemberNum = Math.abs(num);
                effectiveCastId = castId > 0 ? castId : 1;
            }
            CastLib castLib = getCastLib(effectiveCastId);
            if (castLib != null) {
                member = castLib.getMember(effectiveMemberNum);
            }
        }

        return member;
    }

    private String resolveFieldText(CastMember member) {
        if (member == null) {
            return "";
        }

        return member.getTextContent();
    }

    /**
     * Find a handler by name across all cast libraries.
     * Searches loaded external casts for the handler.
     */
    @Override
    public HandlerLocation findHandler(String handlerName) {
        ensureInitialized();

        String normalizedHandlerName = LingoVM.normalizeLookupName(handlerName);
        if (globalHandlerLookupCache.containsKey(normalizedHandlerName)) {
            return globalHandlerLookupCache.get(normalizedHandlerName);
        }

        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) {
                // Only search loaded casts - don't trigger lazy load for handler search
                continue;
            }

            var defaultNames = castLib.getScriptNames();

            for (var script : castLib.getAllScripts()) {
                if (!isGlobalHandlerScriptType(script.getScriptType())) {
                    continue;
                }
                // Use per-script Lnam (each Lctx has its own lnamSectionId)
                var scriptNames = getPerScriptNames(script, defaultNames);
                if (scriptNames == null) {
                    continue;
                }
                var handler = script.findHandler(handlerName, scriptNames);
                if (handler != null) {
                    HandlerLocation location = new HandlerLocation(castLib.getNumber(), script, handler, scriptNames);
                    globalHandlerLookupCache.put(normalizedHandlerName, location);
                    return location;
                }
            }
        }

        globalHandlerLookupCache.put(normalizedHandlerName, null);
        return null;
    }

    static boolean isGlobalHandlerScriptType(com.libreshockwave.chunks.ScriptChunk.ScriptType scriptType) {
        return scriptType == com.libreshockwave.chunks.ScriptChunk.ScriptType.MOVIE_SCRIPT;
    }

    @Override
    public int getScriptChunkId(int castLibNumber, int memberNumber) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null || !castLib.isLoaded()) {
            return -1;
        }
        var script = castLib.getScript(memberNumber);
        return script != null ? script.id().value() : -1;
    }

    @Override
    public ScriptOrigin findScriptOrigin(int scriptChunkId) {
        ensureInitialized();
        if (scriptChunkId <= 0) {
            return null;
        }
        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) {
                continue;
            }
            ScriptOrigin origin = castLib.findScriptOrigin(scriptChunkId);
            if (origin != null) {
                return origin;
            }
        }
        return null;
    }

    /**
     * Find a handler in a specific script by its cast member number.
     * Used for method calls on script instances - only searches the instance's parent script.
     *
     * @param memberNumber The cast member number (not the script chunk ID)
     * @param handlerName The handler name to find
     * @return The handler location if found, null otherwise
     */
    @Override
    public HandlerLocation findHandlerInScript(int memberNumber, String handlerName) {
        ensureInitialized();

        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) {
                continue;
            }

            // Look up script by member number
            if (castLib.getScript(memberNumber) != null) {
                HandlerLocation location = findHandlerInLoadedScript(castLib, memberNumber, handlerName);
                if (location != null) {
                    return location;
                }
                // Found the script but no handler - don't continue searching
                return null;
            }
        }

        return null;
    }

    /**
     * Find a handler in a specific script in a specific cast library.
     * Used when we know both the cast lib number and member number.
     */
    public HandlerLocation findHandlerInScript(int castLibNumber, int memberNumber, String handlerName) {
        ensureInitialized();

        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null || !castLib.isLoaded()) {
            return null;
        }

        return findHandlerInLoadedScript(castLib, memberNumber, handlerName);
    }

    private HandlerLocation findHandlerInLoadedScript(CastLib castLib, int memberNumber, String handlerName) {
        String normalizedHandlerName = LingoVM.normalizeLookupName(handlerName);
        int scriptKey = handlerScriptKey(castLib.getNumber(), memberNumber);
        Map<String, HandlerLocation> cachedByName = handlerLookupCache.get(scriptKey);
        if (cachedByName != null && cachedByName.containsKey(normalizedHandlerName)) {
            return cachedByName.get(normalizedHandlerName);
        }

        var defaultNames = castLib.getScriptNames();
        if (defaultNames == null) {
            return null;
        }

        var script = castLib.getScript(memberNumber);
        if (script != null) {
            var scriptNames = getPerScriptNames(script, defaultNames);
            var handler = script.findHandler(handlerName, scriptNames);
            if (handler != null) {
                HandlerLocation location = new HandlerLocation(castLib.getNumber(), script, handler, scriptNames);
                handlerLookupCache
                        .computeIfAbsent(scriptKey, ignored -> new HashMap<>())
                        .put(normalizedHandlerName, location);
                return location;
            }
        }

        handlerLookupCache
                .computeIfAbsent(scriptKey, ignored -> new HashMap<>())
                .put(normalizedHandlerName, null);
        return null;
    }

    private static int handlerScriptKey(int castLibNumber, int memberNumber) {
        return (castLibNumber << 16) ^ (memberNumber & 0xFFFF);
    }

    public void clearHandlerLookupCache() {
        handlerLookupCache.clear();
        globalHandlerLookupCache.clear();
        memberByNameLookupCache.clear();
        registryMemberByNameLookupCache.clear();
        scriptPropertyNamesCache.clear();
    }

    /**
     * Check if all external casts have been loaded.
     * Returns true when every external cast library has reached the LOADED state.
     */
    public boolean areAllCastsLoaded() {
        ensureInitialized();
        for (CastLib castLib : castLibs.values()) {
            if (castLib.isExternal() && !castLib.isLoaded()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public java.util.List<String> getScriptPropertyNames(int castLibNumber, int memberNumber) {
        ensureInitialized();

        // If castLibNumber is 0, search all cast libs
        if (castLibNumber == 0) {
            for (CastLib castLib : castLibs.values()) {
                if (!castLib.isLoaded()) continue;
                var script = castLib.getScript(memberNumber);
                if (script != null && script.hasProperties()) {
                    int scriptKey = handlerScriptKey(castLib.getNumber(), memberNumber);
                    List<String> cached = scriptPropertyNamesCache.get(scriptKey);
                    if (cached != null) {
                        return cached;
                    }
                    var scriptNames = getPerScriptNames(script, castLib.getScriptNames());
                    List<String> names = List.copyOf(script.getPropertyNames(scriptNames));
                    scriptPropertyNamesCache.put(scriptKey, names);
                    return names;
                }
            }
            return java.util.List.of();
        }

        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null || !castLib.isLoaded()) {
            return java.util.List.of();
        }

        var script = castLib.getScript(memberNumber);
        if (script == null || !script.hasProperties()) {
            return java.util.List.of();
        }

        int scriptKey = handlerScriptKey(castLibNumber, memberNumber);
        List<String> cached = scriptPropertyNamesCache.get(scriptKey);
        if (cached != null) {
            return cached;
        }
        var scriptNames = getPerScriptNames(script, castLib.getScriptNames());
        List<String> names = List.copyOf(script.getPropertyNames(scriptNames));
        scriptPropertyNamesCache.put(scriptKey, names);
        return names;
    }

    /**
     * Get the per-script Lnam for a script, falling back to a default.
     */
    private static com.libreshockwave.chunks.ScriptNamesChunk getPerScriptNames(
            com.libreshockwave.chunks.ScriptChunk script,
            com.libreshockwave.chunks.ScriptNamesChunk defaultNames) {
        if (script.file() != null) {
            var names = script.file().getScriptNamesForScript(script);
            if (names != null) return names;
        }
        return defaultNames;
    }
}
