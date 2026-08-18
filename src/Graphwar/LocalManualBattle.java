package Graphwar;

import GraphServer.Constants;
import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class LocalManualBattle {
    private static final String PFX = "[LOCAL-MANUAL] ";
    private static final long POLL_MS = 100L;
    private static Path out;
    private static int attempt;
    private static int manualTurn = 0;
    private static String lastProbeContent = null;

    public static void premain(String args, Instrumentation inst) {
        Thread t = new Thread(LocalManualBattle::mainAgent, "LocalManualBattle");
        t.setDaemon(false);
        t.start();
    }

    private static void mainAgent() {
        try {
            out = Paths.get(System.getProperty("gw.out", "."));
            Files.createDirectories(out);
            attempt = Integer.parseInt(System.getProperty("gw.attempt", "1"));
            int port = Integer.parseInt(System.getProperty("gw.port", "26112"));
            int aiLevel = Integer.parseInt(System.getProperty("gw.aiLevel", "9001"));
            int aiCount = Integer.parseInt(System.getProperty("gw.aiCount", "10"));
            int mySoldiers = Integer.parseInt(System.getProperty("gw.mySoldiers", "4"));
            int aiSoldiers = Integer.parseInt(System.getProperty("gw.aiSoldiers", "4"));
            int expectedEnemies = aiCount * aiSoldiers;

            Graphwar gw = waitForGraphwar();
            log("FRAME_READY attempt=" + attempt);
            gw.createGame(port);
            GameData gd = gw.getGameData();
            waitUntil(() -> gd.isLeader(), 10000, "leader");
            log("LOCAL_SERVER port=" + port);

            gd.addPlayer("GPT");
            waitUntil(() -> gd.getPlayers().size() >= 1, 10000, "GPT add");
            Player me = gd.getPlayers().get(0);
            ensureTeam(gd, me, Constants.TEAM1);
            ensureSoldiers(gd, me, mySoldiers);

            for (int i = 1; i <= aiCount; i++) {
                final int target = i + 1;
                gd.addPC("AI-" + i, aiLevel);
                waitUntil(() -> gd.getPlayers().size() >= target, 10000, "AI add " + i);
                Player ai = gd.getPlayers().get(target - 1);
                ensureTeam(gd, ai, Constants.TEAM2);
                ensureSoldiers(gd, ai, aiSoldiers);
            }

            log("SETUP players=" + gd.getPlayers().size() + " mySoldiers=" + mySoldiers +
                " aiCount=" + aiCount + " aiSoldiers=" + aiSoldiers + " aiLevel=" + aiLevel);
            dumpPlayers(gd, "SETUP_PLAYER");

            for (Player p : new ArrayList<>(gd.getPlayers())) gd.setReady(p, true);
            waitUntil(() -> gd.getGameState() == Constants.GAME, 20000, "game start");
            log("GAME_STARTED players=" + gd.getPlayers().size());

            // GAME state flips slightly before all Soldier.alive flags are initialized.
            // Never judge a result until the expected 4-vs-40 armies have actually appeared.
            waitUntil(() -> aliveTeam(gd, Constants.TEAM1) >= mySoldiers &&
                            aliveTeam(gd, Constants.TEAM2) >= expectedEnemies,
                      15000, "army initialization");
            log("ARMIES_READY ownAlive=" + aliveTeam(gd, Constants.TEAM1) +
                " enemyAlive=" + aliveTeam(gd, Constants.TEAM2));

            boolean humanTurnLatched = false;
            while (true) {
                int ownAlive = aliveTeam(gd, Constants.TEAM1);
                int enemyAlive = aliveTeam(gd, Constants.TEAM2);
                if (ownAlive <= 0 && enemyAlive > 0) {
                    finish("LOSS", ownAlive, enemyAlive);
                    break;
                }
                if (ownAlive > 0 && enemyAlive <= 0) {
                    finish("WIN", ownAlive, enemyAlive);
                    break;
                }
                if (ownAlive <= 0 && enemyAlive <= 0) {
                    finish("DRAW", ownAlive, enemyAlive);
                    break;
                }

                if (gd.getGameState() == Constants.GAME) {
                    Player cur = safeCurrent(gd);
                    boolean human = cur != null && isHumanGPT(cur);
                    if (!human) {
                        humanTurnLatched = false;
                    } else if (!humanTurnLatched && !gd.isDrawingFunction() && !gd.isExploding()) {
                        handleManualTurn(gw, gd, cur);
                        humanTurnLatched = true;
                    }
                }
                Thread.sleep(POLL_MS);
            }

            Thread.sleep(8000);
            log("Agent finished; closing Graphwar.");
            try { gw.dispose(); } catch (Throwable ignore) {}
            System.exit(0);
        } catch (Throwable t) {
            try { log("FATAL " + t); } catch (Throwable ignore) {}
            t.printStackTrace();
            try { writeText(out.resolve("result.txt"), "FATAL\n" + t + "\n"); } catch (Throwable ignore) {}
            System.exit(2);
        }
    }

    private static Graphwar waitForGraphwar() throws Exception {
        long end = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < end) {
            for (java.awt.Frame f : java.awt.Frame.getFrames()) {
                if (f instanceof Graphwar) {
                    Graphwar gw = (Graphwar) f;
                    if (gw.getGameData() != null && gw.getUI() != null) return gw;
                }
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException("Graphwar frame not initialized");
    }

    private static Player safeCurrent(GameData gd) {
        try { return gd.getCurrentTurnPlayer(); }
        catch (Throwable t) { return null; }
    }

    private static boolean isHumanGPT(Player p) {
        return p != null && p.isLocalPlayer() && !(p instanceof ComputerPlayer) && "GPT".equals(p.getName());
    }

    private static void ensureTeam(GameData gd, Player p, int team) throws Exception {
        if (p.getTeam() != team) {
            gd.switchSide(p);
            waitUntil(() -> p.getTeam() == team, 5000, "team for " + p.getName());
        }
    }

    private static void ensureSoldiers(GameData gd, Player p, int n) throws Exception {
        while (p.getNumSoldiers() < n) {
            int before = p.getNumSoldiers();
            gd.addSoldier(p);
            waitUntil(() -> p.getNumSoldiers() > before, 5000, "add soldier " + p.getName());
        }
        while (p.getNumSoldiers() > n) {
            int before = p.getNumSoldiers();
            gd.removeSoldier(p);
            waitUntil(() -> p.getNumSoldiers() < before, 5000, "remove soldier " + p.getName());
        }
    }

    private static void handleManualTurn(Graphwar gw, GameData gd, Player cur) throws Exception {
        manualTurn++;
        lastProbeContent = null;
        Files.deleteIfExists(out.resolve("command.txt"));
        Files.deleteIfExists(out.resolve("probe.txt"));
        Files.deleteIfExists(out.resolve("probe-result.txt"));
        Files.deleteIfExists(out.resolve("command-error.txt"));
        dumpState(gd, cur, "WAITING_FOR_GPT_COMMAND");
        log("GPT_TURN attempt=" + attempt + " turn=" + manualTurn + " soldier=" + cur.getCurrentTurnSoldierIndex());

        long lastPause = System.currentTimeMillis();
        while (gd.getGameState() == Constants.GAME && safeCurrent(gd) == cur && isHumanGPT(cur)) {
            long now = System.currentTimeMillis();
            long delta = now - lastPause;
            if (delta > 0) {
                pauseHumanClock(gw, gd, delta);
                lastPause = now;
            }

            Path probe = out.resolve("probe.txt");
            if (Files.exists(probe)) {
                String content = Files.readString(probe, StandardCharsets.UTF_8);
                if (!content.equals(lastProbeContent)) {
                    lastProbeContent = content;
                    doProbe(gd, content);
                }
            }

            Path cmd = out.resolve("command.txt");
            if (Files.exists(cmd)) {
                String formula = Files.readString(cmd, StandardCharsets.UTF_8).trim();
                if (!formula.isEmpty()) {
                    try { new Function(formula); }
                    catch (MalformedFunction e) {
                        writeText(out.resolve("command-error.txt"), "Malformed: " + formula + "\n" + e + "\n");
                        Files.deleteIfExists(cmd);
                        Thread.sleep(100);
                        continue;
                    }
                    dumpState(gd, cur, "FIRING");
                    log("GPT_FIRE attempt=" + attempt + " turn=" + manualTurn + " formula=" + formula);
                    gd.sendFunction(formula);
                    Files.deleteIfExists(cmd);
                    return;
                }
            }
            Thread.sleep(100);
        }
    }

    private static void pauseHumanClock(Graphwar gw, GameData gd, long delta) {
        try { shiftLongField(gd, "timeTurnStarted", delta); } catch (Throwable ignore) {}
        try {
            Field f = Graphwar.class.getDeclaredField("gameServer");
            f.setAccessible(true);
            Object server = f.get(gw);
            if (server != null) shiftLongField(server, "timeTurnStarted", delta);
        } catch (Throwable ignore) {}
    }

    private static void shiftLongField(Object obj, String name, long delta) throws Exception {
        Field f = obj.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.setLong(obj, f.getLong(obj) + delta);
    }

    private static void doProbe(GameData gd, String content) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("attempt=").append(attempt).append(" turn=").append(manualTurn).append('\n');
        for (String raw : content.split("\\R")) {
            String formula = raw.trim();
            if (formula.isEmpty() || formula.startsWith("#")) continue;
            try {
                Function fn = new Function(formula);
                Player[] ps = gd.getPlayers().toArray(new Player[0]);
                boolean rev = gd.getCurrentTurnPlayer().getTeam() == Constants.TEAM2;
                int mode = gd.getGameMode();
                if (mode == Constants.NORMAL_FUNC) fn.processFunctionRange(gd.getObstacle(), ps, ps.length, gd.getCurrentTurnIndex(), rev);
                else if (mode == Constants.FST_ODE) fn.processRK4Range(gd.getObstacle(), ps, ps.length, gd.getCurrentTurnIndex(), rev);
                else fn.processRK42Range(gd.getObstacle(), ps, ps.length, gd.getCurrentTurnIndex(), gd.getCurrentTurnPlayer().getCurrentTurnSoldier().getAngle(), rev);

                int enemy = 0, friendly = 0;
                StringBuilder hits = new StringBuilder();
                int myTeam = gd.getCurrentTurnPlayer().getTeam();
                for (int i = 0; i < fn.getNumPlayersHit(); i++) {
                    int pi = fn.getPlayerHit(i), si = fn.getSoldierHit(i);
                    if (pi < 0 || pi >= ps.length) continue;
                    Player hp = ps[pi];
                    if (hp.getTeam() == myTeam) friendly++; else enemy++;
                    if (hits.length() > 0) hits.append(',');
                    hits.append(hp.getName()).append('#').append(si);
                }
                sb.append("formula=").append(formula)
                  .append(" enemyHits=").append(enemy)
                  .append(" friendlyHits=").append(friendly)
                  .append(" totalHits=").append(fn.getNumPlayersHit())
                  .append(" steps=").append(fn.getNumSteps())
                  .append(" lastX=").append(String.format(Locale.ROOT, "%.4f", fn.getLastX()))
                  .append(" lastY=").append(String.format(Locale.ROOT, "%.4f", fn.getLastY()))
                  .append(" hits=").append(hits).append('\n');
            } catch (Throwable e) {
                sb.append("formula=").append(formula).append(" ERROR=").append(e.getClass().getSimpleName()).append(':').append(e.getMessage()).append('\n');
            }
        }
        writeText(out.resolve("probe-result.txt"), sb.toString());
        log("PROBE_DONE attempt=" + attempt + " turn=" + manualTurn);
    }

    private static void dumpState(GameData gd, Player cur, String status) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("status=").append(status).append('\n');
        sb.append("attempt=").append(attempt).append('\n');
        sb.append("turn=").append(manualTurn).append('\n');
        sb.append("mode=").append(gd.getGameMode()).append('\n');
        try { sb.append("remainingMs=").append(gd.getRemainingTime()).append('\n'); } catch (Throwable ignore) {}
        sb.append("terrainReversed=").append(gd.isTerrainReversed()).append('\n');
        try { sb.append("functionReversed=").append(gd.isFunctionReversed()).append('\n'); } catch (Throwable ignore) {}
        sb.append("currentPlayer=").append(cur.getName()).append(" id=").append(cur.getID())
          .append(" team=").append(cur.getTeam()).append(" soldierIndex=").append(cur.getCurrentTurnSoldierIndex()).append('\n');
        Soldier cs = cur.getCurrentTurnSoldier();
        if (cs != null) sb.append("currentSoldier x=").append(cs.getX()).append(" y=").append(cs.getY()).append(" angle=").append(cs.getAngle()).append('\n');
        List<Player> ps = gd.getPlayers();
        for (int pi = 0; pi < ps.size(); pi++) {
            Player p = ps.get(pi);
            sb.append("PLAYER pi=").append(pi).append(" name=").append(p.getName()).append(" id=").append(p.getID())
              .append(" local=").append(p.isLocalPlayer()).append(" pc=").append(p instanceof ComputerPlayer)
              .append(" team=").append(p.getTeam()).append(" alive=").append(alive(p)).append('/').append(p.getNumSoldiers()).append('\n');
            Soldier[] ss = p.getSoldiers();
            for (int si = 0; si < p.getNumSoldiers(); si++) {
                Soldier s = ss[si];
                sb.append(" SOLDIER si=").append(si).append(" alive=").append(s.isAlive())
                  .append(" x=").append(s.getX()).append(" y=").append(s.getY())
                  .append(" angle=").append(String.format(Locale.ROOT, "%.5f", s.getAngle())).append('\n');
            }
        }
        writeText(out.resolve("state.txt"), sb.toString());
    }

    private static void dumpPlayers(GameData gd, String tag) {
        for (Player p : gd.getPlayers()) log(tag + " name=" + p.getName() + " team=" + p.getTeam() + " soldiers=" + p.getNumSoldiers() + " pc=" + (p instanceof ComputerPlayer));
    }

    private static int alive(Player p) {
        int n = 0;
        for (int i = 0; i < p.getNumSoldiers(); i++) if (p.getSoldiers()[i].isAlive()) n++;
        return n;
    }

    private static int aliveTeam(GameData gd, int team) {
        int n = 0;
        for (Player p : gd.getPlayers()) if (p.getTeam() == team) n += alive(p);
        return n;
    }

    private static void finish(String result, int ownAlive, int enemyAlive) throws IOException {
        String text = "result=" + result + "\nattempt=" + attempt + "\nownAlive=" + ownAlive + "\nenemyAlive=" + enemyAlive + "\nmanualTurns=" + manualTurn + "\n";
        writeText(out.resolve("result.txt"), text);
        log("RESULT " + result + " ownAlive=" + ownAlive + " enemyAlive=" + enemyAlive + " turns=" + manualTurn);
    }

    private static void waitUntil(Check c, long timeout, String what) throws Exception {
        long end = System.currentTimeMillis() + timeout;
        while (System.currentTimeMillis() < end) {
            if (c.ok()) return;
            Thread.sleep(50);
        }
        throw new IllegalStateException("timeout waiting for " + what);
    }

    private interface Check { boolean ok() throws Exception; }

    private static synchronized void log(String s) {
        String line = PFX + s;
        System.out.println(line);
        try {
            Files.writeString(out.resolve("manual.log"), line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Throwable ignore) {}
    }

    private static void writeText(Path p, String s) throws IOException {
        Path tmp = p.resolveSibling(p.getFileName().toString() + ".tmp");
        Files.writeString(tmp, s, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        try { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException e) { Files.move(tmp, p, StandardCopyOption.REPLACE_EXISTING); }
    }
}
