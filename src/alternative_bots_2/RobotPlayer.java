package alternative_bots_2;

import battlecode.common.*;
import java.util.Random;

/**
 * RobotPlayer - "The Aggressor" (Greedy by Attrition)
 *
 * Heuristik: Maksimalkan penguasaan wilayah per turn.
 * Fungsi evaluasi: (Tiles painted) + (Enemy paint erased * 2) + (Enemy tower damage)
 *
 * Komposisi: mayoritas Soldier untuk ekspansi teritorial cepat.
 * Mopper hanya spawn jika ada ancaman nyata dari musuh.
 */
public class RobotPlayer {

    static RobotController rc;
    // seed dari robot ID agar setiap bot punya jalur eksplorasi berbeda
    static Random rng = null;

    static MapLocation spawnLoc = null;
    static MapLocation exploreTarget = null;

    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    static final Direction[] cardinalDirs = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
    };

    static final UnitType[] towerTypes = {
        UnitType.LEVEL_ONE_PAINT_TOWER,
        UnitType.LEVEL_ONE_MONEY_TOWER,
        UnitType.LEVEL_ONE_DEFENSE_TOWER
    };

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        RobotPlayer.rc = rc;
        // setiap robot punya random seed berbeda → menyebar ke arah berbeda
        if (rng == null) rng = new Random(rc.getID());
        if (spawnLoc == null) spawnLoc = rc.getLocation();

        while (true) {
            try {
                if (rc.getType().isTowerType()) {
                    runTower();
                } else if (rc.getType() == UnitType.SOLDIER) {
                    runSoldier();
                } else if (rc.getType() == UnitType.MOPPER) {
                    runMopper();
                } else if (rc.getType() == UnitType.SPLASHER) {
                    runSplasher();
                }
            } catch (GameActionException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    // ==================== TOWER ====================

    /**
     * Tower: serang musuh, spawn unit.
     * Greedy: selalu spawn soldier kecuali ada ancaman nyata musuh.
     * Soldier = coverage; Mopper = hanya saat pertempuran.
     */
    public static void runTower() throws GameActionException {
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        // serang musuh HP terendah
        if (enemies.length > 0) {
            RobotInfo best = null;
            int lowestHP = Integer.MAX_VALUE;
            for (RobotInfo e : enemies) {
                if (rc.canAttack(e.getLocation()) && e.getHealth() < lowestHP) {
                    lowestHP = e.getHealth();
                    best = e;
                }
            }
            if (best != null) rc.attack(best.getLocation());
        }

        if (!rc.isActionReady()) return;

        int chips = rc.getChips();
        int paint = rc.getPaint();

        // hitung seberapa banyak paint musuh yang terlihat
        int enemyPaintCount = 0;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (tile.getPaint().isEnemy()) enemyPaintCount++;
        }

        UnitType toSpawn = null;

        if (chips >= 250 && paint >= 200) {
            // spawn mopper HANYA jika ada ancaman aktif (robot musuh dekat atau banyak paint musuh)
            if ((enemies.length > 0 || enemyPaintCount > 8) && paint >= 150) {
                toSpawn = UnitType.MOPPER;
            } else {
                // default: soldier untuk ekspansi teritorial
                toSpawn = UnitType.SOLDIER;
            }
        } else if (chips >= 250 && paint >= 150) {
            toSpawn = UnitType.SOLDIER;
        } else if (chips >= 300 && paint >= 100) {
            toSpawn = UnitType.MOPPER;
        }

        if (toSpawn != null) spawnUnit(toSpawn);
    }

    public static void spawnUnit(UnitType type) throws GameActionException {
        MapLocation loc = rc.getLocation();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation candidate = new MapLocation(loc.x + dx, loc.y + dy);
                if (loc.distanceSquaredTo(candidate) > 4) continue;
                if (rc.canBuildRobot(type, candidate)) {
                    rc.buildRobot(type, candidate);
                    return;
                }
            }
        }
    }

    // ==================== SOLDIER ====================

    /**
     * Soldier: ekspansi teritorial agresif.
     * Greedy per turn: cat tile terbaik (paling dekat ke target musuh) dalam radius attack.
     * Ini membangun jalur paint menuju wilayah musuh setiap turn.
     *
     * Prioritas:
     * 1. Refill jika paint < 20%
     * 2. Serang + dekati tower musuh
     * 3. Bangun tower di ruin yang ADA DI JALUR ke musuh
     * 4. Gerak ke wilayah musuh + cat tile terbaik setiap langkah
     */
    public static void runSoldier() throws GameActionException {
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;

        if (paint < maxPaint * 0.2) {
            seekPaintRefill();
            return;
        }

        // hitung explore target sekali untuk digunakan di seluruh turn ini
        MapLocation target = getExploreTarget();

        // 1. serang tower musuh
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo targetTower = null;
        int minTowerDist = Integer.MAX_VALUE;
        for (RobotInfo e : enemies) {
            if (e.getType().isTowerType()) {
                int d = rc.getLocation().distanceSquaredTo(e.getLocation());
                if (d < minTowerDist) { minTowerDist = d; targetTower = e; }
            }
        }
        if (targetTower != null) {
            if (rc.isActionReady() && rc.canAttack(targetTower.getLocation()))
                rc.attack(targetTower.getLocation());
            greedyMove(targetTower.getLocation());
            if (rc.isActionReady() && rc.canAttack(targetTower.getLocation()))
                rc.attack(targetTower.getLocation());
            paintBestTile(target);
            return;
        }

        // 2. bangun ruin yang ADA DI JALUR ke musuh (tidak mengejar ruin di belakang)
        MapInfo ruinOnPath = findRuinOnPath(target);
        if (ruinOnPath != null) {
            buildTowerAtRuin(ruinOnPath.getMapLocation());
            paintBestTile(target);
            return;
        }

        // 3. ekspansi: gerak ke wilayah musuh lalu cat tile terbaik
        moveTowardsEnemyTerritory(target);
        paintBestTile(target);
    }

    /**
     * Hitung atau ambil explore target yang sudah ada.
     * Target selalu di sisi musuh, menggunakan spawnLoc sebagai referensi.
     */
    public static MapLocation getExploreTarget() {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 9) {
            int baseX = (spawnLoc != null) ? w - 1 - spawnLoc.x : w / 2;
            int baseY = (spawnLoc != null) ? h - 1 - spawnLoc.y : h / 2;
            // offset berbeda per robot (rng unik dari ID) — menyebar di area musuh
            int ex = Math.max(0, Math.min(w - 1, baseX + rng.nextInt(13) - 6));
            int ey = Math.max(0, Math.min(h - 1, baseY + rng.nextInt(13) - 6));
            exploreTarget = new MapLocation(ex, ey);
        }
        return exploreTarget;
    }

    /**
     * Cat tile terbaik dalam radius attack (distSq <= 9) yang paling dekat ke target.
     * Greedy: selalu perluas wilayah ke arah musuh, bukan sekadar cat tile di bawah kaki.
     */
    public static void paintBestTile(MapLocation target) throws GameActionException {
        if (!rc.isActionReady()) return;
        MapLocation bestTile = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo tile : rc.senseNearbyMapInfos(rc.getLocation(), 3)) {
            MapLocation tileLoc = tile.getMapLocation();
            if (!rc.canAttack(tileLoc)) continue;
            // hanya cat tile kosong — soldier tidak bisa overwrite paint musuh
            if (tile.getPaint() != PaintType.EMPTY) continue;
            int dist = tileLoc.distanceSquaredTo(target);
            if (dist < minDist) { minDist = dist; bestTile = tileLoc; }
        }

        // fallback: cat tile di bawah kaki jika tidak ada tile kosong menuju target
        if (bestTile == null) {
            MapLocation cur = rc.getLocation();
            if (!rc.senseMapInfo(cur).getPaint().isAlly() && rc.canAttack(cur))
                rc.attack(cur);
        } else {
            rc.attack(bestTile);
        }
    }

    /**
     * Cari ruin yang ada di jalur menuju target musuh.
     * Ruin "di jalur" = lebih dekat ke target daripada posisi kita, DAN dalam vision range.
     */
    public static MapInfo findRuinOnPath(MapLocation target) throws GameActionException {
        MapInfo nearest = null;
        int minDist = Integer.MAX_VALUE;
        int distToTarget = rc.getLocation().distanceSquaredTo(target);

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;
            if (rc.senseRobotAtLocation(tile.getMapLocation()) != null) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            // hanya proses ruin yang ada di depan kita (lebih dekat ke target)
            if (ruinLoc.distanceSquaredTo(target) > distToTarget) continue;
            int dist = rc.getLocation().distanceSquaredTo(ruinLoc);
            if (dist < minDist) { minDist = dist; nearest = tile; }
        }
        return nearest;
    }

    public static void buildTowerAtRuin(MapLocation ruinLoc) throws GameActionException {
        for (UnitType towerType : towerTypes) {
            if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
                rc.markTowerPattern(towerType, ruinLoc);
                break;
            }
        }

        MapLocation unpaintedTile = null;
        int minDist = Integer.MAX_VALUE;
        for (MapInfo pt : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (pt.getMark() != PaintType.EMPTY && pt.getMark() != pt.getPaint()) {
                int dist = rc.getLocation().distanceSquaredTo(pt.getMapLocation());
                if (dist < minDist) { minDist = dist; unpaintedTile = pt.getMapLocation(); }
            }
        }

        if (unpaintedTile != null) {
            greedyMove(unpaintedTile);
        } else {
            Direction toRuin = rc.getLocation().directionTo(ruinLoc);
            if (rc.canMove(toRuin)) rc.move(toRuin);
        }

        for (MapInfo pt : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (pt.getMark() != PaintType.EMPTY && pt.getMark() != pt.getPaint()) {
                boolean useSecondary = pt.getMark() == PaintType.ALLY_SECONDARY;
                if (rc.canAttack(pt.getMapLocation())) {
                    rc.attack(pt.getMapLocation(), useSecondary);
                    break;
                }
            }
        }

        for (UnitType towerType : towerTypes) {
            if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
                rc.completeTowerPattern(towerType, ruinLoc);
                break;
            }
        }
    }

    // ==================== MOPPER ====================

    /**
     * Mopper: hapus paint musuh untuk membuka wilayah bagi soldier.
     * Greedy: maksimalkan (enemy paint erased * 2) + damage ke robot musuh per turn.
     */
    public static void runMopper() throws GameActionException {
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;

        if (paint < maxPaint * 0.15) {
            seekPaintRefill();
            return;
        }

        if (rc.isActionReady() && paint > maxPaint * 0.5) {
            for (RobotInfo ally : rc.senseNearbyRobots(2, rc.getTeam())) {
                if (ally.getType().isTowerType()) continue;
                int pct = (ally.getPaintAmount() * 100) / ally.getType().paintCapacity;
                if (pct < 20) {
                    int transfer = Math.min(paint / 2, ally.getType().paintCapacity - ally.getPaintAmount());
                    if (transfer > 0 && rc.canTransferPaint(ally.getLocation(), transfer)) {
                        rc.transferPaint(ally.getLocation(), transfer);
                        break;
                    }
                }
            }
        }

        if (rc.isActionReady()) {
            int bestScore = 0;
            int bestAction = 0;
            MapLocation bestMopTarget = null;
            Direction bestSwingDir = null;

            for (MapInfo tile : rc.senseNearbyMapInfos(rc.getLocation(), 2)) {
                MapLocation tileLoc = tile.getMapLocation();
                if (!rc.canAttack(tileLoc) || !tile.getPaint().isEnemy()) continue;
                int score = 2;
                RobotInfo r = rc.senseRobotAtLocation(tileLoc);
                if (r != null && r.getTeam() != rc.getTeam()) score += 20;
                if (score > bestScore) { bestScore = score; bestAction = 1; bestMopTarget = tileLoc; }
            }

            for (Direction swingDir : cardinalDirs) {
                if (!rc.canMopSwing(swingDir)) continue;
                int score = 0;
                for (int step = 1; step <= 2; step++) {
                    MapLocation center = rc.getLocation();
                    for (int s = 0; s < step; s++) center = center.add(swingDir);
                    Direction left = swingDir.rotateLeft().rotateLeft();
                    Direction right = swingDir.rotateRight().rotateRight();
                    MapLocation[] targets = { center, center.add(left), center.add(right) };
                    for (MapLocation t : targets) {
                        if (rc.canSenseLocation(t)) {
                            RobotInfo r = rc.senseRobotAtLocation(t);
                            if (r != null && r.getTeam() != rc.getTeam()) score += 10;
                        }
                    }
                }
                if (score > bestScore) { bestScore = score; bestAction = 2; bestSwingDir = swingDir; }
            }

            if (bestAction == 1 && bestMopTarget != null) rc.attack(bestMopTarget);
            else if (bestAction == 2 && bestSwingDir != null) rc.mopSwing(bestSwingDir);
        }

        if (rc.isMovementReady()) {
            RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
            MapLocation closestEnemy = null;
            int minED = Integer.MAX_VALUE;
            for (RobotInfo e : enemies) {
                int d = rc.getLocation().distanceSquaredTo(e.getLocation());
                if (d < minED) { minED = d; closestEnemy = e.getLocation(); }
            }

            MapLocation bestEnemyPaint = null;
            int minPD = Integer.MAX_VALUE;
            for (MapInfo tile : rc.senseNearbyMapInfos()) {
                if (tile.getPaint().isEnemy()) {
                    int d = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                    if (d < minPD) { minPD = d; bestEnemyPaint = tile.getMapLocation(); }
                }
            }

            if (closestEnemy != null) greedyMove(closestEnemy);
            else if (bestEnemyPaint != null) greedyMove(bestEnemyPaint);
            else moveTowardsEnemyTerritory(getExploreTarget());
        }

        if (rc.isActionReady()) {
            for (MapInfo tile : rc.senseNearbyMapInfos(rc.getLocation(), 2)) {
                MapLocation tileLoc = tile.getMapLocation();
                if (!rc.canAttack(tileLoc) || !tile.getPaint().isEnemy()) continue;
                rc.attack(tileLoc);
                return;
            }
            for (Direction swingDir : cardinalDirs) {
                if (!rc.canMopSwing(swingDir)) continue;
                boolean hasTarget = false;
                outer:
                for (int step = 1; step <= 2; step++) {
                    MapLocation center = rc.getLocation();
                    for (int s = 0; s < step; s++) center = center.add(swingDir);
                    Direction left = swingDir.rotateLeft().rotateLeft();
                    Direction right = swingDir.rotateRight().rotateRight();
                    MapLocation[] ts = { center, center.add(left), center.add(right) };
                    for (MapLocation t : ts) {
                        if (rc.canSenseLocation(t)) {
                            RobotInfo r = rc.senseRobotAtLocation(t);
                            if (r != null && r.getTeam() != rc.getTeam()) { hasTarget = true; break outer; }
                        }
                    }
                }
                if (hasTarget) { rc.mopSwing(swingDir); break; }
            }
        }
    }

    // ==================== SPLASHER ====================

    public static void runSplasher() throws GameActionException {
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;
        if (paint < maxPaint * 0.2) { seekPaintRefill(); return; }

        if (rc.isActionReady() && paint >= 50) {
            MapLocation bestTarget = null;
            int bestScore = 0;
            MapLocation curLoc = rc.getLocation();
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    MapLocation target = new MapLocation(curLoc.x + dx, curLoc.y + dy);
                    if (curLoc.distanceSquaredTo(target) > 4 || !rc.canAttack(target)) continue;
                    int score = 0;
                    for (int ax = -1; ax <= 1; ax++) {
                        for (int ay = -1; ay <= 1; ay++) {
                            MapLocation aoe = new MapLocation(target.x + ax, target.y + ay);
                            if (target.distanceSquaredTo(aoe) > 2 || !rc.canSenseLocation(aoe)) continue;
                            PaintType pt = rc.senseMapInfo(aoe).getPaint();
                            if (pt.isEnemy()) score += 4;
                            else if (pt == PaintType.EMPTY) score += 1;
                        }
                    }
                    if (score > bestScore) { bestScore = score; bestTarget = target; }
                }
            }
            if (bestTarget != null && bestScore > 0) rc.attack(bestTarget);
        }

        moveTowardsEnemyTerritory(getExploreTarget());
    }

    // ==================== UTILITAS ====================

    public static void greedyMove(MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;
        int minDist = rc.getLocation().distanceSquaredTo(target);
        Direction bestDir = null;
        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            int dist = rc.getLocation().add(dir).distanceSquaredTo(target);
            if (dist < minDist) { minDist = dist; bestDir = dir; }
        }
        if (bestDir != null) rc.move(bestDir);
    }

    /**
     * Bergerak menuju wilayah musuh.
     * Jika ada enemy paint terlihat: langsung kejar (greedy optimal).
     * Jika tidak: bergerak ke target yang sudah dihitung dari spawnLoc.
     * Jika terblokir: pilih arah mana saja yang bisa.
     */
    public static void moveTowardsEnemyTerritory(MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;

        // prioritas: kejar enemy paint terdekat
        MapLocation bestEnemyPaint = null;
        int minPD = Integer.MAX_VALUE;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (tile.getPaint().isEnemy()) {
                int d = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (d < minPD) { minPD = d; bestEnemyPaint = tile.getMapLocation(); }
            }
        }
        if (bestEnemyPaint != null) {
            greedyMove(bestEnemyPaint);
            return;
        }

        // bergerak ke explore target
        int minDist = Integer.MAX_VALUE;
        Direction bestDir = null;
        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            int dist = rc.getLocation().add(dir).distanceSquaredTo(target);
            if (dist < minDist) { minDist = dist; bestDir = dir; }
        }
        // fallback jika semua arah menjauh
        if (bestDir == null) {
            int si = rng.nextInt(directions.length);
            for (int i = 0; i < directions.length; i++) {
                Direction d = directions[(si + i) % directions.length];
                if (rc.canMove(d)) { bestDir = d; break; }
            }
        }
        if (bestDir != null) rc.move(bestDir);
    }

    public static void seekPaintRefill() throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation bestTower = null;
        int minDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
                if (dist < minDist) { minDist = dist; bestTower = ally.getLocation(); }
            }
        }

        if (bestTower != null) {
            if (rc.getLocation().distanceSquaredTo(bestTower) <= 2) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                if (rc.isActionReady() && rc.canTransferPaint(bestTower, -needed))
                    rc.transferPaint(bestTower, -needed);
            } else {
                greedyMove(bestTower);
            }
        } else {
            if (spawnLoc != null) greedyMove(spawnLoc);
            else {
                int si = rng.nextInt(directions.length);
                for (int i = 0; i < directions.length; i++) {
                    Direction d = directions[(si + i) % directions.length];
                    if (rc.canMove(d)) { rc.move(d); break; }
                }
            }
        }
    }
}
