import javafx.application.Application;
import javafx.application.Platform;
import javafx.stage.Stage;
import javafx.scene.control.ChoiceDialog;

import java.io.*;
import java.net.*;
import java.util.*;

public class KidPaint extends Application {
    private static final int DISCOVERY_PORT = 54321;
    private static final int DISCOVERY_TIMEOUT_MS = 800;
    private static final String DISCOVERY_REQ = "KIDPAINT_DISCOVERY";

    // protocol snippets used for the probe scan
    private static final int NAME = 0;
    private static final int STUDIOS_LIST = 4;

    static class ServerInfo {
        final String studio;
        final String host;
        final int port;
        ServerInfo(String studio, String host, int port){
            this.studio = studio;
            this.host = host;
            this.port = port;
        }
        @Override
        public String toString(){
            return studio + " - " + host + ":" + port;
        }
    }

    @Override
    public void start(Stage stage) {
        askNameAndThen(stage);
    }

    // show name dialog, then proceed to server selection for that name
    private void askNameAndThen(Stage stage) {
        try {
            GetNameDialog dlg = new GetNameDialog("KidPaint - Enter Name");
            String username = dlg.getPlayername();
            if (username == null || username.trim().isEmpty()) {
                Platform.exit();
                return;
            }
            // enter server-selection / launch loop for this username
            selectServerAndLaunch(stage, username);
        } catch (Exception e) {
            e.printStackTrace();
            Platform.exit();
        }
    }

    // discover servers, ask user to pick/create, then launch MainWindow.
    // If user cancels at the ChoiceDialog we go back to name entry.
    // If the running MainWindow sets MainWindow.requestServerSelection upon its close,
    // we re-enter server selection for the same username.
    private void selectServerAndLaunch(Stage stage, String username) {
        final String createOpt = "Create New Local Server (run local server)";
        try {
            List<ServerInfo> servers = discoverServers();

            // Group discovered entries by host:port so we present only servers (not individual studios).
            Map<String, List<String>> serversByEndpoint = new LinkedHashMap<>();
            for (ServerInfo si : servers) {
                String endpoint = si.host + ":" + si.port;
                serversByEndpoint.computeIfAbsent(endpoint, k -> new ArrayList<>()).add(si.studio);
            }

            List<String> serverChoices = new ArrayList<>(serversByEndpoint.keySet());
            serverChoices.add(createOpt);

            ChoiceDialog<String> serverDlg = new ChoiceDialog<>(serverChoices.isEmpty() ? createOpt : serverChoices.get(0), serverChoices);
            serverDlg.setTitle("Select Server");
            serverDlg.setHeaderText("Choose a server to query studios from");

            Optional<String> srvOpt = serverDlg.showAndWait();
            if (!srvOpt.isPresent()) {
                // user cancelled server selection -> go back to name entry
                askNameAndThen(stage);
                return;
            }

            String selectedServer = srvOpt.get();
            String host;
            int port;

            if (createOpt.equals(selectedServer)) {
                // start local server
                port = findFreePort(12345);
                int finalPort = port;
                Thread serverThread = new Thread(() -> {
                    try {
                        new KidPaintServer(finalPort);
                    } catch (IOException e) {
                        System.out.println("Local server failed: " + e.getMessage());
                    }
                }, "KidPaintServer-Main");
                serverThread.setDaemon(true);
                serverThread.start();
                host = "127.0.0.1";

                // wait for server to start listening (timeout ~4s)
                long deadline = System.currentTimeMillis() + 4000;
                boolean started = false;
                while (System.currentTimeMillis() < deadline) {
                    if (isPortOpen(host, port, 250)) { started = true; break; }
                    try { Thread.sleep(100); } catch (InterruptedException ignored) {}
                }
                if (!started) {
                    // failed to start local server -> re-open server selection
                    Platform.runLater(() -> selectServerAndLaunch(stage, username));
                    return;
                }
            } else {
                // parse host:port
                String[] parts = selectedServer.split(":", 2);
                host = parts[0];
                port = Integer.parseInt(parts[1]);
            }

            // prepare to detect when the MainWindow requests re-selection (user cancels studio selection)
            MainWindow.requestServerSelection = false;
            stage.setOnHidden(ev -> {
                if (MainWindow.requestServerSelection) {
                    MainWindow.requestServerSelection = false;
                    Platform.runLater(() -> selectServerAndLaunch(stage, username));
                } else {
                    Platform.exit();
                }
            });

            // Launch client pointing to chosen host/port; MainWindow will request studio list from that server
            try {
                new MainWindow(stage, username, host, port);
            } catch (IOException ex) {
                System.out.println("Failed to open MainWindow: " + ex.getMessage());
                Platform.runLater(() -> selectServerAndLaunch(stage, username));
            }
        } catch (Exception e) {
            System.out.println("Selection failed: " + e.getMessage());
            Platform.exit();
        }
    }

