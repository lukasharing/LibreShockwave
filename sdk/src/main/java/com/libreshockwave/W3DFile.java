package com.libreshockwave;

import com.libreshockwave.io.BinaryReader;
import com.libreshockwave.w3d.*;

import java.io.IOException;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class W3DFile {

    private int version;
    private final List<W3DEntry> entries = new ArrayList<>();
    private final List<W3DNode> nodes = new ArrayList<>();
    private final List<W3DShape> shapes = new ArrayList<>();
    private final List<W3DMeshResource> meshResources = new ArrayList<>();
    private final List<W3DTexture> textures = new ArrayList<>();
    private final List<W3DMaterial> materials = new ArrayList<>();
    private final List<W3DResourceRef> resourceRefs = new ArrayList<>();

    private W3DFile() {}

    public static W3DFile load(Path path) throws IOException {
        return load(Files.readAllBytes(path));
    }

    public static W3DFile load(byte[] data) {
        W3DFile file = new W3DFile();
        file.parse(data);
        return file;
    }

    private void parse(byte[] data) {
        BinaryReader reader = new BinaryReader(data, ByteOrder.LITTLE_ENDIAN);

        // Read entries until EOF
        while (reader.bytesLeft() >= 10) { // minimum entry: 2 (type) + 4 (len) + 4 (parent)
            W3DEntry entry = W3DEntry.read(reader);
            entries.add(entry);

            // Extract version from VERSION entries
            if (entry.type() == W3DEntryType.VERSION && entry.data().length >= 4) {
                BinaryReader vr = new BinaryReader(entry.data(), ByteOrder.LITTLE_ENDIAN);
                version = vr.readI32();
            }

            // Parse typed entries
            switch (entry.type()) {
                case NODE, LIGHT_DATA -> nodes.add(W3DNode.parse(entry.data()));
                case SHAPE -> shapes.add(W3DShape.parse(entry.data()));
                case MESH_RESOURCE -> meshResources.add(W3DMeshResource.parse(entry.data()));
                case BINARY_DATA -> textures.add(W3DTexture.parse(entry.data()));
                case MATERIAL -> materials.add(W3DMaterial.parse(entry.data()));
                case RESOURCE_REF -> resourceRefs.add(W3DResourceRef.parse(entry.data()));
                default -> {}
            }
        }
    }

    // Header

    public int getVersion() {
        return version;
    }

    // All entries (raw)

    public List<W3DEntry> getEntries() {
        return entries;
    }

    // Typed accessors

    public List<W3DNode> getNodes() {
        return nodes;
    }

    public List<W3DShape> getShapes() {
        return shapes;
    }

    public List<W3DMeshResource> getMeshResources() {
        return meshResources;
    }

    public List<W3DTexture> getTextures() {
        return textures;
    }

    public List<W3DMaterial> getMaterials() {
        return materials;
    }

    public List<W3DResourceRef> getResourceRefs() {
        return resourceRefs;
    }

    // Lookup

    public Optional<W3DNode> findNode(String name) {
        return nodes.stream()
            .filter(n -> n.name().equals(name))
            .findFirst();
    }

    public Optional<W3DTexture> findTexture(String name) {
        return textures.stream()
            .filter(t -> t.name().equals(name))
            .findFirst();
    }
}
