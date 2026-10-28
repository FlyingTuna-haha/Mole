
import java.io.*;
import java.net.*;
import java.util.*;

public class GameServer {
    private static final int PORT = 5000;
    private static final int HOLES = 12;
    private static final int MAX_PLAYERS = 4;

    private static final int[] moles = new int[HOLES];
    private static final long[] expires = new long[HOLES];

    // 4 = normal mole hit, 5 = gold mole hit
    private static final long[] hitExpires = new long[HOLES];
    private static final int[] points = {0, 10, 30, -20};

    private static final int[] scores = new int[MAX_PLAYERS];
    private static final int[] playerX = {500, 500, 500, 500};
    private static final int[] playerY = {350, 350, 350, 350};
    private static final boolean[] connected =
            new boolean[MAX_PLAYERS];

    private static final List<ClientHandler> clients =
            Collections.synchronizedList(new ArrayList<>());

    private static final Random random = new Random();

    private static boolean playing = false;
    private static long endTime = 0;
    private static long nextSpawn = 0;

    public static void main(String[] args) throws IOException {
        ServerSocket server = new ServerSocket(PORT);

        System.out.println("Mole Smash Server started on port " + PORT);
        System.out.println("Waiting for players...");

        Thread gameThread = new Thread(() -> {
            while (true) {
                updateGame();
                broadcastState();

                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    break;
                }
            }
        });

        gameThread.setDaemon(true);
        gameThread.start();

        while (true) {
            Socket socket = server.accept();
            int id = findFreeSlot();

            if (id == -1) {
                PrintWriter reject = new PrintWriter(
                        socket.getOutputStream(), true);
                reject.println("FULL");
                socket.close();
                continue;
            }

            ClientHandler client = new ClientHandler(socket, id);
            clients.add(client);

            synchronized (GameServer.class) {
                connected[id] = true;
            }

            new Thread(client).start();
            System.out.println("Player " + (id + 1) + " connected.");
        }
    }

    private static synchronized int findFreeSlot() {
        for (int i = 0; i < MAX_PLAYERS; i++) {
            if (!connected[i]) return i;
        }
        return -1;
    }

    private static synchronized void updateGame() {
        long now = System.currentTimeMillis();

        if (!playing) return;

        if (now >= endTime) {
            playing = false;
            Arrays.fill(moles, 0);
            return;
        }

        for (int i = 0; i < HOLES; i++) {
            if (moles[i] == 4 || moles[i] == 5) {
                if (now >= hitExpires[i]) {
                    moles[i] = 0;
                }
            } else if (moles[i] != 0 && now >= expires[i]) {
                moles[i] = 0;
            }
        }

        if (now >= nextSpawn) {
            spawnMole(now);

            if (random.nextBoolean()) {
                spawnMole(now);
            }

            nextSpawn = now + 350 + random.nextInt(300);
        }
    }

    private static void spawnMole(long now) {
        List<Integer> free = new ArrayList<>();

        for (int i = 0; i < HOLES; i++) {
            if (moles[i] == 0) free.add(i);
        }

        if (free.isEmpty()) return;

        int hole = free.get(random.nextInt(free.size()));
        int r = random.nextInt(100);

        if (r < 12) {
            moles[hole] = 3;
            expires[hole] = now + 1300;
        } else if (r < 32) {
            moles[hole] = 2;
            expires[hole] = now + 1000;
        } else {
            moles[hole] = 1;
            expires[hole] = now + 1700;
        }
    }

    private static synchronized void handleMessage(
            int id, String message) {

        if ("START".equals(message)) {
            if (!playing) {
                Arrays.fill(scores, 0);
                Arrays.fill(moles, 0);

                playing = true;
                endTime = System.currentTimeMillis() + 60000;
                nextSpawn = 0;

                System.out.println("Game started!");
            }

        } else if (message.startsWith("HIT:") && playing) {
            try {
                int hole = Integer.parseInt(message.substring(4));

                if (hole >= 0 && hole < HOLES
                        && moles[hole] >= 1 && moles[hole] <= 3) {

                    int moleType = moles[hole];

                    scores[id] += points[moleType];

                    if (moleType == 1) {
                        moles[hole] = 4;
                        hitExpires[hole] = System.currentTimeMillis() + 250;
                    } else if (moleType == 2) {
                        moles[hole] = 5;
                        hitExpires[hole] = System.currentTimeMillis() + 250;
                    } else {
                        moles[hole] = 0;
                    }
                }
            } catch (NumberFormatException ignored) {
            }

        } else if (message.startsWith("MOVE:")) {
            try {
                String[] xy = message.substring(5).split(",");

                playerX[id] = Math.max(0,
                        Math.min(1000, Integer.parseInt(xy[0])));
                playerY[id] = Math.max(0,
                        Math.min(700, Integer.parseInt(xy[1])));

            } catch (Exception ignored) {
            }
        }
    }

    private static synchronized String getState() {
        int seconds = playing
                ? (int) Math.max(0,
                    (endTime - System.currentTimeMillis() + 999) / 1000)
                : 0;

        StringBuilder s = new StringBuilder();

        s.append("STATE|")
         .append(playing).append("|")
         .append(seconds).append("|");

        for (int i = 0; i < MAX_PLAYERS; i++) {
            if (i > 0) s.append(",");
            s.append(scores[i]);
        }

        s.append("|");

        for (int i = 0; i < HOLES; i++) {
            if (i > 0) s.append(",");
            s.append(moles[i]);
        }

        s.append("|");

        for (int i = 0; i < MAX_PLAYERS; i++) {
            if (i > 0) s.append(";");
            s.append(playerX[i]).append(",")
             .append(playerY[i]).append(",")
             .append(connected[i] ? 1 : 0);
        }

        return s.toString();
    }

    private static void broadcastState() {
        String state = getState();

        synchronized (clients) {
            for (ClientHandler client : clients) {
                client.send(state);
            }
        }
    }

    private static class ClientHandler implements Runnable {
        private final Socket socket;
        private final int id;
        private PrintWriter out;

        ClientHandler(Socket socket, int id) {
            this.socket = socket;
            this.id = id;
        }

        void send(String message) {
            if (out != null) out.println(message);
        }

        public void run() {
            try {
                out = new PrintWriter(
                        socket.getOutputStream(), true);

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(socket.getInputStream()));

                out.println("WELCOME:" + (id + 1));

                String message;

                while ((message = in.readLine()) != null) {
                    handleMessage(id, message);
                }

            } catch (IOException e) {
                System.out.println(
                        "Player " + (id + 1) + " disconnected.");

            } finally {
                clients.remove(this);

                synchronized (GameServer.class) {
                    connected[id] = false;
                }

                try {
                    socket.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}
