package com.libreshockwave.vm.builtin.cast;

import com.libreshockwave.vm.LingoVM;
import com.libreshockwave.vm.datum.Datum;

/**
 * Interface for cast library access.
 * Implemented by Player in player-core to provide access to cast libraries.
 *
 * Cast libraries contain cast members (bitmaps, scripts, sounds, etc.)
 * and can be accessed via castLib(number) or castLib("name") in Lingo.
 */
public interface CastLibProvider {

    /**
     * Get a cast library by number.
     * @param castLibNumber 1-based cast library number
     * @return The cast library number if found, or -1 if not found
     */
    int getCastLibByNumber(int castLibNumber);

    /**
     * Get a cast library by name.
     * @param name The cast library name
     * @return The cast library number if found, or -1 if not found
     */
    int getCastLibByName(String name);

    /**
     * Get a cast library property.
     * @param castLibNumber The cast library number
     * @param propName The property name (name, fileName, number, preloadMode, etc.)
     * @return The property value
     */
    Datum getCastLibProp(int castLibNumber, String propName);

    /**
     * Set a cast library property.
     * @param castLibNumber The cast library number
     * @param propName The property name
     * @param value The value to set
     * @return true if set successfully
     */
    boolean setCastLibProp(int castLibNumber, String propName, Datum value);

    /**
     * Get a cast member reference.
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @return A CastMemberRef datum, or VOID if not found
     */
    Datum getMember(int castLibNumber, int memberNumber);

    /**
     * Get a cast member by name.
     * @param castLibNumber The cast library number (0 to search all)
     * @param memberName The member name
     * @return A CastMemberRef datum, or VOID if not found
     */
    Datum getMemberByName(int castLibNumber, String memberName);

    /**
     * Get a script cast member by name.
     *
     * Director's script("Name") constructor namespace is narrower than
     * member("Name"): field/bitmap data members can share names with parent
     * scripts and must not satisfy script construction.
     */
    default Datum getScriptMemberByName(int castLibNumber, String memberName) {
        Datum member = getMemberByName(castLibNumber, memberName);
        if (member instanceof Datum.CastMemberRef ref) {
            Datum type = getMemberProp(ref.castLibNum(), ref.memberNum(), "type");
            if (type instanceof Datum.Symbol symbol && "script".equalsIgnoreCase(symbol.name())) {
                return member;
            }
        }
        return Datum.VOID;
    }

    /**
     * Get a cast member by name for registry-style lookups.
     * This is narrower than getMemberByName(0, ...): it should only expose
     * members that are already part of the movie's stable global namespace.
     * Temporary runtime-retargeted cast slots should remain invisible here
     * until authored movie code explicitly registers or copies them.
     *
     * The default falls back to the broader lookup so existing providers keep
     * their current behavior unless they need stricter registry visibility.
     */
    default Datum getRegistryMemberByName(int castLibNumber, String memberName) {
        return getMemberByName(castLibNumber, memberName);
    }

    /**
     * Check whether a specific member slot should remain visible through
     * registry-style lookups such as movie-owned getmemnum()/exists() APIs.
     *
     * This is narrower than plain memberExists(): temporary import/scratch
     * casts may be live Director members while still being intentionally
     * excluded from the movie's stable registry namespace.
     */
    default boolean isRegistryVisibleMember(int castLibNumber, int memberNumber) {
        return memberExists(castLibNumber, memberNumber);
    }

    /**
     * Resolve a raw member number stored by a movie-owned member registry into a
     * full cast/member slot when the provider can do so without relying on a
     * broad name lookup. Some Director movies persist plain member numbers in
     * registry propLists, then later load additional casts whose member names can
     * collide. Returning 0 means the raw value should be kept as authored.
     */
    default int resolveRawRegistryMemberSlot(String registryName, int memberNumber) {
        return 0;
    }

    /**
     * Get the number of cast libraries.
     */
    int getCastLibCount();

    /**
     * Get a cast member property.
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @param propName The property name (name, type, width, height, etc.)
     * @return The property value
     */
    Datum getMemberProp(int castLibNumber, int memberNumber, String propName);

