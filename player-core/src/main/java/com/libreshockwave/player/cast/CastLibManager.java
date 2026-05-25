package com.libreshockwave.player.cast;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.bitmap.Palette;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastChunk;
import com.libreshockwave.chunks.CastListChunk;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.id.SlotId;
import com.libreshockwave.util.FileUtil;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.LingoVM;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Manages cast libraries for the player.
 * Provides access to cast libraries and their members for Lingo scripts.
 * Cast libraries are lazily loaded when first accessed via castLib().
 */
public class CastLibManager implements CastLibProvider {

    private final DirectorFile file;
    private final Map<Integer, CastLib> castLibs = new LinkedHashMap<>();
    private final Map<Integer, Map<String, HandlerLocation>> handlerLookupCache = new HashMap<>();
    private final Map<Integer, List<String>> scriptPropertyNamesCache = new HashMap<>();
    private boolean initialized = false;
    private int lastResolvedFieldCastLib = -1;
    private int lastResolvedFieldMember = -1;

    // Callback for cast data loading: when Lingo sets castLib.fileName, this is called
    // with (castLibNumber, fileName). Can load data synchronously (JVM) or queue for
    // async delivery (WASM).
    private final BiConsumer<Integer, String> castDataRequestCallback;

    public CastLibManager(DirectorFile file, BiConsumer<Integer, String> castDataRequestCallback) {
        this.file = file;
        this.castDataRequestCallback = castDataRequestCallback;
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

        String basePath = file.getBasePath();

        if (castList != null && !castList.entries().isEmpty()) {
            boolean[] assignedCastChunks = new boolean[casts.size()];
            // Use CastListChunk entries as the source
            for (int i = 0; i < castList.entries().size(); i++) {
                CastListChunk.CastListEntry listEntry = castList.entries().get(i);

                // Cast lib number is 1-based index
                int castLibNumber = i + 1;

                CastChunk castChunk = findCastChunkForListEntry(casts, assignedCastChunks, listEntry);
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
            }
        } else if (!casts.isEmpty()) {
            // Fallback: use CastChunks directly if no cast list
            for (int i = 0; i < casts.size(); i++) {
                int castLibNumber = i + 1;
                CastLib castLib = new CastLib(castLibNumber, casts.get(i), null);
                castLib.setBasePath(basePath);
                castLib.setSourceFile(file);
                castLibs.put(castLibNumber, castLib);
            }
        }
    }

