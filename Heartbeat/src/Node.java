import java.io.IOException;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Scanner;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class Node {
    private final int myId;
    private final int unicastPort;
    private final DatagramSocket unicastSocket;

    private final InetAddress mcastGroup;
    private final int mcastPort;
    private final MulticastSocket mcastSocket;

    private final AtomicBoolean running = new AtomicBoolean(true);

    // <peerId, lastSeenMs>
    private final Map<Integer, Long> lastHeartbeat = new ConcurrentHashMap<>();

    private static final long HEARTBEAT_INTERVAL_MS = 1000;
    private static final long FAILURE_TIMEOUT_MS = 3000;

    public Node(int myId,
                         int unicastPort,
                         String mcastIp,
                         int mcastPort) throws IOException {
        this.myId = myId;
        this.unicastPort = unicastPort;
        this.unicastSocket = new DatagramSocket(unicastPort);

        this.mcastPort = mcastPort;
        this.mcastGroup = InetAddress.getByName(mcastIp);
        this.mcastSocket = new MulticastSocket(mcastPort);
        this.mcastSocket.joinGroup(new InetSocketAddress(mcastGroup, mcastPort), NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));

        lastHeartbeat.put(myId, System.currentTimeMillis());
    }

    public void sendMulticast(String msg) {
        try {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, mcastGroup, mcastPort);
            mcastSocket.send(packet);
        } catch (IOException e) {
            System.err.println("[ERR] sendMulticast: " + e.getMessage());
        }
    }

    public void sendUnicastMsg(String ip, int port, String msg) {
        try {
            byte[] data = msg.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length,
                    InetAddress.getByName(ip), port);
            unicastSocket.send(packet);
        } catch (IOException e) {
            System.err.println("[ERR] sendUnicastMsg: " + e.getMessage());
        }
    }

    public void startMulticastReceiver() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    mcastSocket.receive(packet);
                    String data = new String(packet.getData(), packet.getOffset(),
                            packet.getLength(), StandardCharsets.UTF_8).trim();
                    handleMulticastPacket(data);
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("[ERR] mcast recv: " + e.getMessage());
                    }
                    break;
                }
            }
        }, "MulticastReceiver");
        t.start();
    }

    public void startUnicastReceiver() {
        Thread t = new Thread(() -> {
            byte[] buf = new byte[2048];
            while (running.get()) {
                DatagramPacket packet = new DatagramPacket(buf, buf.length);
                try {
                    unicastSocket.receive(packet);
                    String data = new String(packet.getData(), packet.getOffset(),
                            packet.getLength(), StandardCharsets.UTF_8).trim();
                    handleUnicastPacket(data);
                } catch (IOException e) {
                    if (running.get()) {
                        System.err.println("[ERR] unicast recv: " + e.getMessage());
                    }
                    break;
                }
            }
        }, "UnicastReceiver");
        t.start();
    }

    public void startHeartbeatSender() {
        Thread t = new Thread(() -> {
            while (running.get()) {
                String payload = "HEARTBEAT;" + myId + ";" + System.currentTimeMillis() + ";" + InetAddressInfo.getMyIp() + ";" + unicastPort;
                sendMulticast(payload);
                try { Thread.sleep(HEARTBEAT_INTERVAL_MS); } catch (InterruptedException ignored) {}
            }
        }, "HeartbeatSender");
        t.start();
    }

    public void startFailureDetector() {
        Thread t = new Thread(() -> {
            while (running.get()) {
                long now = System.currentTimeMillis();
                for (Map.Entry<Integer, Long> e : lastHeartbeat.entrySet()) {
                    int id = e.getKey();
                    if (id == myId) continue;
                    long diff = now - e.getValue();
                    if (diff > FAILURE_TIMEOUT_MS) {
                        System.out.println("[ALERT] Node " + id + " considered DEAD (" + diff + " ms no heartbeat)");
                    }
                }
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            }
        }, "FailureDetector");
        t.start();
    }

    public void startConsole() {
        Thread t = new Thread(() -> {
            Scanner sc = new Scanner(System.in);
            System.out.println("Commands:");
            System.out.println(" list     -> show available nodes");
            System.out.println(" msg <id> <txt>  -> send message to node <id>");
            System.out.println(" exit     -> close");
            while (running.get()) {
                System.out.print("cmd> ");
                if (!sc.hasNextLine()) break;
                String line = sc.nextLine().trim();
                if (line.equals("exit")) {
                    shutdown();
                    break;
                } else if (line.equals("list")) {
                    dumpNodes();
                } else if (line.startsWith("msg ")) {
                    String[] parts = line.split("\\s+", 3);
                    if (parts.length < 3) {
                        System.out.println("Usage: msg <id> <text>");
                        continue;
                    }
                    int destId = Integer.parseInt(parts[1]);
                    String txt = parts[2];

                    NodeContact contact = NodeDirectory.get(destId);
                    if (contact == null) {
                        System.out.println("Not found node #" + destId);
                    } else {
                        String payload = "MSG;" + myId + ";" + destId + ";" + txt;
                        sendUnicastMsg(contact.ip, contact.port, payload);
                    }
                }
            }
        }, "Console");
        t.start();
    }

    private void handleMulticastPacket(String data) {
        // expected HEARTBEAT;id;ts;ip;port
        String[] parts = data.split(";");
        if (parts.length >= 5 && parts[0].equals("HEARTBEAT")) {
            int id = Integer.parseInt(parts[1]);
            String ip = parts[3];
            int port = Integer.parseInt(parts[4]);

            lastHeartbeat.put(id, System.currentTimeMillis());
            NodeDirectory.put(id, ip, port);

//            System.out.println(data);
        }
    }

    private void handleUnicastPacket(String data) {
        // MSG;fromId;toId;text
        String[] parts = data.split(";", 4);
        if (parts.length >= 4 && parts[0].equals("MSG")) {
            int toId = Integer.parseInt(parts[2]);
            if (toId == myId) {
                int fromId = Integer.parseInt(parts[1]);
                String text = parts[3];
                System.out.println("[MSG] from #" + fromId + ": " + text);
            }
        }
    }

    public void dumpNodes() {
        System.out.println("Current node: " + myId + " unicastPort=" + unicastPort);
        NodeDirectory.dump();
    }

    public void shutdown() {
        if (!running.compareAndSet(true, false)) return;
        System.out.println("Shutting down node " + myId);
        try {
            mcastSocket.leaveGroup(new InetSocketAddress(mcastGroup, mcastPort), NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
        } catch (IOException ignored) {}
        mcastSocket.close();
        unicastSocket.close();
    }
}