    /**
     * Get a property from a styled text member range such as member.line[1].
     * The range coordinates are 1-based Director chunk coordinates.
     */
    default Datum getMemberTextRangeProp(int castLibNumber, int memberNumber,
                                         String chunkType, int start, int end,
                                         String propName) {
        return Datum.VOID;
    }

    /**
     * Set a cast member property.
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @param propName The property name
     * @param value The value to set
     * @return true if set successfully
     */
    boolean setMemberProp(int castLibNumber, int memberNumber, String propName, Datum value);

    /**
     * Set a property on a styled text member range such as member.char[1..5].color.
     * The range coordinates are 1-based Director chunk coordinates.
     */
    default boolean setMemberTextRangeProp(int castLibNumber, int memberNumber,
                                           String chunkType, int start, int end,
                                           String propName, Datum value) {
        return false;
    }

    /**
     * Import an external file into an existing cast member.
     * Runtime environments may decode and assign media here; returning true also
     * acknowledges formats that cannot yet be decoded so Director download
     * scripts can complete their bookkeeping.
     */
    default boolean importFileIntoMember(int castLibNumber, int memberNumber, String url, Datum options) {
        return memberExists(castLibNumber, memberNumber);
    }

    default String getCastLibName(int castLibNumber) {
        return null;
    }

    default String getCastLibFileName(int castLibNumber) {
        return null;
    }

    /**
     * Get the text content of a field (text cast member).
     * Used by GET_FIELD opcode for "field memberName" syntax.
     * @param memberNameOrNum The member name (string) or number
     * @param castId The cast library (0 for default/all casts)
     * @return The field text content, or empty string if not found
     */
    default String getFieldValue(Object memberNameOrNum, int castId) {
        return "";
    }

    /**
     * Get a field value as a string-like Datum.
     * Allows the runtime to preserve source member identity for caching while
     * keeping Director-visible field() semantics string-based.
     */
    default Datum getFieldDatum(Object memberNameOrNum, int castId) {
        return Datum.of(getFieldValue(memberNameOrNum, castId));
    }

    /**
     * Get a parsed Datum for a field member's text content.
     * Used by value(field(...)) to reuse the same parser/cache path generically.
     */
    default Datum getFieldParsedValue(int castLibNumber, int memberNumber, LingoVM vm) {
        return Datum.VOID;
    }

    /**
     * Set the text content of a field (text cast member).
     * Used by SET_FIELD opcode for "put ... into field N" syntax.
     * @param memberNameOrNum The member name (string) or number
     * @param castId The cast library (0 for default/all casts)
     * @param value The text value to set
     */
    default void setFieldValue(Object memberNameOrNum, int castId, String value) {
    }

    /**
     * Fetch an external cast library synchronously.
     * @param castLibNumber The cast library number
     * @return true if fetch was successful
     */
    default boolean fetchCastLib(int castLibNumber) {
        return false;
    }

    /**
     * Check if a cast library is external.
     * @param castLibNumber The cast library number
     * @return true if external
     */
    default boolean isCastLibExternal(int castLibNumber) {
        return false;
    }

    /**
     * Preload casts based on preloadMode.
     * Called during movie initialization.
     * @param mode 1 for AfterFrameOne, 2 for BeforeFrameOne (MovieLoaded)
     */
    default void preloadCasts(int mode) {
        // Default: do nothing
    }

    /**
     * Get the number of members in a specific cast library.
     * @param castLibNumber The cast library number
     * @return The member count, or 0 if not found
     */
    default int getMemberCount(int castLibNumber) {
        return 0;
    }

    /**
     * Check if a member exists in a specific cast library.
     * Unlike getMember() which always returns a CastMemberRef,
     * this returns true only if the member is actually present.
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @return true if the member exists
     */
    default boolean memberExists(int castLibNumber, int memberNumber) {
        return false;
    }

