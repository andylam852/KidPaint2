// java
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.*;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

public class KidPaintServer {
    // Protocol constants
    private static final int NAME = 0;
    private static final int PIXELS = 1;
    private static final int TEXT = 2;
    private static final int SNAPSHOT = 3;
    private static final int STUDIOS_LIST = 4;
    private static final int JOIN_STUDIO = 5;
    private static final int CREATE_STUDIO = 6;

    private static final int DEFAULT_SIZE = 50;
    private static final int DISCOVERY_PORT = 54321;
    private static final String DISCOVERY_REQ = "KIDPAINT_DISCOVERY";

    class Point { int x, y; Point(int x, int y){ this.x = x; this.y = y; } }

    class Studio {
        final String name;
        int width, height;
        int[][] data;
        Studio(String name, int w, int h){
            this.name = name;
            this.width = w;
            this.height = h;
            data = new int[h][w];
        }
    }

    // socket -> output
    HashMap<Socket, DataOutputStream> clientList = new HashMap<>();
    // socket -> studio name
    HashMap<Socket, String> socketStudio = new HashMap<>();
    // studio name -> studio
    HashMap<String, Studio> studios = new HashMap<>();

    private final int tcpPort;

    public KidPaintServer(int port) throws IOException {
        this.tcpPort = port;
        studios.put("Default", new Studio("Default", DEFAULT_SIZE, DEFAULT_SIZE));
        // start discovery responder
        startDiscoveryResponder();

        ServerSocket serverSocket = new ServerSocket(port);
        System.out.println("KidPaintServer listening on port " + port);
        while (true) {
            Socket socket = serverSocket.accept();
            synchronized (clientList) {
                clientList.put(socket, new DataOutputStream(socket.getOutputStream()));
            }
            Thread t = new WorkerThread(this, socket);
            t.start();
        }
    }

    private void startDiscoveryResponder() {
        Thread t = new Thread(() -> {
            try (DatagramSocket ds = new DatagramSocket(DISCOVERY_PORT)) {
                byte[] buf = new byte[1024];
                System.out.println("Discovery responder listening on UDP " + DISCOVERY_PORT);
                while (true) {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    ds.receive(p);
                    String req = new String(p.getData(), 0, p.getLength()).trim();
                    if (!DISCOVERY_REQ.equals(req)) continue;
                    // build reply: each line "studioName;host;port\n"
                    String localHost;
                    try {
                        localHost = InetAddress.getLocalHost().getHostAddress();
                    } catch (Exception e) {
                        localHost = p.getAddress().getHostAddress();
                    }
                    StringBuilder sb = new StringBuilder();
                    synchronized (studios) {
                        for (String sname : studios.keySet()) {
                            sb.append(sname).append(";").append(localHost).append(";").append(tcpPort).append("\n");
                        }
                    }
                    byte[] out = sb.toString().getBytes();
                    DatagramPacket resp = new DatagramPacket(out, out.length, p.getAddress(), p.getPort());
                    ds.send(resp);
                }
            } catch (IOException e) {
                System.out.println("Discovery responder stopped: " + e.getMessage());
            }
        }, "DiscoveryResponder");
        t.setDaemon(true);
        t.start();
    }

    void serve(Socket socket, WorkerThread thread) throws IOException {
        DataInputStream in = new DataInputStream(socket.getInputStream());
        DataOutputStream out = new DataOutputStream(socket.getOutputStream());
        while (true) {
            int type = in.read();
            if (type == -1) throw new IOException("client closed");
            switch (type) {
                case NAME:
                    readName(in, thread);
                    sendStudioList(out);
                    break;
                case JOIN_STUDIO:
                    readJoinStudio(in, socket, out, thread);
                    break;
                case CREATE_STUDIO:
                    readCreateStudio(in, socket, out, thread);
                    break;
                case PIXELS:
                    readPixels(in, socket);
                    break;
                case TEXT:
                    readText(in, thread.getUsername(), socket);
                    break;
                case SNAPSHOT:
                    readSnapshot(in, socket);
                    break;
                default:
                    System.out.println("Unknown message type: " + type);
            }
        }
    }

    void readName(DataInputStream in, WorkerThread thread) throws IOException {
        int len = in.readInt();
        byte[] buf = new byte[len];
        in.readFully(buf, 0, len);
        thread.setUsername(new String(buf, 0, len));
    }

