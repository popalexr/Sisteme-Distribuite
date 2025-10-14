import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Random;

public class Client {
    private static int[] message_sizes = {128, 512, 1024, 2048};
    public static void main(String[] args) throws Exception
    {
        if(args.length < 2)
        {
            System.out.println("Usage: java Client <server_ip> <server_port> [runs per size]");

            return;
        }

        String server_ip = args[0];
        int server_port = Integer.parseInt(args[1]);
        int runs_per_size = args.length >= 3 ? Integer.parseInt(args[2]) : 1;

        InetAddress serverAddr = InetAddress.getByName(server_ip);

        try (DatagramSocket socket = new DatagramSocket()){
            socket.setSoTimeout(3000); // ms

            System.out.printf("RTT test to %s:%d, for %s, runs per size: %d\n", server_ip, server_port, Arrays.toString(message_sizes), runs_per_size);

            for (int size : message_sizes)
            {
                long total_rtt = 0;
                int ok = 0;

                for (int i = 1; i <= runs_per_size; i ++)
                {
                    byte[] data = payload(size, i);
                    DatagramPacket packet = new DatagramPacket(data, data.length, serverAddr, server_port);

                    Instant t0 = Instant.now();
                    socket.send(packet);

                    byte[] recvBuff = new byte[size + 64]; // safety reasons
                    DatagramPacket recvPacket = new DatagramPacket(recvBuff, recvBuff.length);

                    try {
                        socket.receive(recvPacket);
                        Instant t1 = Instant.now();

                        long rtt = Duration.between(t0, t1).toMillis();

                        boolean sizeOk = recvPacket.getLength() == size;
                        boolean seqOk = recvPacket.getLength() >= 4 &&
                                recvBuff[0] == data[0] &&
                                recvBuff[1] == data[1] &&
                                recvBuff[2] == data[2] &&
                                recvBuff[3] == data[3];

                        System.out.printf("size=%4d  run=%d  RTT=%4d ms  (echo ok=%s, seq ok=%s)\n", size, i, rtt, sizeOk, seqOk);

                        if (sizeOk && seqOk) {
                            total_rtt += rtt;
                            ok++;
                        }
                    }
                    catch(SocketTimeoutException e) {
                        System.out.printf("size=%4d  run=%d  RTT=TIMEOUT\n", size, i);
                    }
                }

                if (ok > 0)
                {
                    System.out.printf("==> size=%4d  avg RTT=%.2f ms (%d/%d ok)\n\n", size, (double) total_rtt / ok, ok, runs_per_size);
                } else {
                    System.out.printf("==> size=%4d  no valid responses.\n", size);
                }
                System.out.println();
            }
        }
    }

    private static byte[] payload(int size, int seq) {
        byte[] data = new byte[size];
        new Random(seq).nextBytes(data);

        data[0] = (byte) ((seq >>> 24) & 0xFF);
        data[1] = (byte) ((seq >>> 16) & 0xFF);
        data[2] = (byte) ((seq >>> 8) & 0xFF);
        data[3] = (byte) (seq & 0xFF);

        return data;
    }
}
