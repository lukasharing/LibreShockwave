package com.libreshockwave.player.debug;

import com.libreshockwave.bitmap.Bitmap;
import com.libreshockwave.cast.MemberType;
import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.player.Player;
import com.libreshockwave.player.PlayerEvent;
import com.libreshockwave.player.cast.CastMember;
import com.libreshockwave.player.event.EventDispatcher;
import com.libreshockwave.player.input.HitTester;
import com.libreshockwave.player.render.pipeline.RenderSprite;
import com.libreshockwave.player.render.pipeline.StageRenderer;
import com.libreshockwave.player.sprite.SpriteState;
import com.libreshockwave.vm.datum.Datum;

import java.util.List;

/**
 * Read-only stage hit inspector for developer tooling.
 */
public final class StageInspector {
    private static final int MAX_HITS = 80;
    private static final int MAX_TEXT_PREVIEW = 240;
    private static final String[] MOUSE_HANDLER_NAMES = {
            PlayerEvent.MOUSE_DOWN.getHandlerName(),
            PlayerEvent.MOUSE_UP.getHandlerName(),
            PlayerEvent.MOUSE_ENTER.getHandlerName(),
            PlayerEvent.MOUSE_LEAVE.getHandlerName(),
            PlayerEvent.MOUSE_WITHIN.getHandlerName(),
            PlayerEvent.MOUSE_UP_OUTSIDE.getHandlerName()
    };

    private StageInspector() {}

    public static String inspect(Player player, int stageX, int stageY) {
        StringBuilder out = new StringBuilder(32768);
        out.append('{');
        field(out, "schema", "libreshockwave.stageInspector.v1", false);
        if (player == null) {
            field(out, "error", "no player", true);
            out.append('}');
            return out.toString();
        }

        StageRenderer renderer = player.getStageRenderer();
        if (renderer == null) {
            field(out, "error", "no stage renderer", true);
            out.append('}');
            return out.toString();
        }

        int frame = player.getCurrentFrame();
        List<RenderSprite> sprites = renderer.getLastBakedSprites();
        boolean lastBaked = sprites != null && !sprites.isEmpty();
        if (!lastBaked) {
            sprites = renderer.getSpritesForFrame(frame);
        }

        EventDispatcher dispatcher = player.getEventDispatcher();
        int spriteCount = sprites != null ? sprites.size() : 0;
        int registryCount = renderer.getSpriteRegistry().getAll().size();
        TopHit top = findTopHits(renderer, sprites, dispatcher, stageX, stageY);

        out.append(",\"stage\":{");
        field(out, "x", stageX, false);
        field(out, "y", stageY, true);
        field(out, "frame", frame, true);
        field(out, "effectiveFrame", player.getEffectiveFrame(), true);
        field(out, "stageWidth", renderer.getStageWidth(), true);
        field(out, "stageHeight", renderer.getStageHeight(), true);
        field(out, "spriteCount", spriteCount, true);
        field(out, "registrySpriteCount", registryCount, true);
        field(out, "spriteSource", lastBaked ? "lastBaked" : "scoreFallback", true);
        field(out, "registryRevision", renderer.getSpriteRegistry().getRevision(), true);
        out.append('}');

        out.append(",\"top\":{");
        field(out, "boundsChannel", top.boundsChannel, false);
        field(out, "activeChannel", top.activeChannel, true);
        field(out, "interactiveExactChannel", top.interactiveExactChannel, true);
        field(out, "interactiveBoundsChannel", top.interactiveBoundsChannel, true);
        out.append('}');

        out.append(",\"hits\":[");
        appendHits(out, renderer, sprites, dispatcher, stageX, stageY);
        out.append(']');
        out.append('}');
        return out.toString();
    }

    private static TopHit findTopHits(StageRenderer renderer, List<RenderSprite> sprites,
                                      EventDispatcher dispatcher, int stageX, int stageY) {
        TopHit top = new TopHit();
        if (sprites == null) {
            return top;
        }
        for (int i = sprites.size() - 1; i >= 0; i--) {
            RenderSprite sprite = sprites.get(i);
            if (!isVisibleNow(renderer, sprite)) {
                continue;
            }
            boolean boundsHit = contains(sprite, stageX, stageY);
            if (!boundsHit) {
                continue;
            }
            boolean activeHit = HitTester.hitsSprite(sprite, stageX, stageY, false);
            boolean interactive = dispatcher != null && dispatcher.isSpriteMouseInteractive(sprite.getChannel());
            if (top.boundsChannel == 0) top.boundsChannel = sprite.getChannel();
            if (top.activeChannel == 0 && activeHit) top.activeChannel = sprite.getChannel();
            if (interactive) {
                if (top.interactiveBoundsChannel == 0) top.interactiveBoundsChannel = sprite.getChannel();
                if (top.interactiveExactChannel == 0 && activeHit) top.interactiveExactChannel = sprite.getChannel();
            }
        }
        return top;
    }

