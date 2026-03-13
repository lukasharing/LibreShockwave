package com.libreshockwave.chunks;

import com.libreshockwave.DirectorFile;
import com.libreshockwave.format.ChunkType;
import com.libreshockwave.id.ChunkId;
import com.libreshockwave.id.FrameId;
import com.libreshockwave.io.BinaryReader;

import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Frame labels chunk (VWLB).
 * Contains labels for specific frames in the score.
 *
 * Structure matches dirplayer-rs vm-rust/src/director/chunks/score.rs FrameLabelsChunk
 */
public record FrameLabelsChunk(
    DirectorFile file,
    ChunkId id,
    List<FrameLabel> labels
) implements Chunk {

    @Override
    public ChunkType type() {
        return ChunkType.VWLB;
    }

    /**
     * A single frame label.
     */
    public record FrameLabel(
        FrameId frameNum,
        String label
    ) {}

    /**
     * Get a frame number by label name.
     */
    public int getFrameByLabel(String labelName) {
        for (FrameLabel label : labels) {
            if (label.label().equalsIgnoreCase(labelName)) {
                return label.frameNum().value();
            }
        }
        return -1;
    }

    /**
     * Get the label for a frame number.
     */
    public String getLabelForFrame(int frameNum) {
        for (FrameLabel label : labels) {
            if (label.frameNum().value() == frameNum) {
                return label.label();
            }
        }
        return null;
    }

    public static FrameLabelsChunk read(DirectorFile file, BinaryReader reader, ChunkId id, int version) {
        reader.setOrder(ByteOrder.BIG_ENDIAN);

        List<FrameLabel> labels = new ArrayList<>();

        if (reader.bytesLeft() < 2) {
            return new FrameLabelsChunk(file, id, labels);
        }

        int labelsCount = reader.readU16();

        // Read label frame/offset pairs
        // Format: (frameNum U16, labelOffset U16) per entry
        // Use parallel lists instead of int[] arrays to avoid TeaVM WASM code-gen
        // issues with array element assignment reordering
        List<Integer> frameNums = new ArrayList<>();
        List<Integer> labelOffsets = new ArrayList<>();
        for (int i = 0; i < labelsCount; i++) {
            if (reader.bytesLeft() < 4) break;
            int frameNum = reader.readU16();
            int labelOffset = reader.readU16();
            frameNums.add(frameNum);
            labelOffsets.add(labelOffset);
        }

        if (reader.bytesLeft() < 4) {
            return new FrameLabelsChunk(file, id, labels);
        }

        int labelsSize = reader.readI32();

        // Read entire string data blob, then extract labels by offset
        byte[] stringData = reader.readBytes(Math.min(labelsSize, reader.bytesLeft()));

        // Build sorted index array (sorted by label offset for correct boundary detection)
        int entryCount = frameNums.size();
        List<Integer> sortedIndices = new ArrayList<>();
        for (int i = 0; i < entryCount; i++) {
            sortedIndices.add(i);
        }
        sortedIndices.sort((a, b) -> {
            int offA = labelOffsets.get(a).intValue();
            int offB = labelOffsets.get(b).intValue();
            return Integer.compare(offA, offB);
        });

        for (int si = 0; si < sortedIndices.size(); si++) {
            int idx = sortedIndices.get(si).intValue();
            int labelOffset = labelOffsets.get(idx).intValue();
            int frameNum = frameNums.get(idx).intValue();

            if (labelOffset < 0 || labelOffset >= stringData.length) continue;

            // Find label length: either to next offset or end of data
            int labelEnd;
            if (si < sortedIndices.size() - 1) {
                int nextIdx = sortedIndices.get(si + 1).intValue();
                int nextOffset = labelOffsets.get(nextIdx).intValue();
                labelEnd = Math.min(nextOffset, stringData.length);
            } else {
                labelEnd = stringData.length;
            }

            // Trim trailing null bytes
            while (labelEnd > labelOffset && stringData[labelEnd - 1] == 0) {
                labelEnd--;
            }

            int labelLen = labelEnd - labelOffset;
            if (labelLen > 0) {
                String labelStr = new String(stringData, labelOffset, labelLen);
                FrameId fid = new FrameId(Math.max(1, frameNum));
                labels.add(new FrameLabel(fid, labelStr));
            }
        }

        return new FrameLabelsChunk(file, id, labels);
    }
}
