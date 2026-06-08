package com.libreshockwave.player.render;

import com.libreshockwave.chunks.ScoreChunk;
import com.libreshockwave.player.sprite.SpriteState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Registry of runtime sprite states.
 * Tracks sprite properties that can be modified by scripts (position, visibility, etc.).
 * Supports both Score-based sprites and dynamically created/puppeted sprites.
 */
public class SpriteRegistry {

    private final Map<Integer, SpriteState> sprites = new HashMap<>();
    private int revision;
    private Runnable revisionListener;

    /**
     * Get or create a sprite state for a channel from Score data.
     * If the sprite doesn't exist, creates it from the channel data.
     */
    public SpriteState getOrCreate(int channel, ScoreChunk.ChannelData data) {
        SpriteState state = sprites.get(channel);
        if (state == null) {
            state = new SpriteState(channel, data);
            sprites.put(channel, state);
        } else if (!state.isPuppet() && !state.hasDynamicMember() && !state.matchesScoreIdentity(data)) {
            state.rebindToScorePreservingScriptInstances(data);
        }
        return state;
    }

    /**
     * Get or create a dynamic sprite for a channel (no Score data required).
     * Used when scripts puppet a sprite channel or set properties on it.
     */
    public SpriteState getOrCreateDynamic(int channel) {
        SpriteState state = sprites.get(channel);
        if (state == null) {
            state = new SpriteState(channel);
            sprites.put(channel, state);
        }
        return state;
    }

    /**
     * Get a sprite state by channel, or null if not registered.
     */
    public SpriteState get(int channel) {
        return sprites.get(channel);
    }

    /**
     * Update a sprite's score-driven properties for new frames.
     */
    public void updateFromScore(int channel, ScoreChunk.ChannelData data) {
        SpriteState state = sprites.get(channel);
        if (state != null && !state.isPuppet() && !state.hasDynamicMember()) {
            if (state.matchesScoreIdentity(data)) {
                state.syncFromScore(data);
            } else {
                state.rebindToScorePreservingScriptInstances(data);
                bumpRevision();
            }
        }
    }

    /**
     * Remove a sprite when it leaves the stage.
     */
    public void remove(int channel) {
        sprites.remove(channel);
    }

    /**
     * Clear all sprites (on movie stop/reset).
     */
    public void clear() {
        sprites.clear();
    }

    /**
     * Clear dynamic sprite bindings that still reference a retired member slot.
     * Director's removeMember invalidates the runtime visual content. A sprite
     * that was showing that member must stay explicitly empty until authored
     * Lingo assigns a new member; falling back to score data resurrects stale
     * visualizer parts during room/window teardown.
     */
    public boolean clearDynamicMemberBindings(int castLib, int memberNum) {
        boolean changed = false;
        for (SpriteState state : sprites.values()) {
            if (!state.hasDynamicMember()) {
                continue;
            }
            if (state.getEffectiveCastLib() == castLib && state.getEffectiveCastMember() == memberNum) {
                resetRetiredDynamicBinding(state);
                changed = true;
            }
        }
        if (changed) {
            bumpRevision();
        }
        return changed;
    }

    /**
     * Clear runtime sprite bindings that point into a cast whose visible contents
     * are being unloaded or replaced. Unlike retiring one runtime member slot,
     * replacing a cast invalidates every member identity in that cast namespace,
     * so score-backed channels must stay explicitly empty until authored code
     * assigns fresh content.
     */
    public boolean clearDynamicMemberBindingsForCast(int castLib) {
        if (castLib <= 0) {
            return false;
        }

        boolean changed = false;
        for (SpriteState state : sprites.values()) {
            if (!state.hasDynamicMember()) {
                continue;
            }
            if (state.getEffectiveCastLib() == castLib && state.getEffectiveCastMember() > 0) {
                resetUnloadedCastBinding(state);
                changed = true;
            }
        }
        if (changed) {
            bumpRevision();
        }
        return changed;
    }

    /**
     * Check if a channel has a registered sprite.
     */
    public boolean contains(int channel) {
        return sprites.containsKey(channel);
    }

    /**
     * Get all dynamic/puppeted sprites that should be rendered.
     * Includes sprites with dynamic members and puppeted sprites with visible
     * content. Empty dynamic channel states are runtime bookkeeping, not
     * renderable stage content.
     */
    public List<SpriteState> getDynamicSprites() {
        List<SpriteState> dynamicSprites = new ArrayList<>();
        for (SpriteState sprite : sprites.values()) {
            if (isRenderableDynamicSprite(sprite)) {
                dynamicSprites.add(sprite);
            }
        }
        return dynamicSprites;
    }

    private static boolean isRenderableDynamicSprite(SpriteState sprite) {
        if (sprite == null || !sprite.isVisible()) {
            return false;
        }
        if (sprite.hasDynamicMember()) {
            return sprite.getEffectiveCastMember() > 0;
        }
        if (!sprite.isPuppet()) {
            return false;
        }
        return sprite.getEffectiveCastMember() > 0 || sprite.hasBackColor();
    }

    /**
     * Get all registered sprites.
     */
    public Map<Integer, SpriteState> getAll() {
        return sprites;
    }

    private static void resetRetiredDynamicBinding(SpriteState state) {
        resetUnloadedCastBinding(state);
    }

    private static void resetUnloadedCastBinding(SpriteState state) {
        if (state == null) {
            return;
        }

        state.setScriptInstanceList(List.of());
        state.setVisible(false);
        state.setCursor(0);
        state.setBlend(100);
        state.setStretch(0);
        state.resetReleasedChannelGeometry();
        state.resetReleasedSpriteTransforms();
        if (state.isDynamic()) {
            state.clearDynamicMember();
        } else {
            state.setDynamicMember(0, 0);
        }
    }

    /**
     * Increment revision counter to signal that sprite state has changed.
     * Used by SoftwareRenderer cache to detect dynamic sprite changes
     * in single-frame movies where the frame number never changes.
     */
    public void bumpRevision() {
        revision++;
        if (revisionListener != null) {
            revisionListener.run();
        }
    }

    /**
     * Get the current sprite revision counter.
     */
    public int getRevision() {
        return revision;
    }

    public void setRevisionListener(Runnable listener) {
        this.revisionListener = listener;
    }
}