    private static void appendHits(StringBuilder out, StageRenderer renderer, List<RenderSprite> sprites,
                                   EventDispatcher dispatcher, int stageX, int stageY) {
        if (sprites == null) {
            return;
        }
        int written = 0;
        for (int i = sprites.size() - 1; i >= 0 && written < MAX_HITS; i--) {
            RenderSprite sprite = sprites.get(i);
            if (!isVisibleNow(renderer, sprite) || !contains(sprite, stageX, stageY)) {
                continue;
            }
            if (written > 0) out.append(',');
            appendHit(out, renderer, dispatcher, sprite, stageX, stageY, i);
            written++;
        }
    }

    private static void appendHit(StringBuilder out, StageRenderer renderer, EventDispatcher dispatcher,
                                  RenderSprite sprite, int stageX, int stageY, int renderIndex) {
        SpriteState state = renderer.getSpriteRegistry().get(sprite.getChannel());
        boolean activeHit = HitTester.hitsSprite(sprite, stageX, stageY, false);
        boolean buttonHit = HitTester.hitsSprite(sprite, stageX, stageY,
                sprite.getType() == RenderSprite.SpriteType.BUTTON);
        boolean interactive = dispatcher != null && dispatcher.isSpriteMouseInteractive(sprite.getChannel());

        out.append('{');
        field(out, "channel", sprite.getChannel(), false);
        field(out, "renderIndex", renderIndex, true);
        field(out, "locZ", sprite.getLocZ(), true);
        field(out, "type", String.valueOf(sprite.getType()), true);
        field(out, "visible", isVisibleNow(renderer, sprite), true);
        field(out, "mouseInteractive", interactive, true);
        field(out, "boundsHit", true, true);
        field(out, "activeAreaHit", activeHit, true);
        field(out, "buttonAreaHit", buttonHit, true);
        field(out, "hasBehaviors", sprite.hasBehaviors(), true);

        out.append(",\"bounds\":{");
        field(out, "left", sprite.getX(), false);
        field(out, "top", sprite.getY(), true);
        field(out, "right", sprite.getX() + sprite.getWidth(), true);
        field(out, "bottom", sprite.getY() + sprite.getHeight(), true);
        field(out, "width", sprite.getWidth(), true);
        field(out, "height", sprite.getHeight(), true);
        out.append('}');

        appendRender(out, sprite);
        appendState(out, state);
        appendMember(out, sprite);
        appendHandlers(out, dispatcher, sprite.getChannel());
        out.append('}');
    }

    private static void appendRender(StringBuilder out, RenderSprite sprite) {
        out.append(",\"render\":{");
        field(out, "ink", sprite.getInk(), false);
        field(out, "inkMode", String.valueOf(sprite.getInkMode()), true);
        field(out, "blend", sprite.getBlend(), true);
        field(out, "foreColor", color(sprite.getForeColor()), true);
        field(out, "backColor", color(sprite.getBackColor()), true);
        field(out, "hasForeColor", sprite.hasForeColor(), true);
        field(out, "hasBackColor", sprite.hasBackColor(), true);
        field(out, "flipH", sprite.isFlipH(), true);
        field(out, "flipV", sprite.isFlipV(), true);
        field(out, "rotation", sprite.getRotation(), true);
        field(out, "skew", sprite.getSkew(), true);
        field(out, "registrationX", sprite.getRegistrationX(), true);
        field(out, "registrationY", sprite.getRegistrationY(), true);
        Bitmap baked = sprite.getBakedBitmap();
        out.append(",\"bakedBitmap\":");
        appendBitmap(out, baked);
        out.append('}');
    }

    private static void appendState(StringBuilder out, SpriteState state) {
        out.append(",\"runtimeState\":");
        if (state == null) {
            out.append("null");
            return;
        }
        out.append('{');
        field(out, "channel", state.getChannel(), false);
        field(out, "locH", state.getLocH(), true);
        field(out, "locV", state.getLocV(), true);
        field(out, "locZ", state.getLocZ(), true);
        field(out, "width", state.getWidth(), true);
        field(out, "height", state.getHeight(), true);
        field(out, "visible", state.isVisible(), true);
        field(out, "puppet", state.isPuppet(), true);
        field(out, "dynamic", state.isDynamic(), true);
        field(out, "moveableSprite", state.isMoveableSprite(), true);
        field(out, "editable", state.isEditable(), true);
        field(out, "effectiveCastLib", state.getEffectiveCastLib(), true);
        field(out, "effectiveMember", state.getEffectiveCastMember(), true);
        field(out, "hasDynamicMember", state.hasDynamicMember(), true);
        field(out, "dynamicMemberMirrored", state.isEffectiveMemberMirrored(), true);
        field(out, "scriptInstanceCount", scriptInstanceCount(state), true);
        out.append('}');
    }

    private static void appendMember(StringBuilder out, RenderSprite sprite) {
        CastMemberChunk authored = sprite.getCastMember();
        CastMember dynamic = sprite.getDynamicMember();
        out.append(",\"member\":{");
        field(out, "name", sprite.getMemberName(), false);
        field(out, "castMemberId", sprite.getCastMemberId(), true);
        out.append(",\"authored\":");
        if (authored == null) {
            out.append("null");
        } else {
            out.append('{');
            field(out, "id", authored.id().value(), false);
            field(out, "name", authored.name(), true);
            field(out, "type", String.valueOf(authored.memberType()), true);
            field(out, "scriptId", authored.scriptId(), true);
            field(out, "regPointX", authored.regPointX(), true);
            field(out, "regPointY", authored.regPointY(), true);
            field(out, "specificDataLength", authored.specificData() != null ? authored.specificData().length : 0, true);
            out.append('}');
        }
        out.append(",\"dynamic\":");
        appendDynamicMember(out, dynamic);
        out.append('}');
    }

