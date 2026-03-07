package alternative_bots_1;

import battlecode.common.*;
import java.util.Random;

/**
 * RobotPlayer is the class that describes your main robot strategy.
 * The run() method inside this class is like your main function: this is what we'll call once your robot
 * is created!
 */
public class RobotPlayer {

    static RobotController rc;

    /**
     * A random number generator.
     * We will use this RNG to make some random moves. The Random class is provided by the java.util.Random
     * import at the top of this file. Here, we *seed* the RNG with a constant number (6147); this makes sure
     * we get the same sequence of numbers every time this code is run. This is very useful for debugging!
     */
    static final Random rng = new Random(6147);


    /** Array containing all the possible movement directions. */
    static final Direction[] directions = {
        Direction.NORTH,
        Direction.NORTHEAST,
        Direction.EAST,
        Direction.SOUTHEAST,
        Direction.SOUTH,
        Direction.SOUTHWEST,
        Direction.WEST,
        Direction.NORTHWEST,
        Direction.CENTER
    };

    // state untuk soldier
    enum SoldierState { 
        EXPLORING, // eksplorasi map dan mencari ruin
        CLAIMING,  // mengklaim ruin yang ditemukan
        BUILDING,  // membangun tower
        STARVING   // kekurangan paint
    }

    // state awal soldier dan target ruin yang ingin diklaim
    static SoldierState current_state = SoldierState.EXPLORING;
    static MapLocation target_ruin = null; 
    
    // komunikasi
    static final int MESSAGE_RUIN_FOUND = 1; // kode tipe pesan untuk penemuan ruin
    static int ruins_count = 0; // untuk tracking jumlah ruin yang ditemukan

    /**
     * run() is the method that is called when a robot is instantiated in the Battlecode world.
     * It is like the main function for your robot. If this method returns, the robot dies!
     *
     * @param rc  The RobotController object. You use it to perform actions from this robot, and to get
     *            information on its current status. Essentially your portal to interacting with the world.
     **/
    @SuppressWarnings("unused")
    public static void run(RobotController rc) throws GameActionException {
        RobotPlayer.rc = rc;

        // Hello world! Standard output is very useful for debugging.
        // Everything you say here will be directly viewable in your terminal when you run a match!
        System.out.println("I'm alive");

        // You can also use indicators to save debug notes in replays.
        rc.setIndicatorString("Hello world!");

        while (true) {
            // This code runs during the entire lifespan of the robot, which is why it is in an infinite
            // loop. If we ever leave this loop and return from run(), the robot dies! At the end of the
            // loop, we call Clock.yield(), signifying that we've done everything we want to do.
            
            // Try/catch blocks stop unhandled exceptions, which cause your robot to explode.
            try {
                // The same run() function is called for every robot on your team, even if they are
                // different types. Here, we separate the control depending on the UnitType, so we can
                // use different strategies on different robots. If you wish, you are free to rewrite
                // this into a different control structure!
                if (rc.getType().isTowerType()) {
                    runTower();
                } else if (rc.getType() == UnitType.SOLDIER) {
                    runSoldier();
                } else if (rc.getType() == UnitType.SPLASHER) {
                    runSplasher();
                } else if (rc.getType() == UnitType.MOPPER) {
                    runMopper();
                }
               } 
            catch (GameActionException e) {
                // Oh no! It looks like we did something illegal in the Battlecode world. You should
                // handle GameActionExceptions judiciously, in case unexpected events occur in the game
                // world. Remember, uncaught exceptions cause your robot to explode!
                System.out.println("Exception in " + rc.getType() + " turn");
                e.printStackTrace();

            } catch (Exception e) {
                // Oh no! It looks like our code tried to do something bad. This isn't a
                // GameActionException, so it's more likely to be a bug in our code.
                System.out.println("Exception");
                e.printStackTrace();
                
            } finally {
                // Signify we've done everything we want to do, thereby ending our turn.
                // This will make our code wait until the next turn, and then perform this loop again.
                Clock.yield();
            }
            // End of loop: go back to the top. Clock.yield() has ended, so it's time for another turn!
        }

        // Your code should never reach here (unless it's intentional)! Self-destruction imminent...
    }

