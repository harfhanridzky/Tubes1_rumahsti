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
        STARVING,  // kekurangan paint
        RALLYING   // bergerak ke ruin yang ditemukan robot lain
    }

    // state awal soldier dan target ruin yang ingin diklaim
    static SoldierState current_state = SoldierState.EXPLORING;
    static MapLocation target_ruin = null;

    // komunikasi
    static final int MESSAGE_RUIN_FOUND = 1;    // kode tipe pesan untuk penemuan ruin
    static final int MESSAGE_TOWER_BUILT = 2;   // kode tipe pesan untuk tower yang sudah dibangun
    static final int MESSAGE_RALLY_TO_RUIN = 3; // kode tipe pesan untuk rally ke ruin
    static int ruins_count = 0; // untuk tracking jumlah ruin yang ditemukan

    // tipe tower yang akan dibangun untuk ruin saat ini
    static UnitType target_tower_type = UnitType.LEVEL_ONE_PAINT_TOWER;

    // untuk menyimpan lokasi tower asal soldier
    static MapLocation home_tower = null;

    // untuk tracking berapa lama soldier sudah menunggu di tower
    static int starve_wait_turns = 0;
    static final int MAX_STARVE_WAIT = 10;

    // berapa lama soldier sudah membangun tower tanpa progress
    static int build_wait_turns = 0;
    static final int MAX_BUILD_WAIT = 30; // maksimal 30 turn untuk membangun tanpa selesai

    // daftar ruin yang sedang di-rally
    static MapLocation rally_ruin = null;

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

        // simpan lokasi tower tempat robot pertama kali di-spawn
        RobotInfo[] nearby = rc.senseNearbyRobots(-1, rc.getTeam());
        for (RobotInfo ally : nearby) {
            if (ally.getType().isTowerType()) {
                home_tower = ally.getLocation();
                break;
            }
        }

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
        // atur frekuensi spawn berdasarkan jumlah tower
        int num_tower = rc.getNumberTowers();
        int spawn_interval = 1;
        if (num_tower >= 5) {
            spawn_interval = 3;
        } else if (num_tower >= 3) {
            spawn_interval = 2;
        }

        if (rc.getRoundNum() % spawn_interval != 0) {
            return;
        }

        // membaca pesan dari robot lain
        Message[] messages = rc.readMessages(-1);
        for (Message m : messages) {
            // decode pesan untuk mengetahui tipe pesan dan lokasi ruin yang ditemukan
            int packed_message = m.getBytes();
            int msg_type = decode_type(packed_message);

            if (msg_type == MESSAGE_RUIN_FOUND) {
                ruins_count++;
                MapLocation ruin_loc = decode_location(packed_message);

                // tower meneruskan pesan sebagai RALLY ke semua sekutu terdekat
                // agar soldier lain datang membantu building
                RobotInfo[] nearby_allies = rc.senseNearbyRobots(-1, rc.getTeam());
                int rally_msg = encode_message(MESSAGE_RALLY_TO_RUIN, ruin_loc);
                for (RobotInfo ally : nearby_allies) {
                    if (rc.canSendMessage(ally.getLocation())) {
                        rc.sendMessage(ally.getLocation(), rally_msg);
                    }
                }
            } else if (msg_type == MESSAGE_TOWER_BUILT) {
                // tower sudah dibangun, kurangi jumlah ruin
                if (ruins_count > 0) {
                    ruins_count--;
                }
            }
        }

        // jika tower belum siap melakukan aksi, skip
        if (!rc.isActionReady()) {
            return;
        }

        // membaca informasi sumber daya dan kondisi sekitar
        int current_chips = rc.getChips();
        int tower_paint = rc.getPaint();
        RobotInfo[] nearby_enemies = rc.senseNearbyRobots(-1, rc.getTeam().opponent());

        // fungsi seleksi: spawn robot bedasarkan prioritas
        // tipe robot yang akan dispawn
        UnitType type_to_spawn = null;
        // jumlah tower saat ini, untuk menentukan apakah perlu spawn soldier untuk klaim ruin atau tidak
        int current_tower_count = rc.getNumberTowers();

        // prioritas 1: defense jika ada musuh dengan spawn Mopper
        if (nearby_enemies.length > 0 && current_chips >= 300 && tower_paint >= 100) {
            type_to_spawn = UnitType.MOPPER;
        }
        // prioritas 2: spawn splasher jika sumber daya sangat banyak
        else if ((current_tower_count >= 3 && current_chips >= 400 && tower_paint >= 300) ||
                (current_chips >= 1000 && tower_paint >= 300)) {
            type_to_spawn = UnitType.SPLASHER;
        }
        // prioritas 3: soldier — selalu spawn jika cukup resource
        else if (current_chips >= 250 && tower_paint >= 200) {
            type_to_spawn = UnitType.SOLDIER;
            if (ruins_count > 0) {
                ruins_count--;
            }
        }   

        // spawn robot jika ada tipe yang dipilih
        if (type_to_spawn != null) {
            spawn_robot(type_to_spawn);
        }
    }

    // fungsi untuk spawn robot dari tower
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

        // baca pesan masuk untuk rally command
        read_soldier_messages();

        // jika paint kurang dari 40%, masuk mode STARVING untuk cari paint di tower
        if (current_paint < (max_paint * 0.40)) {
            if (current_state != SoldierState.STARVING) {
                starve_wait_turns = 0;
            }
            current_state = SoldierState.STARVING;
        }

        // recovery dari STARVING
        if (current_state == SoldierState.STARVING && current_paint > (max_paint * 0.60)) {
            current_state = SoldierState.EXPLORING;
            starve_wait_turns = 0;
            if (target_ruin != null) {
                current_state = SoldierState.BUILDING;
            } else if (rally_ruin != null) {
                current_state = SoldierState.RALLYING;
            }
        }

        // indikator untuk debugging: tampilkan state saat ini, paint, target ruin, dan rally ruin
        rc.setIndicatorString("State: " + current_state + " | Paint: " + current_paint + "/" + max_paint
                + " | Target: " + target_ruin + " | Rally: " + rally_ruin);

        // cat tile saat ini untuk mempertahankan kontrol area, terutama saat EXPLORING
        paint_current_tile();

        // seleksi aksi berdasarkan state saat ini
        switch (current_state) {
            case EXPLORING:
                explore();
                break;
            case CLAIMING:
                claim_ruin();
                break;
            case BUILDING:
                build_tower();
                break;
            case STARVING:
                starve();
                break;
            case RALLYING:
                rally_to_ruin();
                break;
        }
    }

    // fungsi untuk membaca pesan rally dari tower atau soldier lain
    public static void read_soldier_messages() throws GameActionException {
        // baca semua pesan masuk
        Message[] messages = rc.readMessages(-1);

        for (Message m : messages) {
            // decode pesan untuk mengetahui tipe pesan dan lokasi ruin yang ditemukan
            int packed_message = m.getBytes();
            int msg_type = decode_type(packed_message);

            // jika pesan adalah TOWER_BUILT
            if (msg_type == MESSAGE_TOWER_BUILT) {
                MapLocation built_loc = decode_location(packed_message);

                // jika tower yang baru dibangun adalah target rally kita, cancel rally
                if (rally_ruin != null && rally_ruin.equals(built_loc)) {
                    rally_ruin = null;
                    if (current_state == SoldierState.RALLYING) {
                        target_ruin = null;
                        current_state = SoldierState.EXPLORING;
                    }
                }
                // jika tower yang baru dibangun adalah target build, cancel build
                if (target_ruin != null && target_ruin.equals(built_loc)) {
                    target_ruin = null;
                    if (current_state == SoldierState.BUILDING || current_state == SoldierState.CLAIMING) {
                        current_state = SoldierState.EXPLORING;
                    }
                }
            }
            
        }

        // jika pesan adalah RALLY_TO_RUIN, set target rally ke lokasi ruin tersebut
        for (Message m : messages) {
            int packed_message = m.getBytes();
            int msg_type = decode_type(packed_message);    
            
            if (msg_type == MESSAGE_RALLY_TO_RUIN) {
                MapLocation ruin_loc = decode_location(packed_message);

                // Jika lokasi target terdeteksi dan ternyata sudah ada tower, abaikan pesan ini
                if (rc.canSenseLocation(ruin_loc)) {
                    RobotInfo robot = rc.senseRobotAtLocation(ruin_loc);
                    if (robot != null && robot.getType().isTowerType()) {
                        continue; 
                    }
                }

                // hanya rally jika soldier sedang EXPLORING
                if (current_state == SoldierState.EXPLORING) {
                    rally_ruin = ruin_loc;
                    target_ruin = ruin_loc;
                    target_tower_type = pick_tower_type();
                    current_state = SoldierState.RALLYING;
                }
            }
        }
    }

    // fungsi rally: soldier bergerak ke ruin yang ditemukan robot lain
    // begitu sampai, langsung bantu building
    public static void rally_to_ruin() throws GameActionException {
        if (target_ruin == null || rally_ruin == null) {
            rally_ruin = null;
            current_state = SoldierState.EXPLORING;
            return;
        }

        // cek apakah ruin sudah di-build oleh robot lain
        if (rc.canSenseLocation(target_ruin)) {
            RobotInfo robot_at_ruin = rc.senseRobotAtLocation(target_ruin);
            if (robot_at_ruin != null && robot_at_ruin.getType().isTowerType()) {
                // tower sudah jadi, cancel rally
                target_ruin = null;
                rally_ruin = null;
                current_state = SoldierState.EXPLORING;
                return;
            }
        }

        // hitung jarak ke ruin
        int dist = rc.getLocation().distanceSquaredTo(target_ruin);

        // sudah sampai di dekat ruin — langsung mulai building
        if (dist <= 8) {
            current_state = SoldierState.BUILDING;
            build_wait_turns = 0;
            return;
        }

        // bergerak menuju ruin
        greedy_move(target_ruin);
    }

    // fungsi untuk mengecat tile saat ini
    public static void paint_current_tile() throws GameActionException {
        // hanya cat tile saat action ready dan paint cukup
        if (!rc.isActionReady() || rc.getPaint() < 5) {
            return;
        }

        // mendapatkan lokasi saat ini
        MapLocation current_location = rc.getLocation();
        
        // cek apakah tile saat ini bisa disense dan diserang (cat) — jika tidak, skip
        if (!rc.canSenseLocation(current_location) || !rc.canAttack(current_location)) {
            return;
        }

        // cek jenis paint di tile saat ini
        MapInfo tile = rc.senseMapInfo(current_location);
        PaintType pt = tile.getPaint();

        // cat ulang tile netral/musuh mengikuti mark jika tersedia
        if (pt == PaintType.EMPTY || pt == PaintType.ENEMY_PRIMARY || pt == PaintType.ENEMY_SECONDARY) {
            boolean use_secondary = (tile.getMark() == PaintType.ALLY_SECONDARY);
            rc.attack(current_location, use_secondary);
        }
    }

    // fungsi eksplorasi map untuk soldier
    public static void explore() throws GameActionException {
        // cari ruin terdekat yang bisa diklaim
        MapLocation[] ruins = rc.senseNearbyRuins(-1);
        int min_distance = Integer.MAX_VALUE;
        MapLocation best_ruin = null;

        for (MapLocation ruin : ruins) {
            // jika ada robot lain yang sudah berada di lokasi ruin, skip
            if (rc.canSenseLocation(ruin)) {
                RobotInfo robot_at_ruin = rc.senseRobotAtLocation(ruin);
                if (robot_at_ruin != null && robot_at_ruin.getType().isTowerType()) {
                    continue;
                }
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
            target_tower_type = pick_tower_type();
            current_state = SoldierState.CLAIMING;

            // kirim pesan ke tower terdekat — tower akan broadcast rally ke semua soldier
            RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
            for (RobotInfo ally : allies) {
                if (ally.getType().isTowerType() && rc.canSendMessage(ally.getLocation())) {
                    rc.sendMessage(ally.getLocation(), encode_message(MESSAGE_RUIN_FOUND, best_ruin));
                    break;
                }
            }

            // juga langsung kirim rally ke soldier terdekat secara direct
            for (RobotInfo ally : allies) {
                if (ally.getType() == UnitType.SOLDIER && rc.canSendMessage(ally.getLocation())) {
                    rc.sendMessage(ally.getLocation(), encode_message(MESSAGE_RALLY_TO_RUIN, best_ruin));
                }
            }
            return;
        }

        // cat tile saat ini untuk mempertahankan kontrol area
        if (rc.isActionReady() && rc.getPaint() >= 5) {
            MapLocation current_location = rc.getLocation();
            if (rc.canAttack(current_location)) {
                PaintType pt = rc.senseMapInfo(current_location).getPaint();
                if (pt != PaintType.ALLY_PRIMARY && pt != PaintType.ALLY_SECONDARY) {
                    rc.attack(current_location);
                }
            }
        }

        // jika tidak ada ruin yang ditemukan, lakukan pergerakan greedy untuk eksplorasi
        greedy_explore(rc);
    }

    // fungsi untuk memilih tipe tower secara bergantian
    public static UnitType pick_tower_type() throws GameActionException {
        // mendapatkan jumlah tower
        int tower_count = rc.getNumberTowers();

        // pilih tipe tower berdasarkan jumlah tower saat ini
        // genap untuk paint tower, ganjil untuk money tower
        if (tower_count % 2 == 0) {
            return UnitType.LEVEL_ONE_PAINT_TOWER;
        } else {
            return UnitType.LEVEL_ONE_MONEY_TOWER;
        }
    }

    //fungsi untuk klaim ruin
    public static void claim_ruin() throws GameActionException {
        // jika target_ruin sudah tidak valid (misal sudah diklaim oleh robot lain)
        // kembali ke state EXPLORING
        if (target_ruin == null) {
            current_state = SoldierState.EXPLORING;
            return;
        }

        // cek apakah ruin sudah diklaim oleh robot lain
        // jika iya, batal klaim dan kembali eksplorasi
        if (rc.canSenseLocation(target_ruin)) {
            RobotInfo robot_at_ruin = rc.senseRobotAtLocation(target_ruin);
            if (robot_at_ruin != null && robot_at_ruin.getType().isTowerType()) {
                target_ruin = null;
                rally_ruin = null;
                current_state = SoldierState.EXPLORING;
                return;
            }
        }

        // jika sudah berada di dekat target_ruin, ganti state ke BUILDING
        if (rc.getLocation().distanceSquaredTo(target_ruin) <= 4) {
            current_state = SoldierState.BUILDING;
            build_wait_turns = 0;
            return;
        }

         // lakukan pergerakan greedy menuju target_ruin
        greedy_move(target_ruin);
    }

    // fungsi untuk membangun tower di target_ruin
    public static void build_tower() throws GameActionException {
        // jika target_ruin sudah tidak valid, kembali ke state EXPLORING
        if (target_ruin == null) {
            current_state = SoldierState.EXPLORING;
            return;
        }

        // hitung jarak ke target_ruin
        int dist = rc.getLocation().distanceSquaredTo(target_ruin);

        // cek apakah ruin sudah diklaim tower lain
        if (rc.canSenseLocation(target_ruin)) {
            RobotInfo robot_at_ruin = rc.senseRobotAtLocation(target_ruin);
            if (robot_at_ruin != null && robot_at_ruin.getType().isTowerType()) {
                // tower sudah jadi — broadcast TOWER_BUILT agar soldier lain berhenti rally
                broadcast_tower_built(target_ruin);
                target_ruin = null;
                rally_ruin = null;
                current_state = SoldierState.EXPLORING;
                return;
            }
        }

        // jika terlalu jauh, bergerak mendekati ruin tersebut
        if (dist > 8) {
            greedy_move(target_ruin);
            return;
        }

        // tracking progres build
        // jika terlalu lama tanpa selesai, abaikan target tersebut dan kembali eksplorasi
        build_wait_turns++;
        if (build_wait_turns >= MAX_BUILD_WAIT) {
            build_wait_turns = 0;
            target_ruin = null;
            rally_ruin = null;
            current_state = SoldierState.EXPLORING;
            return;
        }

        // tandai pola tower jika belum ditandai
        if (rc.canMarkTowerPattern(target_tower_type, target_ruin)) {
            rc.markTowerPattern(target_tower_type, target_ruin);
            return;
        }

        if (rc.isActionReady() && rc.getPaint() >= 5) {
            // cari tile yang sudah ditandai tapi belum dicat sesuai mark, prioritaskan untuk dicat
            MapLocation unpainted_tile = null;
            int closest_unpainted_dist = Integer.MAX_VALUE;

            for (int dx = -2; dx <= 2; dx++) {
                for (int dy = -2; dy <= 2; dy++) {
                    // lokasi tile yang akan dicek
                    MapLocation tile_loc = new MapLocation(target_ruin.x + dx, target_ruin.y + dy);

                    // pastikan tile berada dalam radius 2 dari ruin
                    if ((target_ruin.distanceSquaredTo(tile_loc) > 8) || !rc.canSenseLocation(tile_loc)) {
                        continue;
                    }

                    // cek apakah tile perlu dicat
                    // sudah ditandai tapi paint tidak sesuai mark
                    MapInfo tile = rc.senseMapInfo(tile_loc);

                    // tile perlu dicat: sudah ditandai tapi paint tidak sesuai mark
                    if (tile.getMark() != PaintType.EMPTY && tile.getMark() != tile.getPaint()) {
                        if (rc.canAttack(tile_loc)) {
                            boolean use_secondary = (tile.getMark() == PaintType.ALLY_SECONDARY);
                            rc.attack(tile_loc, use_secondary);
                            return;
                        } else {
                            // simpan tile terdekat yang belum bisa dicat
                            int tile_dist = rc.getLocation().distanceSquaredTo(tile_loc);
                            if (tile_dist < closest_unpainted_dist) {
                                closest_unpainted_dist = tile_dist;
                                unpainted_tile = tile_loc;
                            }
                        }
                    }
                }
            }

            // jika ada tile yang belum bisa dicat, bergerak mendekatinya
            if (unpainted_tile != null) {
                greedy_move(unpainted_tile);
                return;
            }
        }

        // selesaikan pola tower
        if (rc.canCompleteTowerPattern(target_tower_type, target_ruin)) {
            rc.completeTowerPattern(target_tower_type, target_ruin);

            // broadcast ke semua sekutu bahwa tower sudah jadi
            broadcast_tower_built(target_ruin);

            // reset state soldier setelah selesai membangun
            target_ruin = null;
            rally_ruin = null;
            target_tower_type = UnitType.LEVEL_ONE_PAINT_TOWER;
            build_wait_turns = 0;
            current_state = SoldierState.EXPLORING;
            return;
        }

        // jika tidak ada yang bisa dilakukan, bergerak lebih dekat ke target_ruin
        if (dist > 2) {
            greedy_move(target_ruin);
        }
    }

    // fungsi untuk broadcast pesan TOWER_BUILT ke semua sekutu dalam jangkauan
    public static void broadcast_tower_built(MapLocation tower_loc) throws GameActionException {
        int msg = encode_message(MESSAGE_TOWER_BUILT, tower_loc);
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());

        // kirim ke tower terdekat agar diteruskan
        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType() && rc.canSendMessage(ally.getLocation())) {
                rc.sendMessage(ally.getLocation(), msg);
                break;
            }
        }

        // kirim langsung ke soldier terdekat
        for (RobotInfo ally : allies) {
            if (ally.getType() == UnitType.SOLDIER && rc.canSendMessage(ally.getLocation())) {
                rc.sendMessage(ally.getLocation(), msg);
            }
        }
    }

    // fungsi starving soldier: mencari tower, isi paint, lalu kembali ke tugas semula
    public static void starve() throws GameActionException {
        // cari tower sekutu terdekat dengan cadangan paint terbanyak
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation best_tower = null;
        int min_distance = Integer.MAX_VALUE;

        for (RobotInfo ally : allies) {
            if (ally.getType().isTowerType()) {
                // hitung jarak ke tower dan cek cadangan paint
                int dist = rc.getLocation().distanceSquaredTo(ally.getLocation());
                int tower_paint = ally.getPaintAmount();

                // prioritaskan tower dengan cadangan paint > 50
                // jika tidak ada, tetap cari tower terdekat
                if (tower_paint > 50 && dist < min_distance) {
                    min_distance = dist;
                    best_tower = ally.getLocation();
                } else if (best_tower == null && dist < min_distance) {
                    min_distance = dist;
                    best_tower = ally.getLocation();
                }
            }
        }

        // jika tidak ada tower dengan cadangan paint > 50, gunakan tower asal
        if (best_tower == null && home_tower != null) {
            best_tower = home_tower;
        }

        // jika ada tower yang ditemukan, bergerak ke sana dan isi paint
        if (best_tower != null) {
            int dist_to_tower = rc.getLocation().distanceSquaredTo(best_tower);

            // jika sudah dekat dengan tower, isi paint
            if (dist_to_tower <= 4) {
                int max_paint = rc.getType().paintCapacity;
                int current_paint = rc.getPaint();
                int paint_needed = (int) (max_paint * 0.9) - current_paint;

                // jika paint yang dibutuhkan masih positif, transfer paint dari tower ke soldier
                if (paint_needed > 0 && rc.isActionReady() && rc.canTransferPaint(best_tower, -paint_needed)) {
                    rc.transferPaint(best_tower, -paint_needed);
                    starve_wait_turns = 0;
                } else {
                    starve_wait_turns++;
                }

                // cek apakah paint sudah cukup untuk kembali ke tugas semula
                int updated_paint = rc.getPaint();
                if (updated_paint > (max_paint * 0.6)) {
                    current_state = SoldierState.EXPLORING;
                    starve_wait_turns = 0;
                    if (target_ruin != null) {
                        current_state = SoldierState.BUILDING;
                    } else if (rally_ruin != null) {
                        current_state = SoldierState.RALLYING;
                    }
                    return;
                }

                // bunuh diri jika sudah menunggu terlalu lama
                if (starve_wait_turns >= MAX_STARVE_WAIT) {
                    rc.disintegrate();
                    return;
                }

                // sambil menunggu, cat tile di sekitar tower
                if (rc.isActionReady() && rc.getPaint() >= 5) {
                    MapInfo[] nearby_tiles = rc.senseNearbyMapInfos(2);
                    for (MapInfo tile : nearby_tiles) {
                        MapLocation loc = tile.getMapLocation();
                        PaintType pt = tile.getPaint();
                        if ((pt == PaintType.EMPTY || pt == PaintType.ENEMY_PRIMARY
                                || pt == PaintType.ENEMY_SECONDARY)
                                && rc.canAttack(loc)) {
                            rc.attack(loc);
                            break;
                        }
                    }
                }

            } else {
                // jika belum dekat, bergerak mendekati tower
                greedy_move(best_tower);
                starve_wait_turns++;
                if (starve_wait_turns >= MAX_STARVE_WAIT) {
                    rc.disintegrate();
                    return;
                }
            }
        } else {
            // jika tidak ada tower yang ditemukan, tetap lakukan eksplorasi untuk mencari paint di sekitar
            starve_wait_turns++;
            if (starve_wait_turns >= MAX_STARVE_WAIT) {
                rc.disintegrate();
                return;
            }
            greedy_explore(rc);
        }
    }

    // fungis untuk mencari tower sekutu lain dengan cadangan paint terbesar
    public static MapLocation find_other_tower(MapLocation current_tower) throws GameActionException {
        // cari semua robot sekutu di sekitar dan temukan tower dengan cadangan paint terbanyak selain current_tower
        RobotInfo[] allies = rc.senseNearbyRobots(-1, rc.getTeam());
        MapLocation other_tower = null;
        int best_paint = 0;

        for (RobotInfo ally : allies) {
            // pastikan robot tersebut adalah tower dan bukan tower asal
            if (ally.getType().isTowerType() && !ally.getLocation().equals(current_tower)) {
                if (ally.getPaintAmount() > best_paint) {
                    best_paint = ally.getPaintAmount();
                    other_tower = ally.getLocation();
                }
            }
        }

        return other_tower;
    }

    // fungsi untuk robot splasher: pilih target AoE dengan skor cat tertinggi
    public static void runSplasher() throws GameActionException {
        // jika paint kurang dari 50 (tidak cukup)
        if (rc.getPaint() < 50) {
            // lakukan eksplorasi biasa untuk mencari area yang bisa dicat
            greedy_explore(rc);
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
                                if (pt == PaintType.ENEMY_PRIMARY || pt == PaintType.ENEMY_SECONDARY) {
                                    score += 3;
                                } else if (pt == PaintType.EMPTY) {
                                    score += 1;
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
        greedy_explore(rc);
    }

    // fungsi robot mopper: hanya untuk eksplorasi greedy
    public static void runMopper() throws GameActionException {
        greedy_explore(rc);
    }

    // fungsi pergerakan greedy menuju target (pilih langkah yang meminimalkan jarak)
    public static void greedy_move(MapLocation target) throws GameActionException {
        // jika tidak bisa bergerak, skip
        if (!rc.isMovementReady()) {
            return;
        }

        // untuk menyimpan langkah terbaik yang ditemukan
        int min_distance = Integer.MAX_VALUE;
        Direction best_direction = Direction.CENTER;

        for (Direction dir : directions) {
            // skip arah CENTER (tidak bergerak)
            if (dir == Direction.CENTER) {
                continue;
            }

           // jika bisa bergerak ke arah yang dipilih
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

    // fungsi eksplorasi greedy dengan memberi skor tile
    public static void greedy_explore(RobotController rc) throws GameActionException {
        // jika tidak bisa bergerak, skip
        if (!rc.isMovementReady()) {
            return;
        }

        // beri skor untuk setiap kandidat langkah berdasarkan tile di sekitarnya
        int best_move_score = Integer.MIN_VALUE;
        Direction best_move_direction = null;
        MapLocation current_location = rc.getLocation();
        MapInfo[] nearby_tiles = rc.senseNearbyMapInfos(-1);

        for (Direction dir : directions) {
            // skip arah CENTER (tidak bergerak) dan arah yang tidak bisa dilalui
            if (dir == Direction.CENTER || !rc.canMove(dir))
                continue;

            // hitung lokasi baru jika bergerak ke arah ini dan skor berdasarkan tile di sekitarnya
            MapLocation new_location = current_location.add(dir);
            int score = 0;

            for (MapInfo tile : nearby_tiles) {
                // hitung skor untuk tile di sekitar lokasi baru
                PaintType pt = tile.getPaint();
                int current_distance = current_location.distanceSquaredTo(tile.getMapLocation());
                int new_distance = new_location.distanceSquaredTo(tile.getMapLocation());

                // prioritaskan bergerak ke arah yang mendekati tile musuh atau kosong dan menjauh dari tile sekutu
                if (new_distance < current_distance) {
                    if (pt == PaintType.ENEMY_PRIMARY || pt == PaintType.ENEMY_SECONDARY) {
                        score += 10;
                    } else if (pt == PaintType.EMPTY) {
                        score += 3;
                    }
                }
            }

            // beri penalti jika langkah ini membawa lebih dekat ke tile musuh untuk menghindari musuh
            if (rc.canSenseLocation(new_location)) {
                PaintType next_tile_paint = rc.senseMapInfo(new_location).getPaint();
                if (next_tile_paint == PaintType.ENEMY_PRIMARY || next_tile_paint == PaintType.ENEMY_SECONDARY) {
                    score -= 5;
                }
            }

            // update langkah terbaik jika skor lebih tinggi
            if (score > best_move_score) {
                best_move_score = score;
                best_move_direction = dir;
            }
        }

        // bergerak ke arah terbaik jika ditemukan
        if (best_move_direction != null) {
            rc.move(best_move_direction);
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