package com.libreshockwave.lookup;

import com.libreshockwave.chunks.CastMemberChunk;
import com.libreshockwave.chunks.ScriptChunk;
import com.libreshockwave.chunks.ScriptContextChunk;
import com.libreshockwave.id.ChunkId;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides script lookup functionality.
 * Encapsulates the logic for finding scripts by context ID and getting script types.
 */
public final class ScriptLookup {

    private final List<ScriptChunk> scripts;
    private final List<ScriptContextChunk> scriptContexts;
    private final List<CastMemberChunk> castMembers;
    private Map<Integer, ScriptChunk> scriptByContextId;
    private Map<ChunkId, CastMemberChunk> memberByScriptId;

    public ScriptLookup(List<ScriptChunk> scripts, List<ScriptContextChunk> scriptContexts,
                        List<CastMemberChunk> castMembers) {
        this.scripts = scripts;
        this.scriptContexts = scriptContexts;
        this.castMembers = castMembers;
    }

    /**
     * Get a script by its context ID (the scriptId stored in cast members).
     * This uses the ScriptContextChunk (Lctx) to map scriptId to chunk ID.
     * @param scriptId The script ID from the cast member
     * @return The script chunk, or null if not found
     */
    public ScriptChunk getByContextId(int scriptId) {
        List<ScriptChunk> matches = getAllByContextId(scriptId);
        if (!matches.isEmpty()) {
            return matches.get(0);
        }

        return null;
    }

    /**
     * Get all scripts matching a context ID across all Lctx chunks.
     * Older Afterburner movies can carry multiple script contexts whose local
     * indices overlap, so a cast member's scriptId is not globally unique.
     */
    public List<ScriptChunk> getAllByContextId(int scriptId) {
        List<ScriptChunk> matches = new ArrayList<>();
        // scriptId from cast members is 1-based, Lctx entries are 0-based
        int index = scriptId - 1;

        // Search through all script contexts (there can be one per cast library)
        for (ScriptContextChunk ctx : scriptContexts) {
            if (index >= 0 && index < ctx.entries().size()) {
                var entry = ctx.entries().get(index);
                if (entry.id().value() > 0) {
                    for (ScriptChunk script : scripts) {
                        if (script.id().equals(entry.id())) {
                            matches.add(script);
                            break;
                        }
                    }
                }
            }
        }

        // Fallback: try direct match by ID (scriptId is 1-based context index)
        for (ScriptChunk script : scripts) {
            if (script.id().value() == scriptId) {
                matches.add(script);
                break;
            }
        }

        return matches;
    }

    /**
     * Get the reliable script type for a script by looking up its associated cast member.
     * The script type stored in the cast member's specificData is the authoritative source.
     * @param script The script chunk to get the type for
     * @return The script type from the cast member, or null if not found
     */
    public ScriptChunk.ScriptType getScriptType(ScriptChunk script) {
        if (script == null) return null;
        ensureIndex();

        CastMemberChunk member = memberByScriptId.get(script.id());
        if (member != null) {
            return member.getScriptType();
        }

        return null;
    }

    /**
     * Get the cast member name associated with a script chunk.
     */
    public String getScriptName(ScriptChunk script) {
        if (script == null) return "";
        ensureIndex();

        CastMemberChunk member = memberByScriptId.get(script.id());
        if (member == null) {
            return "";
        }
        String name = member.name();
        return name != null ? name : "";
    }

    private void ensureIndex() {
        if (scriptByContextId != null && memberByScriptId != null) {
            return;
        }

        Map<ChunkId, ScriptChunk> scriptsById = new HashMap<>();
        for (ScriptChunk script : scripts) {
            scriptsById.put(script.id(), script);
        }

        Map<Integer, ScriptChunk> byContext = new HashMap<>();
        for (ScriptContextChunk ctx : scriptContexts) {
            for (int i = 0; i < ctx.entries().size(); i++) {
                var entry = ctx.entries().get(i);
                if (entry.id().value() <= 0) {
                    continue;
                }
                ScriptChunk script = scriptsById.get(entry.id());
                if (script != null) {
                    byContext.putIfAbsent(i + 1, script);
                }
            }
        }

        Map<ChunkId, CastMemberChunk> byScript = new HashMap<>();
        for (CastMemberChunk member : castMembers) {
            if (!member.isScript()) {
                continue;
            }
            ScriptChunk script = byContext.get(member.scriptId());
            if (script != null) {
                byScript.putIfAbsent(script.id(), member);
            }
        }

        scriptByContextId = byContext;
        memberByScriptId = byScript;
    }
}