    private boolean isPortOpen(String host, int port, int timeoutMs) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private List<ServerInfo> discoverServers() {
        Set<String> seen = new HashSet<>();
        List<ServerInfo> replies = new ArrayList<>();

        // 1) UDP broadcast discovery
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setBroadcast(true);
            socket.setSoTimeout(DISCOVERY_TIMEOUT_MS);
            byte[] sendData = DISCOVERY_REQ.getBytes();
            try {
                DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, InetAddress.getByName("255.255.255.255"), DISCOVERY_PORT);
                socket.send(sendPacket);
            } catch (Exception ignored) {}

            // also broadcast on each network interface
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface ni = interfaces.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                for (InterfaceAddress ia : ni.getInterfaceAddresses()) {
                    InetAddress broadcast = ia.getBroadcast();
                    if (broadcast == null) continue;
                    try {
                        DatagramPacket p = new DatagramPacket(sendData, sendData.length, broadcast, DISCOVERY_PORT);
                        socket.send(p);
                    } catch (Exception ignored) {}
                }
            }

            long end = System.currentTimeMillis() + DISCOVERY_TIMEOUT_MS;
            while (System.currentTimeMillis() < end) {
                try {
                    byte[] buf = new byte[2048];
                    DatagramPacket packet = new DatagramPacket(buf, buf.length);
                    socket.receive(packet);
                    String resp = new String(packet.getData(), 0, packet.getLength()).trim();
                    // reply format: studioName;host;port\nstudio2;host;port...
                    String[] lines = resp.split("\n");
                    for (String line : lines) {
                        String[] parts = line.split(";");
                        if (parts.length >= 3) {
                            String studio = parts[0];
                            String host = parts[1];
                            int port = Integer.parseInt(parts[2]);

                            // normalize host to loopback if it resolves to a local address
                            String normalizedHost = host;
                            if (isLocalAddress(host)) normalizedHost = "127.0.0.1";

                            String key = normalizedHost + ":" + port + "|" + studio;
                            if (seen.add(key)) {
                                replies.add(new ServerInfo(studio, normalizedHost, port));
                            }
                        }
                    }
                } catch (SocketTimeoutException ste) {
                    break;
                } catch (IOException ioe) {
                    break;
                }
            }
        } catch (IOException e) {
            System.out.println("Discovery failed: " + e.getMessage());
        }

        // 2) Local loopback TCP scan fallback (catch servers that couldn't bind UDP)
        // scan around common base port used by local servers - expanded range
        int base = 12345;
        int start = Math.max(1025, base - 5);
        int endPort = base + 100; // expanded scan to cover sequential ports allocated by servers
        for (int p = start; p <= endPort; p++) {
            try (Socket s = new Socket()) {
                s.connect(new InetSocketAddress("127.0.0.1", p), 200);
                s.setSoTimeout(500);
                DataOutputStream dout = new DataOutputStream(s.getOutputStream());
                DataInputStream din = new DataInputStream(s.getInputStream());

                // send a small NAME probe so the server replies with STUDIOS_LIST
                byte[] nb = "probe".getBytes();
                dout.write(NAME);
                dout.writeInt(nb.length);
                dout.write(nb);
                dout.flush();

                int type = din.read();
                if (type == STUDIOS_LIST) {
                    int count = din.readInt();
                    for (int i = 0; i < count; i++) {
                        int len = din.readInt();
                        byte[] b = new byte[len];
                        din.readFully(b, 0, len);
                        String studio = new String(b, 0, len);
                        String host = "127.0.0.1";
                        int port = p;
                        String key = host + ":" + port + "|" + studio;
                        if (seen.add(key)) {
                            replies.add(new ServerInfo(studio, host, port));
                        }
                    }
                }
            } catch (IOException ignored) {
                // no server at this port or handshake failed; ignore
            }
        }

        return replies;
    }

    private boolean isLocalAddress(String host) {
        try {
            InetAddress addr = InetAddress.getByName(host);
            if (addr.isLoopbackAddress()) return true;
            // compare against all local interface addresses
            Enumeration<NetworkInterface> ifs = NetworkInterface.getNetworkInterfaces();
            while (ifs.hasMoreElements()) {
                NetworkInterface ni = ifs.nextElement();
                Enumeration<InetAddress> adds = ni.getInetAddresses();
                while (adds.hasMoreElements()) {
                    InetAddress a = adds.nextElement();
                    if (a.equals(addr)) return true;
                }
            }
            // also compare to InetAddress.getLocalHost()
            try {
                InetAddress local = InetAddress.getLocalHost();
                if (addr.equals(local)) return true;
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
        return false;
    }

    private int findFreePort(int preferred) {
        // try preferred then sequential search (helps discovery TCP probe find servers)
        try {
            ServerSocket ss = new ServerSocket(preferred);
            int p = ss.getLocalPort();
            ss.close();
            return p;
        } catch (IOException ignored) {}
        // sequentially try preferred+1 .. preferred+100
        int maxTry = preferred + 100;
        for (int p = preferred + 1; p <= maxTry; p++) {
            try (ServerSocket ss = new ServerSocket(p)) {
                return ss.getLocalPort();
            } catch (IOException ignored) {}
        }
        // fallback to ephemeral
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (IOException e) {
            return preferred;
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