    void readJoinStudio(DataInputStream in, Socket socket, DataOutputStream out, WorkerThread thread) throws IOException {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b, 0, len);
        String studioName = new String(b, 0, len);
        if (!studios.containsKey(studioName)) studioName = "Default";
        socketStudio.put(socket, studioName);
        sendSnapshotForStudio(out, studios.get(studioName));
        broadcastJoinMessage(thread.getUsername(), studioName);
    }

    void readCreateStudio(DataInputStream in, Socket socket, DataOutputStream out, WorkerThread thread) throws IOException {
        int len = in.readInt();
        byte[] b = new byte[len];
        in.readFully(b, 0, len);
        String baseName = new String(b, 0, len);
        int w = in.readInt();
        int h = in.readInt();
        if (w <= 0 || h <= 0) { w = DEFAULT_SIZE; h = DEFAULT_SIZE; }
        String candidate = baseName;
        int suffix = 1;
        synchronized (studios) {
            while (studios.containsKey(candidate)) candidate = baseName + "_" + suffix++;
            studios.put(candidate, new Studio(candidate, w, h));
        }
        socketStudio.put(socket, candidate);
        broadcastStudioList();
        sendSnapshotForStudio(out, studios.get(candidate));
        broadcastJoinMessage(thread.getUsername(), candidate);
    }

    void broadcastJoinMessage(String username, String studioName) {
        if (username == null || studioName == null) return;
        String msg = username + " joined studio " + studioName;
        sendTextToStudio(msg.getBytes(), studioName);
    }

    void readSnapshot(DataInputStream in, Socket socket) throws IOException {
        String studioName = socketStudio.get(socket);
        if (studioName == null) return;
        int w = in.readInt();
        int h = in.readInt();
        Studio s = new Studio(studioName, w, h);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                s.data[y][x] = in.readInt();
            }
        }
        synchronized (studios) {
            studios.put(studioName, s);
        }
        broadcastSnapshotToStudio(studioName);
    }

    void readText(DataInputStream in, String username, Socket socket) throws IOException {
        int size = in.readInt();
        byte[] buffer = new byte[size];
        in.readFully(buffer, 0, size);
        String text = username + ":" + new String(buffer, 0, size);
        String studioName = socketStudio.get(socket);
        if (studioName != null) sendTextToStudio(text.getBytes(), studioName);
    }

    void readPixels(DataInputStream in, Socket socket) throws IOException {
        int color = in.readInt();
        int size = in.readInt();
        LinkedList<Point> list = new LinkedList<>();
        for (int i = 0; i < size; i++) {
            int x = in.readInt();
            int y = in.readInt();
            list.add(new Point(x, y));
        }
        String studioName = socketStudio.get(socket);
        if (studioName == null) return;
        Studio s = studios.get(studioName);
        if (s != null) {
            for (Point p : list) {
                if (p.y >= 0 && p.y < s.data.length && p.x >= 0 && p.x < s.data[0].length) {
                    s.data[p.y][p.x] = color;
                }
            }
            sendPixelsToStudio(color, list, studioName);
        }
    }

    void sendStudioList(DataOutputStream out) throws IOException {
        synchronized (clientList) {
            out.write(STUDIOS_LIST);
            out.writeInt(studios.size());
            for (String name : studios.keySet()) {
                byte[] nb = name.getBytes();
                out.writeInt(nb.length);
                out.write(nb);
            }
            out.flush();
        }
    }

    void broadcastStudioList() {
        synchronized (clientList) {
            for (DataOutputStream out : clientList.values()) {
                try { sendStudioList(out); } catch (IOException ignored) {}
            }
        }
    }

    void broadcastSnapshotToStudio(String studioName) {
        synchronized (clientList) {
            for (Map.Entry<Socket, DataOutputStream> e : clientList.entrySet()) {
                String sName = socketStudio.get(e.getKey());
                if (!studioName.equals(sName)) continue;
                try { sendSnapshotForStudio(e.getValue(), studios.get(studioName)); } catch (IOException ignored) {}
            }
        }
    }

    void sendTextToStudio(byte[] buffer, String studioName) {
        synchronized (clientList) {
            for (Map.Entry<Socket, DataOutputStream> e : clientList.entrySet()) {
                String sName = socketStudio.get(e.getKey());
                if (!studioName.equals(sName)) continue;
                try {
                    DataOutputStream out = e.getValue();
                    out.write(TEXT);
                    out.writeInt(buffer.length);
                    out.write(buffer, 0, buffer.length);
                    out.flush();
                } catch (IOException ignored) {}
            }
        }
    }

    void sendPixelsToStudio(int color, LinkedList<Point> list, String studioName) {
        synchronized (clientList) {
            for (Map.Entry<Socket, DataOutputStream> e : clientList.entrySet()) {
                String sName = socketStudio.get(e.getKey());
                if (!studioName.equals(sName)) continue;
                try {
                    DataOutputStream out = e.getValue();
                    out.write(PIXELS);
                    out.writeInt(color);
                    out.writeInt(list.size());
                    for (Point p : list) {
                        out.writeInt(p.x);
                        out.writeInt(p.y);
                    }
                    out.flush();
                } catch (IOException ignored) {}
            }
        }
    }

    void sendSnapshotForStudio(DataOutputStream out, Studio s) throws IOException {
        if (s == null) return;
        out.write(SNAPSHOT);
        out.writeInt(s.width);
        out.writeInt(s.height);
        for (int y = 0; y < s.height; y++) {
            for (int x = 0; x < s.width; x++) {
                out.writeInt(s.data[y][x]);
            }
        }
        out.flush();
    }

    public static void main(String[] args) throws IOException {
        new KidPaintServer(12345);
    }
}
