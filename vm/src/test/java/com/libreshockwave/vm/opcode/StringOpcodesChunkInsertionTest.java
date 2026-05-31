package com.libreshockwave.vm.opcode;

import com.libreshockwave.lingo.StringChunkType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StringOpcodesChunkInsertionTest {

    @Test
    void puttingAfterCharZeroAppendsToEmptyAndExistingStrings() {
        assertEquals("abc", invokePuttingAfter("", StringChunkType.CHAR, 0, "abc"));
        assertEquals("zabc", invokePuttingAfter("z", StringChunkType.CHAR, 1, "abc"));
    }

    @Test
    void puttingBeforeAndAfterMissingChunksClampToStringEdges() {
        assertEquals("abcz", invokePuttingBefore("abc", StringChunkType.CHAR, 99, "z"));
        assertEquals("zabc", invokePuttingBefore("abc", StringChunkType.CHAR, 1, "z"));
        assertEquals("abcz", invokePuttingAfter("abc", StringChunkType.CHAR, 99, "z"));
    }

    @Test
    void puttingAfterMissingItemFallsBackToAppend() {
        assertEquals("a,b,z", invokePuttingAfter("a,b", StringChunkType.ITEM, 99, ",z"));
    }

    @Test
    void puttingIntoReturnDelimitedItemAcceptsLfAndCrLf() {
        assertEquals("first\nSECOND\r\nthird",
                invokePuttingInto("first\nsecond\r\nthird", StringChunkType.ITEM, 2, "SECOND", '\r'));
    }

    @Test
    void deletingReturnDelimitedItemConsumesTheActualLineBreak() {
        assertEquals("first\nthird",
                invokeDeleting("first\nsecond\r\nthird", StringChunkType.ITEM, 2, '\r'));
        assertEquals("first",
                invokeDeleting("first\r\nsecond", StringChunkType.ITEM, 2, '\r'));
    }

    @Test
    void puttingIntoMixedLineUsesActualLineRangeAndKeepsProtocolTerminator() {
        assertEquals("first\r\nSECOND\nthird\u0002",
                invokePuttingInto("first\r\nsecond\nthird\u0002", StringChunkType.LINE, 2, "SECOND", ','));
    }

    @Test
    void deletingFinalLineBeforeProtocolTerminatorConsumesPreviousLineBreak() {
        assertEquals("first\u0002",
                invokeDeleting("first\nsecond\u0002", StringChunkType.LINE, 2, ','));
    }

    private static String invokePuttingBefore(String str, StringChunkType type, int index, String value) {
        return invokeChunkMutation("stringByPuttingBeforeChunk", str, type, index, value, ',');
    }

    private static String invokePuttingAfter(String str, StringChunkType type, int index, String value) {
        return invokeChunkMutation("stringByPuttingAfterChunk", str, type, index, value, ',');
    }

    private static String invokePuttingInto(String str, StringChunkType type, int index, String value, char delimiter) {
        return invokeChunkMutation("stringByPuttingIntoChunk", str, type, index, value, delimiter);
    }

    private static String invokeDeleting(String str, StringChunkType type, int index, char delimiter) {
        try {
            Class<?> chunkExprClass = Class.forName("com.libreshockwave.vm.opcode.StringOpcodes$ChunkExpr");
            var constructor = chunkExprClass.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object chunkExpr = constructor.newInstance(type, index, index);

            Method method = StringOpcodes.class.getDeclaredMethod(
                    "stringByDeletingChunk",
                    String.class,
                    chunkExprClass,
                    char.class);
            method.setAccessible(true);
            return (String) method.invoke(null, str, chunkExpr, delimiter);
        } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException | InstantiationException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private static String invokeChunkMutation(String methodName, String str, StringChunkType type,
                                              int index, String value, char delimiter) {
        try {
            Class<?> chunkExprClass = Class.forName("com.libreshockwave.vm.opcode.StringOpcodes$ChunkExpr");
            var constructor = chunkExprClass.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object chunkExpr = constructor.newInstance(type, index, index);

            Method method = StringOpcodes.class.getDeclaredMethod(
                    methodName,
                    String.class,
                    chunkExprClass,
                    String.class,
                    char.class);
            method.setAccessible(true);
            return (String) method.invoke(null, str, chunkExpr, value, delimiter);
        } catch (NoSuchMethodException | IllegalAccessException | ClassNotFoundException | InstantiationException e) {
            throw new AssertionError(e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }
}