    private static CastChunk findCastChunkForListEntry(
            List<CastChunk> casts,
            boolean[] assignedCastChunks,
            CastListChunk.CastListEntry listEntry) {
        if (casts == null || casts.isEmpty() || listEntry == null) {
            return null;
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

    /**
     * Get a cast library by number, loading it if necessary.
     */
    public CastLib getCastLib(int castLibNumber) {
        ensureInitialized();

        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib != null && !castLib.isLoaded()) {
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

        boolean result = castLib.setProp(propName, value);

        // When authored Lingo sets castLib.fileName to a newly downloaded URL, reload
        // the cast from cached data to match Director's automatic reload behavior.
        // In real Director, setting castLib.fileName triggers an automatic reload.
        if (result && "filename".equalsIgnoreCase(propName)) {
            tryLoadCastFromCache(castLibNumber, value.toStr());
        }

        return result;
    }

    private void tryLoadCastFromCache(int castLibNumber, String newFileName) {
        if (newFileName == null || newFileName.isEmpty()) return;

        clearHandlerLookupCache();
        markPendingExternalLoad(castLibNumber, newFileName);

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
        return castLib.findMemberByNumber(memberNumber) != null
                || castLib.getCachedMember(memberNumber) != null;
    }

    @Override
    public boolean isRegistryVisibleMember(int castLibNumber, int memberNumber) {
        if (memberNumber <= 0) {
            return false;
        }
        CastLib castLib = getCastLib(castLibNumber);
        if (!isRegistryFallbackEligibleCast(castLib)) {
            return false;
        }
        return castLib.findMemberByNumber(memberNumber) != null
                || castLib.getCachedMember(memberNumber) != null;
    }

    @Override
    public Datum getMemberByName(int castLibNumber, String memberName) {
        ensureInitialized();
        memberName = unquoteLingoStringLiteral(memberName);
        if (castLibNumber > 0) {
            Datum found = getMemberByNameInCast(getCastLib(castLibNumber), memberName);
            rememberResolvedFieldMember(found);
            return found;
        } else {
            MemberNameCandidate firstPlaceholder = null;
            // Prefer the movie's stable/authored namespace first. Runtime-retargeted
            // scratch casts may legitimately contain members with colliding names,
            // but they should not hijack broad member("name") lookups while a
            // stable cast already exposes the same member.
            for (CastLib castLib : castLibs.values()) {
                CastLib loadedCast = castForMemberLookup(castLib);
                if (!isRegistryFallbackEligibleCast(loadedCast)) {
                    continue;
                }
                MemberNameCandidate found = getMemberByNameCandidate(loadedCast, memberName);
                if (found == null) {
                    continue;
                }
                if (!found.placeholderBitmap()) {
                    rememberResolvedFieldMember(found.ref());
                    return found.ref();
                }
                if (firstPlaceholder == null) {
                    firstPlaceholder = found;
                }
            }

            for (CastLib castLib : castLibs.values()) {
                MemberNameCandidate found = getMemberByNameCandidate(castForMemberLookup(castLib), memberName);
                if (found == null) {
                    continue;
                }
                if (!found.placeholderBitmap()) {
                    rememberResolvedFieldMember(found.ref());
                    return found.ref();
                }
                if (firstPlaceholder == null) {
                    firstPlaceholder = found;
                }
            }
            if (firstPlaceholder != null) {
                rememberResolvedFieldMember(firstPlaceholder.ref());
                return firstPlaceholder.ref();
            }
        }

        return Datum.VOID;
    }

    private void rememberResolvedFieldMember(Datum member) {
        if (member instanceof Datum.CastMemberRef ref) {
            lastResolvedFieldCastLib = ref.castLibNum();
            lastResolvedFieldMember = ref.memberNum();
        }
    }

    @Override
    public void rememberResolvedFieldMemberSlot(int slotValue) {
        if (slotValue == 0) {
            return;
        }
        SlotId slot = new SlotId(Math.abs(slotValue));
        if (slot.castLib() >= 1 && slot.member() >= 1) {
            lastResolvedFieldCastLib = slot.castLib();
            lastResolvedFieldMember = slot.member();
        }
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
            if (!isRegistryFallbackEligibleCast(loadedCast)) {
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
        if (castLibNumber > 0) {
            CastLib castLib = getCastLib(castLibNumber);
            if (!isRegistryFallbackEligibleCast(castLib)) {
                return Datum.VOID;
            }
            return getMemberByNameInCast(castLib, memberName);
        }

        for (CastLib castLib : castLibs.values()) {
            CastLib loadedCast = castForMemberLookup(castLib);
            if (!isRegistryFallbackEligibleCast(loadedCast)) {
                continue;
            }
            Datum found = getMemberByNameInCast(loadedCast, memberName);
            if (!found.isVoid()) {
                return found;
            }
        }
        return Datum.VOID;
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
        return castLib.getMemberProp(memberNumber, propName);
    }

    @Override
    public boolean setMemberProp(int castLibNumber, int memberNumber, String propName, Datum value) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return false;
        }
        return castLib.setMemberProp(memberNumber, propName, value);
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
        if ("duplicate".equalsIgnoreCase(methodName) && !args.isEmpty()) {
            Datum targetArg = args.get(0);
            Datum.CastMemberRef targetRef = null;

            if (targetArg instanceof Datum.CastMemberRef cmr) {
                targetRef = cmr;
            } else if (targetArg.isInt() || targetArg.isFloat()) {
                int slotValue = targetArg.toInt();
                SlotId slotId = new SlotId(slotValue);
                if (slotId.castLib() >= 1 && slotId.member() >= 1) {
                    Datum decodedRef = Datum.CastMemberRef.of(slotId.castLib(), slotId.member());
                    if (decodedRef instanceof Datum.CastMemberRef cmr) {
                        targetRef = cmr;
                    }
                } else if (castLibNumber >= 1 && slotValue >= 1) {
                    // Fallback for callers that pass a raw member number instead of member.number.
                    Datum fallbackRef = Datum.CastMemberRef.of(castLibNumber, slotValue);
                    if (fallbackRef instanceof Datum.CastMemberRef cmr) {
                        targetRef = cmr;
                    }
                }
            }

            if (targetRef == null) {
                return member.callMethod(methodName, args);
            }

            CastLib targetCastLib = getCastLib(targetRef.castLibNum());
            if (targetCastLib == null) {
                return Datum.VOID;
            }
            CastMember argumentMember = targetCastLib.getMember(targetRef.memberNum());
            if (argumentMember == null) {
                return Datum.VOID;
            }
            Palette receiverPalette = member.getPaletteData();
            if (receiverPalette != null) {
                argumentMember.setPaletteData(receiverPalette);
                return targetArg;
            }
            Palette argumentPalette = argumentMember.getPaletteData();
            if (argumentPalette != null) {
                member.setPaletteData(argumentPalette);
                return Datum.CastMemberRef.of(castLibNumber, memberNumber);
            }
        }
        return member.callMethod(methodName, args);
    }

    /**
     * Get a property for an invalid member reference.
     */

    /**
     * Get a cast member chunk directly.
     */
    public CastMemberChunk getCastMember(int castLibNumber, int memberNumber) {
        CastLib castLib = getCastLib(castLibNumber);
        if (castLib == null) {
            return null;
        }
        return castLib.findMemberByNumber(memberNumber);
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
            }
            CastMemberChunk chunk = castLib.findMemberByNumber(memberNumber);
            if (chunk != null && chunk.file() != null) {
                return chunk.file().resolvePaletteByMemberNumber(memberNumber);
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
            }
            CastMemberChunk chunk = cl.findMemberByNumber(memberNumber);
            if (chunk != null && chunk.file() != null) {
                return chunk.file().resolvePaletteByMemberNumber(memberNumber);
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
     * Searches dynamic members first (runtime-created), then file members wrapped as CastMember.
     * Returns null if not found.
     */
    public CastMember findCastMemberByName(String name) {
        ensureInitialized();

        // Search dynamic members first (runtime-created via new(#type, castLib))
        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) continue;
            CastMember dynamic = castLib.getMemberByName(name);
            if (dynamic != null) {
                return dynamic;
            }
        }

        // Search file members — return from CastLib.getMember() to get a full CastMember
        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) {
                castLib.load();
            }
            CastMemberChunk chunk = castLib.findMemberByName(name);
            if (chunk != null) {
                int memberNumber = castLib.getMemberNumber(chunk);
                CastMember member = castLib.getMember(memberNumber);
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
            if (!castLib.isLoaded()) {
                castLib.load();
            }
            CastMemberChunk member = castLib.findMemberByName(name);
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
    private final Map<String, byte[]> castDataCache = new HashMap<>();
    private final Map<Integer, String> pendingExternalLoads = new HashMap<>();

    /**
     * Cache raw external cast data by base name for later reuse.
     */
    public void cacheExternalData(String url, byte[] data) {
        for (String key : downloadCacheKeys(url)) {
            castDataCache.put(key, data);
        }
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
        for (String key : downloadCacheKeys(baseName)) {
            byte[] data = castDataCache.get(key);
            if (data != null) {
                return data;
            }
        }
        return null;
    }

    /**
     * Look up cached downloaded bytes by URL or file name.
     * Used by importFileInto for external image payloads as well as cast loading.
     */
    public byte[] getCachedDownloadedData(String url) {
        for (String key : downloadCacheKeys(url)) {
            byte[] data = castDataCache.get(key);
            if (data != null) {
                return data;
            }
        }
        return null;
    }

    private static Set<String> downloadCacheKeys(String url) {
        LinkedHashSet<String> keys = new LinkedHashSet<>();
        if (url == null || url.isEmpty()) {
            return keys;
        }
        String fileName = FileUtil.getFileName(url);
        if (fileName == null || fileName.isEmpty()) {
            return keys;
        }
        keys.add(fileName.toLowerCase(Locale.ROOT));
        String baseName = FileUtil.getFileNameWithoutExtension(fileName);
        if (baseName != null && !baseName.isEmpty()) {
            keys.add(baseName.toLowerCase(Locale.ROOT));
        }
        return keys;
    }

    public void clearPendingExternalLoad(int castLibNumber) {
        pendingExternalLoads.remove(castLibNumber);
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
        pendingExternalLoads.put(castLibNumber, normalizedBaseName(fileName));
    }

    /**
     * Set external cast data from preloadNetThing.
     * @param castLibNumber The cast library number
     * @param data The raw file data
     * @return true if parsing was successful
     */
    public boolean setExternalCastData(int castLibNumber, byte[] data) {
        ensureInitialized();
        CastLib castLib = castLibs.get(castLibNumber);
        if (castLib == null) {
            return false;
        }
        if (castLib.isLoaded() && castLib.hasFetchedExternalData(data)) {
            return true;
        }
        DirectorFile reusableSource = findReusableExternalSource(castLibNumber, data);
        boolean loaded = castLib.setExternalData(data, reusableSource);
        if (loaded) {
            clearPendingExternalLoad(castLibNumber);
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

    private boolean isRequestedExternalLoad(CastLib castLib, String url) {
        if (castLib == null) {
            return false;
        }
        String baseName = normalizedBaseName(url);
        if (baseName.isEmpty()) {
            return false;
        }
        String pending = pendingExternalLoads.get(castLib.getNumber());
        return castLib.isFetching() || baseName.equals(pending);
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

    private record MemberNameCandidate(Datum ref, boolean placeholderBitmap) {}

    private static MemberNameCandidate getMemberByNameCandidate(CastLib castLib, String memberName) {
        if (castLib == null || memberName == null || memberName.isEmpty()) {
            return null;
        }
        if (!castLib.isLoaded()) {
            CastMember dynamic = castLib.findCachedMemberByNameExact(memberName);
            if (dynamic != null) {
                return new MemberNameCandidate(
                        Datum.CastMemberRef.of(castLib.getNumber(), dynamic.getMemberNumber()),
                        false);
            }
            return null;
        }
        CastMemberChunk member = castLib.findMemberByName(memberName);
        if (member != null) {
            int memberNumber = castLib.getMemberNumber(member);
            if (memberNumber > 0) {
                Datum ref = Datum.CastMemberRef.of(castLib.getNumber(), memberNumber);
                return new MemberNameCandidate(ref, member.isBitmap()
                        && !castLib.hasVisibleBitmapContent(memberNumber));
            }
        }
        CastMember dynamic = castLib.getMemberByName(memberName);
        if (dynamic != null) {
            return new MemberNameCandidate(
                    Datum.CastMemberRef.of(castLib.getNumber(), dynamic.getMemberNumber()),
                    false);
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

    private static boolean isRegistryFallbackEligibleCast(CastLib castLib) {
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
        com.libreshockwave.bitmap.Palette firstMatch = null;

        for (CastLib castLib : castLibs.values()) {
            com.libreshockwave.chunks.CastMemberChunk chunk = castLib.findMemberByName(name);
            if (chunk != null && chunk.file() != null) {
                int memberNum = castLib.getMemberNumber(chunk);
                com.libreshockwave.bitmap.Palette pal =
                        memberNum > 0 ? chunk.file().resolvePaletteByMemberNumber(memberNum) : null;
                if (pal != null) {
                    if (firstMatch == null) {
                        firstMatch = pal;
                    }
                }
            }
            CastMember dynamic = castLib.getMemberByName(name);
            if (dynamic != null) {
                com.libreshockwave.bitmap.Palette pal = dynamic.getPaletteData();
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
        }
        com.libreshockwave.chunks.CastMemberChunk chunk = castLib.findMemberByNumber(memberNum);
        if (chunk != null && chunk.file() != null) {
            return chunk.file().resolvePaletteByMemberNumber(memberNum);
        }
        return null;
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
            if (num > 65535) {
                effectiveCastId = num >> 16;
                effectiveMemberNum = num & 0xFFFF;
            } else {
                effectiveCastId = castId > 0 ? castId : 1;
                effectiveMemberNum = num;
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

        for (CastLib castLib : castLibs.values()) {
            if (!castLib.isLoaded()) {
                // Only search loaded casts - don't trigger lazy load for handler search
                continue;
            }

            var defaultNames = castLib.getScriptNames();
            if (defaultNames == null) {
                continue;
            }

            for (var script : castLib.getAllScripts()) {
                if (!isGlobalHandlerScriptType(script.getScriptType())) {
                    continue;
                }
                // Use per-script Lnam (each Lctx has its own lnamSectionId)
                var scriptNames = getPerScriptNames(script, defaultNames);
                var handler = script.findHandler(handlerName, scriptNames);
                if (handler != null) {
                    return new HandlerLocation(castLib.getNumber(), script, handler, scriptNames);
                }
            }
        }

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