    /**
     * Run a single turn for towers.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runTower() throws GameActionException {
        // membaca pesan dari dikirimkan robot lain
        Message[] messages = rc.readMessages(-1); 

        for (Message m : messages) {
            // decode pesan
            int packed_message = m.getBytes();

            // jika pesan adalah tentang penemuan ruin
            if (decode_type(packed_message) == MESSAGE_RUIN_FOUND) {
                // jumlah ruin yang ditemukan ditambah
                ruins_count++;

                // meneruskan pesan ke sekutu terdekat
                RobotInfo[] nearby_allies = rc.senseNearbyRobots(-1, rc.getTeam());
                for (RobotInfo ally : nearby_allies) {
                    if (rc.canSendMessage(ally.getLocation())) {
                        rc.sendMessage(ally.getLocation(), packed_message);
                        break;
                    }
                }
            }
        }

        // jika tower belum siap melakukan aksi, skip
        if (!rc.isActionReady()) {
            return; 
        }

        // membaca informasi sumber daya dan kondisi sekitar (sekutu dan musuh)
        int current_chips = rc.getChips(); 
        int tower_paint = rc.getPaint();   
        RobotInfo[] nearby_enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());
        int nearby_allies = rc.senseNearbyRobots(-1, rc.getTeam()).length;

        // fungsi seleksi: spawn robot bedasarkan prioritas
        // tipe robot yang akan dispawn
        UnitType type_to_spawn = null;

        // jika ada musuh yang dekat, prioritaskan defense dengan spawn Mopper
        if (nearby_enemies.length > 0 && current_chips >= 300 && tower_paint >= 100) {
            type_to_spawn = UnitType.MOPPER;
        } 
        
        // jika tidak ada musuh, prioritaskan eksplorasi map dengan spawn Splasher
        else if (current_chips >= 1000 && tower_paint >= 300) {
            type_to_spawn = UnitType.SPLASHER;
        } 
        
        // jika tidak ada musuh dan sudah banyak sekutu, prioritaskan klaim ruin dan spawn soldier
        else if ((ruins_count > 0 || nearby_allies < 2) && current_chips >= 250 && tower_paint >= 200) {
            type_to_spawn = UnitType.SOLDIER;
            if (ruins_count > 0) { // kurangi jumlah ruin yang perlu diklaim
                ruins_count--; 
            }
        }

        // spawn robot jika ada yang dipilih
        if (type_to_spawn != null) {
            spawn_robot(type_to_spawn);
        }
    }

    // fungsi untuk spawn robot
    public static void spawn_robot(UnitType type) throws GameActionException {
        // mendapatkan lokasi saat ini
        MapLocation current_location = rc.getLocation();

        // iterasi semua lokasi dalam grid 5x5 sekitar tower
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                // memastikan lokasi kandidat spawn berada dalam radius (jarak kuadrat <= 4)
                MapLocation candidate_location = new MapLocation(current_location.x + dx, current_location.y + dy);
                if (current_location.distanceSquaredTo(candidate_location) > 4) {
                    continue;
                }

                // jika lokasi kandidat valid, spawn robot
                if (rc.canBuildRobot(type, candidate_location)) {
                    rc.buildRobot(type, candidate_location);
                    return; 
                }
            }
        }
    }

    /**
     * Run a single turn for a Soldier.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */
    public static void runSoldier() throws GameActionException {
        // mendapatkan status paint untuk menentukan state soldier
        int current_paint = rc.getPaint();
        int max_paint = rc.getType().paintCapacity; 
        
        // jika paint < 25%, soldier masuk state STARVING
        if (current_paint < (max_paint * 0.25)) {
            current_state = SoldierState.STARVING;
        } 
        
        // jika soldier sedang STARVING dan paint sudah cukup, kembali ke state EXPLORING
        else if (current_state == SoldierState.STARVING && current_paint > (max_paint * 0.8)) {
            current_state = SoldierState.EXPLORING;
            target_ruin = null;
        }

        // eksekusi logika berdasarkan state saat ini
        switch (current_state) {
            case EXPLORING: explore(); break;
            case CLAIMING:  claim_ruin(); break;
            case BUILDING:  build_tower(); break;
            case STARVING:  starve(); break;
        }
    }

    // fungsi explorasi map dan mencari ruin terdekat yang belum diklaim
    public static void explore() throws GameActionException {
        // mencari semua ruin di sekitar
        MapLocation[] ruins = rc.senseNearbyRuins(-1); 
        int min_distance = Integer.MAX_VALUE;
        MapLocation best_ruin = null;

        for (MapLocation ruin : ruins) {
            // jika ada robot lain yang sudah berada di lokasi ruin, skip
            if (rc.canSenseLocation(ruin) && rc.senseRobotAtLocation(ruin) != null) {
                continue;
            }

            // hitung jarak ke ruin dan update ruin terbaik jika lebih dekat
            int dist = rc.getLocation().distanceSquaredTo(ruin);
            if (dist < min_distance) {
                min_distance = dist;
                best_ruin = ruin;
            }
        }

        // jika ada ruin terbaik yang ditemukan
        if (best_ruin != null) {
            // set target_ruin dan ganti state soldier ke CLAIMING
            target_ruin = best_ruin;
            current_state = SoldierState.CLAIMING;

            // mencari sekutu terdekat
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allies) {
                // mengirimkan pesan ke tower terdekat tentang penemuan ruin
                if (ally.getType().isTowerType() && rc.canSendMessage(ally.getLocation())) {
                    rc.sendMessage(ally.getLocation(), encode_message(MESSAGE_RUIN_FOUND, best_ruin));
                    break;
                }
            }
            return;
        }

