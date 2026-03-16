package com.libreshockwave.editor;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;

/**
 * Editor preferences persisted to ~/.libreshockwave/preferences.json.
 */
public class Preferences {

    private static final Path DIR = Path.of(System.getProperty("user.home"), ".libreshockwave");
    private static final Path FILE = DIR.resolve("preferences.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static Preferences instance;

    private String lastOpenDirectory;

    public static Preferences get() {
        if (instance == null) {
            instance = load();
        }
        return instance;
    }

    public String getLastOpenDirectory() {
        return lastOpenDirectory;
    }

    public void setLastOpenDirectory(String path) {
        this.lastOpenDirectory = path;
        save();
    }

    private void save() {
        try {
            Files.createDirectories(DIR);
            JsonObject json = new JsonObject();
            if (lastOpenDirectory != null) {
                json.addProperty("lastOpenDirectory", lastOpenDirectory);
            }
            Files.writeString(FILE, GSON.toJson(json));
        } catch (IOException e) {
            System.err.println("Failed to save preferences: " + e.getMessage());
        }
    }

    private static Preferences load() {
        Preferences prefs = new Preferences();
        if (!Files.exists(FILE)) return prefs;
        try {
            String content = Files.readString(FILE);
            JsonObject json = JsonParser.parseString(content).getAsJsonObject();
            if (json.has("lastOpenDirectory")) {
                prefs.lastOpenDirectory = json.get("lastOpenDirectory").getAsString();
            }
        } catch (Exception e) {
            System.err.println("Failed to load preferences: " + e.getMessage());
        }
        return prefs;
    }
}