    private static void appendDynamicMember(StringBuilder out, CastMember member) {
        if (member == null) {
            out.append("null");
            return;
        }
        out.append('{');
        field(out, "castLib", member.getCastLibNumber(), false);
        field(out, "member", member.getMemberNumber(), true);
        field(out, "name", member.getName(), true);
        field(out, "type", String.valueOf(member.getMemberType()), true);
        field(out, "loaded", member.isLoaded(), true);
        field(out, "hasDynamicText", member.hasDynamicText(), true);
        field(out, "editable", member.isEditable(), true);
        if (member.getMemberType() == MemberType.TEXT || member.getMemberType() == MemberType.BUTTON) {
            String text = member.hasDynamicText() || member.isLoaded() ? member.getTextContent() : "";
            field(out, "textPreview", preview(text), true);
        }
        out.append(",\"bitmap\":null");
        out.append('}');
    }

    private static void appendHandlers(StringBuilder out, EventDispatcher dispatcher, int channel) {
        out.append(",\"handlers\":{");
        for (int i = 0; i < MOUSE_HANDLER_NAMES.length; i++) {
            if (i > 0) out.append(',');
            String handler = MOUSE_HANDLER_NAMES[i];
            string(out, handler);
            out.append(':').append(dispatcher != null && dispatcher.spriteHasHandler(channel, handler));
        }
        out.append('}');
    }

    private static void appendBitmap(StringBuilder out, Bitmap bitmap) {
        if (bitmap == null) {
            out.append("null");
            return;
        }
        out.append('{');
        field(out, "width", bitmap.getWidth(), false);
        field(out, "height", bitmap.getHeight(), true);
        field(out, "bitDepth", bitmap.getBitDepth(), true);
        field(out, "nativeAlpha", bitmap.isNativeAlpha(), true);
        field(out, "scriptModified", bitmap.isScriptModified(), true);
        field(out, "paletteIndicesLength", bitmap.getPaletteIndices() != null ? bitmap.getPaletteIndices().length : 0, true);
        field(out, "pixelCount", bitmap.getPixels() != null ? bitmap.getPixels().length : 0, true);
        out.append('}');
    }

    private static boolean isVisibleNow(StageRenderer renderer, RenderSprite sprite) {
        if (sprite == null || sprite.getChannel() <= 0) {
            return false;
        }
        SpriteState liveState = renderer.getSpriteRegistry().get(sprite.getChannel());
        return liveState != null ? liveState.isVisible() : sprite.isVisible();
    }

    private static boolean contains(RenderSprite sprite, int stageX, int stageY) {
        if (sprite == null || sprite.getChannel() <= 0) {
            return false;
        }
        int left = sprite.getX();
        int top = sprite.getY();
        return stageX >= left && stageX < left + sprite.getWidth()
                && stageY >= top && stageY < top + sprite.getHeight();
    }

    private static int scriptInstanceCount(SpriteState state) {
        List<Datum> scripts = state.getScriptInstanceList();
        return scripts != null ? scripts.size() : 0;
    }

    private static String preview(String value) {
        if (value == null) {
            return "";
        }
        String normalized = value.replace('\r', '\n');
        return normalized.length() <= MAX_TEXT_PREVIEW
                ? normalized
                : normalized.substring(0, MAX_TEXT_PREVIEW) + "...";
    }

    private static String color(int rgb) {
        String hex = Integer.toHexString(rgb & 0xFFFFFF).toUpperCase();
        while (hex.length() < 6) hex = "0" + hex;
        return "#" + hex;
    }

    private static void field(StringBuilder out, String key, String value, boolean comma) {
        if (comma) out.append(',');
        string(out, key);
        out.append(':');
        string(out, value);
    }

    private static void field(StringBuilder out, String key, int value, boolean comma) {
        if (comma) out.append(',');
        string(out, key);
        out.append(':').append(value);
    }

    private static void field(StringBuilder out, String key, double value, boolean comma) {
        if (comma) out.append(',');
        string(out, key);
        out.append(':').append(Double.isFinite(value) ? value : 0.0);
    }

    private static void field(StringBuilder out, String key, boolean value, boolean comma) {
        if (comma) out.append(',');
        string(out, key);
        out.append(':').append(value);
    }

    private static void string(StringBuilder out, String value) {
        if (value == null) {
            out.append("null");
            return;
        }
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append("\\u");
                        String hex = Integer.toHexString(c);
                        for (int j = hex.length(); j < 4; j++) out.append('0');
                        out.append(hex);
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static final class TopHit {
        int boundsChannel;
        int activeChannel;
        int interactiveExactChannel;
        int interactiveBoundsChannel;
    }
}
