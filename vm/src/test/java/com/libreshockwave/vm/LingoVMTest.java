package com.libreshockwave.vm;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.lingo.Opcode;
import com.libreshockwave.vm.builtin.cast.CastLibProvider;
import com.libreshockwave.vm.builtin.flow.UpdateProvider;
import com.libreshockwave.vm.builtin.movie.MoviePropertyProvider;
import com.libreshockwave.vm.builtin.net.ExternalParamProvider;
import com.libreshockwave.vm.HandlerRef;
import com.libreshockwave.vm.datum.Datum;
import com.libreshockwave.vm.datum.DatumFormatter;
import com.libreshockwave.vm.datum.LingoException;
import com.libreshockwave.vm.support.NoOpCastLibProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;

/**
 * Unit tests for the Lingo VM.
 */
class LingoVMTest {

    @Test
    void rectBuiltinReturnsImageBounds() {
        LingoVM vm = new LingoVM(null);
        Bitmap bitmap = new Bitmap(124, 11, 32);

        Datum result = vm.callHandler("rect", List.of(new Datum.ImageRef(bitmap)));

        assertEquals(new Datum.Rect(0, 0, 124, 11), result);
    }

    @Test
    void unsetBareGlobalNamesResolveDirectorConstants() {
        LingoVM vm = new LingoVM(null);

        assertEquals("", vm.getGlobal("EMPTY").toStr());
        assertEquals("\r", vm.getGlobal("RETURN").toStr());
        assertEquals(1, vm.getGlobal("TRUE").toInt());
        assertTrue(vm.getGlobal("definitely_not_a_constant").isVoid());
    }

    @Test
    void tryCatchBuiltinsClearAndReportErrorState() {
        LingoVM vm = new LingoVM(null);
        vm.setErrorState(true);

        Datum tryResult = vm.callBuiltin("try", List.of());

        assertTrue(tryResult.isVoid());
        assertFalse(vm.isInErrorState());

        vm.setErrorState(true);
        Datum catchResult = vm.callBuiltin("catch", List.of());

        assertEquals(1, catchResult.toInt());
        assertFalse(vm.isInErrorState());
        assertEquals(0, vm.callBuiltin("catch", List.of()).toInt());
    }

    @Test
    void authoredDisconnectPutDoesNotEnterScriptErrorPause() {
        LingoVM vm = new LingoVM(null);
        AtomicInteger errors = new AtomicInteger();
        vm.setTraceListener(new TraceListener() {
            @Override
            public boolean needsHandlerTrace() {
                return false;
            }

            @Override
            public boolean needsInstructionTrace() {
                return false;
            }

            @Override
            public void onError(String message, Exception error) {
                errors.incrementAndGet();
            }
        });

        DebugConfig.setDebugPlaybackEnabled(true);
        DebugConfig.setPauseOnScriptErrorEnabled(true);
        try {
            vm.callHandler("put", List.of(Datum.of(
                    "Error: Time: 10:57:37 PM Method: disconnect Object: Message: Connection disconnected: info")));

            assertFalse(vm.isInErrorState(),
                    "authored disconnect dialogs are normal hotel state, not VM script crashes");
            assertEquals(0, errors.get());
        } finally {
            DebugConfig.setDebugPlaybackEnabled(false);
            DebugConfig.setPauseOnScriptErrorEnabled(false);
            DebugConfig.setPauseOnAuthoredMajorEnabled(false);
        }
    }

