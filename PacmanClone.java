// IJ Masters
// 2024

import javax.swing.*;
import javax.sound.sampled.*;
import java.awt.*;
import java.awt.event.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.awt.geom.Arc2D;
import java.awt.geom.GeneralPath;

/**
 * PacmanClone.java
 * Single-file simple Pac-Man-like clone using Swing.
 *
 * Compile: javac PacmanClone.java
 * Run:     java PacmanClone
 *
 * No external assets required.
 */
public class PacmanClone extends JPanel implements ActionListener, KeyListener {
    // --- Configuration ---
    static final int TILE = 24;
    static final int GRID_W = 28;
    static final int GRID_H = 31;
    static final int SCREEN_W = TILE * GRID_W;
    static final int SCREEN_H = TILE * GRID_H;
    static final int FPS = 60;
    static final double PAC_SPEED = 125.0;   // pixels/sec
    static final double GHOST_SPEED = 85.0;
    static final double GHOST_SPEED_VULN = 50.0;
    static final double POWER_TIME = 8.0;    // seconds

    // Colors
    static final Color NAVY = new Color(10, 10, 40);
    static final Color WALL_COLOR = new Color(0, 0, 150);
    static final Color PELLET_COLOR = new Color(200, 200, 200);
    static final Color POWER_COLOR = new Color(255, 100, 100);
    static final Color[] GHOST_COLORS = {
            new Color(255,0,0), new Color(255,184,255),
            new Color(0,255,255), new Color(255,184,82)
    };
    static final Color VULN_COLOR = new Color(50,50,200);

    // Simplified map (28x31) - '#' wall, '.' pellet, 'o' power, ' ' empty, 'G' ghost start, 'P' pac start
    static final String[] RAW_MAP = new String[] {
            "############################",
            "#............##............#",
            "#.####.#####.##.#####.####.#",
            "#o####.#####.##.#####.####o#",
            "#.####.#####.##.#####.####.#",
            "#..........................#",
            "#.####.##.########.##.####.#",
            "#.####.##.########.##.####.#",
            "#......##....##....##......#",
            "######.##### ## #####.######",
            "     #.##### ## #####.#     ",
            "     #.##          ##.#     ",
            "     #.## ###--### ##.#     ",
            "######.## #      # ##.######",
            "      .   # G  G #   .      ",
            "######.## #      # ##.######",
            "     #.## ######## ##.#     ",
            "     #.##          ##.#     ",
            "     #.## ######## ##.#     ",
            "######.## ######## ##.######",
            "#............##............#",
            "#.####.#####.##.#####.####.#",
            "#o..##................##..o#",
            "###.##.##.########.##.##.###",
            "#......##....##....##......#",
            "#.##########.##.##########.#",
            "#.##########.##.##########.#",
            "#..........................#",
            "############################",
            "                            ",
            "                            ",
            "                            "
    };

    // --- Game state containers ---
    private boolean[][] walls = new boolean[GRID_W][GRID_H];
    private boolean[][] pellets = new boolean[GRID_W][GRID_H];
    private boolean[][] powers = new boolean[GRID_W][GRID_H];
    private final boolean[] tunnelRows = new boolean[GRID_H];
    private List<Point> ghostStarts = new ArrayList<>();
    private Point pacStart = null;

    // Entities
    class Entity {
        double x, y;           // center pixels
        String dir = null;    // "L","R","U","D"
        String req = null;
        double speed;
        int radius;
        Entity(double cx, double cy, double spd, int rad) {
            x = cx; y = cy; speed = spd; radius = rad;
        }
        Point tile() { return new Point(tileIndex(x), tileIndex(y)); }
        boolean atCenter() {
            Point t = tile();
            if (!inBounds(t.x, t.y)) {
                return false;
            }
            Point c = gridCenter(t.x, t.y);
            return Math.abs(x - c.x) < 3 && Math.abs(y - c.y) < 3;
        }
    }

    class Pacman extends Entity {
        double mouth = 0.0;
        int mouthDir = 1;
        int lives = 3;
        int score = 0;
        double poweredUntil = 0.0;
        String facing = "R";
        Pacman(double cx, double cy){ super(cx, cy, PAC_SPEED, TILE/2-2); }
        boolean isPowered(){ return System.currentTimeMillis()/1000.0 < poweredUntil; }
    }

