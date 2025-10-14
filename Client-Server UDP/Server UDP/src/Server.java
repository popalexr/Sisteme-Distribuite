import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;

public class Server {
    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.out.println("Usage: java Server <port>");
            return;
        }

        int port = Integer.parseInt(args[0]);

        try (DatagramSocket server = new DatagramSocket(port)) {
            System.out.println("Server listening on port " + port);

            byte[] buffer = new byte[65507]; // Max UDP packet size

            while(true)
            {
                DatagramPacket req = new DatagramPacket(buffer, buffer.length);
                server.receive(req);

                InetAddress clientAddr = req.getAddress();
                int clientPort = req.getPort();
                System.out.printf("Recv %d bytes from %s:%d\n",
                        req.getLength(), clientAddr.getHostAddress(), clientPort);

                DatagramPacket resp = new DatagramPacket(
                        req.getData(), req.getLength(), clientAddr, clientPort);
                server.send(resp);
            }
        }
    }
}