        // jika tidak ada ruin yang ditemukan, lakukan pergerakan greedy acak untuk eksplorasi
        greedy_move(new MapLocation(rc.getLocation().x + (rng.nextInt(5) - 2), rc.getLocation().y + (rng.nextInt(5) - 2))); 
    }

    // fungsi untuk klaim ruin
    public static void claim_ruin() throws GameActionException {
        // jika target_ruin sudah tidak valid (misal sudah diklaim oleh robot lain)
        // kembali ke state EXPLORING
        if (target_ruin == null) {
            current_state = SoldierState.EXPLORING;
            return;
        }

        // jika sudah berada di dekat target_ruin, ganti state ke BUILDING
        if (rc.getLocation().distanceSquaredTo(target_ruin) <= 8) { 
            current_state = SoldierState.BUILDING;
            return;
        }

        // lakukan pergerakan greedy menuju target_ruin
        greedy_move(target_ruin);
    }

    // fungsi untuk membangun tower
    public static void build_tower() throws GameActionException {
        // jika target_ruin sudah tidak valid (misal sudah diklaim oleh robot lain atau terlalu jauh)
        if (target_ruin == null || rc.getLocation().distanceSquaredTo(target_ruin) > 8) {
            // kembali ke state EXPLORING untuk mencari ruin lain
            current_state = SoldierState.EXPLORING;
            return;
        }

        // jika jumlah paint cukup dan bisa mark pattern tower
        if (rc.isActionReady() && rc.getPaint() >= 25 && rc.canMarkTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target_ruin)) {
            rc.markTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target_ruin);
        }

        // cat tile di sekitar ruin sesuai pola tower
        if (rc.isActionReady() && rc.getPaint() >= 5) { 
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    MapLocation loc = new MapLocation(target_ruin.x + dx, target_ruin.y + dy);
                    
                    // cat lokasi jika bisa
                    if (rc.canAttack(loc)) {
                        rc.attack(loc);
                        return; 
                    }
                }
            }
        }

        // selesaikan pola tower jika semua tile sudah dicat dengan benar
        if (rc.isActionReady() && rc.canCompleteTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target_ruin)) {
            rc.completeTowerPattern(UnitType.LEVEL_ONE_PAINT_TOWER, target_ruin);
            
            // reset target dan kembali ke state EXPLORING untuk mencair ruin berikutnya
            target_ruin = null;
            current_state = SoldierState.EXPLORING;
        }
    }

    // fungsi starve untuk soldier yang kekurangan paint
    public static void starve() throws GameActionException {
        // mencari sekutu di sekitar
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        // untuk menyimpan tower terbaik yang bisa untuk reset paint
        MapLocation best_tower = null;
        int min_distance = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            // menemukan tower terdeket di sekitar sekutu
            if (ally.getType().isTowerType()) { 
                int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
                if (dist < min_distance) {
                    min_distance = dist;
                    best_tower = ally.getLocation();
                }
            }
        }

        if (best_tower != null) {
            // jika dekat dengan tower
            if (rc.getLocation().distanceSquaredTo(best_tower) <= 2) {
                // transfer paint
                if (rc.isActionReady() && rc.canTransferPaint(best_tower, -100)) {
                    rc.transferPaint(best_tower, -100);
                }
            } 
            
            // jika jarak ke tower masih jauh
            else {
                // pergerakan greedy ke tower terbaik
                greedy_move(best_tower);
            }
        } 
        
        // jika tidak ada tower yang terlihat, bergerak greedy acak untku mencari tower
        else {
            greedy_move(new MapLocation(rng.nextInt(60), rng.nextInt(60))); 
        }
    }

    // fungsi robot splasher untku mengecat area
    public static void runSplasher() throws GameActionException {
        // jika paint kurang dari 50 (tidak cukup)
        if (rc.getPaint() < 50) {
            // lakukan pergerakan greedy acak untuk menemukan lokasi lain
            greedy_move(new MapLocation(rc.getLocation().x + (rng.nextInt(5) - 2), rc.getLocation().y + (rng.nextInt(5) - 2)));
            return;
        }

        // jika bisa melakukan aksi
        if (rc.isActionReady()) {
            // mencari lokasi terbaik untuk mengecat
            MapLocation best_target = null;
            int max_tiles_painted = 0;
            MapLocation current_location = rc.getLocation();

            // iterasi semua lokasi dalam grid 5x5 sekitar splasher
            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    // memastikan lokasi target berada dalam radius 2
                    MapLocation target_location = new MapLocation(current_location.x + dx, current_location.y + dy);
                    
                    // skip jika lokasi tidak bisa dicat
                    if ((current_location.distanceSquaredTo(target_location) > 4) || (!rc.canAttack(target_location))) {
                        continue;
                    } 

                    // menghitung berapa banyak tile yang bisa dicat dari target_location
                    int score = 0;
                    for (int ax = -1; ax <= 1; ax++) {
                        for (int ay = -1; ay <= 1; ay++) {
                            // memeriksa setiap lokasi dalam area of effect (AoE)
                            MapLocation aoe_location = new MapLocation(target_location.x + ax, target_location.y + ay);
                            
                            // skip jika lokasi AoE berada di luar radius 2 dari splasher
                            if (target_location.distanceSquaredTo(aoe_location) > 2) {
                                continue;
                            } 
                            
                            // hitung skor jika lokasi AoE bisa dicat (tidak dicat sekutu)
                            if (rc.canSenseLocation(aoe_location)) {
                                PaintType pt = rc.senseMapInfo(aoe_location).getPaint();
                                if (pt != PaintType.ALLY_PRIMARY && pt != PaintType.ALLY_SECONDARY) {
                                    score++;
                                }
                            }
                        }
                    }

                    // update target terbaik jika skor lebih tinggi
                    if (score > max_tiles_painted) {
                        max_tiles_painted = score;
                        best_target = target_location;
                    }
                }
            }

            // jika ada target terbaik yang ditemukan dan bisa mengecat setidaknya 1 tile, lakukan attack
            if (best_target != null && max_tiles_painted > 0) {
                rc.attack(best_target);
                return; 
            }
        }

        // jika tidak bisa melakukan aksi atau tidak ada target yang bagus, lakukan pergerakan greedy acak untuk mencari lokasi lain
        greedy_move(new MapLocation(rc.getLocation().x + (rng.nextInt(5) - 2), rc.getLocation().y + (rng.nextInt(5) - 2)));
    }

    /**
     * Run a single turn for a Mopper.
     * This code is wrapped inside the infinite loop in run(), so it is called once per turn.
     */ 
    public static void runMopper() throws GameActionException {
        // mopper hanya akan akan mencari musuh terdekat dengan pergerakan greedy acak
        greedy_move(new MapLocation(rc.getLocation().x + (rng.nextInt(5) - 2), rc.getLocation().y + (rng.nextInt(5) - 2)));
    }

    // fungsi pergerakan greedy menuju target
    public static void greedy_move(MapLocation target) throws GameActionException {
        // skip jika robot belum siap untuk bergerak
        if (!rc.isMovementReady()) {
            return; 
        }

        // menyimpan arah terbaik dan jarak minimal ke target
        int min_distance = Integer.MAX_VALUE;
        Direction best_direction = Direction.CENTER;

        for (Direction dir : directions) {
            // jika arah CENTER (tidak bergerak), skip
            if (dir == Direction.CENTER) {
                continue;
            }

            // jika bisa bergerak ke arah dir
            if (rc.canMove(dir)) {
                // mendapatkan lokasi berikutnya dan jaraknya
                MapLocation next_location = rc.getLocation().add(dir);
                int distance = next_location.distanceSquaredTo(target);
                
                // pilih arah yang menghasilkan jarak terpendek ke target
                if (distance < min_distance) {
                    min_distance = distance;
                    best_direction = dir;
                }
            }
        }

        // bergerak ke arah terbaik jika ditemukan
        if (best_direction != Direction.CENTER) {
            rc.move(best_direction); 
        }
    }

    // fungsi encode pesan dari robot
    static int encode_message(int type, MapLocation loc) {
        int msg = 0;
        msg = msg | (type << 12);  // simpan tipe pesan di 4 bit paling kiri
        msg = msg | (loc.x << 6);  // simpan koordinat x di 6 bit berikutnya
        msg = msg | loc.y;         // simpan koordinat y di 6 bit paling kanan
        return msg;
    }

    // fungsi decode lokasi
    static MapLocation decode_location(int msg) {
        int y = msg & 0b111111;        // ambil 6 bit paling kanan untuk koordinat y   
        int x = (msg >> 6) & 0b111111; // ambil 6 bit berikutnya untuk koordinat x
        return new MapLocation(x, y);
    }

    // fungsi decode tipe pesan
    static int decode_type(int msg) {
        return (msg >> 12) & 0b1111; // ambil 4 bit paling kiri untuk tipe pesan
    }
}