    class Ghost extends Entity {
        enum Difficulty {
            EASY(70.0, 45.0, 0.35, 0.20, 0),
            NORMAL(GHOST_SPEED, GHOST_SPEED_VULN, 0.70, 0.08, 1),
            PRECISE(98.0, 55.0, 0.90, 0.01, 3),
            INSANE(120.0, 60.0, 1.0, 0.0, 6);

            final double baseSpeed;
            final double vulnSpeed;
            final double chaseBias;
            final double randomTurnChance;
            final int predictionTiles;

            Difficulty(double baseSpeed, double vulnSpeed, double chaseBias, double randomTurnChance, int predictionTiles) {
                this.baseSpeed = baseSpeed;
                this.vulnSpeed = vulnSpeed;
                this.chaseBias = chaseBias;
                this.randomTurnChance = randomTurnChance;
                this.predictionTiles = predictionTiles;
            }
        }

        Color color;
        boolean vulnerable = false;
        double vulnEnd = 0.0;
        boolean alive = true;
        double respawnAt = 0.0;
        Point homeTile;
        final Difficulty difficulty;
        final double baseSpeed;
        final double vulnSpeed;
        final Random rnd = new Random();
        double releaseAt = 0.0;
        boolean inHouse = true;
        double bouncePhase = rnd.nextDouble() * Math.PI * 2;
        Point homeCenter;

        Ghost(double cx, double cy, Color col, Point home, Difficulty diff) {
            super(cx, cy, diff.baseSpeed, TILE/2-2);
            color = col;
            homeTile = home;
            difficulty = diff;
            baseSpeed = diff.baseSpeed;
            vulnSpeed = diff.vulnSpeed;
            dir = randomDir();
            homeCenter = new Point((int)cx, (int)cy);
        }

        String randomDir(){
            String[] D = {"L","R","U","D"};
            return D[rnd.nextInt(D.length)];
        }
    }

    private Pacman pac;
    private List<Ghost> ghosts = new ArrayList<>();
    private final ExecutorService ghostExecutor = Executors.newFixedThreadPool(Math.max(2, GHOST_COLORS.length));
    private double nextHouseReleaseTime;
    private int releasesSinceReset = 0;
    private int ghostCount = 0;
    private static final double INITIAL_RELEASE_DELAY = 0.5;
    private static final double INITIAL_RELEASE_GAP = 1.0;
    private static final double STANDARD_RELEASE_GAP = 3.0;

    // Timer and loop
    private Timer timer;
    private long lastTime;
    private boolean paused = false;
    private boolean gameOver = false;

