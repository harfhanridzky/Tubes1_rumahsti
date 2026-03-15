package alternative_bots_2;

import battlecode.common.*;
import java.util.Random;

//bot berfokus untuk agresif menyerang bot musuh dengan mopper, 
// dengan adanya soldier yang handle build tower dan splasher handle paint
public class RobotPlayer {

    static RobotController rc;
    static Random rng = null;
    static MapLocation spawnLoc = null;
    static MapLocation exploreTarget = null;

    static MapLocation prevLoc = null;
    static int stuckCount = 0;

    static final Direction[] directions = {
        Direction.NORTH, Direction.NORTHEAST, Direction.EAST, Direction.SOUTHEAST,
        Direction.SOUTH, Direction.SOUTHWEST, Direction.WEST, Direction.NORTHWEST,
    };

    static final Direction[] cardinalDirs = {
        Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST,
    };

    static final UnitType[] towerPriority = {
        UnitType.LEVEL_ONE_PAINT_TOWER,
        UnitType.LEVEL_ONE_MONEY_TOWER,
        UnitType.LEVEL_ONE_DEFENSE_TOWER
    };

    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        RobotPlayer.rc = rc;
        if (rng == null) rng = new Random(rc.getID());
        if (spawnLoc == null) spawnLoc = rc.getLocation();

