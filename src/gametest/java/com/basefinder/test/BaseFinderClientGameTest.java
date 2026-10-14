package com.basefinder.test;

import com.basefinder.BaseFinderClient;
import com.basefinder.config.ModConfig;
import com.basefinder.gui.ConfigScreen;
import com.basefinder.scan.ChunkResult;
import com.basefinder.scan.Evaluator;
import com.basefinder.scan.Marker;
import com.basefinder.scan.ScanManager;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestDedicatedServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerConnection;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.ChunkPos;
import org.lwjgl.glfw.GLFW;

import java.util.List;
import java.util.function.Predicate;

/**
 * Automatischer In-Game-Test: gradlew runClientGameTest
 * Baut Testszenen per Befehl, wartet auf die Scans und prüft die Ergebnisse.
 * Screenshots landen in build/run/clientGameTest/screenshots.
 */
public class BaseFinderClientGameTest implements FabricClientGameTest {

    private static final String BEE = "{entity_data:{id:\"minecraft:bee\"},ticks_in_hive:0,min_ticks_in_hive:600}";
    private static final String BEES3 = "{bees:[" + BEE + "," + BEE + "," + BEE + "]}";
    private static final String BEES2 = "{bees:[" + BEE + "," + BEE + "]}";
    private static final String BEES1 = "{bees:[" + BEE + "]}";

    @Override
    public void runTest(ClientGameTestContext context) {
        // Standard-Config für reproduzierbare Ergebnisse
        // Tests prüfen die deutschen Texte; die englische Fassung wird extra geprüft
        context.runOnClient(c -> c.getLanguageManager().setLanguage("de_de"));
        // immer ohne Ton
        context.runOnClient(c -> c.options.getSoundVolumeOption(net.minecraft.sound.SoundCategory.MASTER).setValue(0.0));
        context.runOnClient(c -> {
            ModConfig cfg = new ModConfig();
            BaseFinderClient.replaceConfig(cfg);
        });
        String only = System.getProperty("basefinder.only", "");
        if (only.isEmpty() || only.contains("sp")) singleplayer(context);
        if (only.isEmpty() || only.contains("server")) dedicatedServer(context);
        if (only.isEmpty() || only.contains("structures")) structureSurvey(context);
        if (only.isEmpty() || only.contains("normal")) normalWorld(context);
        log("ALLE TESTS BESTANDEN");
    }

    // ------------------------------------------------------------------ Echte Strukturen (Fehlalarm-Prüfung)

    private record Placement(String dim, String structure) {
    }