    public PacmanClone() {

        setPreferredSize(new Dimension(SCREEN_W, SCREEN_H));
        setFocusable(true);
        addKeyListener(this);
        parseMap();
        initEntities();
        lastTime = System.currentTimeMillis();
        timer = new Timer(1000 / FPS, this);
        timer.start();
        
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            ghostExecutor.shutdownNow();
            SoundManager.shutdown();
        }));
    }

    private void parseMap(){

        for (int y=0;y<GRID_H;y++){

            String row = RAW_MAP[y];

            for (int x=0;x<GRID_W;x++){
                char ch = (x < row.length()) ? row.charAt(x) : ' ';
                if (ch == '#'){
                    walls[x][y] = true;
                } else if (ch == '.'){
                    pellets[x][y] = true;

                } else if (ch == 'o'){
                    powers[x][y] = true;

                } else if (ch == 'G') {
                    ghostStarts.add(new Point(x,y));

                } else if (ch == 'P'){
                    pacStart = new Point(x,y);
                }

                if (y >= GRID_H - 2) {
                    walls[x][y] = true;
                    pellets[x][y] = false;
                    powers[x][y] = false;
                }
            }

            boolean leftTunnel = y < GRID_H - 2 && !walls[0][y] && !walls[1][y];
            boolean rightTunnel = y < GRID_H - 2 && !walls[GRID_W - 1][y] && !walls[GRID_W - 2][y];
            tunnelRows[y] = leftTunnel || rightTunnel;
        }
    }

    private boolean hasGhostStart(int x, int y) {

        for (Point p : ghostStarts) {

            if (p.x == x && p.y == y) {
                return true;
            }
        }
        return false;
    }

    private void ensureGhostStarts(){

        if (ghostStarts.size() >= 4){
            return;
        }

        Point center = new Point(GRID_W/2, GRID_H/2);

        int[][] offsets = {
                {-1, 0}, {1, 0}, {0, -1}, {0, 1},
                {-1, -1}, {1, -1}, {-1, 1}, {1, 1},
                {0, 0}
        };

        for (int[] off : offsets) {
            int nx = center.x + off[0];
            int ny = center.y + off[1];
            if (!inBounds(nx, ny)) continue;
            if (isWall(nx, ny)) continue;
            if (!hasGhostStart(nx, ny)) {
                ghostStarts.add(new Point(nx, ny));
            }

            if (ghostStarts.size() >= 4){
                break;
            }
        }
    }

    private Ghost.Difficulty difficultyForIndex(int idx) {
        switch (idx % 4) {
            case 0:
                return Ghost.Difficulty.INSANE; // red - relentless chaser
            case 1:
                return Ghost.Difficulty.NORMAL; // pink - balanced
            case 2:
                return Ghost.Difficulty.PRECISE; // blue - minimal randomness
            default:
                return Ghost.Difficulty.EASY; // orange - more erratic
        }
    }

    private void initEntities(){

        Point p = (pacStart != null) ? pacStart : new Point(GRID_W/2, GRID_H-5);
        Point c = gridCenterPoint(p.x, p.y);
        pac = new Pacman(c.x, c.y);

        if (ghostStarts.isEmpty()){

            Point center = new Point(GRID_W/2, GRID_H/2);
            ghostStarts.add(new Point(center.x-1, center.y));
            ghostStarts.add(new Point(center.x+1, center.y));
            ghostStarts.add(new Point(center.x, center.y-1));
            ghostStarts.add(new Point(center.x, center.y+1));
        }

        ensureGhostStarts();
        ghosts.clear();
        double now = System.currentTimeMillis() / 1000.0;
        ghostCount = Math.min(4, ghostStarts.size());
        releasesSinceReset = 0;
        nextHouseReleaseTime = now + INITIAL_RELEASE_DELAY;

        for (int i=0;i<ghostCount; i++){

            Point g = ghostStarts.get(i);
            Point gc = gridCenterPoint(g.x, g.y);
            Ghost.Difficulty diff = difficultyForIndex(i);
            Ghost ghost = new Ghost(gc.x, gc.y, GHOST_COLORS[i % GHOST_COLORS.length], g, diff);
            ghost.inHouse = true;
            ghost.dir = "U";
            ghost.x = ghost.homeCenter.x;
            ghost.y = ghost.homeCenter.y;
            scheduleGhostRelease(ghost, now);
            ghosts.add(ghost);
        }

        // If no pellets present (map didn't include them), fill corridors
        boolean hasPellets = false;
        for (int y=0;y<GRID_H;y++) for (int x=0;x<GRID_W;x++) if (pellets[x][y] || powers[x][y]) hasPellets = true;
        if (!hasPellets) {
            Random r = new Random(123);
            for (int y=0;y<GRID_H;y++) for (int x=0;x<GRID_W;x++){
                if (!walls[x][y] && r.nextDouble() < 0.9) pellets[x][y] = true;
            }

            // corners power
            int[][] corners = {{1,1},{GRID_W-2,1},{1,GRID_H-2},{GRID_W-2, GRID_H-2}};
            for (int[] cxy: corners) if (!walls[cxy[0]][cxy[1]]) powers[cxy[0]][cxy[1]] = true;
        }
    }

    private Point gridCenterPoint(int tx, int ty){
        return new Point(tx * TILE + TILE/2, ty * TILE + TILE/2);
    }

    private Point gridCenter(int tx, int ty){ return gridCenterPoint(tx,ty); }

    private int tileIndex(double coord){
        return (int)Math.floor(coord / TILE);
    }

    private boolean isTunnelRow(int ty){
        return ty >= 0 && ty < tunnelRows.length && tunnelRows[ty];
    }

    // --- Game loop tick ---
    public void actionPerformed(ActionEvent e){
        long now = System.currentTimeMillis();
        double dt = (now - lastTime) / 1000.0;
        lastTime = now;
        if (!paused && !gameOver) updateGame(dt);
        repaint();
    }

    private boolean inBounds(int tx, int ty){
        return tx>=0 && tx<GRID_W && ty>=0 && ty<GRID_H;
    }

    private boolean isWall(int tx, int ty){
        if (!inBounds(tx,ty)){
            return false;
        }
        return walls[tx][ty];
    }

    private void updateGame(double dt){

        // pac movement
        updatePac(dt);

        // pellets collection
        Point pt = pac.tile();

        if (inBounds(pt.x, pt.y)) {
            Point pc = gridCenter(pt.x, pt.y);
            if (Math.abs(pac.x - pc.x) < 6 && Math.abs(pac.y - pc.y) < 6) {
                if (pellets[pt.x][pt.y]) {
                    pellets[pt.x][pt.y] = false; pac.score += 10;
                    SoundManager.play(SoundManager.Effect.PELLET);
                } else if (powers[pt.x][pt.y]) {
                    powers[pt.x][pt.y] = false; pac.score += 50;
                    pac.poweredUntil = System.currentTimeMillis()/1000.0 + POWER_TIME;
                    for (Ghost g: ghosts) {
                        g.vulnerable = true; g.vulnEnd = pac.poweredUntil; g.speed = g.vulnSpeed;
                    }
                    SoundManager.play(SoundManager.Effect.POWER);
                }
            }
        }

        // ghost updates
        List<Callable<Void>> tasks = new ArrayList<>();

        for (Ghost g: ghosts) {
            tasks.add(() -> {
                updateGhost(g, dt);
                return null;
            });
        }
        try {
            ghostExecutor.invokeAll(tasks);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        // collisions
        for (Ghost g: ghosts){

            if (!g.alive){
                continue;
            }

            double dx = pac.x - g.x, dy = pac.y - g.y;
            double dist = Math.hypot(dx,dy);

            if (dist < (pac.radius + g.radius)*0.7){
                if (g.vulnerable){
                    // eat ghost
                    g.alive = false; g.respawnAt = System.currentTimeMillis()/1000.0 + 4.0;
                    pac.score += 200;
                    SoundManager.play(SoundManager.Effect.GHOST_EAT);
                } else {
                    // pac dies
                    pac.lives--;
                    SoundManager.play(SoundManager.Effect.PAC_DIE);
                    if (pac.lives <= 0) gameOver = true;
                    else resetPositions();
                }
            }
        }

        // victory if no pellets
        boolean anyPel = false;

        for (int y=0;y<GRID_H;y++){

            for (int x=0;x<GRID_W;x++){

                if (pellets[x][y] || powers[x][y]){
                    anyPel = true;
                }
            }
        }

        if (!anyPel){
            gameOver = true;
        }

        // end vulnerability
        if (!pac.isPowered()) {
            for (Ghost g: ghosts) {
                if (g.vulnerable && System.currentTimeMillis()/1000.0 >= g.vulnEnd) {
                    g.vulnerable = false; g.speed = g.baseSpeed;
                }
            }
        }
    }

    private void updatePac(double dt){

        // animate mouth
        pac.mouth += dt * 4 * pac.mouthDir;
        if (pac.mouth > 1) {
            pac.mouth = 1; pac.mouthDir = -1;
        }

        if (pac.mouth < 0) {
            pac.mouth = 0; pac.mouthDir = 1;
        }

        // try to turn if requested and possible
        if (pac.req != null){
            if ((pac.dir == null || pac.atCenter()) && !collisionInDir(pac, pac.req)){
                pac.dir = pac.req;
                pac.req = null;
            }
        }
        // move in dir
        if (pac.dir != null){
            if (!collisionInDir(pac, pac.dir)){
                moveEntity(pac, pac.dir, pac.speed, dt);
                pac.facing = pac.dir;
            } else if (pac.atCenter()) {
                pac.dir = null;
            } else {
                Point t = pac.tile();
                if (inBounds(t.x, t.y)) {
                    Point c = gridCenter(t.x,t.y);
                    pac.x = c.x; pac.y = c.y;
                }
                pac.dir = null;
            }
        }
    }

    private void updateGhost(Ghost g, double dt){

        double now = System.currentTimeMillis()/1000.0;

        if (!g.alive){
            if (now >= g.respawnAt) {
                g.alive = true;
                Point c = gridCenterPoint(g.homeTile.x, g.homeTile.y);
                g.x = c.x; g.y = c.y;
                g.homeCenter = new Point(c.x, c.y);
                g.dir = "U";
                g.vulnerable = false;
                g.vulnEnd = 0.0;
                g.speed = g.baseSpeed;
                g.inHouse = true;
                scheduleGhostRelease(g, now + 3.0);
            } else return;
        }

        if (g.inHouse) {
            g.x = g.homeCenter.x + Math.sin(now * 2 + g.bouncePhase) * 4;
            g.y = g.homeCenter.y + Math.cos(now * 3 + g.bouncePhase) * 1.5;
            if (now >= g.releaseAt) {
                g.inHouse = false;
                g.dir = "U";
                g.x = g.homeCenter.x;
                g.y = g.homeCenter.y - g.radius;
            } else {
                return;
            }
        }

        // vulnerability timer
        if (g.vulnerable && now >= g.vulnEnd){
            g.vulnerable = false; g.speed = g.baseSpeed;
        }
        g.speed = g.vulnerable ? g.vulnSpeed : g.baseSpeed;

        if (g.atCenter()){
            Point t = g.tile();
            List<String> choices = new ArrayList<>();
            String[] D = {"L","R","U","D"};

            for (String d: D){
                int nx = t.x + dx(d), ny = t.y + dy(d);
                if (!isWall(nx, ny)) choices.add(d);
            }
            if (!choices.isEmpty()){
                if (choices.size() > 1 && opposite(g.dir) != null && choices.contains(opposite(g.dir))){
                    choices.remove(opposite(g.dir));
                }
                String selected = choices.get(g.rnd.nextInt(choices.size()));
                if (g.vulnerable) {
                    selected = chooseByDistance(choices, t, pac.tile(), true);
                } else {
                    Point target = pac.tile();
                    if (g.difficulty.predictionTiles > 0) {
                        target = pacFutureTile(g.difficulty.predictionTiles);
                    }
                    if (g.difficulty == Ghost.Difficulty.INSANE) {
                        target = smartRedTarget();
                    }
                    if (g.rnd.nextDouble() < g.difficulty.chaseBias) {
                        selected = chooseByDistance(choices, t, target, false);
                    }
                }
                if (g.rnd.nextDouble() < g.difficulty.randomTurnChance) {
                    selected = choices.get(g.rnd.nextInt(choices.size()));
                }
                g.dir = selected;
            }
        }

        if (g.rnd.nextDouble() < g.difficulty.randomTurnChance * 0.5){
            g.dir = g.randomDir();
        }

        // move
        if (g.dir != null && !collisionInDir(g, g.dir)){
            moveEntity(g, g.dir, g.speed, dt);
        } else {
            // align if blocked
            if (g.dir != null && collisionInDir(g, g.dir)){
                Point t = g.tile();
                if (inBounds(t.x, t.y)) {
                    Point c = gridCenter(t.x,t.y);
                    g.x = c.x; g.y = c.y;
                }
                g.dir = g.randomDir();
            }
        }
    }

    private void resetPositions(){

        Point p = (pacStart != null) ? pacStart : new Point(GRID_W/2, GRID_H-5);
        Point pc = gridCenterPoint(p.x,p.y);
        pac.x = pc.x; pac.y = pc.y; pac.dir = null; pac.req = null;
        pac.facing = "R";
        double now = System.currentTimeMillis() / 1000.0;
        releasesSinceReset = 0;
        ghostCount = ghosts.size();
        nextHouseReleaseTime = now + INITIAL_RELEASE_DELAY;

        for (Ghost g: ghosts){
            Point gc = gridCenterPoint(g.homeTile.x, g.homeTile.y);
            g.x = gc.x; g.y = gc.y;
            g.homeCenter = new Point(gc.x, gc.y);
            g.dir = "U";
            g.alive = true; g.vulnerable = false;
            g.vulnEnd = 0.0; g.speed = g.baseSpeed;
            g.inHouse = true;
            scheduleGhostRelease(g, now);
        }
    }

    private synchronized void scheduleGhostRelease(Ghost ghost, double earliest){

        int releaseCap = ghostCount <= 0 ? 4 : ghostCount;
        double gap = releasesSinceReset < releaseCap ? INITIAL_RELEASE_GAP : STANDARD_RELEASE_GAP;
        double releaseTime = Math.max(nextHouseReleaseTime, earliest);
        ghost.releaseAt = releaseTime;
        ghost.inHouse = true;
        nextHouseReleaseTime = releaseTime + gap;
        releasesSinceReset++;
    }

    private String chooseByDistance(List<String> options, Point from, Point target, boolean farthest){

        String choice = options.get(0);
        double best = farthest ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;

        for (String dir : options){

            int nx = from.x + dx(dir);
            int ny = from.y + dy(dir);
            double dist = Math.hypot(target.x - nx, target.y - ny);

            if (farthest){
                if (dist > best){
                    best = dist;
                    choice = dir;
                }
            } else {
                if (dist < best){
                    best = dist;
                    choice = dir;
                }
            }
        }
        return choice;
    }

    private Point pacFutureTile(int steps){

        Point tile = pac.tile();
        String d = pac.dir != null ? pac.dir : pac.req;

        if (d == null){
            return tile;
        }

        int tx = tile.x;
        int ty = tile.y;

        for (int i=0; i<steps; i++){
            int nx = tx + dx(d);
            int ny = ty + dy(d);

            if (!inBounds(nx, ny) || isWall(nx, ny)){
                break;
            }
            tx = nx;
            ty = ny;
        }
        tx = Math.max(0, Math.min(GRID_W-1, tx));
        ty = Math.max(0, Math.min(GRID_H-1, ty));

        return new Point(tx, ty);
    }

    private Point advanceUntilWall(Point start, String dir, int steps) {

        if (dir == null){
            return new Point(start);
        }

        int tx = start.x;
        int ty = start.y;
        for (int i = 0; i < steps; i++) {
            int nx = tx + dx(dir);
            int ny = ty + dy(dir);
            if (!inBounds(nx, ny) || isWall(nx, ny)) break;
            tx = nx;
            ty = ny;
        }
        return new Point(tx, ty);
    }

    private Point smartRedTarget() {

        Point predicted = pacFutureTile(Ghost.Difficulty.INSANE.predictionTiles);
        String heading = pac.dir != null ? pac.dir : pac.facing;
        Point extended = advanceUntilWall(predicted, heading, 2);
        Point pacTile = pac.tile();
        int blendX = (int)Math.round((extended.x * 2 + pacTile.x) / 3.0);
        int blendY = (int)Math.round((extended.y * 2 + pacTile.y) / 3.0);
        blendX = Math.max(0, Math.min(GRID_W - 1, blendX));
        blendY = Math.max(0, Math.min(GRID_H - 1, blendY));

        return new Point(blendX, blendY);
    }

    // movement helpers
    private int dx(String d){
        if ("L".equals(d)){
            return -1;
        } if ("R".equals(d)){
            return 1;
        } return 0;
    }

    private int dy(String d){

        if ("U".equals(d)){
            return -1;
        } if ("D".equals(d)){
            return 1;
        } return 0;
    }
    private String opposite(String d){ if (d==null) return null; switch(d){ case "L": return "R"; case "R": return "L"; case "U": return "D"; case "D": return "U"; } return null; }

    private boolean collisionInDir(Entity e, String dir){

        if (dir == null){
            return false;
        }

        double look = TILE/2.0;
        double nx = e.x + dx(dir)*look;
        double ny = e.y + dy(dir)*look;
        int tx = tileIndex(nx), ty = tileIndex(ny);

        if (!inBounds(tx,ty)) {
            if ("L".equals(dir) || "R".equals(dir)) {
                int row = tileIndex(e.y);
                if (isTunnelRow(row)) {
                    return false;
                }
            }
            return true;
        }
        return isWall(tx,ty);
    }

    private void moveEntity(Entity e, String dir, double speed, double dt){
        e.x += dx(dir) * speed * dt;
        e.y += dy(dir) * speed * dt;

        // wrap tunnels if out of bounds
        if (e.x < -TILE / 2.0) {
            e.x = SCREEN_W - TILE / 2.0;
        } else if (e.x > SCREEN_W + TILE / 2.0) {
            e.x = TILE / 2.0;
        }

        e.y = Math.max(e.radius, Math.min(SCREEN_H - e.radius, e.y));
    }

    private static class SoundManager {
        enum Effect {
            PELLET(1200, 50),
            POWER(400, 220),
            GHOST_EAT(250, 260),
            PAC_DIE(180, 400);

            final double frequency;
            final int durationMs;

            Effect(double frequency, int durationMs) {
                this.frequency = frequency;
                this.durationMs = durationMs;
            }
        }

        private static final int SAMPLE_RATE = 16000;
        private static final ExecutorService SOUND_EXECUTOR = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "sound-exec");
            t.setDaemon(true);
            return t;
        });

        static void play(Effect effect) {
            if (effect == null || SOUND_EXECUTOR.isShutdown()) return;
            SOUND_EXECUTOR.submit(() -> playTone(effect.frequency, effect.durationMs));
        }

        private static void playTone(double frequency, int durationMs) {
            AudioFormat format = new AudioFormat(SAMPLE_RATE, 8, 1, true, true);
            int length = (int)(SAMPLE_RATE * (durationMs / 1000.0));

            if (length <= 0){
                return;
            }

            byte[] data = new byte[length];

            for (int i = 0; i < length; i++) {
                double angle = 2.0 * Math.PI * i * frequency / SAMPLE_RATE;
                double envelope = Math.min(1.0, 1.5 * (1 - (double)i / length));
                data[i] = (byte)(Math.sin(angle) * 120 * envelope);
            }
            SourceDataLine line = null;
            try {
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
                line = (SourceDataLine) AudioSystem.getLine(info);
                line.open(format);
                line.start();
                line.write(data, 0, data.length);
                line.drain();
            } catch (LineUnavailableException | IllegalArgumentException ex) {
                // ignore audio issues in headless environments
            } finally {
                if (line != null) {
                    line.stop();
                    line.close();
                }
            }
        }

        static void shutdown() {
            SOUND_EXECUTOR.shutdownNow();
            try {
                SOUND_EXECUTOR.awaitTermination(200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    // --- Rendering ---
    protected void paintComponent(Graphics g0){
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0;
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_NORMALIZE);

        // background
        g.setColor(NAVY); g.fillRect(0,0,getWidth(),getHeight());

        // draw walls
        g.setColor(WALL_COLOR);
        for (int y=0;y<GRID_H;y++) for (int x=0;x<GRID_W;x++) if (walls[x][y]){
            g.fillRect(x*TILE, y*TILE, TILE, TILE);
        }

        // pellets & powers
        for (int y=0;y<GRID_H;y++) for (int x=0;x<GRID_W;x++){
            if (pellets[x][y]){
                Point c = gridCenterPoint(x,y);
                g.setColor(PELLET_COLOR);
                g.fillOval(c.x-3, c.y-3, 6, 6);
            } else if (powers[x][y]){
                Point c = gridCenterPoint(x,y);
                g.setColor(POWER_COLOR);
                g.fillOval(c.x-6, c.y-6, 12, 12);
            }
        }

        // ghosts
        for (Ghost gh: ghosts) {

            if (!gh.alive){
                continue;
            }

            Color bodyColor = gh.vulnerable ? VULN_COLOR : gh.color;
            int cx = (int)gh.x, cy = (int)gh.y, r = gh.radius;
            int top = cy - r;
            int left = cx - r;
            int width = r * 2;
            GeneralPath body = new GeneralPath();
            body.moveTo(left, cy);
            body.quadTo(left, top, cx, top);
            body.quadTo(cx + r, top, cx + r, cy);
            int scallops = 4;
            double step = (double) width / scallops;
            for (int i = scallops; i >= 0; i--) {
                double px = left + i * step;
                double py = cy + (i % 2 == 0 ? r : r - r / 2.0);
                body.lineTo(px, py);
            }
            body.closePath();
            g.setColor(bodyColor);
            g.fill(body);
            g.setColor(bodyColor.brighter());
            g.fillOval(cx - r + 4, top + 4, r, r);
            g.setColor(new Color(0,0,0,120));
            g.fillOval(cx - r + 6, top + r, r/2, r/2);
            Stroke oldStroke = g.getStroke();
            g.setStroke(new BasicStroke(2f));
            g.setColor(bodyColor.darker());
            g.draw(body);
            g.setStroke(oldStroke);

            // eyes
            g.setColor(Color.WHITE);

            int ex = Math.max(6, r/2);

            g.fillOval(cx - ex - 4, cy - ex/2, ex, ex);
            g.fillOval(cx + 4, cy - ex/2, ex, ex);
            g.setColor(Color.BLACK);

            int pup = Math.max(2, r/6);
            int ox=0, oy=0;

            if ("L".equals(gh.dir)){
                ox=-3;
            }

            if ("R".equals(gh.dir)){
                ox=3;
            }

            if ("U".equals(gh.dir)){
                oy=-3;
            }

            if ("D".equals(gh.dir)){
                oy=3;
            }

            g.fillOval(cx - ex - 4 + ox, cy - ex/2 + oy, pup, pup);
            g.fillOval(cx + 4 + ox, cy - ex/2 + oy, pup, pup);
        }
        // Pacman (draw as arc)
        int pcx = (int)pac.x, pcy=(int)pac.y, pr = pac.radius;
        String face = pac.dir != null ? pac.dir : pac.facing;
        double angle = 0;

        if ("L".equals(face)){
            angle = 180;
        }
        else if ("R".equals(face)){
            angle = 0;
        }
        else if ("U".equals(face)){
            angle = 90;
        }
        else if ("D".equals(face)){
            angle = 270;
        }

        double open = 20 + 30 * pac.mouth;
        double start = angle - open;
        double extent = 360 - open*2;
        g.setColor(Color.YELLOW);
        Arc2D.Double arc = new Arc2D.Double(pcx-pr, pcy-pr, pr*2, pr*2, start, extent, Arc2D.PIE);
        g.fill(arc);
        g.setColor(Color.BLACK);
        g.drawOval(pcx-pr, pcy-pr, pr*2, pr*2);

        // HUD
        g.setColor(Color.WHITE);
        g.setFont(new Font("Arial", Font.PLAIN, 16));
        g.drawString("Score: " + pac.score, 8, 18);
        g.drawString("Lives: " + pac.lives, SCREEN_W - 100, 18);

        if (paused){
            g.setFont(new Font("Arial", Font.BOLD, 36));
            g.drawString("PAUSED", SCREEN_W/2 - 70, SCREEN_H/2);
        }

        if (gameOver){
            g.setFont(new Font("Arial", Font.BOLD, 36));
            String msg = pac.lives<=0 ? "GAME OVER" : "YOU WIN!";
            g.drawString(msg, SCREEN_W/2 - 110, SCREEN_H/2);
        }
    }

    // --- Input handling ---
    public void keyPressed(KeyEvent e){

        int k = e.getKeyCode();

        if (k == KeyEvent.VK_ESCAPE){
            System.exit(0);
        }

        if (k == KeyEvent.VK_P){
            paused = !paused;
        }

        String d = null;

        if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A){
            d = "L";
        }

        if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D){
            d = "R";
        }

        if (k == KeyEvent.VK_UP || k == KeyEvent.VK_W){
            d = "U";
        }

        if (k == KeyEvent.VK_DOWN || k == KeyEvent.VK_S){
            d = "D";
        }

        if (d != null){
            pac.req = d;
        }
    }

    public void keyReleased(KeyEvent e){

    }

    public void keyTyped(KeyEvent e){

    }

    // --- Main ---
    public static void main(String[] args){

        JFrame frame = new JFrame("Pac-Clone (Java)");
        PacmanClone panel = new PacmanClone();
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setResizable(false);
        frame.add(panel);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
