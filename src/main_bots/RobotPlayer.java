package main_bots;

import battlecode.common.*;
import java.util.Random;

/**
 * BOT 1: "THE PAINT EXPANDER" - Greedy by Map Control
 * 
 * STRATEGI UTAMA:
 * Bot ini mengabaikan musuh sepenuhnya dan fokus murni pada kondisi kemenangan utama:
 * mencapai 70% cakupan peta (map coverage).
 * 
 * KOMPOSISI UNIT:
 * - 80% Splashers (sangat efisien: 9 tiles per 50 paint = 0.18 tiles/paint)
 * - 20% Soldiers (support: 1 tile per 5 paint = 0.2 tiles/paint, + bangun tower)
 * - 0% Moppers (tidak digunakan karena tidak efisien untuk coverage)
 * 
 * HEURISTIC (Selection Function):
 * Maksimalkan paint baru per aksi:
 *   Score = (Number of Unpainted Tiles in AoE) / Paint Cost
 * 
 * LOGIC:
 * 1. Robot scan semua tiles dalam jarak serang
 * 2. Hitung efisiensi setiap target: unpainted tiles / paint cost
 * 3. Pilih target dengan efisiensi tertinggi dan serang
 * 4. Jika tidak ada unpainted tiles di vision range, bergerak ke area baru
 * 
 * KEUNGGULAN:
 * - Efisiensi maksimal dalam memakai paint (resources)
 * - Ekspansi cepat karena fokus pada AoE (Splasher)
 * - Tidak buang resources untuk combat
 * 
 * KELEMAHAN:
 * - Rentan terhadap serangan musuh (tidak ada defense/counter-attack)
 * - Bisa kalah jika musuh agresif menghancurkan tower & units
 */
public class RobotPlayer {

