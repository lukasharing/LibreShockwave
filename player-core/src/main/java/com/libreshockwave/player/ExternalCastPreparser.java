package com.libreshockwave.player;

import com.libreshockwave.DirectorFile;

interface ExternalCastPreparser {
    ParseJob submit(byte[] data);

    void shutdown();

    interface ParseJob {
        boolean isDone();

        DirectorFile getIfReady();
    }
}
