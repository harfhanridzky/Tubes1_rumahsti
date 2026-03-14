package main_bots;

import battlecode.common.*;

public class RobotPlayer {

    static int turnCount = 0;

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

    static final int PAINT_REFILL_THRESHOLD = 80;

    static MapLocation spawnOrigin = null;
    static MapLocation symmetryTarget = null;

    static MapLocation exploreTarget = null;
    static MapLocation prevLocation = null;
    static int stuckTurns = 0;

    static MapLocation currentRuinTarget = null;
    static int ruinBuildTurns = 0;

    // tipe pesan komunikasi
    static final int MSG_TOWER_LOC = 0;
    static final int MSG_RUIN_LOC = 1;
    static final int MSG_RUIN_COMPLETE = 3;

    static final int MAX_KNOWN = 8;
    static MapLocation[] knownTowers = new MapLocation[MAX_KNOWN];
    static int knownTowerCount = 0;
    static MapLocation[] knownRuins = new MapLocation[MAX_KNOWN];
    static int knownRuinCount = 0;

    static MapLocation[] skippedRuins = new MapLocation[4];
    static int skippedRuinCount = 0;

    // entry point utama, dipanggil tiap turn untuk semua unit
    public static void run(RobotController rc) throws GameActionException {
        if (spawnOrigin == null) {
            spawnOrigin = rc.getLocation();
            int w = rc.getMapWidth();
            int h = rc.getMapHeight();
            symmetryTarget = new MapLocation(w - 1 - spawnOrigin.x, h - 1 - spawnOrigin.y);
        }

        while (true) {
            turnCount++;
            try {
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
                Clock.yield();
            }
        }
    }

    // logika tower: broadcast, serang musuh, spawn unit sesuai kebutuhan
    public static void runTower(RobotController rc) throws GameActionException {
        boolean isDefense = isDefenseTower(rc.getType());

        if (turnCount % 5 == 0) {
            towerBroadcast(rc);
        }

        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            int data = m.getBytes();
            int type = decodeType(data);
            MapLocation loc = decodeLoc(data);
            switch (type) {
                case MSG_TOWER_LOC:     addKnownTower(loc);      break;
                case MSG_RUIN_LOC:      addKnownRuin(loc);       break;
                case MSG_RUIN_COMPLETE: removeKnownRuin(loc);    break;
            }
        }

        if (isDefense) return;

        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        boolean ruinNearby = false;
        for (MapInfo tile : nearbyTiles) {
            if (!tile.hasRuin()) continue;
            RobotInfo robotOnRuin = rc.senseRobotAtLocation(tile.getMapLocation());
            if (robotOnRuin == null) {
                ruinNearby = true;
                break;
            }
        }

        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(-1, rc.getTeam());
        int soldierCount = 0, splasherCount = 0;
        for (RobotInfo r : nearbyAllies) {
            if (r.getType() == UnitType.SOLDIER)  soldierCount++;
            if (r.getType() == UnitType.SPLASHER) splasherCount++;
        }

        int chips = rc.getChips();
        int numTowers = rc.getNumberTowers();

        // upgrade dulu kalau bisa
        if (chips >= 1000 && rc.canUpgradeTower(rc.getLocation())) {
            rc.upgradeTower(rc.getLocation());
            chips = rc.getChips();
        }

        // stagger spawn supaya tidak semua tower spawn bareng
        int spawnInterval = 1;
        if (numTowers >= 5) spawnInterval = 2;
        if (numTowers >= 8) spawnInterval = 3;

        boolean shouldSpawn = (turnCount + rc.getID()) % spawnInterval == 0;
        if (chips > 1500) shouldSpawn = true; // kalau chip numpuk, spawn aja
        if (!shouldSpawn) return;

        if (chips <= 200) return;

        int totalUnits = soldierCount + splasherCount;
        UnitType toSpawn;