    /**
     * Return true when the slot is a runtime-created Director member rather than
     * an authored cast member. Runtime resource managers use this distinction
     * when a generated buffer name collides with a pre-indexed authored member.
     */
    default boolean isRuntimeDynamicMember(int castLibNumber, int memberNumber) {
        return false;
    }

    /**
     * Get the declared property names for a script member.
     * Used to pre-initialize script instance properties to VOID.
     * @param castLib The cast library number
     * @param member The member number
     * @return List of property names declared in the script, or empty list
     */
    default java.util.List<String> getScriptPropertyNames(int castLib, int member) {
        return java.util.List.of();
    }

    /**
     * Create a new cast member of the given type in a cast library.
     * Used by Director's new(#type, castLib) syntax.
     * @param castLibNumber The cast library number
     * @param memberType The member type name (e.g. "field", "text", "bitmap")
     * @return A CastMemberRef datum, or VOID if creation failed
     */
    default Datum createMember(int castLibNumber, String memberType) { return Datum.VOID; }

    /** Resolve a palette cast member by name. Returns null if not found. */
    default com.libreshockwave.bitmap.Palette resolvePaletteByName(String name) { return null; }

    /** Resolve a palette cast member by library and member number. Returns null if not found. */
    default com.libreshockwave.bitmap.Palette resolvePaletteByMember(int castLib, int member) { return null; }

    /**
     * Get the Lscr chunk ID for a script at a specific cast lib and member number.
     * Used to compare script identity (e.g. in findAncestorForCall).
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @return The Lscr chunk ID, or -1 if not found
     */
    default int getScriptChunkId(int castLibNumber, int memberNumber) {
        return -1;
    }

    /**
     * Find a handler by name across all cast libraries.
     * Used by LingoVM to locate handlers in external casts.
     * @param handlerName The handler name to find
     * @return A HandlerLocation with script info, or null if not found
     */
    default HandlerLocation findHandler(String handlerName) {
        return null;
    }

    /**
     * Find a handler in a specific script by its script ID (cast member number).
     * Used for method calls on script instances.
     * @param scriptId The script's cast member number
     * @param handlerName The handler name to find
     * @return A HandlerLocation if found in that script, or null
     */
    default HandlerLocation findHandlerInScript(int scriptId, String handlerName) {
        return null;
    }

    /**
     * Find a handler in a specific script in a specific cast library.
     * More precise than findHandlerInScript(int, String) when cast lib number is known.
     * @param castLibNumber The cast library number
     * @param memberNumber The script's cast member number
     * @param handlerName The handler name to find
     * @return A HandlerLocation if found in that script, or null
     */
    default HandlerLocation findHandlerInScript(int castLibNumber, int memberNumber, String handlerName) {
        // Default falls back to searching all cast libs
        return findHandlerInScript(memberNumber, handlerName);
    }

    /**
     * Represents a handler location in a cast library.
     */
    record HandlerLocation(
        int castLibNumber,
        Object script,          // ScriptChunk
        Object handler,         // ScriptChunk.Handler
        Object scriptNames      // ScriptNamesChunk
    ) {}

    /**
     * Call a method on a cast member.
     * Used for member methods like charPosToLoc.
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @param methodName The method name
     * @param args The method arguments
     * @return The method result
     */
    default Datum callMemberMethod(int castLibNumber, int memberNumber,
                                    String methodName, java.util.List<Datum> args) {
        return Datum.VOID;
    }

    /**
     * Get the palette from a palette cast member.
     * Used by image() to attach custom palettes to 8-bit images.
     * @param castLibNumber The cast library number
     * @param memberNumber The member number
     * @return The Palette, or null if not a palette member or not found
     */
    default com.libreshockwave.bitmap.Palette getMemberPalette(int castLibNumber, int memberNumber) {
        return null;
    }

    final class Holder {
        private Holder() {}
        static CastLibProvider current;
    }

    static void setProvider(CastLibProvider provider) {
        Holder.current = provider;
    }

    static void clearProvider() {
        Holder.current = null;
    }

    static CastLibProvider getProvider() {
        return Holder.current;
    }
}
