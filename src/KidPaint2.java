// src/KidPaint2.java (small addition: watch stage hidden and restart if Back requested)
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Alert.AlertType;
import javafx.stage.Stage;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

public class KidPaint2 extends Application {
    final static String title = "KidPaint 2.0";

    @Override
    public void start(Stage stage) {
        try {
            GetNameDialog dialog = new GetNameDialog(title);
            String username = dialog.getPlayername();
            if (username == null || username.trim().isEmpty()) username = "Player";

            String host = "127.0.0.1";
            int port = 12345;

            // If server not reachable, start a local server in background and wait for it.
            if (!isPortOpen(host, port, 300)) {
                Thread srv = new Thread(() -> {
                    try {
                        new KidPaintServer(port);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }, "LocalKidPaintServer");
                srv.setDaemon(true);
                srv.start();

                long deadline = System.currentTimeMillis() + 4000;
                boolean started = false;
                while (System.currentTimeMillis() < deadline) {
                    if (isPortOpen(host, port, 300)) { started = true; break; }
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }
                if (!started) {
                    showErrorAndExit("Unable to start local server on port " + port);
                    return;
                }
            }

            new MainWindow(stage, username, host, port);

            // If MainWindow requests server selection (via Back), restart startup flow
            stage.setOnHidden(ev -> {
                if (MainWindow.requestServerSelection) {
                    MainWindow.requestServerSelection = false;
                    Platform.runLater(() -> {
                        try {
                            // restart the flow (name dialog + startup)
                            start(new Stage());
                        } catch (Exception ignored) {}
                    });
                } else {
                    Platform.exit();
                }
            });
        } catch (Exception ex) {
            showErrorAndExit("Startup failed: " + ex.getMessage());
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

    private void showErrorAndExit(String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(AlertType.ERROR);
            a.setTitle("KidPaint");
            a.setHeaderText(null);
            a.setContentText(msg);
            a.showAndWait();
            Platform.exit();
        });
    }
}