        if (ruinNearby && soldierCount == 0) {
            toSpawn = UnitType.SOLDIER;
        } else if (turnCount < 200) {
            toSpawn = (totalUnits == 0 || soldierCount * 100 / Math.max(1, totalUnits) < 60)
                    ? UnitType.SOLDIER : UnitType.SPLASHER;
        } else if (numTowers < 5) {
            toSpawn = (soldierCount * 100 / Math.max(1, totalUnits) < 40)
                    ? UnitType.SOLDIER : UnitType.SPLASHER;
        } else {
            toSpawn = (soldierCount * 100 / Math.max(1, totalUnits) < 25)
                    ? UnitType.SOLDIER : UnitType.SPLASHER;
        }

        if (rc.isActionReady()) {
            Direction bestDir = null;
            if (toSpawn == UnitType.SOLDIER && ruinNearby) {
                bestDir = findSpawnDirToward(rc, nearbyTiles, toSpawn);
            }

            if (bestDir != null) {
                rc.buildRobot(toSpawn, rc.getLocation().add(bestDir));
            } else {
                for (Direction dir : directions) {
                    if (rc.canBuildRobot(toSpawn, rc.getLocation().add(dir))) {
                        rc.buildRobot(toSpawn, rc.getLocation().add(dir));
                        break;
                    }
                }
            }
        }
    }

    // cari arah spawn unit yang mengarah ke ruin terdekat
    static Direction findSpawnDirToward(RobotController rc, MapInfo[] tiles, UnitType type) throws GameActionException {
        MapLocation closestRuin = null;
        int closestDist = Integer.MAX_VALUE;
        for (MapInfo tile : tiles) {
            if (!tile.hasRuin()) continue;
            RobotInfo r = rc.senseRobotAtLocation(tile.getMapLocation());
            if (r != null) continue;
            int dist = rc.getLocation().distanceSquaredTo(tile.getMapLocation());
            if (dist < closestDist) {
                closestDist = dist;
                closestRuin = tile.getMapLocation();
            }
        }
        if (closestRuin == null) return null;
        Direction toRuin = rc.getLocation().directionTo(closestRuin);
        Direction[] tryDirs = {toRuin, toRuin.rotateLeft(), toRuin.rotateRight(),
                toRuin.rotateLeft().rotateLeft(), toRuin.rotateRight().rotateRight()};
        for (Direction dir : tryDirs) {
            if (rc.canBuildRobot(type, rc.getLocation().add(dir))) return dir;
        }
        return null;
    }

    // logika splasher: serang area musuh, cover tile kosong, kejar enemy tower
    public static void runSplasher(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        trackStuck(myLoc);
        processMessages(rc);

        if (rc.getPaint() < PAINT_REFILL_THRESHOLD) {
            if (tryRefillPaint(rc)) return;
        }

        // cat tile: prioritas area kosong/musuh
        if (rc.isActionReady() && rc.getPaint() >= 50) {
            MapLocation bestTarget = greedySplasherTarget(rc, myLoc);
            if (bestTarget != null && rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
            } else {
                MapInfo cur = rc.senseMapInfo(myLoc);
                if (!cur.getPaint().isAlly() && rc.canAttack(myLoc)) rc.attack(myLoc);
            }
        }

        // gerak ke frontier atau explore
        if (rc.isMovementReady()) {
            if (shouldDisperse(rc, myLoc)) {
                disperseMove(rc, myLoc);
            } else {
                MapLocation moveTarget = findSplasherFrontier(rc, myLoc);
                if (moveTarget != null) moveToward(rc, moveTarget);
                else splasherExplore(rc, myLoc);
            }
            myLoc = rc.getLocation();
        }

        rc.setIndicatorString("Splash: cover p=" + rc.getPaint());
    }

    // logika soldier: bangun tower di ruin, cat tile, explore map
    public static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        trackStuck(myLoc);
        processMessages(rc);

        if (rc.getPaint() < PAINT_REFILL_THRESHOLD) {
            if (tryRefillPaint(rc)) return;
        }

        // timeout kalau terlalu lama di satu ruin
        if (currentRuinTarget != null) {
            ruinBuildTurns++;
            if (ruinBuildTurns > 20) {
                addSkippedRuin(currentRuinTarget);
                currentRuinTarget = null;
                ruinBuildTurns = 0;
            }
        }

        // cari ruin yang visible
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo bestRuin = null;
        int bestRuinDist = Integer.MAX_VALUE;
        for (MapInfo tile : nearbyTiles) {
            if (!tile.hasRuin()) continue;
            MapLocation rl = tile.getMapLocation();
            if (isSkippedRuin(rl)) continue;
            RobotInfo robotOnRuin = rc.senseRobotAtLocation(rl);
            if (robotOnRuin != null) continue;

            // skip kalau daerahnya penuh cat musuh
            boolean hasEnemyPaint = false;
            for (MapInfo nearby : rc.senseNearbyMapInfos(rl, 8)) {
                if (nearby.getPaint().isEnemy()) {
                    hasEnemyPaint = true;
                    break;
                }
            }
            if (hasEnemyPaint) continue;

            int dist = myLoc.distanceSquaredTo(rl);
            if (dist < bestRuinDist) {
                bestRuinDist = dist;
                bestRuin = tile;
            }
        }

        // kalau tidak ada ruin visible, pakai ruin dari komunikasi
        if (bestRuin == null && knownRuinCount > 0) {
            MapLocation closestKnown = null;
            int closestDist = Integer.MAX_VALUE;
            for (int i = 0; i < knownRuinCount; i++) {
                if (isSkippedRuin(knownRuins[i])) continue;
                int dist = myLoc.distanceSquaredTo(knownRuins[i]);
                if (dist < closestDist) {
                    closestDist = dist;
                    closestKnown = knownRuins[i];
                }
            }
            if (closestKnown != null) {
                if (rc.isMovementReady()) {
                    moveToward(rc, closestKnown);
                    myLoc = rc.getLocation();
                }
                if (rc.canSenseLocation(closestKnown)) {
                    RobotInfo robotOnRuin = rc.senseRobotAtLocation(closestKnown);
                    if (robotOnRuin != null) {
                        removeKnownRuin(closestKnown);
                        broadcastRuinComplete(rc, closestKnown);
                    }
                }
                paintWhileMoving(rc, myLoc);
                rc.setIndicatorString("-> known ruin " + closestKnown);
                return;
            }
        }

        // build tower di ruin
        if (bestRuin != null) {
            MapLocation ruinLoc = bestRuin.getMapLocation();
            if (currentRuinTarget == null || !currentRuinTarget.equals(ruinLoc)) {
                ruinBuildTurns = 0;
            }
            currentRuinTarget = ruinLoc;

            UnitType preferred = getPreferredTower(rc, ruinLoc);
            UnitType fallback = getFallbackTower(rc, ruinLoc);
            if (rc.canMarkTowerPattern(preferred, ruinLoc)) {
                rc.markTowerPattern(preferred, ruinLoc);
            } else if (rc.canMarkTowerPattern(fallback, ruinLoc)) {
                rc.markTowerPattern(fallback, ruinLoc);
            }

            if (rc.isMovementReady()) {
                moveToward(rc, ruinLoc);
                myLoc = rc.getLocation();
            }

            if (rc.isActionReady()) {
                for (MapInfo patternTile : rc.senseNearbyMapInfos(ruinLoc, 8)) {
                    if (patternTile.getMark() != PaintType.EMPTY
                            && patternTile.getMark() != patternTile.getPaint()) {
                        boolean useSecondary = patternTile.getMark() == PaintType.ALLY_SECONDARY;
                        if (rc.canAttack(patternTile.getMapLocation())) {
                            rc.attack(patternTile.getMapLocation(), useSecondary);
                            break;
                        }
                    }
                }
            }

            if (rc.canCompleteTowerPattern(preferred, ruinLoc)) {
                rc.completeTowerPattern(preferred, ruinLoc);
                currentRuinTarget = null;
                broadcastRuinComplete(rc, ruinLoc);
            } else if (rc.canCompleteTowerPattern(fallback, ruinLoc)) {
                rc.completeTowerPattern(fallback, ruinLoc);
                currentRuinTarget = null;
                broadcastRuinComplete(rc, ruinLoc);
            }

            rc.setIndicatorString("Building @ " + ruinLoc);
            return;
        }

        // tidak ada ruin, explore
        if (rc.isMovementReady()) {
            if (shouldDisperse(rc, myLoc)) {
                disperseMove(rc, myLoc);
            } else {
                soldierExplore(rc, myLoc);
            }
            myLoc = rc.getLocation();
        }

        paintWhileMoving(rc, myLoc);

        if (rc.isActionReady() && rc.getPaint() >= 5) {
            MapLocation bestTarget = greedySoldierTarget(rc, myLoc);
            if (bestTarget != null && rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
            }
        }
    }

    // soldier explore: sebar ke 8 titik di map, arah musuh hanya setelah turn 150
    public static void soldierExplore(RobotController rc, MapLocation myLoc) throws GameActionException {
        if (stuckTurns >= 4) { forceUnstuck(rc); return; }

        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        if (exploreTarget == null || myLoc.distanceSquaredTo(exploreTarget) <= 13) {
            int id = rc.getID();
            // Spread ke seluruh map dulu, bukan langsung ke arah musuh
            int quadrant = (id + turnCount / 50) % 8;
            int tx, ty;
            switch (quadrant) {
                case 0: tx = w / 8;         ty = h / 4;         break;
                case 1: tx = 3 * w / 8;     ty = h / 8;         break;
                case 2: tx = 5 * w / 8;     ty = h / 8;         break;
                case 3: tx = 7 * w / 8;     ty = h / 4;         break;
                case 4: tx = 7 * w / 8;     ty = 3 * h / 4;     break;
                case 5: tx = 5 * w / 8;     ty = 7 * h / 8;     break;
                case 6: tx = 3 * w / 8;     ty = 7 * h / 8;     break;
                default: tx = w / 8;        ty = 3 * h / 4;     break;
            }
            // Kalau sudah explore banyak, tambahkan symmetryTarget sebagai salah satu opsi
            if (turnCount > 150 && (id + turnCount / 100) % 3 == 0) {
                int offsetX = ((id % 3) - 1) * (w / 8);
                int offsetY = ((id / 3 % 3) - 1) * (h / 8);
                tx = clamp(symmetryTarget.x + offsetX, 1, w - 2);
                ty = clamp(symmetryTarget.y + offsetY, 1, h - 2);
            }
            int noise = (id * 7) % 9 - 4;
            exploreTarget = new MapLocation(clamp(tx + noise, 1, w - 2), clamp(ty + noise, 1, h - 2));
        }

        // pilih arah ke target, prioritas tile kosong/musuh
        Direction dirToTarget = myLoc.directionTo(exploreTarget);
        int bestScore = Integer.MIN_VALUE;
        Direction bestDir = null;
        for (Direction dir : directions) {
            if (!rc.canMove(dir)) continue;
            MapInfo tile = rc.senseMapInfo(myLoc.add(dir));
            PaintType p = tile.getPaint();
            int tileScore = (p == PaintType.EMPTY) ? 10 : (p.isEnemy() ? 5 : 1);
            int score = tileScore * 10;
            score += (dir.dx * dirToTarget.dx + dir.dy * dirToTarget.dy) * 5;
            if (score > bestScore) { bestScore = score; bestDir = dir; }
        }
        if (bestDir != null) {
            if (rc.canMove(dirToTarget)) {
                MapInfo t = rc.senseMapInfo(myLoc.add(dirToTarget));
                PaintType p = t.getPaint();
                int targetScore = ((p == PaintType.EMPTY) ? 10 : (p.isEnemy() ? 5 : 1)) * 10;
                if (bestScore > targetScore + 50) rc.move(bestDir);
                else rc.move(dirToTarget);
            } else {
                rc.move(bestDir);
            }
        } else {
            moveToward(rc, exploreTarget);
        }
        rc.setIndicatorString("Soldier explore -> " + exploreTarget);
    }

    // splasher explore: cari arah dengan banyak tile kosong/musuh di sekitar
    public static void splasherExplore(RobotController rc, MapLocation myLoc) throws GameActionException {
        if (stuckTurns >= 4) { forceUnstuck(rc); return; }

        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        if (exploreTarget == null || myLoc.distanceSquaredTo(exploreTarget) <= 13) {
            // cari arah dengan banyak tile belum dicat
            int[] dirScore = new int[8];
            for (MapInfo mi : rc.senseNearbyMapInfos(-1)) {
                if (!mi.isPassable()) continue;
                MapLocation loc = mi.getMapLocation();
                if (myLoc.distanceSquaredTo(loc) < 8) continue;
                PaintType paint = mi.getPaint();
                if (paint == PaintType.EMPTY || paint.isEnemy()) {
                    int idx = dirToIndex(myLoc.directionTo(loc));
                    if (idx >= 0) dirScore[idx] += paint.isEnemy() ? 3 : 1;
                }
            }

            int bestIdx = -1;
            int bestScore = 0;
            int startIdx = rc.getID() % 8;
            for (int i = 0; i < 8; i++) {
                int idx = (startIdx + i) % 8;
                if (dirScore[idx] > bestScore) {
                    bestScore = dirScore[idx];
                    bestIdx = idx;
                }
            }

            if (bestIdx >= 0 && bestScore > 0) {
                Direction bestDir = directions[bestIdx];
                int tx = myLoc.x + bestDir.dx * 15;
                int ty = myLoc.y + bestDir.dy * 15;
                exploreTarget = new MapLocation(clamp(tx, 1, w - 2), clamp(ty, 1, h - 2));
            } else {
                int id = rc.getID();
                int offsetX = ((id % 3) - 1) * (w / 6);
                int offsetY = ((id / 3 % 3) - 1) * (h / 6);
                exploreTarget = new MapLocation(
                    clamp(symmetryTarget.x + offsetX, 1, w - 2),
                    clamp(symmetryTarget.y + offsetY, 1, h - 2));
            }
        }

        moveToward(rc, exploreTarget);
        rc.setIndicatorString("Splash explore -> " + exploreTarget);
    }

    // cari tile frontier (kosong/musuh) untuk didekati splasher
    public static MapLocation findSplasherFrontier(RobotController rc, MapLocation myLoc) throws GameActionException {
        MapLocation bestTile = null;
        int bestScore = 0;

        for (MapInfo mi : rc.senseNearbyMapInfos(-1)) {
            PaintType paint = mi.getPaint();
            if ((paint != PaintType.EMPTY && !paint.isEnemy()) || !mi.isPassable()) continue;
            MapLocation loc = mi.getMapLocation();
            int dist = myLoc.distanceSquaredTo(loc);
            if (dist < 8 || dist > 30) continue;
            if (!rc.canMove(myLoc.directionTo(loc))) continue;

            int score = (paint.isEnemy() ? 15 : 5);
            if (score > bestScore) {
                bestScore = score;
                bestTile = loc;
            }
        }
        return bestTile;
    }

    // pilih titik serang splasher yang paling banyak cover tile kosong/musuh
    public static MapLocation greedySplasherTarget(RobotController rc, MapLocation myLoc) throws GameActionException {
        MapLocation bestLoc = null;
        int bestScore = 0;

        for (MapInfo mi : rc.senseNearbyMapInfos(4)) {
            MapLocation center = mi.getMapLocation();
            if (myLoc.distanceSquaredTo(center) > 4) continue;
            if (!rc.canAttack(center)) continue;

            int score = 0;
            for (MapInfo t : rc.senseNearbyMapInfos(center, 4)) {
                PaintType paint = t.getPaint();
                if (paint == PaintType.EMPTY) score += 3;
                else if (paint.isEnemy()) score += 10;
            }

            if (score > bestScore) {
                bestScore = score;
                bestLoc = center;
            }
        }
        return (bestScore >= 1) ? bestLoc : null;
    }

    // pilih tile terdekat yang kosong/musuh untuk dicat soldier
    public static MapLocation greedySoldierTarget(RobotController rc, MapLocation myLoc) throws GameActionException {
        MapLocation bestLoc = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo mi : rc.senseNearbyMapInfos(9)) {
            MapLocation loc = mi.getMapLocation();
            if (myLoc.distanceSquaredTo(loc) > 9) continue;
            if (!rc.canAttack(loc)) continue;

            PaintType paint = mi.getPaint();
            if (paint == PaintType.EMPTY || paint.isEnemy()) {
                int dist = myLoc.distanceSquaredTo(loc);
                if (paint.isEnemy()) dist -= 5;
                if (dist < minDist) {
                    minDist = dist;
                    bestLoc = loc;
                }
            }
        }
        return bestLoc;
    }

    // cek apakah unit terlalu menumpuk (>5 teman dalam radius 13)
    static boolean shouldDisperse(RobotController rc, MapLocation myLoc) throws GameActionException {
        int mobileCount = 0;
        for (RobotInfo r : rc.senseNearbyRobots(13, rc.getTeam())) {
            if (r.getType() == UnitType.SOLDIER || r.getType() == UnitType.SPLASHER)
                mobileCount++;
        }
        return mobileCount > 5;
    }

    // gerak menjauh dari rata-rata posisi teman supaya tersebar
    static void disperseMove(RobotController rc, MapLocation myLoc) throws GameActionException {
        int ax = 0, ay = 0, count = 0;
        for (RobotInfo r : rc.senseNearbyRobots(13, rc.getTeam())) {
            if (r.getType() == UnitType.SOLDIER || r.getType() == UnitType.SPLASHER) {
                ax += r.getLocation().x;
                ay += r.getLocation().y;
                count++;
            }
        }
        if (count == 0) return;
        ax /= count;
        ay /= count;
        Direction awayDir = new MapLocation(ax, ay).directionTo(myLoc);
        if (rc.canMove(awayDir)) rc.move(awayDir);
        else if (rc.canMove(awayDir.rotateLeft())) rc.move(awayDir.rotateLeft());
        else if (rc.canMove(awayDir.rotateRight())) rc.move(awayDir.rotateRight());
    }

    // cat tile yang dipijak kalau belum berwarna ally
    static void paintWhileMoving(RobotController rc, MapLocation myLoc) throws GameActionException {
        if (rc.isActionReady() && rc.getPaint() >= 5) {
            MapInfo currentTile = rc.senseMapInfo(myLoc);
            if (!currentTile.getPaint().isAlly() && rc.canAttack(myLoc)) {
                rc.attack(myLoc);
            }
        }
    }

    // paksa gerak ke arah acak saat stuck, reset explore target
    static void forceUnstuck(RobotController rc) throws GameActionException {
        int startDir = (rc.getID() + turnCount) % 8;
        for (int i = 0; i < 8; i++) {
            Direction dir = directions[(startDir + i) % 8];
            if (rc.canMove(dir)) {
                rc.move(dir);
                stuckTurns = 0;
                exploreTarget = null;
                return;
            }
        }
    }

    // gerak ke target, coba rotasi kanan/kiri kalau arah utama terblokir
    static void moveToward(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        if (myLoc.equals(target)) return;
        Direction dir = myLoc.directionTo(target);
        if (rc.canMove(dir)) { rc.move(dir); return; }
        if (rc.canMove(dir.rotateRight())) { rc.move(dir.rotateRight()); return; }
        if (rc.canMove(dir.rotateLeft())) { rc.move(dir.rotateLeft()); return; }
        if (rc.canMove(dir.rotateRight().rotateRight())) { rc.move(dir.rotateRight().rotateRight()); return; }
        if (rc.canMove(dir.rotateLeft().rotateLeft())) { rc.move(dir.rotateLeft().rotateLeft()); return; }
    }

    // hitung berapa turn unit tidak bergerak (untuk deteksi stuck)
    public static void trackStuck(MapLocation myLoc) {
        if (prevLocation != null && prevLocation.equals(myLoc)) {
            stuckTurns++;
        } else {
            stuckTurns = 0;
        }
        prevLocation = myLoc;
    }

    // isi ulang paint dari tower terdekat, gerak ke sana kalau perlu
    public static boolean tryRefillPaint(RobotController rc) throws GameActionException {
        int need = getPaintCapacity(rc.getType()) - rc.getPaint();
        if (need <= 0) return false;

        // transfer langsung kalau ada tower deket
        for (RobotInfo ally : rc.senseNearbyRobots(2, rc.getTeam())) {
            if (!isUnitTower(ally.getType())) continue;
            if (rc.canTransferPaint(ally.getLocation(), -need)) {
                rc.transferPaint(ally.getLocation(), -need);
                return false;
            }
        }

        // kalau tidak ada, gerak ke tower
        RobotInfo nearestTower = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (!isUnitTower(ally.getType())) continue;
            int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
            if (isPaintTower(ally.getType())) dist -= 10;
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestTower = ally;
            }
        }

        if (nearestTower != null && rc.isMovementReady()) {
            moveToward(rc, nearestTower.getLocation());
            return true;
        }

        if (knownTowerCount > 0 && rc.isMovementReady()) {
            MapLocation closestKnown = null;
            int closestDist = Integer.MAX_VALUE;
            for (int i = 0; i < knownTowerCount; i++) {
                int dist = rc.getLocation().distanceSquaredTo(knownTowers[i]);
                if (dist < closestDist) {
                    closestDist = dist;
                    closestKnown = knownTowers[i];
                }
            }
            if (closestKnown != null) {
                moveToward(rc, closestKnown);
                return true;
            }
        }

        return false;
    }

    // encode pesan: type (8 bit) + x (12 bit) + y (12 bit)
    static int encodeMessage(int type, MapLocation loc) {
        return (type << 24) | (loc.x << 12) | loc.y;
    }

    // ambil tipe pesan dari data encoded
    static int decodeType(int data) {
        return (data >> 24) & 0xFF;
    }

    // ambil lokasi dari data encoded
    static MapLocation decodeLoc(int data) {
        int x = (data >> 12) & 0xFFF;
        int y = data & 0xFFF;
        return new MapLocation(x, y);
    }

    // proses semua pesan masuk dan update info tower/ruin
    static void processMessages(RobotController rc) throws GameActionException {
        for (Message m : rc.readMessages(-1)) {
            int data = m.getBytes();
            int type = decodeType(data);
            MapLocation loc = decodeLoc(data);
            switch (type) {
                case MSG_TOWER_LOC:     addKnownTower(loc);      break;
                case MSG_RUIN_LOC:      addKnownRuin(loc);       break;
                case MSG_RUIN_COMPLETE: removeKnownRuin(loc);    break;
            }
        }
    }

    // tower broadcast lokasi diri dan ruin yang terlihat ke semua unit
    static void towerBroadcast(RobotController rc) throws GameActionException {
        if (rc.canBroadcastMessage()) {
            rc.broadcastMessage(encodeMessage(MSG_TOWER_LOC, rc.getLocation()));
        }

        for (MapInfo tile : rc.senseNearbyMapInfos()) {
            if (!tile.hasRuin()) continue;
            RobotInfo robotOnRuin = rc.senseRobotAtLocation(tile.getMapLocation());
            if (robotOnRuin != null) continue;

            MapLocation ruinLoc = tile.getMapLocation();
            boolean alreadyKnown = false;
            for (int i = 0; i < knownRuinCount; i++) {
                if (knownRuins[i].equals(ruinLoc)) { alreadyKnown = true; break; }
            }
            if (!alreadyKnown && rc.canBroadcastMessage()) {
                rc.broadcastMessage(encodeMessage(MSG_RUIN_LOC, ruinLoc));
                addKnownRuin(ruinLoc);
            }
            break;
        }
    }

    // broadcast ke tower bahwa ruin sudah selesai dibangun
    static void broadcastRuinComplete(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        for (RobotInfo ally : rc.senseNearbyRobots(-1, rc.getTeam())) {
            if (isUnitTower(ally.getType()) && rc.canSendMessage(ally.getLocation())) {
                rc.sendMessage(ally.getLocation(), encodeMessage(MSG_RUIN_COMPLETE, ruinLoc));
                break;
            }
        }
        removeKnownRuin(ruinLoc);
    }

    // tambah tower ally ke daftar yang diketahui (skip kalau duplikat)
    static void addKnownTower(MapLocation loc) {
        for (int i = 0; i < knownTowerCount; i++) {
            if (knownTowers[i].equals(loc)) return;
        }
        if (knownTowerCount < MAX_KNOWN) knownTowers[knownTowerCount++] = loc;
    }

    // tambah ruin ke daftar yang diketahui (skip kalau duplikat)
    static void addKnownRuin(MapLocation loc) {
        for (int i = 0; i < knownRuinCount; i++) {
            if (knownRuins[i].equals(loc)) return;
        }
        if (knownRuinCount < MAX_KNOWN) knownRuins[knownRuinCount++] = loc;
    }

    // hapus ruin dari daftar (sudah dibangun atau tidak valid)
    static void removeKnownRuin(MapLocation loc) {
        for (int i = 0; i < knownRuinCount; i++) {
            if (knownRuins[i].equals(loc)) {
                knownRuins[i] = knownRuins[knownRuinCount - 1];
                knownRuinCount--;
                return;
            }
        }
    }


    // tandai ruin sebagai skipped (gagal dibangun/timeout)
    static void addSkippedRuin(MapLocation loc) {
        if (skippedRuinCount < skippedRuins.length) {
            skippedRuins[skippedRuinCount++] = loc;
        } else {
            skippedRuins[0] = loc;
        }
    }

    // cek apakah ruin ini ada di daftar skipped
    static boolean isSkippedRuin(MapLocation loc) {
        for (int i = 0; i < skippedRuinCount; i++) {
            if (skippedRuins[i].equals(loc)) return true;
        }
        return false;
    }

    // konversi Direction ke index array directions[]
    static int dirToIndex(Direction dir) {
        for (int i = 0; i < 8; i++) {
            if (directions[i] == dir) return i;
        }
        return -1;
    }

    // batasi nilai dalam range [min, max]
    static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    // pilih tipe tower berdasarkan kondisi chip/paint
    public static UnitType getPreferredTower(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        int chips = rc.getChips();
        int paint = rc.getPaint();
        if (chips < 800) return UnitType.LEVEL_ONE_MONEY_TOWER;
        if (paint <= 0)  return UnitType.LEVEL_ONE_PAINT_TOWER;
        double ratio = (double) chips / paint;
        if (ratio > 3.0) return UnitType.LEVEL_ONE_PAINT_TOWER;
        return UnitType.LEVEL_ONE_MONEY_TOWER;
    }

    // pilih tipe tower alternatif (kebalikan dari preferred)
    public static UnitType getFallbackTower(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        UnitType preferred = getPreferredTower(rc, ruinLoc);
        return (preferred == UnitType.LEVEL_ONE_PAINT_TOWER)
                ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    // cek apakah unit adalah paint tower (level 1-3)
    public static boolean isPaintTower(UnitType t) {
        return t == UnitType.LEVEL_ONE_PAINT_TOWER || t == UnitType.LEVEL_TWO_PAINT_TOWER
                || t == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    // cek apakah unit adalah tower (money/paint/defense, semua level)
    public static boolean isUnitTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_MONEY_TOWER
            || type == UnitType.LEVEL_TWO_MONEY_TOWER
            || type == UnitType.LEVEL_THREE_MONEY_TOWER
            || type == UnitType.LEVEL_ONE_PAINT_TOWER
            || type == UnitType.LEVEL_TWO_PAINT_TOWER
            || type == UnitType.LEVEL_THREE_PAINT_TOWER
            || isDefenseTower(type);
    }

    // cek apakah unit adalah defense tower (level 1-3)
    public static boolean isDefenseTower(UnitType type) {
        return type == UnitType.LEVEL_ONE_DEFENSE_TOWER
            || type == UnitType.LEVEL_TWO_DEFENSE_TOWER
            || type == UnitType.LEVEL_THREE_DEFENSE_TOWER;
    }

    // kembalikan kapasitas paint maksimum sesuai tipe unit
    public static int getPaintCapacity(UnitType type) {
        if (type == UnitType.SOLDIER)  return 200;
        if (type == UnitType.SPLASHER) return 300;
        if (type == UnitType.MOPPER)   return 100;
        return 0;
    }
}
