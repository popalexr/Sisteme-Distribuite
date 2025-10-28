import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class NodeDirectory {
    private static final Map<Integer, NodeContact> contacts = new ConcurrentHashMap<>();
    public static void put(int id, String ip, int port) {
        contacts.put(id, new NodeContact(ip, port));
    }
    public static NodeContact get(int id) {
        return contacts.get(id);
    }
    public static void dump() {
        for (Map.Entry<Integer, NodeContact> e : contacts.entrySet()) {
            System.out.println("Node " + e.getKey() + " -> " + e.getValue().ip + ":" + e.getValue().port);
        }
    }
}