        while (true) {
            try {
                // cek stuck setiap turn untuk semua unit non-tower
                if (!rc.getType().isTowerType()) {
                    MapLocation curLoc = rc.getLocation();
                    if (prevLoc != null && curLoc.equals(prevLoc)) stuckCount++;
                    else stuckCount = 0;
                    prevLoc = curLoc;
                }

                if (rc.getType().isTowerType()) runTower();
                else if (rc.getType() == UnitType.SOLDIER) runSoldier();
                else if (rc.getType() == UnitType.MOPPER) runMopper();
                else if (rc.getType() == UnitType.SPLASHER) runSplasher();
            } catch (GameActionException e) {
                e.printStackTrace();
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                Clock.yield();
            }
        }
    }

    //Tower selalu spawn unit setiap turn jika resource cukup, dengan prioritas splasher untuk paint territory dan mopper untuk serang musuh.
    // Soldier tetap dipertahankan untuk maintenance tower dan serang tower musuh jika terlihat.
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
        int round = rc.getRoundNum();

        UnitType toSpawn = null;
        int roll = rng.nextInt(100);

        if (round < 100) {
            if (roll < 50) {
                if (chips >= 250 && paint >= 200) toSpawn = UnitType.SOLDIER;
            } else {
                if (chips >= 400 && paint >= 300) toSpawn = UnitType.SPLASHER;
            }
            if (toSpawn == null && chips >= 250 && paint >= 200) toSpawn = UnitType.SOLDIER;
        } else {
            if (roll < 50) {
                if (chips >= 400 && paint >= 300) toSpawn = UnitType.SPLASHER;
            } else if (roll < 85) {
                if (chips >= 300 && paint >= 100) toSpawn = UnitType.MOPPER;
            } else {
                if (chips >= 250 && paint >= 200) toSpawn = UnitType.SOLDIER;
            }
            // konditional fallback untuk pastikan spawn setiap turn jika resource cukup
            if (toSpawn == null && chips >= 400 && paint >= 300) toSpawn = UnitType.SPLASHER;
            if (toSpawn == null && chips >= 300 && paint >= 100) toSpawn = UnitType.MOPPER;
            if (toSpawn == null && chips >= 250 && paint >= 200) toSpawn = UnitType.SOLDIER;
        }

        if (toSpawn != null) spawnUnit(toSpawn);
    }

    // Spawn unit ke arah wilayah musuh (greedy positioning).
    public static void spawnUnit(UnitType type) throws GameActionException {
        MapLocation loc = rc.getLocation();
        MapLocation enemySide = new MapLocation(
            rc.getMapWidth() - 1 - loc.x,
            rc.getMapHeight() - 1 - loc.y
        );

        MapLocation bestSpawn = null;
        int bestDist = Integer.MAX_VALUE;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation c = new MapLocation(loc.x + dx, loc.y + dy);
                if (loc.distanceSquaredTo(c) > 4) continue;
                if (!rc.canBuildRobot(type, c)) continue;
                int dist = c.distanceSquaredTo(enemySide);
                if (dist < bestDist) { bestDist = dist; bestSpawn = c; }
            }
        }
        if (bestSpawn != null) rc.buildRobot(type, bestSpawn);
    }

    // soldier fokus tower, serang tower musuh, dan bantu paint
    public static void runSoldier() throws GameActionException {
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;

        if (paint < maxPaint * 0.2) {
            seekPaintRefill();
            return;
        }

        MapLocation target = getExploreTarget();

        tryUpgradeNearbyTower();
        tryCompleteSRP();

        // serang tower musuh jika terlihat
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
            smartMove(targetTower.getLocation());
            if (rc.isActionReady() && rc.canAttack(targetTower.getLocation()))
                rc.attack(targetTower.getLocation());
            paintBestTile(target);
            return;
        }

        // Bangun tower di ruin terdekat
        MapInfo anyRuin = findNearestRuin();
        if (anyRuin != null) {
            buildTowerAtRuin(anyRuin.getMapLocation());
            paintBestTile(target);
            return;
        }

        tryMarkSRP();

        // Explore + paint territory
        moveTowardsEnemyTerritory(target);
        paintBestTile(target);
    }

    
     //Upgrade tower ally terdekat. Prioritas: paint tower
    public static void tryUpgradeNearbyTower() throws GameActionException {
        MapLocation paintTowerLoc = null;
        MapLocation otherTowerLoc = null;

        for (RobotInfo ally : rc.senseNearbyRobots(2, rc.getTeam())) {
            if (!ally.getType().isTowerType()) continue;
            if (!rc.canUpgradeTower(ally.getLocation())) continue;
            UnitType t = ally.getType();
            if (t == UnitType.LEVEL_ONE_PAINT_TOWER || t == UnitType.LEVEL_TWO_PAINT_TOWER) {
                paintTowerLoc = ally.getLocation();
            } else if (otherTowerLoc == null) {
                otherTowerLoc = ally.getLocation();
            }
        }

        if (paintTowerLoc != null) rc.upgradeTower(paintTowerLoc);
        else if (otherTowerLoc != null) rc.upgradeTower(otherTowerLoc);
    }

    // Complete SRP yang sudah terpaint penuh untuk dapat bonus resource
    public static void tryCompleteSRP() throws GameActionException {
        if (rc.getChips() < 200) return;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.isResourcePatternCenter()) continue;
            if (rc.canCompleteResourcePattern(tile.getMapLocation())) {
                rc.completeResourcePattern(tile.getMapLocation());
                return;
            }
        }
    }

    // mark SRP di wilayah aman (allied territory) jika punya cukup resource.
    public static void tryMarkSRP() throws GameActionException {
        if (rc.getChips() < 300) return;
        if (rc.getPaint() < 50) return;
        MapLocation myLoc = rc.getLocation();
        if (!rc.senseMapInfo(myLoc).getPaint().isAlly()) return;

        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                MapLocation center = new MapLocation(myLoc.x + dx, myLoc.y + dy);
                if (rc.canMarkResourcePattern(center)) {
                    rc.markResourcePattern(center);
                    return;
                }
            }
        }
    }

    //Hitung explore target di sisi musuh.
    public static MapLocation getExploreTarget() {
        int w = rc.getMapWidth();
        int h = rc.getMapHeight();
        if (exploreTarget == null || rc.getLocation().distanceSquaredTo(exploreTarget) <= 9) {
            int baseX = (spawnLoc != null) ? w - 1 - spawnLoc.x : w / 2;
            int baseY = (spawnLoc != null) ? h - 1 - spawnLoc.y : h / 2;
            int ex = Math.max(0, Math.min(w - 1, baseX + rng.nextInt(13) - 6));
            int ey = Math.max(0, Math.min(h - 1, baseY + rng.nextInt(13) - 6));
            exploreTarget = new MapLocation(ex, ey);
        }
        return exploreTarget;
    }

    /**
     * Cat tile terbaik. Prioritas:
     * 1. Tile dengan mark yang belum dipaint (untuk tower/SRP pattern)
     * 2. Tile kosong terdekat ke target
     * 3. Fallback: cat tile di bawah kaki
     */
    public static void paintBestTile(MapLocation target) throws GameActionException {
        if (!rc.isActionReady()) return;

        // Prioritas 1: paint tile yang sudah di-mark tapi belum sesuai
        for (MapInfo tile : rc.senseNearbyMapInfos(rc.getLocation(), 3)) {
            MapLocation tileLoc = tile.getMapLocation();
            if (!rc.canAttack(tileLoc)) continue;
            if (tile.getMark() != PaintType.EMPTY && tile.getMark() != tile.getPaint()) {
                boolean useSecondary = tile.getMark() == PaintType.ALLY_SECONDARY;
                rc.attack(tileLoc, useSecondary);
                return;
            }
        }

        // Prioritas 2: tile kosong terdekat ke target
        MapLocation bestTile = null;
        int minDist = Integer.MAX_VALUE;
        for (MapInfo tile : rc.senseNearbyMapInfos(rc.getLocation(), 3)) {
            MapLocation tileLoc = tile.getMapLocation();
            if (!rc.canAttack(tileLoc)) continue;
            if (tile.getPaint() != PaintType.EMPTY) continue;
            int dist = tileLoc.distanceSquaredTo(target);
            if (dist < minDist) { minDist = dist; bestTile = tileLoc; }
        }

        if (bestTile != null) {
            rc.attack(bestTile);
        } else {
            // Fallback: cat tile di bawah kaki jika belum allied
            MapLocation cur = rc.getLocation();
            if (!rc.senseMapInfo(cur).getPaint().isAlly() && rc.canAttack(cur))
                rc.attack(cur);
        }
    }

    //Cari ruin TERDEKAT apapun (untuk Fase 1 — bangun tower secepat mungkin).
    public static MapInfo findNearestRuin() throws GameActionException {
        MapInfo nearest = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;
            if (rc.senseRobotAtLocation(tile.getMapLocation()) != null) continue;
            int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
            if (dist < minDist) { minDist = dist; nearest = tile; }
        }
        return nearest;
    }

    // Cari ruin di jalur menuju target 
    public static MapInfo findRuinOnPath(MapLocation target) throws GameActionException {
        MapInfo nearest = null;
        int minDist = Integer.MAX_VALUE;
        int distToTarget = rc.getLocation().distanceSquaredTo(target);

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;
            if (rc.senseRobotAtLocation(tile.getMapLocation()) != null) continue;
            MapLocation ruinLoc = tile.getMapLocation();
            if (ruinLoc.distanceSquaredTo(target) > distToTarget) continue;
            int dist = rc.getLocation().distanceSquaredTo(ruinLoc);
            if (dist < minDist) { minDist = dist; nearest = tile; }
        }
        return nearest;
    }

    // Bangun tower di ruin. Prioritas tipe: paint > money > defense.
    public static void buildTowerAtRuin(MapLocation ruinLoc) throws GameActionException {
        // Mark pattern (paint tower first)
        for (UnitType towerType : towerPriority) {
            if (rc.canMarkTowerPattern(towerType, ruinLoc)) {
                rc.markTowerPattern(towerType, ruinLoc);
                break;
            }
        }

        // Cari tile yang belum dipaint sesuai mark
        MapLocation unpaintedTile = null;
        int minDist = Integer.MAX_VALUE;
        for (MapInfo pt : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (pt.getMark() != PaintType.EMPTY && pt.getMark() != pt.getPaint()) {
                int dist = rc.getLocation().distanceSquaredTo(pt.getMapLocation());
                if (dist < minDist) { minDist = dist; unpaintedTile = pt.getMapLocation(); }
            }
        }

        // Gerak ke tile yang perlu dipaint
        if (unpaintedTile != null) {
            smartMove(unpaintedTile);
        } else {
            Direction toRuin = rc.getLocation().directionTo(ruinLoc);
            if (rc.canMove(toRuin)) rc.move(toRuin);
        }

        // Paint tile sesuai mark
        for (MapInfo pt : rc.senseNearbyMapInfos(ruinLoc, 8)) {
            if (pt.getMark() != PaintType.EMPTY && pt.getMark() != pt.getPaint()) {
                boolean useSecondary = pt.getMark() == PaintType.ALLY_SECONDARY;
                if (rc.canAttack(pt.getMapLocation())) {
                    rc.attack(pt.getMapLocation(), useSecondary);
                    break;
                }
            }
        }

        // Complete tower jika pattern sudah lengkap
        for (UnitType towerType : towerPriority) {
            if (rc.canCompleteTowerPattern(towerType, ruinLoc)) {
                rc.completeTowerPattern(towerType, ruinLoc);
                break;
            }
        }
    }

    // ==================== MOPPER ====================
    public static void runMopper() throws GameActionException {
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;

        if (paint < maxPaint * 0.2) {
            seekPaintRefill();
            return;
        }

        // Transfer paint ke ally kritis
        if (rc.isActionReady() && paint > maxPaint * 0.5) {
            transferToCriticalAlly();
        }

        // Serang sebelum gerak
        if (rc.isActionReady()) executeBestMopAction();

        // movement serang bot musuh
        if (rc.isMovementReady()) {
            mopperMove();
        }

        // Serang lagi setelah gerak
        if (rc.isActionReady()) executeBestMopAction();
    }

    /**
     * Mopper movement: buru bot musuh.
     * - if ada bot musuh terlihat kejar yang terdekat
     * - Tidak ada bot musuh -> explore ke wilayah musuh (cari mangsa)
     * - tetap mop enemy paint yang ditemui
     */
    public static void mopperMove() throws GameActionException {
        if (!rc.isMovementReady()) return;

        // Cari bot musuh terdekat -> robot non-tower > tower)
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        RobotInfo bestTarget = null;
        int bestDist = Integer.MAX_VALUE;

        for (RobotInfo e : enemies) {
            // robot non-tower mop langsung)
            if (e.getType().isTowerType()) continue;
            int d = rc.getLocation().distanceSquaredTo(e.getLocation());
            if (d < bestDist) { bestDist = d; bestTarget = e; }
        }

        if (bestTarget != null) {
            smartMove(bestTarget.getLocation());
            return;
        }

        // Tidak ada bot musuh -> cari enemy paint sebagai petunjuk arah musuh
        MapLocation closestEnemyPaint = null;
        int minDist = Integer.MAX_VALUE;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.getPaint().isEnemy()) continue;
            int d = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
            if (d < minDist) { minDist = d; closestEnemyPaint = tile.getMapLocation(); }
        }

        if (closestEnemyPaint != null) {
            // Enemy paint = petunjuk arah musuh, ikuti
            smartMove(closestEnemyPaint);
        } else {
            // Explore ke wilayah musuh
            smartMove(getExploreTarget());
        }
    }

    //Transfer paint ke ally yang kritis (< 20% paint).
    public static void transferToCriticalAlly() throws GameActionException {
        int paint = rc.getPaint();
        for (RobotInfo ally : rc.senseNearbyRobots(2, rc.getTeam())) {
            if (ally.getType().isTowerType()) continue;
            int pct = (ally.getPaintAmount() * 100) / ally.getType().paintCapacity;
            if (pct < 20) {
                int transfer = Math.min(paint / 2, ally.getType().paintCapacity - ally.getPaintAmount());
                if (transfer > 0 && rc.canTransferPaint(ally.getLocation(), transfer)) {
                    rc.transferPaint(ally.getLocation(), transfer);
                    return;
                }
            }
        }
    }

    /**
     * Scoring (prioritas serang bot musuh):
     * - Mop tile dengan robot musuh: 15 -> steal paint musuh
     * - Mop tile enemy paint : 3 
     * - Mop swing 1+ robot: 8 per robot
     */
    public static void executeBestMopAction() throws GameActionException {
        if (!rc.isActionReady()) return;

        int bestScore = 0;
        int bestAction = 0; 
        MapLocation bestMopTarget = null;
        Direction bestSwingDir = null;

        // Score mop single tile prioritas tile dengan robot musuh
        for (MapInfo tile : rc.senseNearbyMapInfos(rc.getLocation(), 2)) {
            MapLocation tileLoc = tile.getMapLocation();
            if (!rc.canAttack(tileLoc) || !tile.getPaint().isEnemy()) continue;
            int score = 3; 
            RobotInfo r = rc.senseRobotAtLocation(tileLoc);
            if (r != null && r.getTeam() != rc.getTeam()) score = 15; // UTAMA: steal paint dari bot!
            if (score > bestScore) { bestScore = score; bestAction = 1; bestMopTarget = tileLoc; }
        }

        // Score mop swing apabila mengenai 1+ robot musuh
        for (Direction swingDir : cardinalDirs) {
            if (!rc.canMopSwing(swingDir)) continue;
            int robotCount = 0;
            for (int step = 1; step <= 2; step++) {
                MapLocation center = rc.getLocation();
                for (int s = 0; s < step; s++) center = center.add(swingDir);
                Direction left = swingDir.rotateLeft().rotateLeft();
                Direction right = swingDir.rotateRight().rotateRight();
                MapLocation[] targets = { center, center.add(left), center.add(right) };
                for (MapLocation t : targets) {
                    if (!rc.canSenseLocation(t)) continue;
                    RobotInfo r = rc.senseRobotAtLocation(t);
                    if (r != null && r.getTeam() != rc.getTeam()) robotCount++;
                }
            }
            // Swing worthwhile jika 1+ robot — kita buru musuh!
            if (robotCount >= 1) {
                int score = robotCount * 8;
                if (score > bestScore) { bestScore = score; bestAction = 2; bestSwingDir = swingDir; }
            }
        }

        if (bestAction == 1 && bestMopTarget != null) rc.attack(bestMopTarget);
        else if (bestAction == 2 && bestSwingDir != null) rc.mopSwing(bestSwingDir);
    }

    // ==================== SPLASHER ====================
    public static void runSplasher() throws GameActionException {
        int paint = rc.getPaint();
        int maxPaint = rc.getType().paintCapacity;
        if (paint < maxPaint * 0.2) { seekPaintRefill(); return; }

        // Serang sebelum gerak
        if (rc.isActionReady() && paint >= 50) {
            executeBestSplash();
        }

        moveTowardsEnemyTerritory(getExploreTarget());

        // Serang setelah gerak
        if (rc.isActionReady() && rc.getPaint() >= 50) {
            executeBestSplash();
        }
    }

    //Cari dan eksekusi splash target terbaik.
    public static void executeBestSplash() throws GameActionException {
        if (!rc.isActionReady() || rc.getPaint() < 50) return;

        MapLocation bestTarget = null;
        int bestScore = 0;
        MapLocation curLoc = rc.getLocation();

        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                MapLocation target = new MapLocation(curLoc.x + dx, curLoc.y + dy);
                if (curLoc.distanceSquaredTo(target) > 4 || !rc.canAttack(target)) continue;

                int score = 0;
                for (int ax = -2; ax <= 2; ax++) {
                    for (int ay = -2; ay <= 2; ay++) {
                        MapLocation aoe = new MapLocation(target.x + ax, target.y + ay);
                        int distToCenter = target.distanceSquaredTo(aoe);
                        if (distToCenter > 4 || !rc.canSenseLocation(aoe)) continue;

                        MapInfo info = rc.senseMapInfo(aoe);
                        if (info.isWall() || info.hasRuin()) continue;
                        PaintType pt = info.getPaint();

                        if (distToCenter <= 2) {
                            // overwrite enemy paint!
                            if (pt.isEnemy()) score += 5;
                            else if (pt == PaintType.EMPTY) score += 1;
                        } else {
                            // hanya paint empty
                            if (pt == PaintType.EMPTY) score += 1;
                        }
                    }
                }

                // Bonus damage ke enemy tower
                if (rc.canSenseLocation(target)) {
                    RobotInfo r = rc.senseRobotAtLocation(target);
                    if (r != null && r.getTeam() != rc.getTeam() && r.getType().isTowerType())
                        score += 8;
                }

                if (score > bestScore) { bestScore = score; bestTarget = target; }
            }
        }

        if (bestTarget != null && bestScore > 2) rc.attack(bestTarget);
    }

    // movements
    public static void smartMove(MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;

        // jika stuck selama 3 turn, reset explore target untuk cari target baru dan hindari balik ke spot yang sama
        if (stuckCount >= 3) {
            stuckCount = 0;
            exploreTarget = null; // Force target baru agar tidak balik ke stuck spot
            randomMove();
            return;
        }

        // Greedy arah yang paling dekat ke target
        int curDist = rc.getLocation().distanceSquaredTo(target);
        Direction bestDir = null;
        int bestDist = curDist;
        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            int dist = rc.getLocation().add(dir).distanceSquaredTo(target);
            if (dist < bestDist) { bestDist = dist; bestDir = dir; }
        }

        if (bestDir != null) {
            rc.move(bestDir);
            return;
        }

        // Wall sliding: coba seluruh rotasi dari arah target 
        Direction dirToTarget = rc.getLocation().directionTo(target);
        if (dirToTarget == Direction.CENTER) return;

        Direction[] wallSlide = {
            dirToTarget.rotateLeft(), dirToTarget.rotateRight(),
            dirToTarget.rotateLeft().rotateLeft(), dirToTarget.rotateRight().rotateRight(),
            dirToTarget.opposite().rotateRight(), dirToTarget.opposite().rotateLeft(),
            dirToTarget.opposite()
        };
        for (Direction d : wallSlide) {
            if (rc.canMove(d)) { rc.move(d); return; }
        }
    }

    // gerak ke arah random yang bisa dilalui.
    public static void randomMove() throws GameActionException {
        int si = rng.nextInt(directions.length);
        for (int i = 0; i < directions.length; i++) {
            Direction d = directions[(si + i) % directions.length];
            if (rc.canMove(d)) { rc.move(d); return; }
        }
    }


    /**
     * Bergerak menuju wilayah musuh.
     * Soldier/Splasher -> kejar enemy paint terdekat, lalu explore target.
     */
    public static void moveTowardsEnemyTerritory(MapLocation target) throws GameActionException {
        if (!rc.isMovementReady()) return;

        // Prioritas: kejar enemy paint terdekat
        MapLocation bestEnemyPaint = null;
        int minPD = Integer.MAX_VALUE;
        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (tile.getPaint().isEnemy()) {
                int d = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
                if (d < minPD) { minPD = d; bestEnemyPaint = tile.getMapLocation(); }
            }
        }
        if (bestEnemyPaint != null) {
            smartMove(bestEnemyPaint);
            return;
        }

        smartMove(target);
    }

    // cari tower ally terdekat untuk refill paint. Jika sudah di sebelah tower lakukan withdraw. Jika jauh bot gerak ke tower.
    public static void seekPaintRefill() throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation bestTower = null;
        int minDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allies) {
            if (!ally.getType().isTowerType()) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
            if (dist < minDist) { minDist = dist; bestTower = ally.getLocation(); }
        }

        if (bestTower != null) {
            if (rc.getLocation().distanceSquaredTo(bestTower) <= 2) {
                int needed = rc.getType().paintCapacity - rc.getPaint();
                if (rc.isActionReady() && rc.canTransferPaint(bestTower, -needed))
                    rc.transferPaint(bestTower, -needed);
            } else {
                smartMove(bestTower);
            }
        } else {
            // Tidak ada tower visible, gerak ke spawn location
            if (spawnLoc != null) smartMove(spawnLoc);
            else randomMove();
        }
    }
}