    static int turnCount = 0;
    static final Random rng = new Random(6147);

    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
    };

    // Robot akan balik ke tower untuk refill jika paint di bawah nilai ini
    static final int PAINT_REFILL_THRESHOLD = 80;

    public static void run(RobotController rc) throws GameActionException {
        System.out.println("Bot 1: The Paint Expander (Greedy by Map Control)");
        rc.setIndicatorString("Paint Expander Bot - Target: 70% Coverage");

        while (true) {
            turnCount += 1;
            try {
                // Bot 1 fokus pada coverage, tidak spawn Mopper
                switch (rc.getType()) {
                    case SOLDIER:  runSoldier(rc);  break;
                    case SPLASHER: runSplasher(rc); break;
                    default:       runTower(rc);    break;
                }
            } catch (GameActionException e) {
                System.out.println("GameActionException");
                e.printStackTrace();
            } catch (Exception e) {
                System.out.println("Exception");
                e.printStackTrace();
            } finally {
                // Clock.yield() wajib dipanggil di akhir setiap turn agar robot tidak mati
                Clock.yield();
            }
        }
    }

    public static void runTower(RobotController rc) throws GameActionException {
        // Bot 1: Tidak menyerang musuh, fokus murni pada spawning untuk coverage
        
        // Defense tower tidak bisa spawn unit
        if (isDefenseTower(rc.getType())) return;

        // Hitung komposisi unit untuk menjaga rasio 80% Splasher, 20% Soldier
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, rc.getTeam());
        int soldierCount  = 0;
        int splasherCount = 0;
        for (RobotInfo r : nearbyAllies) {
            if (r.getType() == UnitType.SOLDIER)  soldierCount++;
            if (r.getType() == UnitType.SPLASHER) splasherCount++;
        }

        // Bot 1 Strategy: 80% Splashers (lebih efisien untuk AoE painting), 20% Soldiers
        // Splashers dapat mewarnai 9 tiles dengan 50 paint, Soldiers hanya 1 tile dengan 5 paint
        int totalUnits = soldierCount + splasherCount;
        UnitType toSpawn;
        
        if (totalUnits == 0 || (soldierCount * 100 / totalUnits) < 20) {
            toSpawn = UnitType.SOLDIER;
        } else {
            toSpawn = UnitType.SPLASHER;
        }

        if (rc.isActionReady()) {
            for (Direction dir : directions) {
                MapLocation spawnLoc = rc.getLocation().add(dir);
                if (rc.canBuildRobot(toSpawn, spawnLoc)) {
                    rc.buildRobot(toSpawn, spawnLoc);
                    System.out.println("Built a " + toSpawn + " at " + spawnLoc);
                    break;
                }
            }
        }

        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            System.out.println("Tower received message from #" + m.getSenderID() + ": " + m.getBytes());
        }
    }

    // Bot 1: Tidak menyerang - fokus murni pada coverage
    // Tower hanya spawn unit, tidak attack musuh

    public static void runSplasher(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        
        // Refill paint jika hampir habis
        if (rc.getPaint() < PAINT_REFILL_THRESHOLD) {
            boolean refilled = withdrawFromNearbyTower(rc);
            if (refilled) {
                rc.setIndicatorString("Splasher refilling...");
                return;
            }
        }

        // Bot 1 Heuristic: Maksimalkan Unpainted Tiles / Paint Cost
        // Splasher cost = 50 paint per attack, dapat mewarnai area 3x3
        if (rc.isActionReady() && rc.getPaint() >= 50) {
            MapLocation bestTarget = greedySplasherTarget(rc, myLoc);
            if (bestTarget != null && rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
                rc.setIndicatorString("Splasher painting efficiently!");
            }
        }

        // Warnai tile sendiri untuk menghindari penalty
        MapInfo currentTile = rc.senseMapInfo(myLoc);
        if (!currentTile.getPaint().isAlly() && rc.canAttack(myLoc) && rc.getPaint() >= 50) {
            rc.attack(myLoc);
        }

        // Bergerak ke tile unpainted terdekat untuk ekspansi coverage
        if (rc.isMovementReady()) {
            MapLocation target = findNearestUnpaintedTile(rc, myLoc);
            if (target != null) {
                moveToward(rc, myLoc, target);
                rc.setIndicatorString("Moving to unpainted area");
            } else {
                exploreRandom(rc);
            }
        }
    }

    // Bot 1 Heuristic untuk Splasher:
    // Score = (Number of Unpainted Tiles in AoE) / Paint Cost
    // Splasher: cost = 50 paint, AoE = 3x3 (radius^2 = 4)
    // Pilih target yang memaksimalkan efisiensi coverage per paint
    public static MapLocation greedySplasherTarget(RobotController rc, MapLocation myLoc)
            throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(4);
        MapLocation bestLoc = null;
        double bestScore = 0.0;
        final int SPLASHER_COST = 50;

        for (MapInfo mi : nearbyTiles) {
            MapLocation center = mi.getMapLocation();
            if (myLoc.distanceSquaredTo(center) > 4) continue;
            if (!rc.canAttack(center)) continue;

            // Hitung unpainted tiles dalam AoE dari center ini
            int unpaintedCount = countUnpaintedInAoE(rc, center, 4);
            
            // Bot 1 Formula: efisiensi = unpainted tiles / paint cost
            double efficiency = (double) unpaintedCount / SPLASHER_COST;
            
            if (efficiency > bestScore) {
                bestScore = efficiency;
                bestLoc   = center;
            }
        }

        return (bestScore > 0) ? bestLoc : null;
    }

    // Hitung berapa banyak tile yang belum diwarnai (kosong atau milik musuh)
    // dalam radius tertentu dari suatu center. Dipakai untuk menghitung score greedy.
    public static int countUnpaintedInAoE(RobotController rc, MapLocation center, int radiusSq)
            throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos(center, radiusSq);
        int count = 0;
        for (MapInfo t : tiles) {
            PaintType paint = t.getPaint();
            if (paint == PaintType.EMPTY || paint.isEnemy()) count++;
        }
        return count;
    }

    public static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        
        // Refill paint jika hampir habis
        if (rc.getPaint() < PAINT_REFILL_THRESHOLD) {
            withdrawFromNearbyTower(rc);
        }

        // Bot 1: Soldier bertugas membangun tower untuk meningkatkan spawn capacity
        // Lebih banyak tower = lebih cepat mencapai 70% coverage
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo curRuin = null;
        for (MapInfo tile : nearbyTiles) {
            if (tile.hasRuin()) {
                curRuin = tile;
                break;
            }
        }

        if (curRuin != null) {
            MapLocation targetLoc = curRuin.getMapLocation();
            Direction dirToRuin   = myLoc.directionTo(targetLoc);

            if (rc.canMove(dirToRuin)) rc.move(dirToRuin);

            if (rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)) {
                rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
                rc.setIndicatorString("Marking tower pattern");
            }

            // Warnai sesuai pola tower
            for (MapInfo patternTile : rc.senseNearbyMapInfos(targetLoc, 8)) {
                if (patternTile.getMark() != patternTile.getPaint()
                        && patternTile.getMark() != PaintType.EMPTY) {
                    boolean useSecondary = patternTile.getMark() == PaintType.ALLY_SECONDARY;
                    if (rc.canAttack(patternTile.getMapLocation()))
                        rc.attack(patternTile.getMapLocation(), useSecondary);
                }
            }

            if (rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc)) {
                rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, targetLoc);
                System.out.println("Tower built at " + targetLoc + "!");
            }
        }

        // Bot 1 Heuristic untuk Soldier: maksimalkan coverage efficiency
        // Soldier cost = 5 paint per tile
        if (rc.isActionReady() && rc.getPaint() >= 5) {
            MapLocation bestTarget = greedySoldierTarget(rc, myLoc);
            if (bestTarget != null && rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
                rc.setIndicatorString("Soldier painting efficiently");
            }
        }

        // Warnai tile di bawah kaki untuk menghindari penalty
        MapInfo currentTile = rc.senseMapInfo(myLoc);
        if (!currentTile.getPaint().isAlly() && rc.canAttack(myLoc) && rc.getPaint() >= 5) {
            rc.attack(myLoc);
        }

        // Ekspansi ke unpainted area
        if (rc.isMovementReady()) {
            MapLocation target = findNearestUnpaintedTile(rc, myLoc);
            if (target != null) {
                moveToward(rc, myLoc, target);
                rc.setIndicatorString("Expanding coverage");
            } else {
                exploreRandom(rc);
            }
        }
    }

    // Bot 1 Heuristic untuk Soldier:
    // Score = 1 / Paint Cost untuk semua unpainted tiles
    // Bot 1 MENGABAIKAN MUSUH - semua unpainted tiles diperlakukan sama
    // Fokus murni pada coverage, bukan combat
    public static MapLocation greedySoldierTarget(RobotController rc, MapLocation myLoc)
            throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(9);
        MapLocation bestLoc = null;
        int minDist = Integer.MAX_VALUE;
    

        // Bot 1: Pilih unpainted tile terdekat (musuh atau kosong, tidak ada bedanya)
        // Semua unpainted tiles memiliki score yang sama: 1/5 = 0.2 efficiency
        for (MapInfo mi : nearbyTiles) {
            MapLocation loc = mi.getMapLocation();
            if (myLoc.distanceSquaredTo(loc) > 9) continue;
            if (!rc.canAttack(loc)) continue;

            PaintType paint = mi.getPaint();
            // Bot 1: Treat enemy tiles dan empty tiles sama - keduanya unpainted
            if (paint == PaintType.EMPTY || paint.isEnemy()) {
                int dist = myLoc.distanceSquaredTo(loc);
                if (dist < minDist) {
                    minDist = dist;
                    bestLoc = loc;
                }
            }
        }
        return bestLoc;
    }

    // Bot 1: Tidak menggunakan Mopper
    // Fokus pada Splasher (80%) dan Soldier (20%) untuk maksimalisasi coverage
    // Mopper tidak efisien untuk mencapai 70% map coverage

    // Bot 1: Tidak ada combat logic - mengabaikan musuh sepenuhnya
    // Bot ini fokus murni pada mencapai 70% map coverage

    // Navigasi sederhana menuju target dengan mencoba rotasi kanan/kiri jika arah utama terblokir.
    // Ini bukan pathfinding optimal, tapi cukup hemat bytecode untuk Battlecode.
    public static void moveToward(RobotController rc, MapLocation from, MapLocation target)
            throws GameActionException {
        Direction dir = from.directionTo(target);
        if (rc.canMove(dir)) { rc.move(dir); return; }

        Direction right = dir.rotateRight();
        Direction left  = dir.rotateLeft();
        if (rc.canMove(right)) { rc.move(right); return; }
        if (rc.canMove(left))  { rc.move(left);  return; }
        if (rc.canMove(right.rotateRight())) { rc.move(right.rotateRight()); return; }
        if (rc.canMove(left.rotateLeft()))   { rc.move(left.rotateLeft());   return; }
    }

    // Eksplorasi berdasarkan round number agar deterministik (hasil selalu sama untuk input sama).
    // Ini penting di Battlecode karena re-run pertandingan harus menghasilkan hasil yang identik.
    public static void exploreRandom(RobotController rc) throws GameActionException {
        for (int i = 0; i < directions.length; i++) {
            Direction tryDir = directions[(rc.getRoundNum() + i) % directions.length];
            if (rc.canMove(tryDir)) {
                rc.move(tryDir);
                return;
            }
        }
    }

    public static MapLocation findNearestUnpaintedTile(RobotController rc, MapLocation myLoc)
            throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos(-1);
        MapLocation nearest = null;
        int nearestDist = Integer.MAX_VALUE;

        for (MapInfo mi : tiles) {
            // Bot 1: Treat all unpainted tiles equally (empty OR enemy)
            // isPassable() memastikan tile bukan wall atau ruin
            PaintType paint = mi.getPaint();
            if ((paint == PaintType.EMPTY || paint.isEnemy()) && mi.isPassable()) {
                int dist = myLoc.distanceSquaredTo(mi.getMapLocation());
                if (dist < nearestDist) {
                    nearestDist = dist;
                    nearest     = mi.getMapLocation();
                }
            }
        }
        return nearest;
    }

    public static boolean withdrawFromNearbyTower(RobotController rc) throws GameActionException {
        // Cek tower dalam radius sqrt(2) untuk withdraw langsung tanpa perlu gerak
        RobotInfo[] closeAllies = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : closeAllies) {
            if (!isUnitTower(ally.getType())) continue;
            int need = getPaintCapacity(rc.getType()) - rc.getPaint();
            if (need <= 0) return true;
            // transferPaint dengan nilai negatif = ambil paint dari tower ke robot
            if (rc.canTransferPaint(ally.getLocation(), -need)) {
                rc.transferPaint(ally.getLocation(), -need);
                System.out.println("Withdrew " + need + " paint from tower");
                return true;
            }
        }

        // Jika tower tidak dalam jangkauan, cari tower terdekat dan gerak ke sana
        RobotInfo[] allAllies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo nearestTower = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allAllies) {
            if (!isUnitTower(ally.getType())) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
            if (dist < nearestDist) {
                nearestDist  = dist;
                nearestTower = ally;
            }
        }

        if (nearestTower != null && rc.isMovementReady()) {
            moveToward(rc, rc.getLocation(), nearestTower.getLocation());
        }
        return false;
    }

    public static boolean isUnitTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_MONEY_TOWER
            || type == UnitType.LEVEL_TWO_MONEY_TOWER
            || type == UnitType.LEVEL_THREE_MONEY_TOWER
            || type == UnitType.LEVEL_ONE_PAINT_TOWER
            || type == UnitType.LEVEL_TWO_PAINT_TOWER
            || type == UnitType.LEVEL_THREE_PAINT_TOWER
            || isDefenseTower(type);
    }

    public static boolean isDefenseTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_DEFENSE_TOWER
            || type == UnitType.LEVEL_TWO_DEFENSE_TOWER
            || type == UnitType.LEVEL_THREE_DEFENSE_TOWER;
    }

    // Helper untuk mendapatkan kapasitas paint maksimal per tipe robot.
    // Soldier: 200, Splasher: 300, Mopper: 100 (sesuai spesifikasi game)
    public static int getPaintCapacity(UnitType type) {
        if (type == UnitType.SOLDIER)  return 200;
        if (type == UnitType.SPLASHER) return 300;
        if (type == UnitType.MOPPER)   return 100;
        return 0;
    }
}

