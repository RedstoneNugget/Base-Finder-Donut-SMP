package com.basefinder;

import com.basefinder.config.ModConfig;
import com.basefinder.gui.ConfigScreen;
import com.basefinder.render.MarkerRenderer;
import com.basefinder.scan.Patterns;
import com.basefinder.scan.ScanManager;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.event.lifecycle.v1.CommonLifecycleEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * "Base Finder Donut SMP" – rein clientseitige Fabric-Mod (Mod-ID base_finder).
 * Keine Server-Entrypoints, keine Pakete: gearbeitet wird nur mit den Chunks, die der Client hat.
 */
public class BaseFinderClient implements ClientModInitializer {

    public static final String MOD_ID = "base_finder";
    public static final String MOD_NAME = "Base Finder Donut SMP";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_NAME);

    private static ModConfig config;
    private static long saveAt = -1;

    public static KeyBinding menuKey;
    public static KeyBinding toggleMarkersKey;
    public static KeyBinding rescanKey;
    public static KeyBinding pauseKey;

    public static ModConfig config() {
        if (config == null) config = ModConfig.load();
        return config;
    }

    /** Nach jeder Änderung im Menü: sofort anwenden, Speichern gebündelt kurz danach. */
    public static void onConfigEdited() {
        config().sanitize();
        ScanManager.INSTANCE.configChanged();
        saveAt = System.currentTimeMillis() + 1000;
    }

    /** Config komplett ersetzen (z. B. für Tests mit Standardwerten). */
    public static void replaceConfig(ModConfig c) {
        config = c;
        onConfigEdited();
    }

    public static void saveNow() {
        saveAt = -1;
        config().save();
    }

    @Override
    public void onInitializeClient() {
        config();

        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(MOD_ID, "main"));
        menuKey = register("menu", GLFW.GLFW_KEY_G, category);
        toggleMarkersKey = register("toggle_markers", GLFW.GLFW_KEY_H, category);
        rescanKey = register("rescan", GLFW.GLFW_KEY_J, category);
        pauseKey = register("pause", GLFW.GLFW_KEY_K, category);

        ClientChunkEvents.CHUNK_LOAD.register(ScanManager.INSTANCE::onChunkLoad);
        ClientChunkEvents.CHUNK_UNLOAD.register(ScanManager.INSTANCE::onChunkUnload);
        // Block-Tags kommen vom Server; danach Muster mit "#tag" neu auflösen.
        CommonLifecycleEvents.TAGS_LOADED.register((registries, client) -> {
            if (client) {
                Patterns.clearCache();
                ScanManager.INSTANCE.configChanged();
            }
        });
        ClientTickEvents.END_CLIENT_TICK.register(BaseFinderClient::tick);
        ClientLifecycleEvents.CLIENT_STOPPING.register(c -> {
            if (saveAt >= 0) saveNow();
        });
        MarkerRenderer.register();

        LOGGER.info("{} geladen – G Menü, H Markierungen, J Vollscan, K Pause", MOD_NAME);
    }

    private static KeyBinding register(String name, int key, KeyBinding.Category category) {
        return KeyBindingHelper.registerKeyBinding(new KeyBinding("key." + MOD_ID + "." + name, InputUtil.Type.KEYSYM, key, category));
    }

    private static void tick(MinecraftClient mc) {
        ModConfig cfg = config();
        while (menuKey.wasPressed()) {
            if (mc.currentScreen == null) mc.setScreen(new ConfigScreen(null));
        }
        while (toggleMarkersKey.wasPressed()) {
            cfg.general.markersVisible = !cfg.general.markersVisible;
            saveAt = System.currentTimeMillis() + 1000;
            ScanManager.INSTANCE.notifier.showNow(Text.literal(Lang.t("Markierungen: ", "Markers: ") + (cfg.general.markersVisible ? Lang.t("sichtbar", "visible") : Lang.t("ausgeblendet", "hidden")))
                    .formatted(cfg.general.markersVisible ? Formatting.GREEN : Formatting.GRAY));
        }
        while (rescanKey.wasPressed()) {
            if (mc.world != null) {
                int n = ScanManager.INSTANCE.forceFullRescan();
                ScanManager.INSTANCE.notifier.showNow(Text.literal(Lang.t("Vollscan: ", "Full scan: ") + n + Lang.t(" Chunks werden neu gescannt …", " chunks are being rescanned …")).formatted(Formatting.AQUA));
            }
        }
        while (pauseKey.wasPressed()) {
            cfg.general.constantScanning = !cfg.general.constantScanning;
            onConfigEdited();
            ScanManager.INSTANCE.notifier.showNow(Text.literal(Lang.t("Konstantes Scannen: ", "Constant scanning: ") + (cfg.general.constantScanning ? Lang.t("läuft", "running") : Lang.t("pausiert", "paused")))
                    .formatted(cfg.general.constantScanning ? Formatting.GREEN : Formatting.YELLOW));
        }

        ScanManager.INSTANCE.tick(mc);

        if (saveAt >= 0 && System.currentTimeMillis() >= saveAt) saveNow();
    }
}
