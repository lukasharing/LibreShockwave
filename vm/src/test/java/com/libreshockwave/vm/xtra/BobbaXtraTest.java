package com.libreshockwave.vm.xtra;

import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BobbaXtraTest {

    @Test
    void publicDirectorSelectorsAcceptHashPrefixAndUnderscores() {
        BobbaXtra xtra = new BobbaXtra();
        int instanceId = xtra.createInstance(List.of());

        assertEquals(0, xtra.callHandler(instanceId, "Crypto_IsReady", List.of()).toInt());
        assertEquals(0, xtra.callHandler(instanceId, "#Crypto_IsReady", List.of()).toInt());
        assertEquals(0, xtra.callHandler(instanceId, "cryptoisready", List.of()).toInt());
    }

    @Test
    void deviceMachineIdUsesNativePublicSelector() {
        BobbaXtra xtra = new BobbaXtra();
        int instanceId = xtra.createInstance(List.of());

        String machineId = xtra.callHandler(instanceId, "Device_GetMachineId", List.of()).toStr();

        assertFalse(machineId.isEmpty());
        assertTrue(machineId.matches("BX1-[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){4}"));
    }

    @Test
    void machineIdIsStableForRuntimeSeedAndDistinctAcrossSeeds() {
        try {
            BobbaXtra.setRuntimeMachineSeed("runtime-a");
            BobbaXtra firstXtra = new BobbaXtra();
            int firstInstance = firstXtra.createInstance(List.of());
            String first = firstXtra.callHandler(firstInstance, "Device_GetMachineId", List.of()).toStr();

            BobbaXtra secondXtra = new BobbaXtra();
            int secondInstance = secondXtra.createInstance(List.of());
            assertEquals(first, secondXtra.callHandler(secondInstance, "Device_GetMachineId", List.of()).toStr());

            BobbaXtra.setRuntimeMachineSeed("runtime-b");
            BobbaXtra thirdXtra = new BobbaXtra();
            int thirdInstance = thirdXtra.createInstance(List.of());
            String third = thirdXtra.callHandler(thirdInstance, "Device_GetMachineId", List.of()).toStr();

            assertFalse(first.equals(third));
            assertTrue(third.matches("BX1-[A-HJ-NP-Z2-9]{4}(-[A-HJ-NP-Z2-9]{4}){4}"));
        } finally {
            BobbaXtra.setRuntimeMachineSeed("");
        }
    }

    @Test
    void machineIdUsesRawThirtyTwoByteBase64SeedLikeNativeXtra() {
        try {
            BobbaXtra.setRuntimeMachineSeed("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
            BobbaXtra xtra = new BobbaXtra();
            int instanceId = xtra.createInstance(List.of());

            assertEquals("BX1-AFVG-S8XP-9BTM-475N-P38A",
                    xtra.callHandler(instanceId, "Device_GetMachineId", List.of()).toStr());
        } finally {
            BobbaXtra.setRuntimeMachineSeed("");
        }
    }

    @Test
    void xtraMsgTableCandidatesRecoverProtectedSelectorNames() {
        BobbaXtra xtra = new BobbaXtra();
        int instanceId = xtra.createInstance(List.of());

        assertEquals(
                "Crypto_DecryptHeader",
                xtra.resolveHandlerName(instanceId, "txtBgColor",
                        List.of("txtBgColor", "Crypto_DecryptHeader")));
        assertEquals(
                "Crypto_EncryptPayload",
                xtra.resolveHandlerName(instanceId, "moveH",
                        List.of("moveH", "Crypto_EncryptPayload")));
        assertEquals(
                "Device_GetMachineId",
                xtra.resolveHandlerName(instanceId, "setHeaderEncoder",
                        List.of("setHeaderEncoder", "Device_GetMachineId")));
        assertEquals(
                "Crypto_IsReady",
                xtra.resolveHandlerName(instanceId, "Crypto_IsReady", List.of("host")));
        assertEquals(
                "txtBgColor",
                xtra.resolveHandlerName(instanceId, "txtBgColor", List.of("foreColor", "moveH")));
    }

    @Test
    void xtraManagerResolvesHandlerNamesThroughXtraMsgTable() {
        XtraManager manager = new XtraManager();
        manager.registerXtra(new BobbaXtra());
        Datum instance = manager.createInstance("BobbaXtra", List.of());

        assertTrue(instance instanceof Datum.XtraInstance);
        assertEquals(
                "Crypto_DecryptPayload",
                manager.resolveHandlerName((Datum.XtraInstance) instance, "backColor",
                        List.of("backColor", "Crypto_DecryptPayload")));
    }

    @Test
    void nativeCryptoPublicSelectorsWorkWithoutProtectedAliases() {
        BobbaXtra xtra = new BobbaXtra();
        int instanceId = xtra.createInstance(List.of());

        assertEquals(0, xtra.callHandler(instanceId, "Crypto_IsReady", List.of()).toInt());
        xtra.callHandler(instanceId, "Crypto_GeneratePublicKey", List.of());
        xtra.callHandler(instanceId, "Crypto_SetServerPublicKey", List.of(Datum.of("3")));

        assertEquals(1, xtra.callHandler(instanceId, "Crypto_IsReady", List.of()).toInt());

        Datum publicHeader = xtra.callHandler(instanceId, "Crypto_EncryptHeader", List.of(Datum.of("\u0001@@C")));
        assertTrue(publicHeader.isString());
        assertEquals(6, publicHeader.toStr().length());

        xtra.callHandler(instanceId, "Crypto_Reset", List.of());
        xtra.callHandler(instanceId, "Crypto_GeneratePublicKey", List.of());
        xtra.callHandler(instanceId, "Crypto_SetServerPublicKey", List.of(Datum.of("3")));
        Datum publicPayload = xtra.callHandler(instanceId, "Crypto_EncryptPayload", List.of(Datum.of("@@payload")));
        assertTrue(publicPayload.isString());
        assertFalse(publicPayload.toStr().isEmpty());

        xtra.callHandler(instanceId, "Crypto_Reset", List.of());
        xtra.callHandler(instanceId, "Crypto_GeneratePublicKey", List.of());
        xtra.callHandler(instanceId, "Crypto_SetServerPublicKey", List.of(Datum.of("3")));
        Datum publicDecryptHeader = xtra.callHandler(instanceId, "Crypto_DecryptHeader", List.of(Datum.of("AAAAAA")));
        Datum publicDecryptPayload = xtra.callHandler(instanceId, "Crypto_DecryptPayload", List.of(Datum.of("AAAA")));

        assertTrue(publicDecryptHeader.isString());
        assertEquals(4, publicDecryptHeader.toStr().length());
        assertTrue(publicDecryptPayload.isString());
        assertEquals(3, publicDecryptPayload.toStr().length());
        assertTrue(xtra.callHandler(instanceId, "txtBgColor", List.of(Datum.of("AAAAAA"))).isVoid());
    }

    @Test
    void cipherHandlersRejectMissingArgumentsWithoutProducingCiphertext() {
        BobbaXtra xtra = new BobbaXtra();
        int instanceId = xtra.createInstance(List.of());

        xtra.callHandler(instanceId, "Crypto_GeneratePublicKey", List.of());
        xtra.callHandler(instanceId, "Crypto_SetServerPublicKey", List.of(Datum.of("3")));

        assertEquals(0, xtra.callHandler(instanceId, "Crypto_EncryptPayload", List.of()).toInt());
        assertEquals(
                "Crypto c2s payload requires a string argument",
                xtra.callHandler(instanceId, "Crypto_GetLastError", List.of()).toStr());
    }

    @Test
    void fuseMessageSequencePreviewShowsSingleUnterminatedOutboundPayload() {
        byte[] payload = new byte[] {
                'A', 'A', 'n', 'e', 'w'
        };

        String preview = BobbaXtra.fuseMessageSequencePreview(payload);

        assertTrue(preview.contains("type=65"));
        assertTrue(preview.contains("paramLen=3"));
        assertTrue(preview.contains("term=false"));
        assertTrue(preview.contains("params=\"new\""));
    }

    @Test
    void fuseMessageSequencePreviewShowsConcatenatedTerminatedInboundPayloads() {
        byte[] payload = new byte[] {
                '@', 't', 'h', 'i', 1,
                'C', 'f', 'x', 'y', 'z', 1
        };

        String preview = BobbaXtra.fuseMessageSequencePreview(payload);

        assertTrue(preview.contains("type=52"));
        assertTrue(preview.contains("params=\"hi\""));
        assertTrue(preview.contains("type=230"));
        assertTrue(preview.contains("params=\"xyz\""));
    }

    @Test
    void fuseMessageSequencePreviewMarksTrailingPartialMessage() {
        byte[] payload = new byte[] {
                '@', 't', 'h', 'i', 1,
                'Z'
        };

        String preview = BobbaXtra.fuseMessageSequencePreview(payload);

        assertTrue(preview.contains("type=52"));
        assertTrue(preview.contains("tailLen=1"));
    }
}
