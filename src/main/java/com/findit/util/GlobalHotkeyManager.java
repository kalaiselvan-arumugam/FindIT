package com.findit.util;

import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinUser;

import java.awt.event.KeyEvent;

/**
 * Registers and manages a global hotkey using JNA (Windows only).
 * On non-Windows platforms, this class is a no-op.
 */
public class GlobalHotkeyManager {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalHotkeyManager.class);
    private static final int HOTKEY_ID  = 0xBEEF;
    private static final int WM_HOTKEY  = 0x0312;

    // Windows VK modifier bits
    private static final int MOD_ALT   = 0x0001;
    private static final int MOD_CTRL  = 0x0002;
    private static final int MOD_SHIFT = 0x0004;
    private static final int MOD_WIN   = 0x0008;

    private static final boolean IS_WINDOWS =
            System.getProperty("os.name", "").toLowerCase().contains("windows");

    private final Runnable onActivate;
    private Thread pollThread;
    private volatile boolean running = false;

    public GlobalHotkeyManager(Runnable onActivate) {
        this.onActivate = onActivate;
    }

    /** Register (or re-register) the hotkey from settings. */
    public void apply() {
        unregister();
        if (!IS_WINDOWS) return;
        if (!Settings.get().hotkeyEnabled()) return;

        String combo = Settings.get().hotkeyCombo();
        int[] modVk = parseCombo(combo);
        if (modVk == null) {
            LOG.warn("Cannot parse hotkey combo: {}", combo);
            return;
        }

        running = true;
        pollThread = new Thread(() -> {
            try {
                boolean ok = User32.INSTANCE.RegisterHotKey(null, HOTKEY_ID, modVk[0], modVk[1]);
                if (!ok) {
                    LOG.warn("RegisterHotKey failed for {} — key may be in use by another app", combo);
                    return;
                }
            } catch (Exception e) {
                LOG.warn("RegisterHotKey error: {}", e.getMessage());
                return;
            }
            LOG.info("Global hotkey registered: {}", combo);

            WinUser.MSG msg = new WinUser.MSG();
            while (running) {
                try {
                    // PeekMessage with PM_REMOVE (0x0001) doesn't block forever
                    boolean hasMsg = User32.INSTANCE.PeekMessage(msg, null, 0, 0, 1);
                    if (hasMsg) {
                        if (msg.message == WM_HOTKEY && msg.wParam.intValue() == HOTKEY_ID) {
                            Platform.runLater(onActivate);
                        }
                    } else {
                        Thread.sleep(50); 
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    if (running) LOG.debug("Hotkey poll error: {}", e.getMessage());
                }
            }
            
            // Unregister MUST happen on the thread that registered it
            try { User32.INSTANCE.UnregisterHotKey(null, HOTKEY_ID); } catch (Exception ignored) {}
        }, "findit-hotkey");
        pollThread.setDaemon(true);
        pollThread.start();
    }

    public void unregister() {
        running = false;
        if (pollThread != null) { 
            pollThread.interrupt(); 
            pollThread = null; 
        }
        // Native UnregisterHotKey moved to thread cleanup block
    }

    /** Parse robustly: "ALT+SPACE", "ALT_CTRL+F1", etc. → [modifiers, vkCode] */
    private int[] parseCombo(String combo) {
        if (combo == null || combo.isBlank()) return null;
        int modifiers = 0, vk = 0;
        String upper = combo.toUpperCase();
        
        if (upper.contains("ALT")) modifiers |= MOD_ALT;
        if (upper.contains("CTRL") || upper.contains("CONTROL")) modifiers |= MOD_CTRL;
        if (upper.contains("SHIFT")) modifiers |= MOD_SHIFT;
        if (upper.contains("WIN")) modifiers |= MOD_WIN;
        
        String remainder = upper
            .replace("ALT", "")
            .replace("CONTROL", "")
            .replace("CTRL", "")
            .replace("SHIFT", "")
            .replace("WIN", "")
            .replaceAll("[+\\-_ ]", "")
            .trim();
        
        if (!remainder.isEmpty()) {
            vk = resolveVk(remainder);
        }
        return vk != 0 ? new int[]{modifiers, vk} : null;
    }

    private int resolveVk(String key) {
        return switch (key) {
            case "SPACE"  -> KeyEvent.VK_SPACE;
            case "ENTER"  -> KeyEvent.VK_ENTER;
            case "TAB"    -> KeyEvent.VK_TAB;
            case "ESC", "ESCAPE" -> KeyEvent.VK_ESCAPE;
            case "F1"  -> KeyEvent.VK_F1;  case "F2"  -> KeyEvent.VK_F2;
            case "F3"  -> KeyEvent.VK_F3;  case "F4"  -> KeyEvent.VK_F4;
            case "F5"  -> KeyEvent.VK_F5;  case "F6"  -> KeyEvent.VK_F6;
            case "F7"  -> KeyEvent.VK_F7;  case "F8"  -> KeyEvent.VK_F8;
            case "F9"  -> KeyEvent.VK_F9;  case "F10" -> KeyEvent.VK_F10;
            case "F11" -> KeyEvent.VK_F11; case "F12" -> KeyEvent.VK_F12;
            default -> key.length() == 1 ? (int) key.charAt(0) : 0;
        };
    }
}
