import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class InelTCP {
    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.out.println("Usage: java InelTCP <process_port> <next_IP> <next_port>");

            return;
        }

        int processPort = Integer.parseInt(args[0]);
        String nextIP = args[1];
        int nextPort = Integer.parseInt(args[2]);

        new InelTCP().run(processPort, nextIP, nextPort);
    }

    private void run(int processPort, String nextIP, int nextPort) throws Exception {
        System.out.println("Starting process node on port " + processPort);
        System.out.println("Next node is " + nextIP + ":" + nextPort);

        try (ServerSocket server = new ServerSocket(processPort)) {
            server.setReuseAddress(true);

            Socket outSocket = connectWithRetry(nextIP, nextPort);

            System.out.println("Waiting for connection IN on previous node");
            Socket inSocket = server.accept();
            System.out.println("IN connection established on " + inSocket.getRemoteSocketAddress());

            try (
                    BufferedReader in = new BufferedReader(new InputStreamReader(inSocket.getInputStream(), "UTF-8"));
                    PrintWriter out = new PrintWriter(new OutputStreamWriter(outSocket.getOutputStream(), "UTF-8"), true);
                    BufferedReader console = new BufferedReader(new InputStreamReader(System.in))
            ){
                CountDownLatch done = new CountDownLatch(2);

                // Thread tx
                Thread tx = new Thread(() -> {
                    try {
                        String line = console.readLine();

                        String ip = inSocket.getLocalAddress().getHostAddress();
                        int port = inSocket.getLocalPort();

                        String jsonPacket = String.format("{\"ip\":\"%s\",\"port\":%d,\"message\":\"%s\"}",
                                ip, port, line);

                        out.println(jsonPacket);
                    } catch (IOException e) {
                        System.out.println("Error: " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });

                // Thread rx
                Thread rx = new Thread(() -> {
                    try {
                        String line;
                        while ((line = in.readLine()) != null) {
                            line = line.trim();
                            if (line.isEmpty()) continue;

                            if (line.startsWith("{") && line.endsWith("}")) {
                                try {
                                    String body = line.substring(1, line.length() - 1).trim();
                                    String[] parts = body.split(",\\s*");
                                    String ip = "", message = "";
                                    int port = -1;

                                    for (String part : parts) {
                                        String[] kv = part.split(":", 2);
                                        if (kv.length != 2) continue;
                                        String key = kv[0].replace("\"", "").trim();
                                        String value = kv[1].replace("\"", "").trim();

                                        switch (key) {
                                            case "ip":
                                                ip = value;
                                                break;
                                            case "port":
                                                port = Integer.parseInt(value);
                                                break;
                                            case "message":
                                                message = value;
                                                break;
                                        }
                                    }

                                    System.out.printf("[From %s:%d] %s%n", ip, port, message);

                                    if (ip.equals(inSocket.getLocalAddress().getHostAddress()) && port == inSocket.getLocalPort()) {
                                        System.out.println("Message received back!");
                                    } else {
                                        out.println(line);
                                    }

                                    if ("exit".equalsIgnoreCase(message)) {
                                        break;
                                    }
                                } catch (Exception parseErr) {
                                    System.out.println("Invalid JSON packet: " + line);
                                }
                            } else {
                                System.out.println("Received (raw): " + line);
                                if ("exit".equalsIgnoreCase(line)) {
                                    break;
                                }
                            }
                        }
                    } catch (IOException e) {
                        System.out.println("Error: " + e.getMessage());
                    } finally {
                        done.countDown();
                    }
                });

                tx.setDaemon(true);
                rx.setDaemon(true);

                tx.start();
                rx.start();

                done.await();
            }
            finally {
                try {
                    inSocket.close();
                } catch (IOException e) {
                    System.out.println("Error closing IN socket: " + e.getMessage());
                }
                try {
                    outSocket.close();
                } catch (IOException e) {
                    System.out.println("Error closing OUT socket: " + e.getMessage());
                }

                System.out.println("Process on port " + processPort + " stopped.");
            }
        }
        catch (IOException e) {
            System.out.println("Error: " + e.getMessage());
        }
    }

    private Socket connectWithRetry(String host, int port) throws InterruptedException {
        int tries = 0;
        while (true) {
            try {
                Socket s = new Socket();
                s.setReuseAddress(true);
                s.connect(new InetSocketAddress(host, port), 700);
                System.out.println("OUT connection established on " + host + ":" + port);
                return s;
            } catch (IOException e) {
                tries++;
                long backoffMs = Math.min(2000, 150L * (1L << Math.min(tries, 4)));
                System.out.printf("Can't connect to %s:%d (%d). Retrying in %d ms...%n",
                        host, port, tries, backoffMs);
                try { TimeUnit.MILLISECONDS.sleep(backoffMs); } catch (InterruptedException ie) { throw ie; }
            }
        }
    }
}
