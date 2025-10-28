public class Heartbeat {
    public static void main(String[] args) throws Exception
    {
        if (args.length < 3) {
            System.out.println("Usage: java Heartbeat <myId> <unicastPort> <mcastIp> [mcastPort]");
            System.exit(1);
        }

        int myId = Integer.parseInt(args[0]);
        int unicastPort = Integer.parseInt(args[1]);
        String mcastIp = args[2];
        int mcastPort = (args.length >= 4) ? Integer.parseInt(args[3]) : 5000;

        Node node = new Node(myId, unicastPort, mcastIp, mcastPort);
        node.startMulticastReceiver();
        node.startUnicastReceiver();
        node.startHeartbeatSender();
        node.startFailureDetector();
        node.startConsole();
    }
}