    private void structureSurvey(ClientGameTestContext context) {
        List<Placement> list = List.of(
                new Placement("overworld", "village_plains"), new Placement("overworld", "village_desert"),
                new Placement("overworld", "village_savanna"), new Placement("overworld", "village_snowy"),
                new Placement("overworld", "village_taiga"), new Placement("overworld", "mineshaft"),
                new Placement("overworld", "mineshaft_mesa"), new Placement("overworld", "pillager_outpost"),
                new Placement("overworld", "desert_pyramid"), new Placement("overworld", "jungle_pyramid"),
                new Placement("overworld", "igloo"), new Placement("overworld", "swamp_hut"),
                new Placement("overworld", "trial_chambers"), new Placement("overworld", "ancient_city"),
                new Placement("overworld", "stronghold"), new Placement("overworld", "monument"),
                new Placement("overworld", "trail_ruins"), new Placement("overworld", "ruined_portal"),
                new Placement("overworld", "shipwreck"), new Placement("overworld", "mansion"),
                new Placement("the_nether", "fortress"), new Placement("the_nether", "bastion_remnant"),
                new Placement("the_end", "end_city"));
        int falseAlarms = 0;
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(true).create()) {
            TestServerContext server = sp.getServer();
            sp.getClientWorld().waitForChunksRender();
            server.runCommand("gamemode creative @a");
            server.runCommand("gamerule doDaylightCycle false");
            server.runCommand("gamerule doMobSpawning false");
            int i = 0;
            for (Placement p : list) {
                i++;
                int x = 2000 * i;
                int y = p.dim.equals("overworld") ? -60 : 64;
                String in = "execute in minecraft:" + p.dim + " run ";
                if (!p.dim.equals("overworld")) {
                    server.runCommand(in + "tp @a " + x + " " + (y + 40) + " 0");
                } else {
                    server.runCommand("tp @a " + x + " -40 0");
                }
                context.waitTicks(20);
                sp.getClientWorld().waitForChunksRender();
                if (!p.dim.equals("overworld")) {
                    // Platz schaffen, damit die Struktur im Nether/End nicht im Gestein steckt
                    server.runCommand(in + "fill " + (x - 40) + " " + (y - 20) + " -40 " + (x + 40) + " " + (y + 40) + " 40 minecraft:air");
                }
                server.runCommand(in + "place structure minecraft:" + p.structure + " " + x + " " + y + " 0");
                context.waitTicks(40);
                waitScans(context, c -> true, p.structure);
                context.waitTicks(40);
                waitScans(context, c -> true, p.structure + " (Nachscan)");
                final int fx = x;
                List<String> report = context.computeOnClient(c -> surveyReport(c, fx));
                for (String line : report) log("  " + line);
                long alarms = report.stream().filter(l -> l.startsWith("MELDUNG")).count();
                falseAlarms += (int) alarms;
                Runtime rt = Runtime.getRuntime();
                log("Struktur " + p.structure + ": " + alarms + " gemeldete Chunks (Heap " + (rt.totalMemory() - rt.freeMemory()) / 1048576 + " / " + rt.maxMemory() / 1048576 + " MB, Cache " + context.computeOnClient(c -> ScanManager.INSTANCE.cachedCount()) + ")");
                if (i % 6 == 0 || i == list.size()) context.takeScreenshot("3" + i + "_struktur_" + p.structure);
            }
            server.runCommand("execute in minecraft:overworld run tp @a 0 -60 0");
            context.waitTicks(20);
        }
        log("Struktur-Umfrage: insgesamt " + falseAlarms + " gemeldete Strukturchunks");
    }

    /** Pro Chunk in der Nähe der Struktur: Rohscore, erkannte Struktur und ob gemeldet wird. */
    private static List<String> surveyReport(MinecraftClient c, int x) {
        List<String> out = new java.util.ArrayList<>();
        ModConfig cfg = BaseFinderClient.config();
        com.basefinder.scan.Compiled comp = new com.basefinder.scan.Compiled(cfg);
        java.util.Map<Long, List<String>> structures = Evaluator.detectStructures(ScanManager.INSTANCE.cache(), cfg);
        for (ChunkResult r : ScanManager.INSTANCE.cache().values()) {
            if (Math.abs((r.cx << 4) - x) > 100) continue;
            for (ChunkResult.SpawnerFact f : r.spawners) {
                Evaluator.SpawnerVerdict v = Evaluator.spawnerVerdict(f, cfg.spawners, comp, c.world.getRegistryKey());
                out.add((v.reasons().isEmpty() ? "SPAWNER ok " : "MELDUNG SPAWNER ") + f.pos().toShortString() + " Mob " + f.mob()
                        + " Gründe " + v.reasons() + " Umgebung " + java.util.Arrays.toString(f.contextCounts()));
            }
        }
        for (ChunkResult r : ScanManager.INSTANCE.cache().values()) {
            if (Math.abs((r.cx << 4) - x) > 100) continue;
            Evaluator.AnomalyScore s = Evaluator.scoreChunk(r, structures.get(r.key), cfg, comp, c.world.getRegistryKey());
            if (s.raw < 4 && s.structure == null) continue;
            boolean shown = s.score >= cfg.anomaly.minScore;
            out.add((shown ? "MELDUNG " : "still   ") + "Chunk (" + r.cx + "," + r.cz + ") roh " + Math.round(s.raw)
                    + " Struktur " + s.structure + " | " + s.reason(4));
        }
        return out;
    }

    // ------------------------------------------------------------------ Normale Welt (Leistung + Fehlalarme)

    private void normalWorld(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(true)
                .adjustSettings(cr -> {
                    cr.setWorldType(cr.getNormalWorldTypes().get(0));
                    cr.setSeed("base finder");
                    cr.setGenerateStructures(true);
                }).create()) {
            TestServerContext server = sp.getServer();
            server.runCommand("gamemode creative @a");
            server.runCommand("gamerule doDaylightCycle false");
            server.runCommand("gamerule doMobSpawning false");
            sp.getClientWorld().waitForChunksRender(true, 6000);
            for (int round = 0; round < 3; round++) {
                if (round > 0) {
                    server.runCommand("tp @a " + (round * 1500) + " 200 " + (round * 700));
                    context.waitTicks(10);
                    sp.getClientWorld().waitForChunksRender(true, 6000);
                }
                context.computeOnClient(c -> ScanManager.INSTANCE.takePeakTickMs());
                long scannedBefore = context.computeOnClient(c -> ScanManager.INSTANCE.scannedChunks());
                long t0 = System.currentTimeMillis();
                context.computeOnClient(c -> com.basefinder.scan.ChunkScanJob.takePhaseStats());
                int ticks = context.waitFor(c -> ScanManager.INSTANCE.coverage() >= 1.0, 3000);
                log("Normale Welt Runde " + round + ": alle Chunks gescannt nach " + ticks + " Ticks");
                log("Phasen: " + context.computeOnClient(c -> com.basefinder.scan.ChunkScanJob.takePhaseStats()));
                long ms = System.currentTimeMillis() - t0;
                long scanned = context.computeOnClient(c -> ScanManager.INSTANCE.scannedChunks()) - scannedBefore;
                double peak = context.computeOnClient(c -> ScanManager.INSTANCE.takePeakTickMs());
                log(String.format("Normale Welt Runde %d: %d Chunks in %.1f s (%.1f Chunks/s), Spitzen-Tick %.2f ms, Radius %d",
                        round, scanned, ms / 1000.0, scanned * 1000.0 / Math.max(1, ms), peak,
                        context.computeOnClient(c -> ScanManager.INSTANCE.radius())));
                dump(context);
                context.runOnClient(c -> {
                    com.basefinder.scan.Compiled comp = new com.basefinder.scan.Compiled(BaseFinderClient.config());
                    for (ChunkResult r : ScanManager.INSTANCE.cache().values()) {
                        for (ChunkResult.SpawnerFact f : r.spawners) {
                            log("    Spawner " + f.pos().toShortString() + " Mob " + f.mob() + " Gründe "
                                    + Evaluator.spawnerVerdict(f, BaseFinderClient.config().spawners, comp, c.world.getRegistryKey()).reasons()
                                    + " Umgebung " + java.util.Arrays.toString(f.contextCounts()));
                        }
                    }
                    for (Marker m : ScanManager.INSTANCE.results().get(Marker.Mode.ANOMALY)) {
                        ChunkResult r = ScanManager.INSTANCE.cache().get(Long.parseLong(m.key.substring(2)));
                        if (r == null) continue;
                        StringBuilder sb = new StringBuilder();
                        r.counts.reference2IntEntrySet().stream().sorted((x, y) -> y.getIntValue() - x.getIntValue()).limit(40)
                                .forEach(e -> sb.append(net.minecraft.registry.Registries.BLOCK.getId(e.getKey()).getPath()).append("=").append(e.getIntValue()).append(" "));
                        log("    Inhalt " + m.key + ": " + sb + " | Stärken " + java.util.Arrays.toString(r.structureStrength));
                    }
                });
                profile(context, "normale Welt Runde " + round);
                if (round == 0) {
                    server.runCommand("tp @a 0 120 0 facing 30 60 30");
                    context.waitTicks(20);
                    context.takeScreenshot("40_normale_welt");
                }
            }
        }
    }

    // ------------------------------------------------------------------ Einzelspieler

    private void singleplayer(ClientGameTestContext context) {
        try (TestSingleplayerContext sp = context.worldBuilder().setUseConsistentSettings(true).create()) {
            TestServerContext server = sp.getServer();
            sp.getClientWorld().waitForChunksRender();
            buildScene(server);
            server.runCommand("tp @a 0.5 -60 0.5 0 0");
            context.waitTicks(20);

            // Warten, bis alles gescannt und ausgewertet ist
            waitScans(context, c -> amethyst(c).size() >= 2 && !bees(c).isEmpty() && kelp(c).size() >= 2 && !anomaly(c).isEmpty(), "Erste Funde");
            context.waitTicks(30);
            double peak = context.computeOnClient(c -> ScanManager.INSTANCE.takePeakTickMs());
            log("Spitzen-Tickdauer beim ersten Scan: " + String.format("%.2f", peak) + " ms");
            dump(context);
            profile(context, "nach erstem Scan");

            // --- Amethyst ---
            List<Marker> am = context.computeOnClient(BaseFinderClientGameTest::amethyst);
            check(am.size() == 2, "Amethyst: 2 Chunks über Schwelle erwartet, waren " + am.size());
            check(am.get(0).best && am.get(0).lines.get(1).equals("25 Cluster"), "Amethyst: bester Chunk mit 25 Clustern erwartet: " + am.get(0).lines);
            check(am.get(1).lines.get(1).equals("16 Cluster"), "Amethyst: zweiter Chunk 16 Cluster: " + am.get(1).lines);
            check(am.get(0).box.minY == -59 && am.get(0).box.maxY == -58, "Amethyst: Rahmen von Boden bis Decke der Funde: " + am.get(0).box);

            // Knospen zuschalten -> 25 + 5 große Knospen = 30
            context.runOnClient(c -> {
                BaseFinderClient.config().amethyst.countLargeBud = true;
                BaseFinderClient.onConfigEdited();
            });
            context.waitTicks(10);
            am = context.computeOnClient(BaseFinderClientGameTest::amethyst);
            check(am.get(0).lines.get(1).startsWith("30 gezählt"), "Amethyst: mit großen Knospen 30 erwartet: " + am.get(0).lines);
            context.runOnClient(c -> {
                BaseFinderClient.config().amethyst.countLargeBud = false;
                BaseFinderClient.onConfigEdited();
            });

            // --- Bienen ---
            context.waitFor(c -> !bees(c).isEmpty() && bees(c).get(0).lines.get(3).startsWith("Bienen: 3"), 200);
            List<Marker> bz = context.computeOnClient(BaseFinderClientGameTest::bees);
            check(bz.size() == 2, "Bienen: 2 unnatürliche (Höhle + Stock ohne Baum) erwartet, waren " + bz.size() + " " + lines(bz));
            check(bz.get(0).lines.get(1).startsWith("Grund: Unter der Erde"), "Bienen: Hauptgrund 'Unter der Erde': " + bz.get(0).lines);
            check(bz.get(0).lines.get(3).equals("Bienen: 3 · Honig: 3/5"), "Bienen: 3 Bienen, Honig 3: " + bz.get(0).lines);
            check(bz.get(1).lines.get(0).contains("Bienenstock") && bz.get(1).lines.get(1).startsWith("Grund: Keine Baumstruktur"),
                    "Bienen: Bienenstock ohne Baum an zweiter Stelle: " + bz.get(1).lines);
            check(bz.stream().noneMatch(m -> m.target.x == -39.5), "Bienen: natürliches Baum-Nest darf nicht markiert sein");
            // leeres Nest wird ausgeblendet, mit Schalter sichtbar
            context.runOnClient(c -> {
                BaseFinderClient.config().bees.hideEmpty = false;
                BaseFinderClient.onConfigEdited();
            });
            context.waitTicks(10);
            check(context.computeOnClient(BaseFinderClientGameTest::bees).size() == 3, "Bienen: mit 'Leere Nester zeigen' 3 Funde");
            context.runOnClient(c -> {
                BaseFinderClient.config().bees.hideEmpty = true;
                BaseFinderClient.onConfigEdited();
            });

            // --- Spawner ---
            context.waitFor(c -> ScanManager.INSTANCE.results().get(Marker.Mode.SPAWNERS).size() >= 4, 200);
            List<Marker> sp2 = context.computeOnClient(c -> ScanManager.INSTANCE.results().get(Marker.Mode.SPAWNERS));
            check(sp2.size() == 4, "Spawner: 3 unnatürliche + 1 natürlicher erwartet: " + lines(sp2));
            check(sp2.get(3).lines.get(0).equals("Spawner (natürlich)") && sp2.get(3).lines.get(2).equals("Struktur: Verlies"),
                    "Spawner: echtes Verlies wird als natürlich angezeigt: " + sp2.get(3).lines);
            check(sp2.get(0).lines.get(2).startsWith("Grund: Mob nie aus natürlichen Spawnern") && sp2.get(0).target.x == 20.5,
                    "Spawner: Schweine-Spawner zuerst (falscher Mob): " + sp2.get(0).lines);
            check(sp2.get(1).lines.get(2).startsWith("Grund: Falsche Dimension (erwartet: Nether-Festung)"),
                    "Spawner: Lohe in der Oberwelt: " + sp2.get(1).lines);
            check(sp2.get(2).lines.get(2).startsWith("Grund: Ohne Mob"), "Spawner: leerer Spawner: " + sp2.get(2).lines);
            check(context.computeOnClient(c -> ScanManager.INSTANCE.results().qualifying.get(Marker.Mode.SPAWNERS).stream()
                    .noneMatch(f -> f.key().equals("S:" + new net.minecraft.util.math.BlockPos(39, -60, -25).asLong()))),
                    "Spawner: natürliches Verlies wird nicht gemeldet");

            // --- Kelp ---
            List<Marker> kp = context.computeOnClient(BaseFinderClientGameTest::kelp);
            check(kp.size() == 2, "Kelp: 2 Säulen >= 26 erwartet, waren " + kp.size() + " " + lines(kp));
            // Kelp wächst während des Tests weiter (Zufallsticks) - daher "mindestens"
            int k1 = Integer.parseInt(kp.get(0).lines.get(1).replaceAll("[^0-9]", ""));
            int k2 = Integer.parseInt(kp.get(1).lines.get(1).replaceAll("[^0-9]", ""));
            check(k1 >= 31 && k2 >= 27 && k2 < 31 && kp.get(0).target.x == -10.5, "Kelp: Höhen ~31 und ~27: " + lines(kp));

            // --- Anomalien ---
            List<Marker> an = context.computeOnClient(BaseFinderClientGameTest::anomaly);
            Marker base = an.stream().filter(m -> m.key.equals("N:" + ChunkPos.toLong(4, -1))).findFirst().orElse(null);
            check(base != null, "Anomalie: Basis-Chunk (4, -1) erwartet: " + lines(an));
            check(base.lines.get(1).contains("wahrscheinlich Basis"), "Anomalie: 'wahrscheinlich Basis' erwartet: " + base.lines);
            check(base.lines.get(2).contains("8 Fackeln"), "Anomalie: Grund mit 8 Fackeln: " + base.lines);
            check(base.lines.stream().anyMatch(l -> l.contains("künstl. beleuchtete")), "Anomalie: Licht-Check hat künstlich beleuchtete Luft gefunden: " + base.lines);
            check(an.stream().noneMatch(m -> m.key.equals("N:" + ChunkPos.toLong(-1, -4))), "Anomalie: Dorf-Chunk (Glocke) muss vom Strukturfilter ausgeblendet sein");
            check("Dorf".equals(context.computeOnClient(c -> Evaluator.detectStructure(ScanManager.INSTANCE.cache().get(ChunkPos.toLong(-1, -4)), ScanManager.INSTANCE.cache(), BaseFinderClient.config()))),
                    "Anomalie: Dorf erkannt");
            int scoreBefore = score(base);

            // Screenshots der Markierungen
            server.runCommand("tp @a 20 -45 20 facing 60 -58 -8");
            context.waitTicks(10);
            sp.getClientWorld().waitForChunksRender();
            context.takeScreenshot("01_uebersicht");
            server.runCommand("tp @a 56 -59 -8 facing 71 -57 -8");
            context.waitTicks(10);
            context.takeScreenshot("02_basis_durch_wand");
            server.runCommand("tp @a 30 -50 30 facing 52 -59 52");
            context.waitTicks(10);
            context.takeScreenshot("03_amethyst");
            server.runCommand("tp @a -30 -50 30 facing -41 -57 55");
            context.waitTicks(10);
            context.takeScreenshot("04_bienennest_hoehle");
            server.runCommand("tp @a -20 -40 50 facing -10 -40 66");
            context.waitTicks(10);
            context.takeScreenshot("05_kelp");
            server.runCommand("tp @a 10 -55 25 facing 20 -60 40");
            context.waitTicks(10);
            context.takeScreenshot("05b_spawner");

            // --- Änderung erkennen: 10 Fackeln mehr in der Basis ---
            server.runCommand("tp @a 0.5 -60 0.5 0 0");
            context.waitTicks(5);
            context.computeOnClient(c -> ScanManager.INSTANCE.takePeakTickMs());
            server.runCommand("fill 67 -60 -6 76 -60 -6 minecraft:torch");
            context.waitFor(c -> {
                Marker b = anomaly(c).stream().filter(m -> m.key.equals("N:" + ChunkPos.toLong(4, -1))).findFirst().orElse(null);
                return b != null && b.lines.get(2).contains("18 Fackeln");
            }, 200);
            Marker base2 = context.computeOnClient(c -> anomaly(c).stream().filter(m -> m.key.equals("N:" + ChunkPos.toLong(4, -1))).findFirst().orElseThrow());
            check(score(base2) > scoreBefore, "Anomalie: Score steigt nach neuen Fackeln (" + scoreBefore + " -> " + score(base2) + ")");

            // --- Taste J: Vollscan mit Zusammenfassung ---
            context.getInput().pressKey(BaseFinderClient.rescanKey);
            context.waitFor(c -> ScanManager.INSTANCE.isFullScanActive(), 20);
            waitScans(context, c -> !ScanManager.INSTANCE.isFullScanActive(), "Vollscan");
            context.waitTicks(5);
            String summary = context.computeOnClient(c -> Evaluator.summary(ScanManager.INSTANCE.results(), BaseFinderClient.config()));
            log("Zusammenfassung: " + summary);
            check(summary.contains("Amethyst: bester Chunk 25 Cluster") && summary.contains("Kelp: höchster 3"), "Zusammenfassung korrekt");
            context.takeScreenshot("06_vollscan_actionbar");

            // --- Taste H: Markierungen aus/an ---
            context.getInput().pressKey(BaseFinderClient.toggleMarkersKey);
            context.waitTicks(2);
            check(!context.computeOnClient(c -> BaseFinderClient.config().general.markersVisible), "H blendet Markierungen aus");
            context.getInput().pressKey(BaseFinderClient.toggleMarkersKey);
            context.waitTicks(2);
            check(context.computeOnClient(c -> BaseFinderClient.config().general.markersVisible), "H blendet Markierungen wieder ein");

            // --- Entladen / Neu laden ---
            server.runCommand("tp @a 5000 -60 5000");
            context.waitFor(c -> amethyst(c).isEmpty() && kelp(c).isEmpty(), 400);
            check(true, "Entladen: Cache leert sich, Markierungen verschwinden");
            server.runCommand("tp @a 0.5 -60 0.5 0 0");
            waitScans(context, c -> amethyst(c).size() == 2, "Neu laden");
            check(true, "Neu laden: Funde sind wieder da");

            // --- Nether: Dimension als Grund, Leuchtstein ist dort natürlich ---
            server.runCommand("execute in minecraft:the_nether run tp @a 0.5 71 0.5");
            context.waitTicks(20);
            sp.getClientWorld().waitForChunksRender();
            server.runCommand("execute in minecraft:the_nether run fill -4 70 -4 4 76 4 minecraft:air");
            server.runCommand("execute in minecraft:the_nether run fill -4 69 -4 4 69 4 minecraft:obsidian");
            server.runCommand("execute in minecraft:the_nether run setblock 2 70 2 minecraft:bee_nest[honey_level=1]" + BEES1);
            server.runCommand("execute in minecraft:the_nether run fill -3 70 -3 -1 70 -1 minecraft:glowstone");
            server.runCommand("execute in minecraft:the_nether run tp @a 0.5 71 0.5");
            context.waitTicks(20);
            waitScans(context, c -> !bees(c).isEmpty(), "Nether");
            List<Marker> nb = context.computeOnClient(BaseFinderClientGameTest::bees);
            check(nb.get(0).lines.get(1).startsWith("Grund: Im Nether"), "Nether: Grund 'Im Nether': " + nb.get(0).lines);
            check(context.computeOnClient(c -> {
                ChunkResult r = ScanManager.INSTANCE.cache().get(ChunkPos.toLong(-1, -1));
                if (r == null) return false;
                Evaluator.AnomalyScore s = Evaluator.scoreChunk(r, ScanManager.INSTANCE.cache(), BaseFinderClient.config(),
                        new com.basefinder.scan.Compiled(BaseFinderClient.config()), c.world.getRegistryKey());
                return s.parts.stream().noneMatch(p -> p.label().contains("Leuchtstein"));
            }), "Nether: Leuchtstein zählt dort nicht als künstlich");
            context.takeScreenshot("07_nether_nest");
            server.runCommand("execute in minecraft:overworld run tp @a 0.5 -60 0.5");
            context.waitTicks(40);

            waitScans(context, c -> amethyst(c).size() == 2, "Zurück in der Oberwelt");

            // --- Fundliste: ausblenden und Fokus ---
            String pigKey = "S:" + new net.minecraft.util.math.BlockPos(20, -60, 40).asLong();
            context.runOnClient(c -> ScanManager.INSTANCE.hide(pigKey));
            context.waitTicks(5);
            check(context.computeOnClient(c -> ScanManager.INSTANCE.results().all.get(Marker.Mode.SPAWNERS).stream().noneMatch(m -> m.key.equals(pigKey))),
                    "Fundliste: ausgeblendeter Spawner verschwindet");
            context.runOnClient(c -> ScanManager.INSTANCE.unhideAll());
            context.waitTicks(5);
            check(context.computeOnClient(c -> ScanManager.INSTANCE.results().all.get(Marker.Mode.SPAWNERS).stream().anyMatch(m -> m.key.equals(pigKey))),
                    "Fundliste: wieder eingeblendet");
            int allCount = context.computeOnClient(c -> {
                int n = 0;
                for (Marker.Mode mode : Marker.Mode.values()) n += ScanManager.INSTANCE.results().all.get(mode).size();
                return n;
            });
            log("Fundliste enthält " + allCount + " Funde");
            check(allCount >= 11, "Fundliste: alle Funde enthalten (" + allCount + ")");

            // --- Englische Anzeige (Spielsprache nicht Deutsch) ---
            context.runOnClient(c -> c.getLanguageManager().setLanguage("en_us"));
            context.runOnClient(c -> ScanManager.INSTANCE.configChanged());
            context.waitTicks(30);
            List<Marker> enBees = context.computeOnClient(BaseFinderClientGameTest::bees);
            check(enBees.get(0).lines.get(1).startsWith("Reason: Underground"), "Englisch: Bienen-Label " + enBees.get(0).lines);
            List<Marker> enSp = context.computeOnClient(c -> ScanManager.INSTANCE.results().get(Marker.Mode.SPAWNERS));
            check(enSp.get(0).lines.get(0).contains("Unnatural spawner"), "Englisch: Spawner-Label " + enSp.get(0).lines);
            context.getInput().pressKey(BaseFinderClient.menuKey);
            context.waitForScreen(ConfigScreen.class);
            context.waitTicks(3);
            context.takeScreenshot("21_menu_englisch");
            context.runOnClient(c -> ((ConfigScreen) c.currentScreen).selectTab("Spawner"));
            context.waitTicks(3);
            context.takeScreenshot("22_menu_englisch_spawner");
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(c -> c.currentScreen == null);
            context.runOnClient(c -> c.getLanguageManager().setLanguage("de_de"));
            context.runOnClient(c -> ScanManager.INSTANCE.configChanged());
            context.waitTicks(30);

            // --- Menü ---
            context.getInput().pressKey(BaseFinderClient.menuKey);
            context.waitForScreen(ConfigScreen.class);
            context.waitTicks(3);
            context.takeScreenshot("10_menu_funde");
            List<String> tabs = List.of("Amethyst", "Bienen", "Spawner", "Kelp", "Anomalien", "Allgemein", "Gewichte");
            for (String tab : tabs) {
                context.runOnClient(c -> ((ConfigScreen) c.currentScreen).selectTab(tab));
                context.waitTicks(3);
                context.takeScreenshot("1" + (1 + tabs.indexOf(tab)) + "_menu_" + tab.toLowerCase());
            }
            context.getInput().scroll(-10);
            context.waitTicks(3);
            context.takeScreenshot("19_menu_gewichte_gescrollt");
            // Fokus auf einen Fund: Screenshot zeigt nur diesen
            context.runOnClient(c -> ((ConfigScreen) c.currentScreen).selectTab("Funde"));
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(c -> c.currentScreen == null);
            context.runOnClient(c -> ScanManager.INSTANCE.setFocus(ScanManager.INSTANCE.results().get(Marker.Mode.AMETHYST).get(0).key));
            server.runCommand("tp @a 30 -50 30 facing 52 -59 52");
            context.waitTicks(10);
            context.takeScreenshot("20_fokus_amethyst");
            context.runOnClient(c -> ScanManager.INSTANCE.setFocus(null));
            context.getInput().pressKey(BaseFinderClient.menuKey);
            context.waitForScreen(ConfigScreen.class);
            context.getInput().pressKey(GLFW.GLFW_KEY_ESCAPE);
            context.waitFor(c -> c.currentScreen == null);
            check(java.nio.file.Files.exists(ModConfig.path()), "Config-Datei wurde gespeichert: " + ModConfig.path());
        }
    }

    // ------------------------------------------------------------------ Fremder (dedizierter) Server

    private void dedicatedServer(ClientGameTestContext context) {
        try (TestDedicatedServerContext server = context.worldBuilder().setUseConsistentSettings(true).createServer()) {
            try (TestServerConnection connection = server.connect()) {
                connection.getClientWorld().waitForChunksRender();
                buildScene(server);
                server.runCommand("tp @a 0.5 -60 0.5 0 0");
                context.waitTicks(20);
                waitScans(context, c -> amethyst(c).size() >= 2 && !bees(c).isEmpty() && !anomaly(c).isEmpty()
                        && ScanManager.INSTANCE.results().get(Marker.Mode.SPAWNERS).size() >= 4, "Server");
                dump(context);
                List<Marker> bz = context.computeOnClient(BaseFinderClientGameTest::bees);
                check(bz.get(0).lines.get(1).startsWith("Grund: Unter der Erde") || bz.get(1).lines.get(1).startsWith("Grund: Unter der Erde"),
                        "Server: Höhlen-Nest gefunden: " + lines(bz));
                check(bz.stream().anyMatch(m -> m.lines.get(3).startsWith("Bienen: ?")), "Server: Bienenanzahl unbekannt (?), weil Vanilla-Server sie nicht sendet");
                check(context.computeOnClient(BaseFinderClientGameTest::amethyst).get(0).lines.get(1).equals("25 Cluster"), "Server: Amethyst 25 Cluster");
                List<Marker> ss = context.computeOnClient(c -> ScanManager.INSTANCE.results().get(Marker.Mode.SPAWNERS));
                check(ss.size() == 4 && ss.get(0).lines.get(2).startsWith("Grund: Mob nie aus"),
                        "Server: Spawner-Mob kommt beim Client an: " + lines(ss));
                server.runCommand("tp @a 20 -45 20 facing 60 -58 -8");
                context.waitTicks(20);
                context.takeScreenshot("20_server_uebersicht");
            }
        }
    }

    // ------------------------------------------------------------------ Szene

    private static void buildScene(TestServerContext s) {
        s.runCommand("time set noon");
        s.runCommand("gamerule doDaylightCycle false");
        s.runCommand("gamemode creative @a");

        // Amethyst: Chunk (3,3) 25 Cluster + 5 große Knospen, Chunk (3,-3) 16 Cluster, Chunk (2,4) 10 Cluster (unter Schwelle)
        s.runCommand("fill 50 -60 50 54 -60 54 minecraft:stone");
        s.runCommand("fill 50 -59 50 54 -59 54 minecraft:amethyst_cluster[facing=up]");
        s.runCommand("fill 56 -60 50 60 -60 50 minecraft:stone");
        s.runCommand("fill 56 -59 50 60 -59 50 minecraft:large_amethyst_bud[facing=up]");
        s.runCommand("fill 50 -60 -40 53 -60 -37 minecraft:stone");
        s.runCommand("fill 50 -59 -40 53 -59 -37 minecraft:amethyst_cluster[facing=up]");
        s.runCommand("fill 34 -60 66 38 -60 67 minecraft:stone");
        s.runCommand("fill 34 -59 66 38 -59 67 minecraft:amethyst_cluster[facing=up]");

        // Bienen: Nest mit 3 Bienen in massivem Stein (Höhle), Chunk (-3,3)
        s.runCommand("fill -44 -60 52 -38 -54 58 minecraft:stone");
        s.runCommand("setblock -41 -57 55 minecraft:bee_nest[honey_level=3]" + BEES3);
        // natürliches Nest am Baum, Chunk (-3,-3)
        s.runCommand("fill -40 -60 -40 -40 -55 -40 minecraft:oak_log");
        s.runCommand("fill -42 -56 -42 -38 -53 -38 minecraft:oak_leaves[persistent=true] keep");
        s.runCommand("setblock -40 -58 -41 minecraft:bee_nest[facing=north]" + BEES1);
        // leeres Nest und Bienenstock mit 2 Bienen auf Steinsäulen ohne Baum, Chunk (-3,0)
        s.runCommand("setblock -40 -60 8 minecraft:stone");
        s.runCommand("setblock -40 -59 8 minecraft:bee_nest");
        s.runCommand("setblock -36 -60 8 minecraft:stone");
        s.runCommand("setblock -36 -59 8 minecraft:beehive[honey_level=5]" + BEES2);

        // Kelp im Glastank, Chunk (-1,4): Säulen 31 und 27, dazu eine kurze mit 10
        s.runCommand("fill -12 -61 65 -6 -28 67 minecraft:glass hollow");
        s.runCommand("fill -11 -60 66 -7 -29 66 minecraft:water");
        s.runCommand("fill -11 -60 66 -11 -31 66 minecraft:kelp_plant");
        s.runCommand("setblock -11 -30 66 minecraft:kelp");
        s.runCommand("fill -9 -60 66 -9 -35 66 minecraft:kelp_plant");
        s.runCommand("setblock -9 -34 66 minecraft:kelp");
        s.runCommand("fill -7 -60 66 -7 -52 66 minecraft:kelp_plant");
        s.runCommand("setblock -7 -51 66 minecraft:kelp");

        // Basis: geschlossener Steinraum mit Fackeln, Laternen, Werkbank, Ofen, Truhe, Amboss, Chunk (4,-1)
        s.runCommand("fill 66 -61 -14 77 -54 -3 minecraft:stone hollow");
        s.runCommand("fill 68 -60 -12 75 -60 -12 minecraft:torch");
        s.runCommand("setblock 68 -60 -9 minecraft:lantern");
        s.runCommand("setblock 75 -60 -9 minecraft:lantern");
        s.runCommand("setblock 70 -60 -8 minecraft:crafting_table");
        s.runCommand("setblock 71 -60 -8 minecraft:furnace");
        s.runCommand("setblock 72 -60 -8 minecraft:chest");
        s.runCommand("setblock 73 -60 -8 minecraft:anvil");

        // "Dorf": Glocke, Heuballen, Dorfweg und ein paar Fackeln an der Oberfläche, Chunk (-1,-4)
        s.runCommand("setblock -8 -60 -56 minecraft:bell[attachment=floor]");
        s.runCommand("fill -12 -60 -60 -10 -60 -58 minecraft:hay_block");
        s.runCommand("fill -12 -61 -54 -4 -61 -53 minecraft:dirt_path");
        s.runCommand("fill -7 -60 -62 -3 -60 -59 minecraft:torch"); // 20 Fackeln: Score 20, aber Dorf -> ausgeblendet

        // Spawner: natürliches Verlies (darf NICHT markiert werden), Chunk (2,-2)
        s.runCommand("fill 36 -61 -28 42 -55 -22 minecraft:cobblestone hollow");
        s.runCommand("fill 37 -61 -27 41 -61 -23 minecraft:mossy_cobblestone");
        s.runCommand("setblock 39 -60 -25 minecraft:spawner{SpawnData:{entity:{id:\"minecraft:zombie\"}}}");
        // Schweine-Spawner unter freiem Himmel, Chunk (1,2)
        s.runCommand("setblock 20 -60 40 minecraft:spawner{SpawnData:{entity:{id:\"minecraft:pig\"}}}");
        // Lohen-Spawner in der Oberwelt, eingemauert in Stein, Chunk (-2,2)
        s.runCommand("fill -28 -60 36 -24 -56 40 minecraft:stone");
        s.runCommand("setblock -26 -58 38 minecraft:spawner{SpawnData:{entity:{id:\"minecraft:blaze\"}}}");
        // leerer Spawner, eingemauert, Chunk (4,2)
        s.runCommand("fill 68 -60 36 72 -56 40 minecraft:stone");
        s.runCommand("setblock 70 -58 38 minecraft:spawner");
    }

    // ------------------------------------------------------------------ Hilfen

    /** Misst die Dauer von Config-Kompilierung, Auswertung und die Spitzen-Tickdauer im Ruhezustand. */
    private static void profile(ClientGameTestContext context, String when) {
        context.computeOnClient(c -> ScanManager.INSTANCE.takePeakTickMs());
        context.waitTicks(100);
        double idlePeak = context.computeOnClient(c -> ScanManager.INSTANCE.takePeakTickMs());
        double[] t = context.computeOnClient(c -> {
            long a = System.nanoTime();
            com.basefinder.scan.Compiled comp = new com.basefinder.scan.Compiled(BaseFinderClient.config());
            long b = System.nanoTime();
            Evaluator.evaluate(ScanManager.INSTANCE.cache(), BaseFinderClient.config(), comp, c.world.getRegistryKey(), c.player.getEyePos());
            long d = System.nanoTime();
            return new double[]{(b - a) / 1e6, (d - b) / 1e6};
        });
        log(String.format("Profil (%s): Kompilieren %.2f ms, Auswertung %.2f ms, Spitzen-Tick in 100 Ticks %.2f ms", when, t[0], t[1], idlePeak));
    }

    private static List<Marker> amethyst(MinecraftClient c) {
        return ScanManager.INSTANCE.results().get(Marker.Mode.AMETHYST);
    }

    private static List<Marker> bees(MinecraftClient c) {
        return ScanManager.INSTANCE.results().get(Marker.Mode.BEES);
    }

    private static List<Marker> kelp(MinecraftClient c) {
        return ScanManager.INSTANCE.results().get(Marker.Mode.KELP);
    }

    private static List<Marker> anomaly(MinecraftClient c) {
        return ScanManager.INSTANCE.results().get(Marker.Mode.ANOMALY);
    }

    private static int score(Marker m) {
        String s = m.lines.get(1).replace("Score ", "");
        int end = s.indexOf(' ');
        return Integer.parseInt(end < 0 ? s : s.substring(0, end));
    }

    private static void waitScans(ClientGameTestContext context, Predicate<MinecraftClient> extra, String what) {
        // "fertig" = alle geladenen Chunks im Radius gescannt und höchstens ein paar Nachscans offen
        // (in Dörfern, Farmen usw. ändern sich ständig Blöcke - ganz leer wird die Warteschlange nie)
        int ticks = context.waitFor(c -> ScanManager.INSTANCE.coverage() >= 1.0 && ScanManager.INSTANCE.pendingCount() <= 3 && extra.test(c), 1200);
        log(what + ": Scans fertig nach " + ticks + " Ticks (Cache " + context.computeOnClient(c -> ScanManager.INSTANCE.cachedCount()) + " Chunks)");
    }

    private static void dump(ClientGameTestContext context) {
        context.runOnClient(c -> {
            for (Marker.Mode mode : Marker.Mode.values()) {
                for (Marker m : ScanManager.INSTANCE.results().get(mode)) {
                    log("  [" + mode.title() + "] " + String.join(" | ", m.lines));
                }
            }
        });
    }

    private static String lines(List<Marker> ms) {
        StringBuilder sb = new StringBuilder();
        for (Marker m : ms) sb.append(m.lines).append(' ');
        return sb.toString();
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            log("FEHLGESCHLAGEN: " + message);
            throw new AssertionError(message);
        }
        log("ok: " + message.replaceFirst(":.*", ""));
    }

    private static void log(String message) {
        System.out.println("[BaseFinderTest] " + message);
    }
}
