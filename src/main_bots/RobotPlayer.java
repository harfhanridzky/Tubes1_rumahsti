package alt1;

import battlecode.common.*;

/**
 * BOT 1: "THE PAINT EXPANDER" - Greedy by Map Control
 * Soldier: fokus cari ruin & build tower, explore ke symmetry point
 * Splasher: fokus coverage, gerak ke area unpainted density tertinggi
 * Tower: smart spawn & chip management
 * Communication: tower loc, ruin loc, ruin complete, enemy tower
 * Pathfinding: Bug2 nav + greedy frontier (role-specific scoring)
 */
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

    // Spawn origin — diset sekali saat unit pertama kali spawn
    // Digunakan untuk hitung symmetry point (lokasi musuh)
    static MapLocation spawnOrigin = null;
    static MapLocation symmetryTarget = null;

    // Explore state per robot
    static MapLocation exploreTarget = null;
    static MapLocation prevLocation = null;
    static int stuckTurns = 0;

    // Tower building state
    static MapLocation currentRuinTarget = null;
    static int ruinBuildTurns = 0;

    // Communication message types
    static final int MSG_TOWER_LOC = 0;
    static final int MSG_RUIN_LOC = 1;
    static final int MSG_BUILDING_RUIN = 2;
    static final int MSG_RUIN_COMPLETE = 3;  // Ruin sudah jadi tower
    static final int MSG_ENEMY_TOWER = 4;    // Lokasi tower musuh (untuk dihindari)

    // Known locations — increased capacity for better intel
    static final int MAX_KNOWN = 8;
    static MapLocation[] knownTowers = new MapLocation[MAX_KNOWN];
    static int knownTowerCount = 0;
    static MapLocation[] knownRuins = new MapLocation[MAX_KNOWN];
    static int knownRuinCount = 0;
    static MapLocation[] knownEnemyTowers = new MapLocation[MAX_KNOWN];
    static int knownEnemyTowerCount = 0;

    // Bug navigation state (Bug2 algorithm)
    static boolean bugNavigating = false;
    static boolean bugWallOnRight = true;
    static MapLocation bugTarget = null;
    static int bugStartDist = Integer.MAX_VALUE;
    static int bugMinDist = Integer.MAX_VALUE; // Track closest point reached
    static int bugTurns = 0;

    // Soldier: track ruin yang sudah dicoba dan gagal (timeout)
    static MapLocation[] skippedRuins = new MapLocation[4];
    static int skippedRuinCount = 0;

    public static void run(RobotController rc) throws GameActionException {
        // Init saat pertama kali — hitung spawn origin dan symmetry target
        if (spawnOrigin == null) {
            spawnOrigin = rc.getLocation();
            int w = rc.getMapWidth();
            int h = rc.getMapHeight();
            // Map punya rotational symmetry 180° → enemy di (W-1-x, H-1-y)
            symmetryTarget = new MapLocation(w - 1 - spawnOrigin.x, h - 1 - spawnOrigin.y);
        }

        while (true) {
            turnCount += 1;
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

    // TOWER

    public static void runTower(RobotController rc) throws GameActionException {
        // Defense tower: tetap baca messages & attack enemy, tapi ga spawn
        boolean isDefense = isDefenseTower(rc.getType());

        // Broadcast setiap 5 turns
        if (turnCount % 5 == 0) {
            towerBroadcast(rc);
        }

        // Baca messages
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            int data = m.getBytes();
            int type = decodeType(data);
            MapLocation loc = decodeLoc(data);
            switch (type) {
                case MSG_TOWER_LOC:     addKnownTower(loc);      break;
                case MSG_RUIN_LOC:      addKnownRuin(loc);       break;
                case MSG_RUIN_COMPLETE: removeKnownRuin(loc);    break;
                case MSG_ENEMY_TOWER:   addKnownEnemyTower(loc); break;
            }
        }

        // Attack nearby enemies (semua tower termasuk defense)
        RobotInfo[] nearbyEnemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        if (nearbyEnemies.length > 0 && rc.isActionReady()) {
            // Prioritas: serang unit terdekat
            RobotInfo closest = null;
            int closestDist = Integer.MAX_VALUE;
            for (RobotInfo enemy : nearbyEnemies) {
                int dist = rc.getLocation().distanceSquaredTo(enemy.getLocation());
                if (dist < closestDist) {
                    closestDist = dist;
                    closest = enemy;
                }
            }
            if (closest != null && rc.canAttack(closest.getLocation())) {
                rc.attack(closest.getLocation());
            }
        }

        if (isDefense) return; // Defense tower stop di sini

        // Scan nearby: ruin + ally info
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

        // === PRIORITAS 1: UPGRADE TOWER ===
        if (chips >= 1000 && rc.canUpgradeTower(rc.getLocation())) {
            rc.upgradeTower(rc.getLocation());
            chips = rc.getChips();
        }

        // === SPAWN INTERVAL: stagger spawning across towers ===
        int spawnInterval = 1;
        if (numTowers >= 5) spawnInterval = 2;
        if (numTowers >= 8) spawnInterval = 3;

        boolean shouldSpawnThisTurn = (turnCount + rc.getID()) % spawnInterval == 0;
        // Override: jika chip numpuk terlalu banyak, SELALU spawn
        if (chips > 1500) shouldSpawnThisTurn = true;
        if (!shouldSpawnThisTurn) return;

        // === PRIORITAS 2: SPAWN UNITS ===
        int spawnFloor = 200;
        if (chips <= spawnFloor) return;

        int totalUnits = soldierCount + splasherCount;
        UnitType toSpawn;

        // Tentukan unit type berdasarkan kebutuhan
        if (ruinNearby && soldierCount == 0) {
            toSpawn = UnitType.SOLDIER;
        } else if (turnCount < 200) {
            // Early game: soldier heavy (60%) untuk tower expansion
            toSpawn = (totalUnits == 0 || soldierCount * 100 / Math.max(1, totalUnits) < 60)
                    ? UnitType.SOLDIER : UnitType.SPLASHER;
        } else if (numTowers < 5) {
            // Mid game: balance 40% soldier
            toSpawn = (soldierCount * 100 / Math.max(1, totalUnits) < 40)
                    ? UnitType.SOLDIER : UnitType.SPLASHER;
        } else {
            // Late game: splasher heavy (75%) untuk coverage push
            toSpawn = (soldierCount * 100 / Math.max(1, totalUnits) < 25)
                    ? UnitType.SOLDIER : UnitType.SPLASHER;
        }

        if (rc.isActionReady()) {
            // Smart spawn direction: soldier ke arah ruin
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

    // SPLASHER — fokus coverage, gerak ke unpainted density tertinggi

    public static void runSplasher(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        trackStuck(myLoc);
        processMessages(rc);

        // Report enemy towers yang terlihat
        reportEnemyTowers(rc);

        // Refill paint
        if (rc.getPaint() < PAINT_REFILL_THRESHOLD) {
            if (tryRefillPaint(rc)) return;
        }

        // Cek enemy tower yang visible — high value target
        MapLocation visibleEnemyTower = null;
        for (RobotInfo e : rc.senseNearbyRobots(-1, rc.getTeam().opponent())) {
            if (isUnitTower(e.getType())) { visibleEnemyTower = e.getLocation(); break; }
        }

        // ATTACK — prioritas: enemy tower > AoE unpainted/enemy
        if (rc.isActionReady() && rc.getPaint() >= 50) {
            if (visibleEnemyTower != null && rc.canAttack(visibleEnemyTower)) {
                rc.attack(visibleEnemyTower);
            } else {
                MapLocation bestTarget = greedySplasherTarget(rc, myLoc);
                if (bestTarget != null && rc.canAttack(bestTarget)) {
                    rc.attack(bestTarget);
                } else {
                    MapInfo cur = rc.senseMapInfo(myLoc);
                    if (!cur.getPaint().isAlly() && rc.canAttack(myLoc)) rc.attack(myLoc);
                }
            }
        }

        // MOVE — ke enemy tower jika ada, atau ke area unpainted
        if (rc.isMovementReady()) {
            MapLocation etTarget = visibleEnemyTower;
            if (etTarget == null && knownEnemyTowerCount > 0) {
                int best = Integer.MAX_VALUE;
                for (int i = 0; i < knownEnemyTowerCount; i++) {
                    int d = myLoc.distanceSquaredTo(knownEnemyTowers[i]);
                    if (d < best) { best = d; etTarget = knownEnemyTowers[i]; }
                }
            }
            if (etTarget != null && myLoc.distanceSquaredTo(etTarget) > 4) {
                bugMoveToward(rc, etTarget);
            } else if (shouldDisperse(rc, myLoc)) {
                disperseMove(rc, myLoc);
            } else {
                MapLocation moveTarget = findSplasherFrontier(rc, myLoc);
                if (moveTarget != null) bugMoveToward(rc, moveTarget);
                else splasherExplore(rc, myLoc);
            }
            myLoc = rc.getLocation();
        }

        rc.setIndicatorString("Splash: " + (visibleEnemyTower != null ? "ET@" + visibleEnemyTower : "cover") + " p=" + rc.getPaint());
    }

    // SOLDIER — fokus cari ruin & build tower

    public static void runSoldier(RobotController rc) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        trackStuck(myLoc);
        processMessages(rc);

        // Report enemy towers yang terlihat
        reportEnemyTowers(rc);

        // Refill paint
        if (rc.getPaint() < PAINT_REFILL_THRESHOLD) {
            if (tryRefillPaint(rc)) return;
        }

        // Timeout ruin building
        if (currentRuinTarget != null) {
            ruinBuildTurns++;
            if (ruinBuildTurns > 20) {
                addSkippedRuin(currentRuinTarget);
                currentRuinTarget = null;
                ruinBuildTurns = 0;
            }
        }

        // === PRIORITAS 1: Cari ruin terdekat yang visible ===
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos();
        MapInfo bestRuin = null;
        int bestRuinDist = Integer.MAX_VALUE;
        for (MapInfo tile : nearbyTiles) {
            if (!tile.hasRuin()) continue;
            MapLocation rl = tile.getMapLocation();
            if (isSkippedRuin(rl)) continue;
            RobotInfo robotOnRuin = rc.senseRobotAtLocation(rl);
            if (robotOnRuin != null) continue;

            // Skip ruin yang dikelilingi enemy paint — buang waktu
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

        // === PRIORITAS 2: Known ruin dari komunikasi ===
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
                    bugMoveToward(rc, closestKnown);
                    myLoc = rc.getLocation();
                }
                // Validate saat sampai
                if (rc.canSenseLocation(closestKnown)) {
                    RobotInfo robotOnRuin = rc.senseRobotAtLocation(closestKnown);
                    if (robotOnRuin != null) {
                        removeKnownRuin(closestKnown);
                        broadcastRuinComplete(rc, closestKnown);
                    }
                }
                // Paint sambil jalan
                paintWhileMoving(rc, myLoc);
                rc.setIndicatorString("-> known ruin " + closestKnown);
                return;
            }
        }

        // === BUILD TOWER jika ada ruin visible ===
        if (bestRuin != null) {
            MapLocation ruinLoc = bestRuin.getMapLocation();
            if (currentRuinTarget == null || !currentRuinTarget.equals(ruinLoc)) {
                ruinBuildTurns = 0;
            }
            currentRuinTarget = ruinLoc;

            // Mark pattern
            UnitType preferred = getPreferredTower(rc, ruinLoc);
            UnitType fallback = getFallbackTower(rc, ruinLoc);
            if (rc.canMarkTowerPattern(preferred, ruinLoc)) {
                rc.markTowerPattern(preferred, ruinLoc);
            } else if (rc.canMarkTowerPattern(fallback, ruinLoc)) {
                rc.markTowerPattern(fallback, ruinLoc);
            }

            // Gerak mendekati ruin (harus adjacent untuk complete)
            if (rc.isMovementReady()) {
                bugMoveToward(rc, ruinLoc);
                myLoc = rc.getLocation();
            }

            // Paint pattern tiles
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

            // Complete tower
            if (rc.canCompleteTowerPattern(preferred, ruinLoc)) {
                rc.completeTowerPattern(preferred, ruinLoc);
                currentRuinTarget = null;
                broadcastRuinComplete(rc, ruinLoc);
            } else if (rc.canCompleteTowerPattern(fallback, ruinLoc)) {
                rc.completeTowerPattern(fallback, ruinLoc);
                currentRuinTarget = null;
                broadcastRuinComplete(rc, ruinLoc);
            }

            // Broadcast "sedang build" ke tower terdekat
            broadcastBuildingRuin(rc, ruinLoc);

            rc.setIndicatorString("Building @ " + ruinLoc);
            return;
        }

        // === PRIORITAS 3: Tidak ada ruin — explore ke arah symmetry (cari ruin baru) ===
        if (rc.isMovementReady()) {
            if (shouldDisperse(rc, myLoc)) {
                disperseMove(rc, myLoc);
            } else {
                // Soldier explore: greedy ke symmetry point (pasti ada ruin di jalan ke sana)
                soldierExplore(rc, myLoc);
            }
            myLoc = rc.getLocation();
        }

        // Paint sambil jalan
        paintWhileMoving(rc, myLoc);

        // Paint unpainted terdekat
        if (rc.isActionReady() && rc.getPaint() >= 5) {
            MapLocation bestTarget = greedySoldierTarget(rc, myLoc);
            if (bestTarget != null && rc.canAttack(bestTarget)) {
                rc.attack(bestTarget);
            }
        }
    }

    // ROLE-SPECIFIC EXPLORE

    /** Score tile untuk soldier movement: EMPTY=10, ENEMY=5, ALLY=1 */
    static int scoreSoldierTile(MapInfo tile) {
        PaintType p = tile.getPaint();
        if (p == PaintType.EMPTY) return 10;
        if (p.isEnemy())         return 5;
        return 1;
    }
    public static void soldierExplore(RobotController rc, MapLocation myLoc) throws GameActionException {
        if (stuckTurns >= 4) { forceUnstuck(rc); return; }

        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        if (exploreTarget == null || myLoc.distanceSquaredTo(exploreTarget) <= 13) {
            int id = rc.getID();
            if (myLoc.distanceSquaredTo(symmetryTarget) > 100) {
                int offsetX = ((id % 5) - 2) * (w / 10);
                int offsetY = ((id / 5 % 5) - 2) * (h / 10);
                exploreTarget = new MapLocation(
                    clamp(symmetryTarget.x + offsetX, 1, w - 2),
                    clamp(symmetryTarget.y + offsetY, 1, h - 2));
            } else {
                int quadrant = (id + turnCount / 50) % 4;
                int tx, ty;
                switch (quadrant) {
                    case 0: tx = w / 4;     ty = h / 4;     break;
                    case 1: tx = 3 * w / 4; ty = h / 4;     break;
                    case 2: tx = w / 4;     ty = 3 * h / 4; break;
                    default: tx = 3 * w / 4; ty = 3 * h / 4; break;
                }
                int noise = (id * 7) % 7 - 3;
                exploreTarget = new MapLocation(clamp(tx + noise, 1, w - 2), clamp(ty + noise, 1, h - 2));
            }
            bugNavigating = false;
        }

        // Tile-scored move: pilih arah terbaik berdasarkan tile score + bias ke target
        if (!bugNavigating) {
            Direction dirToTarget = myLoc.directionTo(exploreTarget);
            int bestScore = Integer.MIN_VALUE;
            Direction bestDir = null;
            for (Direction dir : directions) {
                if (!rc.canMove(dir)) continue;
                MapInfo tile = rc.senseMapInfo(myLoc.add(dir));
                int score = scoreSoldierTile(tile) * 10;
                score += (dir.dx * dirToTarget.dx + dir.dy * dirToTarget.dy) * 5;
                if (score > bestScore) { bestScore = score; bestDir = dir; }
            }
            if (bestDir != null) {
                // Hanya ambil arah lain jika score jauh lebih tinggi (EMPTY vs ALLY)
                if (rc.canMove(dirToTarget)) {
                    int targetScore = scoreSoldierTile(rc.senseMapInfo(myLoc.add(dirToTarget))) * 10;
                    if (bestScore > targetScore + 50) rc.move(bestDir);
                    else rc.move(dirToTarget);
                } else {
                    rc.move(bestDir);
                }
                rc.setIndicatorString("Soldier explore -> " + exploreTarget);
                return;
            }
        }

        bugMoveToward(rc, exploreTarget);
        rc.setIndicatorString("Soldier explore(bug) -> " + exploreTarget);
    }

    /**
     * Splasher explore: gerak ke area dengan paint density paling rendah (banyak unpainted).
     * Kalau tidak ada frontier visible, head ke arah yang belum di-paint
     * berdasarkan paint density di vision edge.
     */
    public static void splasherExplore(RobotController rc, MapLocation myLoc) throws GameActionException {
        if (stuckTurns >= 4) {
            forceUnstuck(rc);
            return;
        }

        int w = rc.getMapWidth();
        int h = rc.getMapHeight();

        boolean needNewTarget = exploreTarget == null
                || myLoc.distanceSquaredTo(exploreTarget) <= 13;

        if (needNewTarget) {
            // Scan 8 arah: hitung unpainted tiles di edge vision per arah
            // Pilih arah dengan unpainted terbanyak → greedy coverage
            int[] dirScore = new int[8];
            MapInfo[] allTiles = rc.senseNearbyMapInfos(-1);

            for (MapInfo mi : allTiles) {
                if (!mi.isPassable()) continue;
                MapLocation loc = mi.getMapLocation();
                int dist = myLoc.distanceSquaredTo(loc);
                if (dist < 8) continue; // Skip tile dekat (sudah di-handle attack)

                PaintType paint = mi.getPaint();
                if (paint == PaintType.EMPTY || paint.isEnemy()) {
                    Direction dirToTile = myLoc.directionTo(loc);
                    int idx = dirToIndex(dirToTile);
                    if (idx >= 0) {
                        // Weight: tile lebih jauh = lebih berharga (menunjuk ke area unexplored)
                        dirScore[idx] += (paint.isEnemy()) ? 3 : 1; // Enemy paint lebih prioritas
                    }
                }
            }

            // Pilih arah dengan score tertinggi
            int bestIdx = -1;
            int bestScore = 0;
            // Bias berdasarkan robot ID untuk diversifikasi
            int startIdx = rc.getID() % 8;
            for (int i = 0; i < 8; i++) {
                int idx = (startIdx + i) % 8;
                if (dirScore[idx] > bestScore) {
                    bestScore = dirScore[idx];
                    bestIdx = idx;
                }
            }

            if (bestIdx >= 0 && bestScore > 0) {
                // Project 15 tiles ke arah terbaik
                Direction bestDir = directions[bestIdx];
                int tx = myLoc.x + bestDir.dx * 15;
                int ty = myLoc.y + bestDir.dy * 15;
                exploreTarget = new MapLocation(clamp(tx, 1, w - 2), clamp(ty, 1, h - 2));
            } else {
                // Tidak ada unpainted visible → head ke symmetry (enemy territory)
                int id = rc.getID();
                int offsetX = ((id % 3) - 1) * (w / 6);
                int offsetY = ((id / 3 % 3) - 1) * (h / 6);
                int tx = clamp(symmetryTarget.x + offsetX, 1, w - 2);
                int ty = clamp(symmetryTarget.y + offsetY, 1, h - 2);
                exploreTarget = new MapLocation(tx, ty);
            }
            bugNavigating = false;
        }

        bugMoveToward(rc, exploreTarget);
        rc.setIndicatorString("Splash explore -> " + exploreTarget);
    }

    // ROLE-SPECIFIC FRONTIER FINDING

    public static MapLocation findSplasherFrontier(RobotController rc, MapLocation myLoc)
            throws GameActionException {
        MapInfo[] tiles = rc.senseNearbyMapInfos(-1);
        MapLocation bestTile = null;
        int bestScore = Integer.MIN_VALUE;

        // Precompute passable directions
        boolean[] dirPassable = new boolean[8];
        for (int i = 0; i < 8; i++) {
            dirPassable[i] = rc.canMove(directions[i]);
        }

        // Count unpainted per sector (8 arah) — fast approximation of density
        int[] sectorUnpainted = new int[8];
        for (MapInfo mi : tiles) {
            PaintType paint = mi.getPaint();
            if ((paint == PaintType.EMPTY || paint.isEnemy()) && mi.isPassable()) {
                Direction d = myLoc.directionTo(mi.getMapLocation());
                int idx = dirToIndex(d);
                if (idx >= 0) sectorUnpainted[idx]++;
            }
        }

        for (MapInfo mi : tiles) {
            PaintType paint = mi.getPaint();
            if ((paint == PaintType.EMPTY || paint.isEnemy()) && mi.isPassable()) {
                MapLocation loc = mi.getMapLocation();

                // Reachability check
                Direction dirToTile = myLoc.directionTo(loc);
                int dirIdx = dirToIndex(dirToTile);
                if (dirIdx >= 0 && !dirPassable[dirIdx]) {
                    int leftIdx = (dirIdx + 7) % 8;
                    int rightIdx = (dirIdx + 1) % 8;
                    if (!dirPassable[leftIdx] && !dirPassable[rightIdx]) continue;
                }

                int dist = myLoc.distanceSquaredTo(loc);

                // Score: sector density (banyak unpainted di arah ini) + medium distance preferred
                // Sweet spot: tidak terlalu dekat (sudah bisa attack), tidak terlalu jauh
                int density = (dirIdx >= 0) ? sectorUnpainted[dirIdx] : 0;
                int score = density * 10; // Density is king for splasher
                if (dist >= 8 && dist <= 30) score += 20; // Medium range bonus
                if (paint.isEnemy()) score += 15; // Prioritas repaint enemy

                if (score > bestScore) {
                    bestScore = score;
                    bestTile = loc;
                }
            }
        }
        return bestTile;
    }

    // HEURISTICS (attack targeting)

    public static MapLocation greedySplasherTarget(RobotController rc, MapLocation myLoc)
            throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(4);
        MapLocation bestLoc = null;
        int bestScore = 0;

        for (MapInfo mi : nearbyTiles) {
            MapLocation center = mi.getMapLocation();
            if (myLoc.distanceSquaredTo(center) > 4) continue;
            if (!rc.canAttack(center)) continue;

            // Score: unpainted count + bonus for enemy paint
            int score = 0;
            MapInfo[] aoe = rc.senseNearbyMapInfos(center, 4);
            for (MapInfo t : aoe) {
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

    public static MapLocation greedySoldierTarget(RobotController rc, MapLocation myLoc)
            throws GameActionException {
        MapInfo[] nearbyTiles = rc.senseNearbyMapInfos(9);
        MapLocation bestLoc = null;
        int minDist = Integer.MAX_VALUE;

        for (MapInfo mi : nearbyTiles) {
            MapLocation loc = mi.getMapLocation();
            if (myLoc.distanceSquaredTo(loc) > 9) continue;
            if (!rc.canAttack(loc)) continue;

            PaintType paint = mi.getPaint();
            if (paint == PaintType.EMPTY || paint.isEnemy()) {
                int dist = myLoc.distanceSquaredTo(loc);
                // Prioritas enemy paint (need to overwrite)
                if (paint.isEnemy()) dist -= 5;
                if (dist < minDist) {
                    minDist = dist;
                    bestLoc = loc;
                }
            }
        }
        return bestLoc;
    }

    
    // MOVEMENT HELPERS

    /** Check apakah terlalu banyak ally nearby (clustering) */
    static boolean shouldDisperse(RobotController rc, MapLocation myLoc) throws GameActionException {
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(13, rc.getTeam());
        int mobileCount = 0;
        for (RobotInfo r : nearbyAllies) {
            if (r.getType() == UnitType.SOLDIER || r.getType() == UnitType.SPLASHER) {
                mobileCount++;
            }
        }
        return mobileCount > 5;
    }

    /** Gerak menjauh dari cluster ally */
    static void disperseMove(RobotController rc, MapLocation myLoc) throws GameActionException {
        RobotInfo[] nearbyAllies = rc.senseNearbyRobots(13, rc.getTeam());
        int ax = 0, ay = 0, count = 0;
        for (RobotInfo r : nearbyAllies) {
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

    /** Paint tile di bawah kaki sambil jalan */
    static void paintWhileMoving(RobotController rc, MapLocation myLoc) throws GameActionException {
        if (rc.isActionReady() && rc.getPaint() >= 5) {
            MapInfo currentTile = rc.senseMapInfo(myLoc);
            if (!currentTile.getPaint().isAlly() && rc.canAttack(myLoc)) {
                rc.attack(myLoc);
            }
        }
    }

    /** Force unstuck: coba semua arah, reset state */
    static void forceUnstuck(RobotController rc) throws GameActionException {
        int startDir = (rc.getID() + turnCount) % 8;
        for (int i = 0; i < 8; i++) {
            Direction dir = directions[(startDir + i) % 8];
            if (rc.canMove(dir)) {
                rc.move(dir);
                stuckTurns = 0;
                exploreTarget = null;
                bugNavigating = false;
                return;
            }
        }
    }
    // BUG2 NAVIGATION 

    public static void bugMoveToward(RobotController rc, MapLocation target) throws GameActionException {
        MapLocation myLoc = rc.getLocation();
        if (myLoc.equals(target)) return;

        // Reset kalau target baru
        if (bugTarget == null || !bugTarget.equals(target)) {
            bugNavigating = false;
            bugTarget = target;
            bugTurns = 0;
            bugMinDist = myLoc.distanceSquaredTo(target);
        }

        // Track closest point — jika kita sudah lebih dekat, update
        int currentDist = myLoc.distanceSquaredTo(target);
        if (currentDist < bugMinDist) {
            bugMinDist = currentDist;
        }

        // Timeout: switch wall-following direction
        if (bugTurns > 12) {
            bugWallOnRight = !bugWallOnRight;
            bugTurns = 0;
        }

        Direction dirToTarget = myLoc.directionTo(target);

        // Coba direct move dulu (greedy)
        if (!bugNavigating) {
            if (rc.canMove(dirToTarget)) {
                rc.move(dirToTarget);
                return;
            }
            // Coba slight rotations
            if (rc.canMove(dirToTarget.rotateRight())) {
                rc.move(dirToTarget.rotateRight());
                return;
            }
            if (rc.canMove(dirToTarget.rotateLeft())) {
                rc.move(dirToTarget.rotateLeft());
                return;
            }
            // Blocked: enter bug mode
            bugNavigating = true;
            bugStartDist = currentDist;
            bugWallOnRight = (rc.getID() % 2 == 0);
            bugTurns = 0;
        }

        if (bugNavigating) {
            bugTurns++;

            // Exit condition: closer than when we entered bug mode
            if (currentDist < bugStartDist) {
                if (rc.canMove(dirToTarget)) {
                    rc.move(dirToTarget);
                    bugNavigating = false;
                    return;
                }
                // Juga coba rotasi saat exiting
                if (rc.canMove(dirToTarget.rotateRight())) {
                    rc.move(dirToTarget.rotateRight());
                    bugNavigating = false;
                    return;
                }
                if (rc.canMove(dirToTarget.rotateLeft())) {
                    rc.move(dirToTarget.rotateLeft());
                    bugNavigating = false;
                    return;
                }
            }

            // Wall following
            Direction moveDir = dirToTarget;
            if (bugWallOnRight) {
                for (int i = 0; i < 8; i++) {
                    if (rc.canMove(moveDir)) {
                        rc.move(moveDir);
                        return;
                    }
                    moveDir = moveDir.rotateLeft();
                }
            } else {
                for (int i = 0; i < 8; i++) {
                    if (rc.canMove(moveDir)) {
                        rc.move(moveDir);
                        return;
                    }
                    moveDir = moveDir.rotateRight();
                }
            }
        }
    }

    public static void trackStuck(MapLocation myLoc) {
        if (prevLocation != null && prevLocation.equals(myLoc)) {
            stuckTurns++;
        } else {
            stuckTurns = 0;
        }
        prevLocation = myLoc;
    }

    // PAINT REFILL

    public static boolean tryRefillPaint(RobotController rc) throws GameActionException {
        int need = getPaintCapacity(rc.getType()) - rc.getPaint();
        if (need <= 0) return false;

        // Transfer langsung jika adjacent
        RobotInfo[] closeAllies = rc.senseNearbyRobots(2, rc.getTeam());
        for (RobotInfo ally : closeAllies) {
            if (!isUnitTower(ally.getType())) continue;
            if (rc.canTransferPaint(ally.getLocation(), -need)) {
                rc.transferPaint(ally.getLocation(), -need);
                return false; // Sudah refill, lanjut action
            }
        }

        // Gerak ke tower terdekat visible
        RobotInfo[] allAllies = rc.senseNearbyRobots(-1, rc.getTeam());
        RobotInfo nearestTower = null;
        int nearestDist = Integer.MAX_VALUE;
        for (RobotInfo ally : allAllies) {
            if (!isUnitTower(ally.getType())) continue;
            // Prefer paint tower (punya lebih banyak paint)
            int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
            if (isPaintTower(ally.getType())) dist -= 10; // Bias toward paint tower
            if (dist < nearestDist) {
                nearestDist = dist;
                nearestTower = ally;
            }
        }

        if (nearestTower != null && rc.isMovementReady()) {
            bugMoveToward(rc, nearestTower.getLocation());
            return true;
        }

        // Fallback: known towers dari komunikasi
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
                bugMoveToward(rc, closestKnown);
                return true;
            }
        }

        return false;
    }
    // COMMUNICATION 

    static int encodeMessage(int type, MapLocation loc) {
        return (type << 24) | (loc.x << 12) | loc.y;
    }

    static int decodeType(int data) {
        return (data >> 24) & 0xFF;
    }

    static MapLocation decodeLoc(int data) {
        int x = (data >> 12) & 0xFFF;
        int y = data & 0xFFF;
        return new MapLocation(x, y);
    }

    /** Process incoming messages — semua unit panggil ini */
    static void processMessages(RobotController rc) throws GameActionException {
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            int data = m.getBytes();
            int type = decodeType(data);
            MapLocation loc = decodeLoc(data);
            switch (type) {
                case MSG_TOWER_LOC:     addKnownTower(loc);      break;
                case MSG_RUIN_LOC:      addKnownRuin(loc);       break;
                case MSG_RUIN_COMPLETE: removeKnownRuin(loc);    break;
                case MSG_ENEMY_TOWER:   addKnownEnemyTower(loc); break;
            }
        }
    }

    /** Tower broadcast — lokasi sendiri + ruin nearby */
    static void towerBroadcast(RobotController rc) throws GameActionException {
        if (rc.canBroadcastMessage()) {
            rc.broadcastMessage(encodeMessage(MSG_TOWER_LOC, rc.getLocation()));
        }

        MapInfo[] tiles = rc.senseNearbyMapInfos();
        for (MapInfo tile : tiles) {
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

    /** Unit report: enemy towers yang terlihat */
    static void reportEnemyTowers(RobotController rc) throws GameActionException {
        Team enemy = rc.getTeam().opponent();
        RobotInfo[] enemies = rc.senseNearbyRobots(-1, enemy);
        for (RobotInfo e : enemies) {
            if (isUnitTower(e.getType())) {
                MapLocation loc = e.getLocation();
                // Broadcast ke ally tower terdekat
                RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
                for (RobotInfo ally : allies) {
                    if (isUnitTower(ally.getType()) && rc.canSendMessage(ally.getLocation())) {
                        rc.sendMessage(ally.getLocation(), encodeMessage(MSG_ENEMY_TOWER, loc));
                        break;
                    }
                }
                addKnownEnemyTower(loc);
                break; // 1 report per turn
            }
        }
    }

    /** Soldier broadcast: sedang build ruin */
    static void broadcastBuildingRuin(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (isUnitTower(ally.getType()) && rc.canSendMessage(ally.getLocation())) {
                rc.sendMessage(ally.getLocation(), encodeMessage(MSG_BUILDING_RUIN, ruinLoc));
                break;
            }
        }
    }

    /** Broadcast ruin complete supaya semua unit berhenti heading ke sana */
    static void broadcastRuinComplete(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : allies) {
            if (isUnitTower(ally.getType()) && rc.canSendMessage(ally.getLocation())) {
                rc.sendMessage(ally.getLocation(), encodeMessage(MSG_RUIN_COMPLETE, ruinLoc));
                break;
            }
        }
        removeKnownRuin(ruinLoc);
    }

    // =============================================
    // KNOWN LOCATION MANAGEMENT
    // =============================================

    static void addKnownTower(MapLocation loc) {
        for (int i = 0; i < knownTowerCount; i++) {
            if (knownTowers[i].equals(loc)) return;
        }
        if (knownTowerCount < MAX_KNOWN) {
            knownTowers[knownTowerCount++] = loc;
        }
    }

    static void addKnownRuin(MapLocation loc) {
        for (int i = 0; i < knownRuinCount; i++) {
            if (knownRuins[i].equals(loc)) return;
        }
        if (knownRuinCount < MAX_KNOWN) {
            knownRuins[knownRuinCount++] = loc;
        }
    }

    static void removeKnownRuin(MapLocation loc) {
        for (int i = 0; i < knownRuinCount; i++) {
            if (knownRuins[i].equals(loc)) {
                knownRuins[i] = knownRuins[knownRuinCount - 1];
                knownRuinCount--;
                return;
            }
        }
    }

    static void addKnownEnemyTower(MapLocation loc) {
        for (int i = 0; i < knownEnemyTowerCount; i++) {
            if (knownEnemyTowers[i].equals(loc)) return;
        }
        if (knownEnemyTowerCount < MAX_KNOWN) {
            knownEnemyTowers[knownEnemyTowerCount++] = loc;
        }
    }

    // Soldier: track ruin yang timeout (gagal build)
    static void addSkippedRuin(MapLocation loc) {
        if (skippedRuinCount < skippedRuins.length) {
            skippedRuins[skippedRuinCount++] = loc;
        } else {
            // Overwrite oldest
            skippedRuins[0] = loc;
        }
    }

    static boolean isSkippedRuin(MapLocation loc) {
        for (int i = 0; i < skippedRuinCount; i++) {
            if (skippedRuins[i].equals(loc)) return true;
        }
        return false;
    }

    // =============================================
    // HELPERS
    // =============================================

    static int dirToIndex(Direction dir) {
        for (int i = 0; i < 8; i++) {
            if (directions[i] == dir) return i;
        }
        return -1;
    }

    static int clamp(int val, int min, int max) {
        return Math.max(min, Math.min(max, val));
    }

    // Chip/paint ratio ideal (soldier cost ~150 chip, ~50 paint → ratio ≈ 3.0)
    static final double IDEAL_CHIP_PAINT_RATIO = 3.0;

    /**
     * Greedy tower selection berdasarkan resource ratio.
     * Jaga balance chip vs paint supaya selalu bisa spawn unit.
     */
    static UnitType chooseTowerGreedy(RobotController rc, MapLocation ruinLoc) {
        int chips = rc.getChips();
        int paint = rc.getPaint();
        if (chips < 800) return UnitType.LEVEL_ONE_MONEY_TOWER;
        if (paint <= 0)  return UnitType.LEVEL_ONE_PAINT_TOWER;
        double ratio = (double) chips / paint;
        if (ratio > IDEAL_CHIP_PAINT_RATIO) return UnitType.LEVEL_ONE_PAINT_TOWER;
        if (ratio < IDEAL_CHIP_PAINT_RATIO) return UnitType.LEVEL_ONE_MONEY_TOWER;
        int dist = Math.abs(ruinLoc.x - rc.getMapWidth() / 2) + Math.abs(ruinLoc.y - rc.getMapHeight() / 2);
        return (dist < (rc.getMapWidth() + rc.getMapHeight()) / 6)
                ? UnitType.LEVEL_ONE_PAINT_TOWER : UnitType.LEVEL_ONE_MONEY_TOWER;
    }

    public static UnitType getPreferredTower(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        return chooseTowerGreedy(rc, ruinLoc);
    }

    public static UnitType getFallbackTower(RobotController rc, MapLocation ruinLoc) throws GameActionException {
        UnitType preferred = chooseTowerGreedy(rc, ruinLoc);
        return (preferred == UnitType.LEVEL_ONE_PAINT_TOWER)
                ? UnitType.LEVEL_ONE_MONEY_TOWER : UnitType.LEVEL_ONE_PAINT_TOWER;
    }

    public static boolean isPaintTower(UnitType t) {
        return t == UnitType.LEVEL_ONE_PAINT_TOWER || t == UnitType.LEVEL_TWO_PAINT_TOWER
                || t == UnitType.LEVEL_THREE_PAINT_TOWER;
    }

    public static boolean isMoneyTower(UnitType t) {
        return t == UnitType.LEVEL_ONE_MONEY_TOWER || t == UnitType.LEVEL_TWO_MONEY_TOWER
                || t == UnitType.LEVEL_THREE_MONEY_TOWER;
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

    public static int getPaintCapacity(UnitType type) {
        if (type == UnitType.SOLDIER)  return 200;
        if (type == UnitType.SPLASHER) return 300;
        if (type == UnitType.MOPPER)   return 100;
        return 0;
    }
}
