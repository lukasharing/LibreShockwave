package com.libreshockwave.player.cast;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.player.Player;
import com.libreshockwave.vm.datum.Datum;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LateExternalCastReindexTest {

    private static final Path HABBO_MOVIE =
            Path.of("temp/habbo_origins_src/archive/unprotected_client_dump_20_06_2024/habbo.dcr");
    private static final Path BATTLE_BALL_CAST =
            Path.of("temp/habbo_origins_src/archive/unprotected_client_dump_20_06_2024/hh_game_bb.cct");

    @Test
    void prefetchedExternalCastBindsOnlyWhenASlotTargetsItsFileName() throws Exception {
        if (!Files.isRegularFile(HABBO_MOVIE) || !Files.isRegularFile(BATTLE_BALL_CAST)) {
            return;
        }

        DirectorFile movie = DirectorFile.load(HABBO_MOVIE);
        Player player = new Player(movie);
        try {
            int emptyCastSlot = findEmptyCastSlot(player);
            assertTrue(emptyCastSlot > 0, "could not find an empty dynamic cast slot");

            player.onNetFetchComplete("hh_game_bb.cct", Files.readAllBytes(BATTLE_BALL_CAST));

            Datum beforeBinding = player.getCastLibManager().getMemberByName(
                    emptyCastSlot,
                    "bb_loungesystem.variable.index");
            assertTrue(beforeBinding.isVoid(),
                    "prefetch should only cache bytes, not repoint an unrelated cast slot");

            boolean loaded = player.getCastLibManager().setCastLibProp(
                    emptyCastSlot,
                    "fileName",
                    Datum.of("hh_game_bb.cct"));
            assertTrue(loaded, "binding a cached cast file to a slot should succeed");

            Datum afterBinding = player.getCastLibManager().getMemberByName(
                    emptyCastSlot,
                    "bb_loungesystem.variable.index");
            assertFalse(afterBinding.isVoid(),
                    "the slot should expose Battle Ball members immediately after binding");
        } finally {
            player.shutdown();
        }
    }

    private static int findEmptyCastSlot(Player player) {
        return player.getCastLibManager().getCastLibs().entrySet().stream()
                .filter(entry -> entry.getValue().matchesAuthoredExternalFile("empty"))
                .map(java.util.Map.Entry::getKey)
                .findFirst()
                .orElse(-1);
    }
}
