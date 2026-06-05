package com.libreshockwave.vm.opcode;

import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.builtin.movie.MoviePropertyProvider;
import com.libreshockwave.vm.builtin.sprite.SpritePropertyProvider;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.support.NoOpCastLibProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PropertyOpcodesTest {

    @AfterEach
    void tearDown() {
        CastLibProvider.clearProvider();
        MoviePropertyProvider.clearProvider();
        SpritePropertyProvider.clearProvider();
    }

    @Test
    void invalidCastMemberRefReportsZeroNumber() throws Exception {
        CastLibProvider.setProvider(new StubCastLibProvider(false));

        Datum.CastMemberRef ref = (Datum.CastMemberRef) Datum.CastMemberRef.of(11, 7);

        assertEquals(0, getCastMemberProp(ref, "number").toInt());
        assertEquals(0, getCastMemberProp(ref, "memberNum").toInt());
        assertEquals(11, getCastMemberProp(ref, "castLibNum").toInt());
    }

    @Test
    void validCastMemberRefKeepsEncodedNumber() throws Exception {
        CastLibProvider.setProvider(new StubCastLibProvider(true));

        Datum.CastMemberRef ref = (Datum.CastMemberRef) Datum.CastMemberRef.of(11, 7);

        assertEquals((11 << 16) | 7, getCastMemberProp(ref, "number").toInt());
        assertEquals(7, getCastMemberProp(ref, "memberNum").toInt());
        assertEquals(11, getCastMemberProp(ref, "castLibNum").toInt());
    }

    @Test
    void emptyMemberRefReportsZeroNumber() throws Exception {
        CastLibProvider.setProvider(new StubCastLibProvider(true));

        Datum.CastMemberRef ref = (Datum.CastMemberRef) Datum.CastMemberRef.of(11, 0);

        assertEquals(0, getCastMemberProp(ref, "number").toInt());
        assertEquals(0, getCastMemberProp(ref, "memberNum").toInt());
        assertEquals(11, getCastMemberProp(ref, "castLibNum").toInt());
    }

    @Test
    void castMemberChunkPropertyKeepsMemberIdentity() throws Exception {
        CastLibProvider.setProvider(new StubCastLibProvider(true));

        Datum.CastMemberRef ref = (Datum.CastMemberRef) Datum.CastMemberRef.of(11, 7);

        assertEquals(new Datum.TextMemberChunkAccessor(11, 7, "char"),
                getCastMemberProp(ref, "char"));
    }

    @Test
    void textMemberRangeTextPropertyReadsThroughCastProvider() throws Exception {
        CastLibProvider.setProvider(new StubCastLibProvider(true) {
            @Override
            public Datum getMemberTextRangeProp(int castLibNumber, int memberNumber,
                                                String chunkType, int start, int end,
                                                String propName) {
                if (castLibNumber == 11 && memberNumber == 7
                        && "line".equals(chunkType) && start == 1 && end == 1
                        && "text".equalsIgnoreCase(propName)) {
                    return Datum.of("Large TV");
                }
                return Datum.VOID;
            }
        });

        Datum range = new Datum.TextMemberRangeRef(11, 7, "line", 1, 1);

        assertEquals("Large TV", getObjectProperty(range, "text").toStr());
    }

    @Test
    void textMemberChunkAccessorNumericPropertyBuildsRangeReference() throws Exception {
        Datum accessor = new Datum.TextMemberChunkAccessor(11, 7, "line");

        assertEquals(new Datum.TextMemberRangeRef(11, 7, "line", 1, 1),
                getObjectProperty(accessor, "1"));
    }

    @Test
    void playerRefGetsMovieBackedProperties() throws Exception {
        StubMovieProvider provider = new StubMovieProvider();
        provider.movieProps.put("activewindow", Datum.STAGE);
        MoviePropertyProvider.setProvider(provider);

        assertEquals(Datum.STAGE, getPlayerProp("activeWindow"));
    }

    @Test
    void playerRefConstantsDoNotRequireMovieProvider() throws Exception {
        assertEquals(Datum.TRUE, getPlayerProp("true"));
    }

    @Test
    void playerRefSetsMovieBackedProperties() throws Exception {
        StubMovieProvider provider = new StubMovieProvider();
        MoviePropertyProvider.setProvider(provider);

        setPlayerProp("traceScript", Datum.TRUE);

        assertEquals(Datum.TRUE, provider.setProps.get("tracescript"));
    }

    @Test
    void pointAndRectExposeIlkProperties() throws Exception {
        assertEquals(Datum.symbol("point"), getPointProp(new Datum.Point(1, 2), "ilk"));
        assertEquals(Datum.symbol("rect"), getRectProp(new Datum.Rect(0, 0, 10, 20), "ilk"));
    }

    @Test
    void propListObjectPropertiesExposeSymbolKeys() throws Exception {
        Datum.PropList dateParts = new Datum.PropList();
        dateParts.add("day", Datum.of(22), true);
        dateParts.add("month", Datum.of(5), true);
        dateParts.add("year", Datum.of(2026), true);

        assertEquals(22, getObjectProperty(dateParts, "day").toInt());
        assertEquals(5, getObjectProperty(dateParts, "month").toInt());
        assertEquals(2026, getObjectProperty(dateParts, "year").toInt());
    }

    @Test
    void propListObjectPropertiesUseDirectorPropertyNameLookup() throws Exception {
        Datum.PropList stringKeyedMetrics = new Datum.PropList();
        stringKeyedMetrics.add("color", new Datum.Color(238, 238, 238), false);

        assertEquals(0xFFEEEEEE, Datum.datumToArgb(getObjectProperty(stringKeyedMetrics, "color")));

        Datum.PropList hashPrefixedStringKey = new Datum.PropList();
        hashPrefixedStringKey.putTyped("#color", false, Datum.of("not-a-symbol-key"));

        assertEquals(Datum.VOID, getObjectProperty(hashPrefixedStringKey, "color"));
    }

    @Test
    void propListIlkPropertyCanExposeAuthoredStructMarker() throws Exception {
        Datum.PropList structLike = new Datum.PropList();
        structLike.add("ilk", Datum.symbol("struct"), true);

        assertEquals("struct", getObjectProperty(structLike, "ilk").toKeyName());
        assertEquals("struct", getChainedObjProp(structLike, "ilk").toKeyName());
        assertEquals("struct", PropertyOpcodes.resolveTheBuiltin(
                "ilk",
                new Datum.ArgList(List.of(structLike)),
                null).toKeyName());
    }

    @Test
    void propListIlkPropertyFallsBackToBuiltinTypeWhenNoStoredIlkExists() throws Exception {
        Datum.PropList propList = new Datum.PropList();
        propList.add("name", Datum.of("value"), true);

        assertEquals("propList", getObjectProperty(propList, "ilk").toKeyName());
        assertEquals("propList", getChainedObjProp(propList, "ilk").toKeyName());
    }

    @Test
    void numericConversionPropertiesCoerceBeforeSpriteProviderLookup() throws Exception {
        StubSpriteProvider provider = new StubSpriteProvider();
        provider.props.put("42:bgcolor", Datum.of(0x6794A7));
        provider.props.put("2147418139:string", Datum.of("sprite-string"));
        provider.props.put("3:integer", Datum.of(99));
        SpritePropertyProvider.setProvider(provider);

        assertEquals("2147418139", getObjectProperty(Datum.of(2147418139), "string").toStr());
        assertEquals(3, getObjectProperty(Datum.of(3), "integer").toInt());
        assertEquals(3.0, getObjectProperty(Datum.of(3), "float").toDouble());
        assertEquals(4, getObjectProperty(Datum.of(3.5), "integer").toInt());
        assertEquals(-1, getObjectProperty(Datum.of(-0.5), "integer").toInt());
        assertEquals("3.5", getObjectProperty(Datum.of(3.5), "string").toStr());
        assertEquals(0x6794A7, getObjectProperty(Datum.of(42), "bgColor").toInt());
    }

    @Test
    void theBuiltinPrefersReceiverPropertyForSingleArgument() {
        Datum.PropList roomData = new Datum.PropList();
        roomData.add("type", Datum.symbol("private"), true);

        Datum result = PropertyOpcodes.resolveTheBuiltin(
                "type",
                new Datum.ArgList(List.of(roomData)),
                null);

        assertEquals("private", result.toKeyName());
    }

    private static Datum getCastMemberProp(Datum.CastMemberRef ref, String propName) throws Exception {
        Method method = PropertyOpcodes.class.getDeclaredMethod("getCastMemberProp", Datum.CastMemberRef.class, String.class);
        method.setAccessible(true);
        return (Datum) method.invoke(null, ref, propName);
    }

    private static Datum getPlayerProp(String propName) throws Exception {
        Method method = PropertyOpcodes.class.getDeclaredMethod("getPlayerProp", String.class);
        method.setAccessible(true);
        return (Datum) method.invoke(null, propName);
    }

    private static Datum getPointProp(Datum.Point point, String propName) throws Exception {
        Method method = PropertyOpcodes.class.getDeclaredMethod("getPointProp", Datum.Point.class, String.class);
        method.setAccessible(true);
        return (Datum) method.invoke(null, point, propName);
    }

    private static Datum getRectProp(Datum.Rect rect, String propName) throws Exception {
        Method method = PropertyOpcodes.class.getDeclaredMethod("getRectProp", Datum.Rect.class, String.class);
        method.setAccessible(true);
        return (Datum) method.invoke(null, rect, propName);
    }

    private static Datum getObjectProperty(Datum value, String propName) throws Exception {
        Method method = PropertyOpcodes.class.getDeclaredMethod(
                "getObjectProperty", Datum.class, String.class, ExecutionContext.class);
        method.setAccessible(true);
        return (Datum) method.invoke(null, value, propName, null);
    }

    private static Datum getChainedObjProp(Datum value, String propName) throws Exception {
        Method method = PropertyOpcodes.class.getDeclaredMethod(
                "getChainedObjProp", Datum.class, String.class);
        method.setAccessible(true);
        return (Datum) method.invoke(null, value, propName);
    }

    private static void setPlayerProp(String propName, Datum value) throws Exception {
        Method method = PropertyOpcodes.class.getDeclaredMethod("setPlayerProp", String.class, Datum.class);
        method.setAccessible(true);
        method.invoke(null, propName, value);
    }

    private static class StubCastLibProvider extends NoOpCastLibProvider {
        private final boolean memberExists;

        private StubCastLibProvider(boolean memberExists) {
            this.memberExists = memberExists;
        }

        @Override
        public boolean memberExists(int castLibNumber, int memberNumber) {
            return memberExists && memberNumber > 0;
        }
    }

    private static final class StubMovieProvider implements MoviePropertyProvider {
        private final Map<String, Datum> movieProps = new HashMap<>();
        private final Map<String, Datum> setProps = new HashMap<>();

        @Override
        public Datum getMovieProp(String propName) {
            return movieProps.getOrDefault(propName.toLowerCase(), Datum.VOID);
        }

        @Override
        public boolean setMovieProp(String propName, Datum value) {
            setProps.put(propName.toLowerCase(), value);
            return true;
        }
    }

    private static final class StubSpriteProvider implements SpritePropertyProvider {
        private final Map<String, Datum> props = new HashMap<>();

        @Override
        public Datum getSpriteProp(int spriteNum, String propName) {
            return props.getOrDefault(spriteNum + ":" + propName.toLowerCase(), Datum.VOID);
        }

        @Override
        public boolean setSpriteProp(int spriteNum, String propName, Datum value) {
            props.put(spriteNum + ":" + propName.toLowerCase(), value);
            return true;
        }
    }
}
