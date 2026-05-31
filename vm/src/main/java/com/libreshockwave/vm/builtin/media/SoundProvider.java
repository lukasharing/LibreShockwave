package com.libreshockwave.vm.builtin.media;

import com.libreshockwave.vm.datum.Datum;

/**
 * Thread-local provider for sound channel operations.
 * Bridges the VM layer to the player's SoundManager.
 */
public interface SoundProvider {

    void play(int channelNum, Datum args);
    void stop(int channelNum);
    void stopAll();
    void setVolume(int channelNum, int volume);
    int getVolume(int channelNum);
    boolean isPlaying(int channelNum);
    int getElapsedTime(int channelNum);

    final class Holder {
        private Holder() {}
        static SoundProvider current;
    }

    static void setProvider(SoundProvider provider) {
        Holder.current = provider;
    }

    static void clearProvider() {
        Holder.current = null;
    }

    static SoundProvider getProvider() {
        return Holder.current;
    }
}