    @Test
    void testBuiltinSetCursorAliasesCursor() {
        LingoVM vm = new LingoVM(null);
        RecordingMovieProvider provider = new RecordingMovieProvider();
        MoviePropertyProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("setcursor", List.of(Datum.symbol("timer")));
            assertTrue(result.isVoid());
            assertEquals("cursor", provider.lastPropName);
            assertEquals("timer", provider.lastValue.toStr());
        } finally {
            MoviePropertyProvider.clearProvider();
        }
    }

    @Test
    void testDoubleClickBuiltinReadsMovieProperty() {
        LingoVM vm = new LingoVM(null);
        RecordingMovieProvider provider = new RecordingMovieProvider();
        provider.doubleClickValue = Datum.TRUE;
        MoviePropertyProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("doubleClick", List.of());

            assertEquals(1, result.toInt());
            assertEquals("doubleClick", provider.lastReadPropName);
        } finally {
            MoviePropertyProvider.clearProvider();
        }
    }

    @Test
    void classicInputFunctionBuiltinsReadMovieProperties() {
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
    void rolloverFunctionReturnsCurrentChannelOrTestsSpriteArgument() {
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

    @Test
    void getMonotonicMillisBuiltinReadsMovieMilliseconds() {
        LingoVM vm = new LingoVM(null);
        RecordingMovieProvider provider = new RecordingMovieProvider();
        provider.millisecondsValue = Datum.of(1234);
        MoviePropertyProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("getMonotonicMillis", List.of());

            assertEquals(1234, result.toInt());
            assertEquals("milliseconds", provider.lastReadPropName);
        } finally {
            MoviePropertyProvider.clearProvider();
        }
    }

    @Test
    void testGlobalVariables() {
        // Create a VM with a null file (we test globals without a file)
        LingoVM vm = new LingoVM(null);

        // Initially empty
        assertTrue(vm.getGlobals().isEmpty());

        // Set and get
        vm.setGlobal("score", Datum.of(100));
        assertEquals(100, vm.getGlobal("score").toInt());
        assertEquals(100, vm.getGlobal("SCORE").toInt());

        vm.setGlobal("gCore", Datum.of("object-manager"));
        assertEquals("object-manager", vm.getGlobal("gcore").toStr());
        assertEquals("object-manager", vm.getGlobal("GCORE").toStr());

        // Unknown globals return VOID
        assertTrue(vm.getGlobal("unknown").isVoid());

        // Clear
        vm.clearGlobals();
        assertTrue(vm.getGlobals().isEmpty());
    }

    @Test
    void testPrefsAreCaseInsensitiveAndMissingReturnsVoid() {
        LingoVM vm = new LingoVM(null);

        assertTrue(vm.getPref("Blocktime").isVoid());

        Datum stored = vm.setPref("blocktime", Datum.of("123"));
        assertEquals("123", stored.toStr());

        Datum fetched = vm.getPref("Blocktime");
        assertTrue(fetched.isString());
        assertEquals("123", fetched.toStr());
    }

    @Test
    void testBuiltinPrefsUseVmPreferenceStorage() {
        LingoVM vm = new LingoVM(null);

        assertTrue(vm.callHandler("getPref", List.of(Datum.of("Blocktime"))).isVoid());

        Datum stored = vm.callHandler("setPref", List.of(Datum.of("blocktime"), Datum.of("123")));
        assertEquals("123", stored.toStr());

        Datum fetched = vm.callHandler("getPref", List.of(Datum.of("Blocktime")));
        assertTrue(fetched.isString());
        assertEquals("123", fetched.toStr());
    }

    @Test
    void testConvertToPropListParsesReturnDelimitedFields() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("convertToPropList", List.of(
                Datum.of("object.manager.class=[\"Object Manager Class\"]\r"
                        + "error.manager.class=[\"Error Manager Class\"]"),
                Datum.of("\r")));

        assertInstanceOf(Datum.PropList.class, result);
        Datum.PropList props = (Datum.PropList) result;
        assertEquals("[\"Object Manager Class\"]",
                props.getOrDefault("object.manager.class", false, Datum.VOID).toStr());
        assertEquals("[\"Error Manager Class\"]",
                props.getOrDefault("error.manager.class", false, Datum.VOID).toStr());
    }

    @Test
    void testDeferredTasksFlushOnlyAtExplicitBoundary() {
        LingoVM vm = new LingoVM(null);
        AtomicInteger calls = new AtomicInteger();

        vm.deferTask(calls::incrementAndGet);

        assertEquals(0, calls.get());

        vm.flushDeferredTasks();

        assertEquals(1, calls.get());
    }

    @Test
    void testGotoNetPageBuiltinDelegatesToMovieProvider() {
        LingoVM vm = new LingoVM(null);
        RecordingMovieProvider provider = new RecordingMovieProvider();
        MoviePropertyProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("gotoNetPage",
                    List.of(Datum.of("https://example.com"), Datum.of("_new")));
            assertTrue(result.isTruthy());
            assertEquals("https://example.com", provider.lastGotoUrl);
            assertEquals("_new", provider.lastGotoTarget);
        } finally {
            MoviePropertyProvider.clearProvider();
        }
    }

    @Test
    void testGotoNetMovieBuiltinDelegatesToMovieProvider() {
        LingoVM vm = new LingoVM(null);
        RecordingMovieProvider provider = new RecordingMovieProvider();
        MoviePropertyProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("gotoNetMovie",
                    List.of(Datum.of("https://example.com/movie.dcr")));
            assertEquals(73, result.toInt());
            assertEquals("https://example.com/movie.dcr", provider.lastGotoMovieUrl);
        } finally {
            MoviePropertyProvider.clearProvider();
        }
    }

    @Test
    void testCallHandlerPrefersGlobalScriptHandlerOverBuiltin() {
        OverridingHandlerVm vm = new OverridingHandlerVm();

        Datum result = vm.callHandler("getmemnum", List.of(Datum.of("much_sofa1b_a_0_2_0")));

        assertEquals(1, vm.executeCount);
        assertEquals("script:getmemnum", result.toStr());
    }

    @Test
    void testGetmemnumFallsBackToRuntimeCastWhenAuthoredHandlerMisses() {
        OverridingHandlerVm vm = new OverridingHandlerVm("getmemnum", Datum.ZERO);
        RecordingCastProvider provider = new RecordingCastProvider();
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("getmemnum", List.of(Datum.of("Logo")));

            assertEquals(1, vm.executeCount);
            assertEquals((11 << 16) | 7, result.toInt());
            assertEquals("Logo", provider.lastMemberByName);
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testMemberExistsFallsBackToRuntimeCastWhenAuthoredHandlerMisses() {
        OverridingHandlerVm vm = new OverridingHandlerVm("memberExists", Datum.FALSE);
        RecordingCastProvider provider = new RecordingCastProvider();
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("memberExists", List.of(Datum.of("Logo")));

            assertEquals(1, vm.executeCount);
            assertEquals(1, result.toInt());
            assertEquals("Logo", provider.lastMemberByName);
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testGetVariableFallsBackToShockwaveLaunchVariableWhenAuthoredReturnsDefault() {
        OverridingHandlerVm vm = new OverridingHandlerVm("getVariable", Datum.EMPTY_STRING);
        ExternalParamProvider.setProvider(new ExternalParamProvider() {
            @Override
            public String getParamValue(String name) {
                return null;
            }

            @Override
            public String getParamName(int index) {
                return null;
            }

            @Override
            public int getParamCount() {
                return 0;
            }

            @Override
            public java.util.Map<String, String> getAllParams() {
                return java.util.Map.of();
            }

            @Override
            public String getLaunchVariable(String name) {
                return "external.texts.txt".equalsIgnoreCase(name)
                        ? "http://example.test/external_texts.txt"
                        : null;
            }
        });
        try {
            Datum result = vm.callHandler("getVariable",
                    List.of(Datum.of("external.texts.txt"), Datum.EMPTY_STRING));

            assertEquals(1, vm.executeCount);
            assertEquals("http://example.test/external_texts.txt", result.toStr());
        } finally {
            ExternalParamProvider.clearProvider();
        }
    }

    @Test
    void testGlobalGetmemnumResolvesLiveMemberOutsideRegistryNamespace() {
        LingoVM vm = new LingoVM(null);
        CastLibProvider.setProvider(new LiveButRegistryHiddenCastProvider());
        try {
            Datum result = vm.callHandler("getmemnum", List.of(Datum.of("dynamic_artwork")));

            assertEquals((4 << 16) | 9, result.toInt());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testGlobalMemberExistsResolvesLiveMemberOutsideRegistryNamespace() {
        LingoVM vm = new LingoVM(null);
        CastLibProvider.setProvider(new LiveButRegistryHiddenCastProvider());
        try {
            Datum result = vm.callHandler("memberExists", List.of(Datum.of("dynamic_artwork")));

            assertEquals(1, result.toInt());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testGlobalMemberExistsDoesNotUseRegistryOnlyLookup() {
        LingoVM vm = new LingoVM(null);
        CastLibProvider.setProvider(new RegistryOnlyCastProvider());
        try {
            Datum exists = vm.callHandler("memberExists", List.of(Datum.of("registry_only")));
            Datum encoded = vm.callHandler("getmemnum", List.of(Datum.of("registry_only")));

            assertEquals(0, exists.toInt());
            assertEquals((6 << 16) | 12, encoded.toInt());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testReceiveUpdateAndRemoveUpdateAreNotBuiltins() {
        LingoVM vm = new LingoVM(null);
        RecordingUpdateProvider provider = new RecordingUpdateProvider();
        UpdateProvider.setProvider(provider);
        try {
            vm.callHandler("receiveUpdate", List.of(Datum.of("obj-id")));
            vm.callHandler("removeUpdate", List.of(Datum.of("obj-id")));

            assertEquals(0, provider.receiveCalls);
            assertEquals(0, provider.removeCalls);
        } finally {
            UpdateProvider.clearProvider();
        }
    }

    @Test
    void testFormatTraceArgumentShowsVoidAndSymbolsExplicitly() {
        assertEquals("<VOID>", LingoVM.formatTraceArgument(Datum.VOID));
        assertEquals("#mouseUp", LingoVM.formatTraceArgument(Datum.symbol("mouseUp")));
        assertEquals("\"login_a\"", LingoVM.formatTraceArgument(Datum.of("login_a")));
    }

    @Test
    void testExceptionCallStackFormatsFullArgumentsWithoutTruncation() {
        String longArg = "abcdefghijklmnopqrstuvwxyz0123456789".repeat(4);
        String formattedArg = DatumFormatter.formatExpanded(Datum.of(longArg));

        LingoException exception = new LingoException("boom");
        exception.setLingoCallStack(List.of(
                new LingoVM.CallStackFrame(
                        "replaceChunks",
                        "\"String Services Class\" (PARENT)",
                        35,
                        List.of(formattedArg)
                )
        ));

        String stack = exception.formatLingoCallStack();
        assertNotNull(stack);
        assertTrue(stack.contains("replaceChunks(" + formattedArg + ")"));
        assertTrue(stack.contains(longArg));
        assertFalse(stack.contains("..."));
    }

    @Test
    void testExceptionCallStackIncludesScriptOriginWhenAvailable() {
        LingoException exception = new LingoException("boom");
        exception.setLingoCallStack(List.of(
                new LingoVM.CallStackFrame(
                        "openArticle",
                        "\"Bulletin Class\" (PARENT)",
                        42,
                        List.of(),
                        "scriptId=681 castLib=8 member=17 file=\"hh_bulletin.cct\"")
        ));

        String stack = exception.formatLingoCallStack();
        assertNotNull(stack);
        assertTrue(stack.contains("scriptId=681"));
        assertTrue(stack.contains("castLib=8"));
        assertTrue(stack.contains("file=\"hh_bulletin.cct\""));
    }

    @Test
    void testDisplayArgumentsSkipImplicitPrependedReceiver() {
        ScriptChunk.Handler handler = new ScriptChunk.Handler(
                1, 0, 0, 0, 1, 0, 0, 0,
                List.of(),
                List.of(),
                List.of(new ScriptChunk.Handler.Instruction(0, Opcode.RET, 0x01, 0)),
                java.util.Map.of(0, 0)
        );
        Datum.ScriptInstance receiver = new Datum.ScriptInstance(12, java.util.Map.of());
        Scope scope = new Scope(null, handler, List.of(receiver, Datum.of("abc")), receiver);

        assertEquals(List.of(Datum.of("abc")), scope.getDisplayArguments());
    }

    @Test
    void testAlertHookReceivesErrorTypeAndMessage() {
        CapturingAlertHookVm vm = new CapturingAlertHookVm();
        ScriptChunk.Handler alertHandler = new ScriptChunk.Handler(
                1, 0, 0, 0, 2, 0, 0, 0,
                List.of(),
                List.of(),
                List.of(new ScriptChunk.Handler.Instruction(0, Opcode.RET, 0x01, 0)),
                java.util.Map.of(0, 0)
        );
        ScriptChunk alertScript = new ScriptChunk(
                null,
                new ChunkId(7),
                ScriptChunk.ScriptType.PARENT,
                0,
                List.of(alertHandler),
                List.of(),
                List.of(),
                List.of(),
                new byte[0]
        );
        Datum.ScriptInstance hookInstance = new Datum.ScriptInstance(
                77,
                java.util.Map.of(Datum.PROP_SCRIPT_REF, Datum.ScriptRef.of(3, 7))
        );
        MoviePropertyProvider movieProvider = new MoviePropertyProvider() {
            @Override
            public Datum getMovieProp(String propName) {
                return "alerthook".equalsIgnoreCase(propName) ? hookInstance : Datum.VOID;
            }

            @Override
            public boolean setMovieProp(String propName, Datum value) {
                return false;
            }
        };
        CastLibProvider castProvider = new NoOpCastLibProvider() {
            @Override
            public HandlerLocation findHandlerInScript(int castLibNumber, int memberNumber, String handlerName) {
                return new HandlerLocation(castLibNumber, alertScript, alertHandler, null);
            }
        };

        MoviePropertyProvider.setProvider(movieProvider);
        CastLibProvider.setProvider(castProvider);
        try {
            assertTrue(vm.fireAlertHook("Script Error", "boom"));
            assertSame(hookInstance, vm.capturedReceiver);
            assertEquals(2, vm.capturedArgs.size());
            assertEquals("Script Error", vm.capturedArgs.get(0).toStr());
            assertEquals("boom", vm.capturedArgs.get(1).toStr());
        } finally {
            MoviePropertyProvider.clearProvider();
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testMemberBuiltinResolvesCastLibNameArgument() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("member", List.of(Datum.of("present_gen5_small"), Datum.of("bin")));
            assertEquals(11, provider.lastMemberByNameCastLibNumber);
            assertEquals("present_gen5_small", provider.lastMemberByName);
            assertTrue(result instanceof Datum.CastMemberRef);
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testMemberBuiltinResolvesCastLibSymbolArgument() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("member", List.of(Datum.of("present_gen5_small"), Datum.symbol("bin")));
            assertEquals(11, provider.lastMemberByNameCastLibNumber);
            assertEquals("present_gen5_small", provider.lastMemberByName);
            assertTrue(result instanceof Datum.CastMemberRef);
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testMemberBuiltinReturnsVoidWhenNamedMemberIsMissingFromSpecificCast() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        provider.memberByNameResult = Datum.VOID;
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("member", List.of(Datum.of("grunge_barrel.props"), Datum.of("bin")));
            assertEquals(11, provider.lastMemberByNameCastLibNumber);
            assertEquals("grunge_barrel.props", provider.lastMemberByName);
            assertEquals(-1, provider.lastGetMemberCastLibNumber);
            assertEquals(-1, provider.lastGetMemberNumber);
            assertTrue(result.isVoid());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void globalHandlerLookupOnlyTreatsMovieScriptsAsGlobal() {
        assertTrue(LingoVM.isGlobalHandlerScriptType(ScriptChunk.ScriptType.MOVIE_SCRIPT));
        assertFalse(LingoVM.isGlobalHandlerScriptType(ScriptChunk.ScriptType.SCORE));
        assertFalse(LingoVM.isGlobalHandlerScriptType(ScriptChunk.ScriptType.BEHAVIOR));
        assertFalse(LingoVM.isGlobalHandlerScriptType(ScriptChunk.ScriptType.PARENT));
        assertFalse(LingoVM.isGlobalHandlerScriptType(ScriptChunk.ScriptType.UNKNOWN));
    }

    @Test
    void externalProviderCanSatisfyHandlerAfterEarlierMissingLookup() {
        LingoVM vm = new LingoVM(null);
        assertNull(vm.findHandler("lateHandler"));

        ScriptChunk.Handler handler = new ScriptChunk.Handler(
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                0,
                List.of(),
                List.of(),
                List.of(),
                Map.of());
        ScriptChunk script = new ScriptChunk(
                null,
                new ChunkId(31),
                ScriptChunk.ScriptType.MOVIE_SCRIPT,
                0,
                List.of(handler),
                List.of(),
                List.of(),
                List.of(),
                new byte[0]);

        CastLibProvider.setProvider(new NoOpCastLibProvider() {
            @Override
            public CastLibProvider.HandlerLocation findHandler(String handlerName) {
                if ("lateHandler".equalsIgnoreCase(handlerName)) {
                    return new CastLibProvider.HandlerLocation(2, script, handler, null);
                }
                return null;
            }
        });
        try {
            assertNotNull(vm.findHandler("lateHandler"),
                    "a handler miss cached before external casts are visible must not mask provider scripts");
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void handlerTimeoutCanBeDisabledForDeterministicStepLimitedRuntimes() {
        LingoVM vm = new LingoVM(null);

        assertEquals(60_000, vm.getHandlerTimeoutMs());

        vm.setHandlerTimeoutMs(0);
        assertEquals(0, vm.getHandlerTimeoutMs());

        vm.setHandlerTimeoutMs(-1);
        assertEquals(0, vm.getHandlerTimeoutMs());

        vm.setHandlerTimeoutMs(2500);
        assertEquals(2500, vm.getHandlerTimeoutMs());
    }

    @Test
    void disabledHandlerTimeoutStillAllowsInstructionStepLimitToStopRunawayHandlers() {
        ScriptChunk.Handler handler = new ScriptChunk.Handler(
                1, 0, 0, 0, 0, 0, 0, 0,
                List.of(),
                List.of(),
                List.of(new ScriptChunk.Handler.Instruction(0, Opcode.JMP, 0x53, 0)),
                java.util.Map.of(0, 0)
        );
        ScriptChunk script = new ScriptChunk(
                null,
                new ChunkId(99),
                ScriptChunk.ScriptType.MOVIE_SCRIPT,
                0,
                List.of(handler),
                List.of(),
                List.of(),
                List.of(),
                new byte[0]
        );
        LingoVM vm = new LingoVM(null);
        vm.setHandlerTimeoutMs(0);
        vm.setStepLimit(3);

        LingoException exception = assertThrows(
                LingoException.class,
                () -> vm.executeHandler(script, handler, List.of(), Datum.VOID)
        );
        assertTrue(exception.getMessage().contains("Step limit exceeded"));
    }

    @Test
    void testMemberBuiltinReturnsVoidForZeroMemberNumber() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("member", List.of(Datum.ZERO));

            assertTrue(result.isVoid());
            assertEquals(-1, provider.lastGetMemberCastLibNumber);
            assertEquals(-1, provider.lastGetMemberNumber);
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testMemberBuiltinPreservesMirroredEncodedSlot() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("member", List.of(Datum.of(-((11 << 16) | 7))));

            assertTrue(result instanceof Datum.CastMemberRef);
            Datum.CastMemberRef ref = (Datum.CastMemberRef) result;
            assertEquals(11, ref.castLibNum());
            assertEquals(7, ref.memberNum());
            assertTrue(ref.isMirrored());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testFieldBuiltinResolvesCastLibNameArgument() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        provider.fieldValue = "ok";
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("field", List.of(Datum.of("memberalias.index"), Datum.of("bin")));
            assertEquals(11, provider.lastFieldCastId);
            assertEquals("memberalias.index", provider.lastFieldMemberName);
            assertEquals("ok", result.toStr());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testFieldBuiltinResolvesCastLibSymbolArgument() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        provider.fieldValue = "ok";
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("field", List.of(Datum.of("memberalias.index"), Datum.symbol("bin")));
            assertEquals(11, provider.lastFieldCastId);
            assertEquals("memberalias.index", provider.lastFieldMemberName);
            assertEquals("ok", result.toStr());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testValueBuiltinUsesParsedFieldDatum() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        provider.fieldValue = "[#foo: 7]";
        provider.fieldParsedValue = Datum.propList(java.util.Map.of("foo", Datum.of(7)));
        CastLibProvider.setProvider(provider);
        try {
            Datum field = vm.callHandler("field", List.of(Datum.of("grunge_barrel.props"), Datum.of("bin")));
            assertInstanceOf(Datum.FieldText.class, field);

            Datum parsed = vm.callHandler("value", List.of(field));
            assertInstanceOf(Datum.PropList.class, parsed);
            assertEquals(1, provider.fieldParsedCalls);
            assertEquals(7, ((Datum.PropList) parsed).get("foo").toInt());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testValueBuiltinFallsBackToGenericFieldParsingWhenProviderCacheMisses() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        provider.fieldValue = "[#foo: 9]";
        provider.fieldParsedValue = Datum.VOID;
        CastLibProvider.setProvider(provider);
        try {
            Datum field = vm.callHandler("field", List.of(Datum.of("grunge_barrel.props"), Datum.of("bin")));

            Datum parsed = vm.callHandler("value", List.of(field));
            assertInstanceOf(Datum.PropList.class, parsed);
            assertEquals(1, provider.fieldParsedCalls);
            assertEquals(9, ((Datum.PropList) parsed).get("foo").toInt());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testValueBuiltinPreservesBareMultiWordScriptClassNames() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("value", List.of(Datum.of("Broker Manager Class")));

            assertTrue(result.isString());
            assertEquals("Broker Manager Class", result.toStr());
            assertEquals(0, provider.lastMemberByNameCastLibNumber);
            assertEquals("Broker Manager Class", provider.lastMemberByName);
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testValueBuiltinStillReturnsVoidForUnknownMultiWordStrings() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        provider.memberByNameResult = Datum.VOID;
        CastLibProvider.setProvider(provider);
        try {
            Datum result = vm.callHandler("value", List.of(Datum.of("Broker Manager Class")));

            assertTrue(result.isVoid());
            assertEquals(0, provider.lastMemberByNameCastLibNumber);
            assertEquals("Broker Manager Class", provider.lastMemberByName);
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testFieldDatumBehavesLikeAString() {
        LingoVM vm = new LingoVM(null);
        RecordingCastProvider provider = new RecordingCastProvider();
        provider.fieldValue = "ok";
        CastLibProvider.setProvider(provider);
        try {
            Datum field = vm.callHandler("field", List.of(Datum.of("memberalias.index"), Datum.of("bin")));

            assertInstanceOf(Datum.FieldText.class, field);
            assertTrue(vm.callHandler("stringp", List.of(field)).isTruthy());
            assertFalse(vm.callHandler("objectp", List.of(field)).isTruthy());
            assertEquals(2, vm.callHandler("length", List.of(field)).toInt());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    private static final class RecordingMovieProvider implements MoviePropertyProvider {
        private String lastPropName;
        private String lastReadPropName;
        private Datum lastValue = Datum.VOID;
        private Datum doubleClickValue = Datum.FALSE;
        private Datum millisecondsValue = Datum.VOID;
        private final Map<String, Datum> properties = new HashMap<>();
        private String lastGotoUrl;
        private String lastGotoTarget;
        private String lastGotoMovieUrl;

        @Override
        public Datum getMovieProp(String propName) {
            lastReadPropName = propName;
            for (Map.Entry<String, Datum> entry : properties.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(propName)) {
                    return entry.getValue();
                }
            }
            if ("doubleClick".equalsIgnoreCase(propName)) {
                return doubleClickValue;
            }
            if ("milliseconds".equalsIgnoreCase(propName)) {
                return millisecondsValue;
            }
            return Datum.VOID;
        }

        @Override
        public boolean setMovieProp(String propName, Datum value) {
            lastPropName = propName;
            lastValue = value;
            return true;
        }

        @Override
        public void gotoNetPage(String url, String target) {
            lastGotoUrl = url;
            lastGotoTarget = target;
        }

        @Override
        public int gotoNetMovie(String url) {
            lastGotoMovieUrl = url;
            return 73;
        }
    }

    private static final class LiveButRegistryHiddenCastProvider extends NoOpCastLibProvider {
        @Override
        public Datum getRegistryMemberByName(int castLibNumber, String memberName) {
            return Datum.VOID;
        }

        @Override
        public Datum getMemberByName(int castLibNumber, String memberName) {
            return "dynamic_artwork".equalsIgnoreCase(memberName)
                    ? Datum.CastMemberRef.of(4, 9)
                    : Datum.VOID;
        }

        @Override
        public boolean memberExists(int castLibNumber, int memberNumber) {
            return castLibNumber == 4 && memberNumber == 9;
        }

        @Override
        public boolean isRegistryVisibleMember(int castLibNumber, int memberNumber) {
            return false;
        }
    }

    private static final class RegistryOnlyCastProvider extends NoOpCastLibProvider {
        @Override
        public Datum getRegistryMemberByName(int castLibNumber, String memberName) {
            return "registry_only".equalsIgnoreCase(memberName)
                    ? Datum.CastMemberRef.of(6, 12)
                    : Datum.VOID;
        }

        @Override
        public Datum getMemberByName(int castLibNumber, String memberName) {
            return Datum.VOID;
        }

        @Override
        public boolean memberExists(int castLibNumber, int memberNumber) {
            return castLibNumber == 6 && memberNumber == 12;
        }
    }

    private static final class RecordingCastProvider extends NoOpCastLibProvider {
        private int lastMemberByNameCastLibNumber = -1;
        private String lastMemberByName;
        private int lastGetMemberCastLibNumber = -1;
        private int lastGetMemberNumber = -1;
        private int lastFieldCastId = -1;
        private String lastFieldMemberName;
        private String fieldValue = "";
        private Datum fieldParsedValue = Datum.VOID;
        private int fieldParsedCalls = 0;
        private Datum memberByNameResult = Datum.CastMemberRef.of(11, 7);

        @Override
        public int getCastLibByName(String name) {
            if ("bin".equalsIgnoreCase(name)) {
                return 11;
            }
            return -1;
        }

        @Override
        public Datum getMember(int castLibNumber, int memberNumber) {
            lastGetMemberCastLibNumber = castLibNumber;
            lastGetMemberNumber = memberNumber;
            return Datum.CastMemberRef.of(castLibNumber, memberNumber);
        }

        @Override
        public Datum getMemberByName(int castLibNumber, String memberName) {
            lastMemberByNameCastLibNumber = castLibNumber;
            lastMemberByName = memberName;
            return memberByNameResult;
        }

        @Override
        public int getCastLibCount() {
            return 11;
        }

        @Override
        public boolean memberExists(int castLibNumber, int memberNumber) {
            return castLibNumber == 11 && memberNumber == 7;
        }

        @Override
        public String getFieldValue(Object memberNameOrNum, int castId) {
            lastFieldCastId = castId;
            lastFieldMemberName = String.valueOf(memberNameOrNum);
            return fieldValue;
        }

        @Override
        public Datum getFieldDatum(Object memberNameOrNum, int castId) {
            lastFieldCastId = castId;
            lastFieldMemberName = String.valueOf(memberNameOrNum);
            return new Datum.FieldText(fieldValue, castId > 0 ? castId : 11, 7);
        }

        @Override
        public Datum getFieldParsedValue(int castLibNumber, int memberNumber, LingoVM vm) {
            fieldParsedCalls++;
            return fieldParsedValue;
        }
    }

    private static final class RecordingUpdateProvider implements UpdateProvider {
        private int receiveCalls;
        private int removeCalls;

        @Override
        public void receiveUpdate(Datum target) {
            receiveCalls++;
        }

        @Override
        public void removeUpdate(Datum target) {
            removeCalls++;
        }
    }

    private static final class OverridingHandlerVm extends LingoVM {
        private final String handlerName;
        private final Datum handlerResult;
        private int executeCount;

        private OverridingHandlerVm() {
            this("getmemnum", Datum.of("script:getmemnum"));
        }

        private OverridingHandlerVm(String handlerName, Datum handlerResult) {
            super(null);
            this.handlerName = handlerName;
            this.handlerResult = handlerResult;
        }

        @Override
        public HandlerRef findHandler(String handlerName) {
            if (this.handlerName.equalsIgnoreCase(handlerName)) {
                return new HandlerRef(null, null);
            }
            return null;
        }

        @Override
        public Datum executeHandler(com.libreshockwave.chunks.ScriptChunk script,
                                    com.libreshockwave.chunks.ScriptChunk.Handler handler,
                                    List<Datum> args, Datum receiver) {
            executeCount++;
            return handlerResult;
        }
    }

    private static final class CapturingAlertHookVm extends LingoVM {
        private List<Datum> capturedArgs = List.of();
        private Datum capturedReceiver = Datum.VOID;

        private CapturingAlertHookVm() {
            super(null);
        }

        @Override
        public Datum executeHandler(com.libreshockwave.chunks.ScriptChunk script,
                                    com.libreshockwave.chunks.ScriptChunk.Handler handler,
                                    List<Datum> args, Datum receiver) {
            this.capturedArgs = List.copyOf(args);
            this.capturedReceiver = receiver;
            return Datum.TRUE;
        }
    }

    @Test
    void testBuiltinAbs() {
        LingoVM vm = new LingoVM(null);

        // Test via callHandler (will use builtin)
        Datum result = vm.callHandler("abs", List.of(Datum.of(-42)));
        assertEquals(42, result.toInt());

        result = vm.callHandler("abs", List.of(Datum.of(42)));
        assertEquals(42, result.toInt());

        result = vm.callHandler("abs", List.of(Datum.of(-3.14)));
        assertEquals(3.14, result.toDouble(), 0.001);
    }

    @Test
    void testBuiltinSqrt() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("sqrt", List.of(Datum.of(16)));
        assertEquals(4.0, result.toDouble(), 0.001);

        result = vm.callHandler("sqrt", List.of(Datum.of(2)));
        assertEquals(1.414, result.toDouble(), 0.01);
    }

    @Test
    void testBuiltinRandom() {
        LingoVM vm = new LingoVM(null);

        // Random should return 1 to max
        for (int i = 0; i < 100; i++) {
            Datum result = vm.callHandler("random", List.of(Datum.of(10)));
            int value = result.toInt();
            assertTrue(value >= 1 && value <= 10, "Random value " + value + " out of range 1-10");
        }
    }

    @Test
    void testBuiltinString() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("string", List.of(Datum.of(42)));
        assertEquals("42", result.toStr());

        result = vm.callHandler("string", List.of(Datum.of(3.14)));
        assertEquals("3.14", result.toStr());
    }

    @Test
    void testBuiltinInteger() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("integer", List.of(Datum.of(3.7)));
        assertEquals(4, result.toInt());

        result = vm.callHandler("integer", List.of(Datum.of("42")));
        assertEquals(42, result.toInt());
    }

    @Test
    void testBuiltinFloat() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("float", List.of(Datum.of(42)));
        assertEquals(42.0, result.toDouble(), 0.001);
    }

    @Test
    void testBuiltinFloatReturnsOriginalStringForNonNumeric() {
        // float() should return the original string unchanged if it's not a valid number
        // This matches dirplayer-rs behavior and is important for scripts that use
        // floatp(float(x)) to check if x is numeric
        LingoVM vm = new LingoVM(null);

        // Non-numeric string should be returned unchanged
        Datum result = vm.callHandler("float", List.of(Datum.of("hello")));
        assertTrue(result.isString(), "float(\"hello\") should return a string");
        assertEquals("hello", result.toStr());

        // String with class name (like in Variable Container Class props)
        result = vm.callHandler("float", List.of(Datum.of("CastLoad Manager Class")));
        assertTrue(result.isString(), "float(\"CastLoad Manager Class\") should return a string");
        assertEquals("CastLoad Manager Class", result.toStr());

        // Numeric string should be converted
        result = vm.callHandler("float", List.of(Datum.of("3.14")));
        assertTrue(result.isFloat(), "float(\"3.14\") should return a float");
        assertEquals(3.14, result.toDouble(), 0.001);
    }

    @Test
    void testBuiltinSystemDateExposesDirectorDateParts() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("systemDate", List.of());

        assertTrue(result instanceof Datum.PropList, "systemDate() should return an object with date fields");
        Datum.PropList date = (Datum.PropList) result;
        assertTrue(date.get("day").toInt() >= 1 && date.get("day").toInt() <= 31);
        assertTrue(date.get("month").toInt() >= 1 && date.get("month").toInt() <= 12);
        assertTrue(date.get("year").toInt() >= 2000);
    }

    @Test
    void testBuiltinIntegerReturnsVoidForNonNumericString() {
        // integer() returns VOID for non-numeric strings (Director behavior).
        // ScummVM: strtol fails, res left as default (VOID).
        LingoVM vm = new LingoVM(null);

        // Non-numeric string should return VOID
        Datum result = vm.callHandler("integer", List.of(Datum.of("hello")));
        assertTrue(result.isVoid(), "integer(\"hello\") should return VOID");

        // Numeric string should be converted
        result = vm.callHandler("integer", List.of(Datum.of("42")));
        assertTrue(result.isInt(), "integer(\"42\") should return an int");
        assertEquals(42, result.toInt());

        // Float string: rounds to nearest, same as integer(3.7)
        result = vm.callHandler("integer", List.of(Datum.of("3.7")));
        assertTrue(result.isInt(), "integer(\"3.7\") should return an int");
        assertEquals(4, result.toInt());
    }

    @Test
    void testBuiltinLength() {
        LingoVM vm = new LingoVM(null);

        // String length
        Datum result = vm.callHandler("length", List.of(Datum.of("hello")));
        assertEquals(5, result.toInt());

        // List length
        result = vm.callHandler("length", List.of(Datum.list(Datum.of(1), Datum.of(2), Datum.of(3))));
        assertEquals(3, result.toInt());
    }

    @Test
    void testBuiltinChars() {
        LingoVM vm = new LingoVM(null);

        // chars("hello", 2, 4) should return "ell"
        Datum result = vm.callHandler("chars", List.of(Datum.of("hello"), Datum.of(2), Datum.of(4)));
        assertEquals("ell", result.toStr());
    }

    @Test
    void testBuiltinCharToNum() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("charToNum", List.of(Datum.of("A")));
        assertEquals(65, result.toInt());
    }

    @Test
    void charToNumTreatsEmptyStringAsZero() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("charToNum", List.of(Datum.EMPTY_STRING));

        assertEquals(0, result.toInt());
    }

    @Test
    void testBuiltinNumToChar() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("numToChar", List.of(Datum.of(65)));
        assertEquals("A", result.toStr());
    }

    @Test
    void testBuiltinPoint() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("point", List.of(Datum.of(100), Datum.of(200)));
        assertTrue(result instanceof Datum.Point);
        assertEquals(100, ((Datum.Point) result).x());
        assertEquals(200, ((Datum.Point) result).y());
    }

    @Test
    void testBuiltinRect() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("rect", List.of(
            Datum.of(0), Datum.of(0), Datum.of(640), Datum.of(480)));
        assertTrue(result instanceof Datum.Rect);
        assertEquals(0, ((Datum.Rect) result).left());
        assertEquals(0, ((Datum.Rect) result).top());
        assertEquals(640, ((Datum.Rect) result).right());
        assertEquals(480, ((Datum.Rect) result).bottom());
    }

    @Test
    void testBuiltinColor() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("color", List.of(
            Datum.of(255), Datum.of(128), Datum.of(0)));
        assertTrue(result instanceof Datum.Color);
        assertEquals(255, ((Datum.Color) result).r());
        assertEquals(128, ((Datum.Color) result).g());
        assertEquals(0, ((Datum.Color) result).b());
    }

    @Test
    void singleArgumentColorReturnsDirectPaletteIndex() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("color", List.of(Datum.of(137)));

        assertTrue(result instanceof Datum.PaletteIndexColor);
        assertEquals(137, ((Datum.PaletteIndexColor) result).index());
    }

    @Test
    void testBuiltinCount() {
        LingoVM vm = new LingoVM(null);

        Datum list = Datum.list(Datum.of(1), Datum.of(2), Datum.of(3));
        Datum result = vm.callHandler("count", List.of(list));
        assertEquals(3, result.toInt());
    }

    @Test
    void countBuiltinKeepsPlainStringsOnListSemantics() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("count", List.of(Datum.of("Large TV")));

        assertEquals(0, result.toInt());
    }

    @Test
    void countBuiltinCountsStringChunkAccessors() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("count",
                List.of(new Datum.StringChunkAccessor("Large TV", "char")));

        assertEquals(8, result.toInt());

        result = vm.callHandler("count",
                List.of(new Datum.StringChunkAccessor("plain,bold", "item")));

        assertEquals(2, result.toInt());
    }

    @Test
    void countBuiltinCountsTextMemberChunkAccessors() {
        LingoVM vm = new LingoVM(null);
        CastLibProvider.setProvider(new NoOpCastLibProvider() {
            @Override
            public Datum getMemberTextRangeProp(int castLibNumber, int memberNumber,
                                                String chunkType, int start, int end,
                                                String propName) {
                if (castLibNumber == 3 && memberNumber == 7
                        && "char".equals(chunkType)
                        && start == 1 && end == -1
                        && "text".equalsIgnoreCase(propName)) {
                    return Datum.of("Large TV");
                }
                return Datum.VOID;
            }
        });
        try {
            Datum result = vm.callHandler("count",
                    List.of(new Datum.TextMemberChunkAccessor(3, 7, "char")));

            assertEquals(8, result.toInt());
        } finally {
            CastLibProvider.clearProvider();
        }
    }

    @Test
    void testBuiltinGetAt() {
        LingoVM vm = new LingoVM(null);

        Datum list = Datum.list(Datum.of(10), Datum.of(20), Datum.of(30));

        // Lingo is 1-indexed
        Datum result = vm.callHandler("getAt", List.of(list, Datum.of(1)));
        assertEquals(10, result.toInt());

        result = vm.callHandler("getAt", List.of(list, Datum.of(2)));
        assertEquals(20, result.toInt());

        result = vm.callHandler("getAt", List.of(list, Datum.of(3)));
        assertEquals(30, result.toInt());

        // Out of bounds
        result = vm.callHandler("getAt", List.of(list, Datum.of(4)));
        assertTrue(result.isVoid());
    }

    @Test
    void testBuiltinPropListGetAtPrefersPositionOverNumericKey() {
        LingoVM vm = new LingoVM(null);
        Datum.PropList propList = new Datum.PropList();
        propList.add("first", Datum.of("positional"), true);
        propList.add(Datum.of(1), Datum.of("numeric-key"));

        Datum result = vm.callHandler("getAt", List.of(propList, Datum.of(1)));

        assertEquals("positional", result.toStr());
        assertEquals("numeric-key", vm.callHandler("getaProp", List.of(propList, Datum.of(1))).toStr());
    }

    @Test
    void testBuiltinPropListSetAtRaisesWhenOutOfRange() {
        LingoVM vm = new LingoVM(null);
        Datum.PropList propList = new Datum.PropList();

        assertThrows(LingoException.class,
                () -> vm.callHandler("setAt", List.of(propList, Datum.of(42), Datum.of("roller"))));

        assertEquals(0, propList.size());
    }

    @Test
    void testBuiltinPropListGetAtUsesNumericKeyOutOfRangeWhenCompatibilityEnabled() {
        LingoVM vm = new LingoVM(null);
        vm.setPropListSetAtByKeyCompatibilityEnabled(true);
        Datum.PropList propList = new Datum.PropList();
        propList.add(Datum.of(2147418112), Datum.of("sandbox"));

        Datum result = vm.callHandler("getAt", List.of(propList, Datum.of(2147418112)));

        assertEquals("sandbox", result.toStr());
    }

    @Test
    void testBuiltinPropListSetAtCreatesNumericKeyWhenCompatibilityEnabled() {
        LingoVM vm = new LingoVM(null);
        vm.setPropListSetAtByKeyCompatibilityEnabled(true);
        Datum.PropList propList = new Datum.PropList();

        vm.callHandler("setAt", List.of(propList, Datum.of(2147418112), Datum.of("chair")));

        assertEquals(1, propList.size());
        assertEquals("chair", vm.callHandler("getaProp", List.of(propList, Datum.of(2147418112))).toStr());
    }

    @Test
    void testBuiltinPropListSetAtKeyCompatibilityUsesSetAPropFirstCompatibleBehavior() {
        LingoVM vm = new LingoVM(null);
        vm.setPropListSetAtByKeyCompatibilityEnabled(true);
        Datum.PropList propList = new Datum.PropList();
        propList.add("manager", Datum.of("old"), true);
        propList.add("manager", Datum.of("second"), false);

        vm.callHandler("setAt", List.of(propList, Datum.of("manager"), Datum.of("new")));

        assertEquals(2, propList.size());
        assertTrue(propList.entries().getFirst().isSymbolKey());
        assertEquals("new", propList.getValue(0).toStr());
        assertEquals("second", propList.getValue(1).toStr());
    }

    @Test
    void testBuiltinGetaPropUsesFirstCompatibleProperty() {
        LingoVM vm = new LingoVM(null);
        Datum.PropList propList = new Datum.PropList();
        propList.add("room_interface", Datum.of(1), true);
        propList.add("room_interface", Datum.of(2), false);

        Datum symbolResult = vm.callHandler("getaProp", List.of(propList, Datum.symbol("room_interface")));
        Datum stringResult = vm.callHandler("getaProp", List.of(propList, Datum.of("room_interface")));

        assertEquals(1, symbolResult.toInt());
        assertEquals(1, stringResult.toInt());
    }

    @Test
    void testBuiltinDeletePropRemovesFirstCompatibleProperty() {
        LingoVM vm = new LingoVM(null);
        Datum.PropList propList = new Datum.PropList();
        propList.add("room_interface", Datum.of(1), true);
        propList.add("room_interface", Datum.of(2), false);

        vm.callHandler("deleteProp", List.of(propList, Datum.of("room_interface")));

        assertEquals(1, propList.size());
        assertTrue(propList.entries().getFirst().keyDatum() instanceof Datum.Str);
        assertEquals(2, propList.entries().getFirst().value().toInt());
    }

    @Test
    void testTrigFunctions() {
        LingoVM vm = new LingoVM(null);

        // sin(90) should be ~1
        Datum result = vm.callHandler("sin", List.of(Datum.of(90)));
        assertEquals(1.0, result.toDouble(), 0.001);

        // cos(0) should be 1
        result = vm.callHandler("cos", List.of(Datum.of(0)));
        assertEquals(1.0, result.toDouble(), 0.001);

        // cos(90) should be ~0
        result = vm.callHandler("cos", List.of(Datum.of(90)));
        assertEquals(0.0, result.toDouble(), 0.001);
    }

    @Test
    void testUnknownHandlerReturnsVoid() {
        LingoVM vm = new LingoVM(null);

        Datum result = vm.callHandler("nonExistentHandler", List.of());
        assertTrue(result.isVoid());
    }

    @Test
    void testCallStackDepth() {
        LingoVM vm = new LingoVM(null);

        assertEquals(0, vm.getCallStackDepth());
        assertNull(vm.getCurrentScope());
    }
}
