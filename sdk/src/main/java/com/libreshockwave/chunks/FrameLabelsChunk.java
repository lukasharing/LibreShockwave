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
        List<int[]> labelFrames = new ArrayList<>();
        for (int i = 0; i < labelsCount; i++) {
            if (reader.bytesLeft() < 4) break;
            int frameNum = reader.readU16();
            int labelOffset = reader.readU16();
            labelFrames.add(new int[] { labelOffset, frameNum });
        }

        if (reader.bytesLeft() < 4) {
            return new FrameLabelsChunk(file, id, labels);
        }

        int labelsSize = reader.readI32();

        // Read entire string data blob, then extract labels by offset
        byte[] stringData = reader.readBytes(Math.min(labelsSize, reader.bytesLeft()));

        for (int i = 0; i < labelFrames.size(); i++) {
            int labelOffset = labelFrames.get(i)[0];
            int frameNum = labelFrames.get(i)[1];

            if (labelOffset < 0 || labelOffset >= stringData.length) continue;

            // Find label length: either to next offset or end of data
            int labelEnd;
            if (i < labelFrames.size() - 1) {
                labelEnd = Math.min(labelFrames.get(i + 1)[0], stringData.length);
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
                labels.add(new FrameLabel(new FrameId(Math.max(1, frameNum)), labelStr));
            }
        }

        return new FrameLabelsChunk(file, id, labels);
    }
}
