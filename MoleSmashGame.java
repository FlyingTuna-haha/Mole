import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.io.File;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.Socket;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class MoleSmashGame extends JPanel implements ActionListener, MouseMotionListener, MouseListener {
    private static final int W = 1000, H = 700;
    private final Timer timer = new Timer(16, this);
    private final Random random = new Random();
    private final Player player = new Player("Player 1");
    private final List<Hole> holes = new ArrayList<>();
    private BufferedImage holeImg, normalImg, goldImg, bombImg, hitNormalImg, hitGoldImg, hammerImg, hammerHitImg;
    private long endTime, nextSpawn;
    private int mouseX = 500, mouseY = 350;
    private boolean playing = false, hammerDown = false;
    // Online multiplayer state: the server is the source of truth.
    private Socket socket;
    private PrintWriter serverOut;
    private boolean connected = false;
    private final int[] networkMoles = new int[12]; // 0 empty, 1 normal, 2 gold, 3 bomb
    private int sharedSeconds = 60;
    private int playerId = 0;
    private final int[] playerScores = new int[4];
    private final int[] otherMouseX = {500, 500, 500, 500};
    private final int[] otherMouseY = {350, 350, 350, 350};
    private final boolean[] otherConnected = new boolean[4];
    private long lastMoveSent = 0;

    public MoleSmashGame(String serverHost) {
        setPreferredSize(new Dimension(W, H));
        setBackground(new Color(42, 74, 62));
        addMouseMotionListener(this);
        addMouseListener(this);
        loadImages();
        connectToServer(serverHost);
        // 12 holes in a regular 4 x 3 grid
        int[] xs = {150, 383, 616, 849};
        int[] ys = {260, 420, 580};
        for (int y : ys) for (int x : xs) holes.add(new Hole(x, y));
        timer.start();
    }

    private void connectToServer(String host) {
        try {
            socket = new Socket(host, 5000);
            serverOut = new PrintWriter(socket.getOutputStream(), true);
            connected = true;
            Thread reader = new Thread(() -> {
                try {
                    BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                    String line;
                    while ((line = in.readLine()) != null) {
                        if (line.startsWith("WELCOME:")) {
                            playerId = Integer.parseInt(line.substring(8)) - 1;
                            SwingUtilities.invokeLater(() -> repaint());
                        } else if ("FULL".equals(line)) {
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this, "The game already has 4 players."));
                            break;
                        } else if (line.startsWith("STATE|")) {
                            String[] parts = line.split("\\|");
                            if (parts.length >= 6) {
                                boolean serverPlaying = Boolean.parseBoolean(parts[1]);
                                int seconds = Integer.parseInt(parts[2]);
                                String[] scoreParts = parts[3].split(",");
                                String[] moleParts = parts[4].split(",");
                                String[] cursorParts = parts[5].split(";");
                                SwingUtilities.invokeLater(() -> {
                                    playing = serverPlaying;
                                    sharedSeconds = seconds;
                                    for (int i = 0; i < playerScores.length && i < scoreParts.length; i++) {
                                        try { playerScores[i] = Integer.parseInt(scoreParts[i]); }
                                        catch (NumberFormatException ignored) { playerScores[i] = 0; }
                                    }
                                    for (int i = 0; i < networkMoles.length && i < moleParts.length; i++) {
                                        try { networkMoles[i] = Integer.parseInt(moleParts[i]); }
                                        catch (NumberFormatException ignored) { networkMoles[i] = 0; }
                                    }
                                    for (int i = 0; i < otherConnected.length && i < cursorParts.length; i++) {
                                        try {
                                            String[] xy = cursorParts[i].split(",");
                                            otherMouseX[i] = Integer.parseInt(xy[0]);
                                            otherMouseY[i] = Integer.parseInt(xy[1]);
                                            otherConnected[i] = Integer.parseInt(xy[2]) == 1;
                                        } catch (Exception ignored) { otherConnected[i] = false; }
                                    }
                                    repaint();
                                });
                            }
                        }
                    }
                } catch (IOException ex) {
                    SwingUtilities.invokeLater(() -> {
                        connected = false;
                        playing = false;
                        repaint();
                        JOptionPane.showMessageDialog(this, "Disconnected from server.");
                    });
                }
            }, "MoleSmash-NetworkReader");
            reader.setDaemon(true);
            reader.start();
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this,
                "Cannot connect to server at " + host + ":5000\nStart GameServer.java first and check the IP address.",
                "Connection failed", JOptionPane.ERROR_MESSAGE);
            connected = false;
        }
    }

    private void sendToServer(String message) {
        if (serverOut != null && connected) serverOut.println(message);
    }

    private void loadImages() {
        holeImg = loadCropped("hole.png");
        normalImg = loadCropped("NorMole.png");
        goldImg = loadCropped("goldMole.png");
        hitNormalImg = loadCropped("hitNorMole.png");
        hitGoldImg = loadCropped("hitGoldMole.png");
        hammerImg = loadCropped("hammer.png");
        hammerHitImg = loadCropped("hammerHit.png");
        bombImg = loadCropped("boom.png");
    }

    // The supplied PNGs have large transparent margins. Crop them so the artwork fills its display area.
    private BufferedImage loadCropped(String name) {
        try {
            File file = new File("pic/" + name);
            if (!file.exists()) file = new File("assets/" + name); // supports the earlier folder name too
            BufferedImage source = ImageIO.read(file);
            if (source == null) return null;
            int minX = source.getWidth(), minY = source.getHeight(), maxX = -1, maxY = -1;
            for (int y = 0; y < source.getHeight(); y++) {
                for (int x = 0; x < source.getWidth(); x++) {
                    if ((source.getRGB(x, y) >>> 24) != 0) {
                        if (x < minX) minX = x;
                        if (x > maxX) maxX = x;
                        if (y < minY) minY = y;
                        if (y > maxY) maxY = y;
                    }
                }
            }
            if (maxX < minX || maxY < minY) return source;
            return source.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
        } catch (Exception e) {
            System.err.println("Cannot load image: " + name + " (check the pic folder)");
            return null;
        }
    }

    private void startGame() {
        if (connected) {
            sendToServer("START");
            requestFocusInWindow();
            return;
        }
        requestFocusInWindow();
    }

    @Override public void actionPerformed(ActionEvent e) {
        if (connected) {
            repaint();
            return;
        }
        long now = System.currentTimeMillis();
        if (playing) {
            if (now >= endTime) {
                playing = false;
                for (Hole h : holes) h.mole = null;
            } else {
                if (now >= nextSpawn) {
                    // Keep several moles visible at once, rather than only one.
                    spawnMole(now);
                    if (random.nextBoolean()) spawnMole(now);
                    nextSpawn = now + 350 + random.nextInt(300);
                }
                for (Hole h : holes) {
                    if (h.mole != null && now - h.mole.spawnTime > h.mole.lifetime) h.mole = null;
                }
            }
        }
        repaint();
    }

    private void spawnMole(long now) {
        List<Hole> free = new ArrayList<>();
        for (Hole h : holes) if (h.mole == null) free.add(h);
        if (free.isEmpty()) return;
        Hole h = free.get(random.nextInt(free.size()));
        int r = random.nextInt(100);
        if (r < 12) h.mole = new BombMole(now);
        else if (r < 32) h.mole = new GoldenMole(now);
        else h.mole = new NormalMole(now);
    }

    @Override protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);

        // Grassy field with more decorative details
        g2.setColor(new Color(63, 105, 75)); g2.fillRect(0, 0, W, H);
        g2.setColor(new Color(77, 122, 82));
        for (int i = 0; i < 30; i++) {
            int x = (i * 137 + 35) % W, y = (i * 83 + 135) % H;
            g2.fillOval(x, y, 55 + (i % 3) * 12, 25 + (i % 2) * 10);
        }

        // Scoreboard
        g2.setColor(new Color(35, 47, 39)); g2.fillRoundRect(22, 18, W - 44, 132, 24, 24);
        g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif", Font.BOLD, 25));
        g2.drawString("MOLE SMASH ONLINE", 42, 48);
        g2.setFont(new Font("SansSerif", Font.BOLD, 17));
        int seconds = connected ? sharedSeconds : (playing ? (int)Math.max(0, (endTime - System.currentTimeMillis() + 999) / 1000) : 60);
        g2.drawString("Time: " + seconds + "s", 815, 48);
        g2.setFont(new Font("SansSerif", Font.PLAIN, 13));
        g2.drawString("Normal +10   Gold +30   Bomb -20   |   First hit gets the mole!", 42, 68);
        int boxWidth = 210;
        for (int i = 0; i < 4; i++) {
            int bx = 42 + i * 230;
            g2.setColor(i == playerId ? new Color(255, 205, 70) : new Color(75, 93, 76));
            g2.fillRoundRect(bx, 82, boxWidth, 54, 12, 12);
            g2.setColor(i == playerId ? Color.BLACK : Color.WHITE);
            g2.setFont(new Font("SansSerif", Font.BOLD, 16));
            g2.drawString("Player " + (i + 1) + (i == playerId ? " (YOU)" : ""), bx + 12, 103);
            g2.setFont(new Font("SansSerif", Font.BOLD, 20));
            g2.drawString("Score: " + (connected ? playerScores[i] : (i == 0 ? player.score : 0)), bx + 12, 126);
        }

        // Draw all moles first, anchored low enough to overlap the hole opening.
        // Then draw the hole rims in front so each mole appears to rise out of its own hole.
        
        for (Hole h : holes) {
            drawImage(g2, holeImg, h.x - 66, h.y - 24, 132, 39);
        }
        
        for (int i = 0; i < holes.size(); i++) {
            Hole h = holes.get(i);
            if (connected) {
                BufferedImage img = null;

                if (networkMoles[i] == 5) {
                    img = hitGoldImg;
                } else if (networkMoles[i] == 4) {
                    img = hitNormalImg;
                } else if (networkMoles[i] == 3) {
                    img = bombImg;
                } else if (networkMoles[i] == 2) {
                    img = goldImg;
                } else if (networkMoles[i] == 1) {
                    img = normalImg;
                }

                if (networkMoles[i] == 4 || networkMoles[i] == 5) {
                    drawImage(g2, img, h.x - 58, h.y - 65, 116, 80);
                } else {
                    drawImage(g2, img, h.x - 58, h.y - 85, 116, 100);
                }
            } else if (h.mole != null) {
                if (h.mole instanceof BombMole) {
                    drawImage(g2, bombImg, h.x - 58, h.y - 85, 116, 100);
                } else {
                    BufferedImage img = h.mole instanceof GoldenMole
                            ? goldImg : normalImg;
                    drawImage(g2, img, h.x - 58, h.y - 85, 116, 100);
                }
            } else if (h.mole != null) {
                if (h.mole instanceof BombMole) drawImage(g2, bombImg, h.x - 58, h.y - 85, 116, 100);
                else {
                    BufferedImage img = h.mole instanceof GoldenMole ? goldImg : normalImg;
                    drawImage(g2, img, h.x - 58, h.y - 85, 116, 100);
                }
            }
        }

        // Show the other players' hammers/cursors at their latest shared positions.
        if (connected) {
            for (int i = 0; i < otherConnected.length; i++) {
                if (i == playerId || !otherConnected[i]) continue;
                int cx = otherMouseX[i], cy = otherMouseY[i];
                drawImage(g2, hammerImg, cx - 31, cy - 31, 62, 62);
                g2.setColor(Color.WHITE);
                g2.setFont(new Font("SansSerif", Font.BOLD, 12));
                g2.drawString("P" + (i + 1), cx + 18, cy - 18);
            }
        }

        if (!playing) {
            g2.setColor(new Color(0, 0, 0, 155)); g2.fillRoundRect(245, 245, 510, 190, 28, 28);
            g2.setColor(Color.WHITE); g2.setFont(new Font("SansSerif", Font.BOLD, 34));
            g2.drawString(!connected ? "Server not connected" : (playerScores[0] + playerScores[1] + playerScores[2] + playerScores[3] == 0 ? "Ready to play?" : "Game over!"), 345, 300);
            g2.setFont(new Font("SansSerif", Font.PLAIN, 20));
            g2.drawString(!connected ? "Check server and restart the client" : "Click to start the 4-player game", 340, 340);
            g2.setColor(new Color(255, 205, 70)); g2.fillRoundRect(390, 365, 220, 48, 16, 16);
            g2.setColor(Color.BLACK); g2.setFont(new Font("SansSerif", Font.BOLD, 20));
            g2.drawString("START / RESTART", 410, 396);
        }

        // Larger hammer; the transparent margins were removed when the image was loaded.
        drawImage(g2, hammerDown ? hammerHitImg : hammerImg, mouseX - 42, mouseY - 42, 84, 84);
        g2.dispose();
    }

    private void drawImage(Graphics2D g, BufferedImage img, int x, int y, int w, int h) {
        if (img != null) g.drawImage(img, x, y, w, h, null);
    }

    @Override public void mouseClicked(MouseEvent e) {
        if (!playing) {
            if (connected && e.getX() >= 480 && e.getX() <= 780
            && e.getY() >= 420 && e.getY() <= 510) {
            startGame();
            }
            return;
        }
        for (int i = 0; i < holes.size(); i++) {
            Hole h = holes.get(i);
            boolean hasMole = connected ? networkMoles[i] != 0 : h.mole != null;
            if (hasMole && Math.abs(e.getX() - h.x) < 80
                && e.getY() >= h.y - 140 && e.getY() <= h.y + 20) {
                if (connected) sendToServer("HIT:" + i);
                else {
                    player.score += h.mole.points;
                    h.mole = null;
                }
                break;
            }
        }
    }
    @Override
    public void mousePressed(MouseEvent e) {
        mouseX = e.getX();
        mouseY = e.getY();

        // Check if the START button was pressed
        if (!playing && connected
                && e.getX() >= 350 && e.getX() <= 650
                && e.getY() >= 340 && e.getY() <= 440) {
            sendToServer("START");
            repaint();
            return;
        }

        hammerDown = true;
        sendMousePosition();
        repaint();
    }
    @Override public void mouseReleased(MouseEvent e) { hammerDown = false; }
    @Override public void mouseMoved(MouseEvent e) { mouseX = e.getX(); mouseY = e.getY(); sendMousePosition(); }
    @Override public void mouseDragged(MouseEvent e) { mouseX = e.getX(); mouseY = e.getY(); sendMousePosition(); }
    private void sendMousePosition() {
        long now = System.currentTimeMillis();
        if (connected && now - lastMoveSent >= 35) {
            lastMoveSent = now;
            sendToServer("MOVE:" + mouseX + "," + mouseY);
        }
    }
    @Override public void mouseEntered(MouseEvent e) {}
    @Override public void mouseExited(MouseEvent e) {}

    private static class Player { private final String name; private int score; Player(String name) { this.name = name; } }
    private static class Hole { final int x, y; BaseMole mole; Hole(int x, int y) { this.x = x; this.y = y; } }
    private static abstract class BaseMole {
        final long spawnTime; final int lifetime; final int points;
        BaseMole(long spawnTime, int lifetime, int points) { this.spawnTime = spawnTime; this.lifetime = lifetime; this.points = points; }
    }
    private static class NormalMole extends BaseMole { NormalMole(long time) { super(time, 1700, 10); } }
    private static class GoldenMole extends BaseMole { GoldenMole(long time) { super(time, 1000, 30); } }
    private static class BombMole extends BaseMole { BombMole(long time) { super(time, 1300, -20); } }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String host = JOptionPane.showInputDialog(null,
                "Enter the Server IP address\n(Server computer: 127.0.0.1 or its LAN IP)", "127.0.0.1");
            if (host == null || host.trim().isEmpty()) return;
            JFrame frame = new JFrame("Mole Smash Online - Shared Game");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setResizable(false);
            frame.add(new MoleSmashGame(host.trim()));
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }
}
