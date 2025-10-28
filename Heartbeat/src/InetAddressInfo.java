import java.net.InetAddress;
import java.net.UnknownHostException;

public class InetAddressInfo {
    public static String getMyIp() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (UnknownHostException e) {
            return "0.0.0.0";
        }
    }
}
