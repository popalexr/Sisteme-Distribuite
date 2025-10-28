import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Multicast chat + history + coordinated shutdown (LAN-only via TTL=1).
 *
 * Usage:
 *   javac MulticastChatPeer.java
 *   java MulticastChatPeer <PEER_ID> [group=230.0.0.1] [port=50000] [ifaceName]
 *
 * Examples (run in terminale diferite):
 *   java MulticastChatPeer A
 *   java MulticastChatPeer B
 *   java MulticastChatPeer C
 *
 * Comenzi în consolă:
 *   orice text        -> trimite mesaje către grup
 *   /get <ID>         -> cere ultimul istoric de la peerul <ID>
 *   /quit             -> iese local (test rapid)
 */
public class MulticastChatPeer {
    // Config implicit (poți schimba după nevoie)
    private static final String DEFAULT_GROUP = "230.0.0.1"; // adresa multicast din clasa D
    private static final int DEFAULT_PORT = 50000;
    private static final int HISTORY_SIZE = 50;

    // Tipuri de mesaje
    private enum Type { HELLO, CHAT, HISTORY_REQUEST, HISTORY_RESPONSE, SHUTDOWN }

    private final String peerId;
    private final InetAddress group;
    private final int port;
    private final MulticastSocket socket;
    private final Set<String> knownPeers = ConcurrentHashMap.newKeySet();
    private final Deque<String> history = new ArrayDeque<>(HISTORY_SIZE);
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);
    private final AtomicBoolean sentShutdown = new AtomicBoolean(false);

    public MulticastChatPeer(String peerId, String groupAddr, int port, String ifaceName) throws Exception {
        this.peerId = Objects.requireNonNull(peerId);
        this.group = InetAddress.getByName(groupAddr);
        this.port = port;

        socket = new MulticastSocket(port);

        // LAN-only
        socket.setTimeToLive(1); // TTL=1: rămâne în LAN. :contentReference[oaicite:1]{index=1}
        socket.setReuseAddress(true);

        // Binding la interfață dacă e specificată
        if (ifaceName != null) {
            NetworkInterface nif = NetworkInterface.getByName(ifaceName);
            if (nif == null) throw new IllegalArgumentException("Interface not found: " + ifaceName);
            socket.joinGroup(new InetSocketAddress(group, port), nif);
        } else {
            socket.joinGroup(group);
        }
    }

    // Serializare simplă: type|sender|timestamp|payload
    private byte[] pack(Type type, String payload) {
        String wire = type + "|" + peerId + "|" + Instant.now().toEpochMilli() + "|" + (payload == null ? "" : payload);
        return wire.getBytes(StandardCharsets.UTF_8);
    }

    private static class Msg {
        final Type type;
        final String sender;
        final long ts;
        final String payload;
        Msg(Type t, String s, long ts, String p) { this.type = t; this.sender = s; this.ts = ts; this.payload = p; }
    }

    private Msg unpack(DatagramPacket p) {
        String s = new String(p.getData(), p.getOffset(), p.getLength(), StandardCharsets.UTF_8);
        String[] parts = s.split("\\|", 4);
        if (parts.length < 4) return null;
        try {
            return new Msg(Type.valueOf(parts[0]), parts[1], Long.parseLong(parts[2]), parts[3]);
        } catch (Exception e) {
            return null;
        }
    }

    private void send(Type type, String payload) throws IOException {
        byte[] data = pack(type, payload);
        socket.send(new DatagramPacket(data, data.length, group, port));
    }

    private synchronized void addToHistory(String msg) {
        if (history.size() == HISTORY_SIZE) history.removeFirst();
        history.addLast(msg);
    }

    private boolean isLowestKnownId() {
        // includem și pe noi înșine
        Set<String> ids = new HashSet<>(knownPeers);
        ids.add(peerId);
        return peerId.equals(ids.stream().min(String::compareTo).orElse(peerId));
    }

    private void startReceiver() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[8192];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    socket.receive(in);
                    Msg m = unpack(in);
                    if (m == null) continue;

                    // notează peer
                    knownPeers.add(m.sender);

                    switch (m.type) {
                        case HELLO -> {
                            System.out.printf("[%s] %s joined. Payload: %s%n", m.sender, m.sender, m.payload);
                        }
                        case CHAT -> {
                            System.out.printf("[%s] %s%n", m.sender, m.payload);
                            addToHistory(String.format("[%s] %s", m.sender, m.payload));
                        }
                        case HISTORY_REQUEST -> {
                            // payload: targetId (cel care trebuie să răspundă)
                            if (peerId.equalsIgnoreCase(m.payload.trim())) {
                                // răspunde cu lista (linie cu linie)
                                StringBuilder sb = new StringBuilder("HISTORY for ").append(peerId);
                                for (String h : history) sb.append("\n").append(h);
                                send(Type.HISTORY_RESPONSE, sb.toString());
                            }
                        }
                        case HISTORY_RESPONSE -> {
                            System.out.println("---- ISTORIC PRIMIT ----");
                            System.out.println(m.payload);
                            System.out.println("------------------------");
                        }
                        case SHUTDOWN -> {
                            System.out.printf("[SYSTEM] Shutdown requested by %s%n", m.sender);
                            initiateShutdown();
                        }
                    }
                } catch (IOException e) {
                    if (!socket.isClosed()) e.printStackTrace();
                    break;
                }
            }
        }, "Receiver");
        t.setDaemon(true);
        t.start();
    }

    private void startConsole() {
        Thread t = new Thread(() -> {
            try (BufferedReader br = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.isEmpty()) continue;

                    if (line.equalsIgnoreCase("/quit")) {
                        initiateShutdown();
                        break;
                    } else if (line.toLowerCase(Locale.ROOT).startsWith("/get ")) {
                        String target = line.substring(5).trim();
                        send(Type.HISTORY_REQUEST, target);
                    } else {
                        send(Type.CHAT, line);
                        addToHistory("[" + peerId + "] " + line);
                    }
                }
            } catch (Exception e) {
                if (!socket.isClosed()) e.printStackTrace();
            }
        }, "Console");
        t.setDaemon(true);
        t.start();
    }

    private void scheduleShutdownCoordinator() {
        // 5–20s delay
        int delay = ThreadLocalRandom.current().nextInt(5, 21);
        ScheduledExecutorService ses = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread th = new Thread(r, "ShutdownScheduler");
            th.setDaemon(true);
            return th;
        });
        ses.schedule(() -> {
            if (shuttingDown.get()) return;

            ses.shutdown();
        }, delay, TimeUnit.SECONDS);
    }

    private void announceHello() throws IOException {
        send(Type.HELLO, "Hello from " + peerId);
    }

    private void initiateShutdown() {
        if (shuttingDown.compareAndSet(false, true)) {
            try {
                socket.leaveGroup(group);
            } catch (Exception ignored) {}
            socket.close();
            System.out.println("[SYSTEM] Exiting...");
            System.exit(0);
        }
    }

    public void run() throws Exception {
        startReceiver();
        startConsole();
        announceHello();
        scheduleShutdownCoordinator();
        // menține thread-ul principal viu
        Thread.currentThread().join();
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("Usage: java MulticastChatPeer <PEER_ID> [group=230.0.0.1] [port=50000] [ifaceName]");
            System.exit(1);
        }
        String id = args[0];
        String group = args.length > 1 ? args[1] : DEFAULT_GROUP;
        int port = args.length > 2 ? Integer.parseInt(args[2]) : DEFAULT_PORT;
        String iface = args.length > 3 ? args[3] : "lo0";

        MulticastChatPeer peer = new MulticastChatPeer(id, group, port, iface);
        peer.run();
    }
}
