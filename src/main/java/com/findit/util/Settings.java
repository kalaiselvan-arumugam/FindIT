package com.findit.util;

import java.util.prefs.Preferences;

/**
 * Application settings backed by {@link java.util.prefs.Preferences}.
 * All keys, defaults, and get/set helpers are centralised here.
 */
public final class Settings {

    private static final Settings INSTANCE = new Settings();
    private final Preferences prefs = Preferences.userRoot().node("com/findit");

    private Settings() {}

    public static Settings get() { return INSTANCE; }

    // ── Keys ─────────────────────────────────────────────────────────────────

    public String theme()           { return prefs.get("theme", "dark"); }
    public void   theme(String v)   { prefs.put("theme", v); }

    public String indexRoots()      { return prefs.get("indexRoots", ""); }
    public void   indexRoots(String v){ prefs.put("indexRoots", v); }

    public String excludePaths()    { return prefs.get("excludePaths", ""); }
    public void   excludePaths(String v){ prefs.put("excludePaths", v); }

    public int    maxResults()      { return prefs.getInt("maxResults", 100_000); }
    public void   maxResults(int v) { prefs.putInt("maxResults", v); }

    public int    debounceMs()      { return prefs.getInt("debounceMs", 150); }
    public void   debounceMs(int v) { prefs.putInt("debounceMs", v); }

    public boolean watcherEnabled()          { return prefs.getBoolean("watcherEnabled", true); }
    public void    watcherEnabled(boolean v) { prefs.putBoolean("watcherEnabled", v); }

    public boolean startMinimized()          { return prefs.getBoolean("startMinimized", false); }
    public void    startMinimized(boolean v) { prefs.putBoolean("startMinimized", v); }

    public boolean showIcons()          { return prefs.getBoolean("showIcons", true); }
    public void    showIcons(boolean v) { prefs.putBoolean("showIcons", v); }

    // ── Global hotkey ─────────────────────────────────────────────────────────

    public boolean hotkeyEnabled()          { return prefs.getBoolean("hotkeyEnabled", true); }
    public void    hotkeyEnabled(boolean v) { prefs.putBoolean("hotkeyEnabled", v); }

    /** Key combination string, e.g. "CTRL+SHIFT+F", "CTRL+SPACE". */
    public String hotkeyCombo()       { return prefs.get("hotkeyCombo", "CTRL+SHIFT+F"); }
    public void   hotkeyCombo(String v){ prefs.put("hotkeyCombo", v); }

    public void flush() {
        try { prefs.flush(); } catch (Exception ignored) {}
    }
}
