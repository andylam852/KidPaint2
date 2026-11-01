// java
import javafx.application.Platform;
import javafx.fxml.FXMLLoader;
import javafx.fxml.FXML;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.scene.Parent;
import javafx.embed.swing.SwingFXUtils;
import javafx.stage.Popup;
import javafx.geometry.Side;
import javax.imageio.ImageIO;
import java.io.*;
import java.net.Socket;
import java.util.*;

public class MainWindow {
    private double clamp01(double v) { return v < 0 ? 0 : (v > 1 ? 1 : v); }
    private Image colorMapLargeImage;
    private static final int NAME = 0;
    private static final int PIXELS = 1;
    private static final int TEXT = 2;
    private static final int SNAPSHOT = 3;
    private static final int STUDIOS_LIST = 4;
    private static final int JOIN_STUDIO = 5;
    private static final int CREATE_STUDIO = 6;
    public static volatile boolean requestServerSelection = false;
    private boolean isPenMode = true;
    private boolean isEraserMode = false;
    private int currentDrawColorARGB;

    @FXML private TextArea areaMsg;
    @FXML private TextField txtMsg;
    @FXML private Button btnSend;
    @FXML private ChoiceBox<String> chbMode;
    @FXML private Canvas canvas;
    @FXML private Pane container;
    @FXML private Pane panePicker;
    @FXML private Pane paneColor;
    @FXML private Button btnSave;
    @FXML private Button btnLoad;
    @FXML private Button btnBack;
    @FXML private Button btnColorMap; // up-arrow button
    @FXML private Button btnClear;
    @FXML private Button btnEmoji;

    private Socket socket;
    private DataInputStream in;
    private DataOutputStream out;

    private String username;
    private String currentStudio;
    private boolean initialStudioChosen = false;
    private List<String> studiosCache = new ArrayList<>();

    private int canvasWidthPixels = 50;
    private int canvasHeightPixels = 50;
    private int[][] data;
    private double pixelWidth;
    private double pixelHeight;
    private double startX = 0;
    private double startY = 0;

    private int selectedColorARGB;
    private int lastRow = -1, lastCol = -1;
    private final LinkedList<Point> filledPixels = new LinkedList<>();

    private Stage stage;
    private Image colorMapImage;
    private Popup colorPopup;

    static class Point { final int x, y; Point(int x, int y){ this.x = x; this.y = y; } }


    public MainWindow(Stage stage, String username, String host, int port) throws IOException {
        this.stage = stage;
        this.username = username == null ? "Player" : username;

        socket = new Socket(host, port);
        in = new DataInputStream(socket.getInputStream());
        out = new DataOutputStream(socket.getOutputStream());

        byte[] nb = this.username.getBytes();
        out.write(NAME);
        out.writeInt(nb.length);
        out.write(nb);
        out.flush();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("mainWindownUI.fxml"));
        loader.setController(this);
        Parent root = loader.load();
        Scene scene = new Scene(root);
        stage.setScene(scene);
        stage.setTitle("KidPaint 2.0");
        stage.setMinWidth(scene.getWidth());
        stage.setMinHeight(scene.getHeight());

        canvas.widthProperty().bind(container.widthProperty());
        canvas.heightProperty().bind(container.heightProperty());
        canvas.widthProperty().addListener(e -> onCanvasSizeChange());
        canvas.heightProperty().addListener(e -> onCanvasSizeChange());

        btnSend.setOnAction(e -> sendTextSafe());
        txtMsg.setOnAction(e -> sendTextSafe());
        btnSave.setOnAction(e -> saveSketch());
        btnLoad.setOnAction(e -> loadSketch());
        btnSave.setOnAction(e -> saveSketch());
        btnLoad.setOnAction(e -> loadSketch());
        if (btnBack != null) btnBack.setOnAction(e -> onBack());
        if (btnClear != null) btnClear.setOnAction(e -> clearCanvas());
        if (btnBack != null) btnBack.setOnAction(e -> onBack());

        if (areaMsg != null) {
            areaMsg.setStyle("-fx-font-size: 16px;");
        }
        if (txtMsg != null) {
            txtMsg.setStyle("-fx-font-size: 16px;");
        }

        data = new int[canvasHeightPixels][canvasWidthPixels];
        initColorMap();
        setupCanvasEvents();
        setupEmojiButton();

        // up-arrow button opens popup with larger color map
        if (btnColorMap != null) {
            btnColorMap.setOnAction(e -> showColorMapPopup());
        }

        stage.setOnCloseRequest(e -> quit());
        stage.show();

        Thread t = new Thread(this::receiveData, "ReceiveThread");
        t.setDaemon(true);
        t.start();

        onCanvasSizeChange();
        render();
    }

    private void setupEmojiButton() {
        ContextMenu emojiMenu = new ContextMenu();
        int[] cps = {
                0x1F44B, // 👋
                0x1F604, // 😄
                0x1F60D, // 😍
                0x1F62E, // 😮
                0x1F44D, // 👍
                0x1F600  // 😀
        };
        for (int cp : cps) {
            String emoji = new String(Character.toChars(cp));
            MenuItem mi = new MenuItem(emoji);
            mi.setOnAction(e -> {
                try { sendText(emoji); } catch (IOException ex) {
                    System.out.println("Send emoji failed: " + ex.getMessage());
                }
            });
            emojiMenu.getItems().add(mi);
        }
        btnEmoji.setOnMouseClicked(e -> {
            if (emojiMenu.isShowing()) emojiMenu.hide();
            emojiMenu.show(btnEmoji, Side.BOTTOM, 0, 0);
        });
    }

    private void clearCanvas() {
        // reset all cells to fully transparent (no color)
        int noColor = 0x00000000;
        for (int r = 0; r < canvasHeightPixels; r++) {
            Arrays.fill(data[r], noColor);
        }
        render();
        // broadcast snapshot so others see the reset
        try {
            out.write(SNAPSHOT);
            out.writeInt(canvasWidthPixels);
            out.writeInt(canvasHeightPixels);
            for (int y = 0; y < canvasHeightPixels; y++) {
                for (int x = 0; x < canvasWidthPixels; x++) {
                    out.writeInt(data[y][x]);
                }
            }
            out.flush();
        } catch (IOException ex) {
            System.out.println("Clear snapshot send failed: " + ex.getMessage());
        }
    }

    private void onBack() {
        requestServerSelection = true;
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        try { stage.close(); } catch (Exception ignored) {}
    }

    private void initColorMap() {
        try {
            // keep original inline color map in toolbar
            colorMapImage = new Image("file:color_map.png");

            // popup uses your pasted image; fallback to inline map if not found
            try {
                colorMapLargeImage = new Image("file:pasted_image_1.png");
                if (colorMapLargeImage.isError() || colorMapLargeImage.getWidth() <= 0) {
                    colorMapLargeImage = colorMapImage;
                }
            } catch (Exception ignored) {
                colorMapLargeImage = colorMapImage;
            }

            // inline picker thumbnail (unchanged)
            ImageView smallView = new ImageView(colorMapImage);
            smallView.setFitHeight(30.0);
            smallView.setPreserveRatio(true);
            panePicker.getChildren().clear();
            panePicker.getChildren().add(smallView);

            smallView.setOnMouseClicked(e -> {
                double displayedW = smallView.getBoundsInLocal().getWidth();
                double displayedH = smallView.getBoundsInLocal().getHeight();
                double scaleX = (displayedW > 0) ? (colorMapImage.getWidth() / displayedW) : 1.0;
                double scaleY = (displayedH > 0) ? (colorMapImage.getHeight() / displayedH) : 1.0;
                int ix = (int)(e.getX() * scaleX);
                int iy = (int)(e.getY() * scaleY);
                pickColor(colorMapImage, ix, iy);
            });

            // initialize preview
            pickColor(colorMapImage, 0, 0);
        } catch (Exception e) {
            selectedColorARGB = 0xFF000000;
            if (paneColor != null) paneColor.setStyle("-fx-background-color:#000000;");
        }
    }

    private void showColorMapPopup() {
        if (btnColorMap == null) return;
        if (colorPopup != null && colorPopup.isShowing()) { colorPopup.hide(); return; }

        int w = 480;   // wide hue sweep
        int h = 160;   // taller to show dark->white gradient
        Image useImage = createColorWorldImage(w, h);

        ImageView largeView = new ImageView(useImage);
        largeView.setPreserveRatio(false);
        largeView.setFitWidth(w);
        largeView.setFitHeight(h);

        Pane content = new Pane(largeView);
        content.setPrefSize(w, h);
        content.setStyle("-fx-background-color: white; -fx-border-color: #888; -fx-border-width: 1; "
                + "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 10, 0.3, 0, 2);");

        largeView.setOnMouseClicked(e -> {
            double scaleX = useImage.getWidth() / largeView.getBoundsInLocal().getWidth();
            double scaleY = useImage.getHeight() / largeView.getBoundsInLocal().getHeight();
            int ix = (int)Math.floor(e.getX() * scaleX);
            int iy = (int)Math.floor(e.getY() * scaleY);
            pickColor(useImage, ix, iy);
            if (colorPopup != null) colorPopup.hide();
        });

        colorPopup = new Popup();
        colorPopup.getContent().setAll(content);
        colorPopup.setAutoHide(true);

        javafx.geometry.Bounds b = btnColorMap.localToScreen(btnColorMap.getBoundsInLocal());
        colorPopup.show(stage, b.getMinX(), b.getMaxY() + 4);
    }

    private Image createColorWorldImage(int width, int height) {
        WritableImage img = new WritableImage(width, height);
        PixelWriter pw = img.getPixelWriter();

        // Brighter tunables
        final double minValueTop = 0.40;   // brighter top band
        final double valueGain   = 0.60;   // added brightness range
        final double valueGamma  = 0.50;   // faster rise to bright
        final double satFadeMax  = 0.55;   // keep more saturation toward bottom
        final double satGamma    = 1.00;   // linear fade

        for (int x = 0; x < width; x++) {
            double hue = 360.0 * (x / (double)(width - 1));
            for (int y = 0; y < height; y++) {
                double vy = y / (double)(height - 1);

                // brighter value and gentler desaturation
                double value = minValueTop + valueGain * Math.pow(vy, valueGamma);
                double saturation = 1.0 - satFadeMax * Math.pow(vy, satGamma);

                Color c = Color.hsb(hue, clamp01(saturation), clamp01(value));
                int a = 0xFF;
                int r = (int)Math.round(c.getRed() * 255.0);
                int g = (int)Math.round(c.getGreen() * 255.0);
                int b = (int)Math.round(c.getBlue() * 255.0);
                pw.setArgb(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return img;
    }



    private void pickColor(Image image, int x, int y) {
        if (image == null) return;
        if (x < 0 || y < 0 || x >= (int)image.getWidth() || y >= (int)image.getHeight()) return;
        PixelReader reader = image.getPixelReader();
        selectedColorARGB = reader.getArgb(x, y);
        Color c = reader.getColor(x, y);
        String hex = c.toString().substring(2);
        if (hex.length() >= 6) hex = hex.substring(0, 6);

        if (paneColor != null) {
            paneColor.setStyle("-fx-background-color:#" + hex + "; -fx-border-color: gray; -fx-border-width: 1;");
        }
    }

    private void setupCanvasEvents() {
        chbMode.setValue("Pen");
        canvas.setOnMousePressed(e -> {
            String mode = chbMode.getValue();
            isPenMode = "Pen".equals(mode);
            isEraserMode = "Eraser".equals(mode);
            filledPixels.clear();

            // decide stroke color
            currentDrawColorARGB = isEraserMode ? 0x00000000 : selectedColorARGB;

            if (isPenMode || isEraserMode) {
                penToData(e.getX(), e.getY());
            }
        });
        canvas.setOnMouseDragged(e -> {
            if (isPenMode || isEraserMode) {
                penToData(e.getX(), e.getY());
            }
        });
        canvas.setOnMouseReleased(e -> {
            if (!isPenMode && !isEraserMode) {
                bucketToData(e.getX(), e.getY());
            }
            try {
                if (!filledPixels.isEmpty()) sendPixelChanges();
            } catch (IOException ex) {
                System.out.println("Send pixels failed: " + ex.getMessage());
            }
            lastCol = lastRow = -1;
        });
    }



    private void receiveData() {
        try {
            while (true) {
                int type = in.read();
                if (type == -1) break;
                switch (type) {
                    case STUDIOS_LIST: receiveStudioList(); break;
                    case SNAPSHOT: receiveSnapshot(); break;
                    case PIXELS: receivePixels(); break;
                    case TEXT: receiveText(); break;
                    default: System.out.println("Unknown message type: " + type);
                }
            }
        } catch (IOException ex) {
            System.out.println("Connection lost: " + ex.getMessage());
        }
    }

    private void receiveStudioList() throws IOException {
        int count = in.readInt();
        List<String> studios = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int len = in.readInt();
            byte[] b = new byte[len];
            in.readFully(b, 0, len);
            studios.add(new String(b, 0, len));
        }
        studiosCache.clear();
        studiosCache.addAll(studios);

        if (!initialStudioChosen) {
            initialStudioChosen = true;
            Platform.runLater(() -> promptForInitialStudio(studios));
        }
    }

    private void promptForInitialStudio(List<String> studios) {
        List<String> choices = new ArrayList<>(studios);
        String createOpt = "Create New Studio...";
        choices.add(createOpt);

        ChoiceDialog<String> dlg = new ChoiceDialog<>(choices.isEmpty() ? createOpt : choices.get(0), choices);
        dlg.setTitle("Select Studio");
        dlg.setHeaderText("Choose or create a studio");

        Optional<String> opt = dlg.showAndWait();
        if (!opt.isPresent()) {
            requestServerSelection = true;
            try { socket.close(); } catch (IOException ignored) {}
            stage.close();
            return;
        }
        String selected = opt.get();
        if (createOpt.equals(selected)) createStudioFlow();
        else joinStudio(selected);
    }

    private void joinStudio(String name) {
        try {
            byte[] nb = name.getBytes();
            out.write(JOIN_STUDIO);
            out.writeInt(nb.length);
            out.write(nb);
            out.flush();
            currentStudio = name;
        } catch (IOException e) {
            System.out.println("Join failed: " + e.getMessage());
        }
    }

    private void createStudioFlow() {
        TextInputDialog nameDlg = new TextInputDialog("Studio");
        nameDlg.setTitle("New Studio");
        nameDlg.setHeaderText("Enter studio base name");
        String base = nameDlg.showAndWait().orElse("Studio");

        TextInputDialog widthDlg = new TextInputDialog("80");
        widthDlg.setTitle("Canvas Width");
        widthDlg.setHeaderText("Enter width (pixels)");
        int w;
        try { w = Integer.parseInt(widthDlg.showAndWait().orElse("80")); if (w <= 0) w = 50; } catch (Exception e){ w = 50; }

        TextInputDialog heightDlg = new TextInputDialog("50");
        heightDlg.setTitle("Canvas Height");
        heightDlg.setHeaderText("Enter height (pixels)");
        int h;
        try { h = Integer.parseInt(heightDlg.showAndWait().orElse("50")); if (h <= 0) h = 50; } catch (Exception e){ h = 50; }

        try {
            byte[] nb = base.getBytes();
            out.write(CREATE_STUDIO);
            out.writeInt(nb.length);
            out.write(nb);
            out.writeInt(w);
            out.writeInt(h);
            out.flush();
        } catch (IOException ex) {
            System.out.println("Create studio failed: " + ex.getMessage());
        }
    }

    private void receiveSnapshot() throws IOException {
        int w = in.readInt();
        int h = in.readInt();
        if (w <= 0 || h <= 0) return;
        int[][] newData = new int[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                newData[y][x] = in.readInt();
            }
        }
        data = newData;
        canvasWidthPixels = w;
        canvasHeightPixels = h;
        Platform.runLater(() -> {
            onCanvasSizeChange();
            render();
        });
    }

    private void receivePixels() throws IOException {
        int color = in.readInt();
        int count = in.readInt();
        for (int i = 0; i < count; i++) {
            int x = in.readInt();
            int y = in.readInt();
            if (y >= 0 && y < data.length && x >= 0 && x < data[0].length)
                data[y][x] = color;
        }
        Platform.runLater(this::render);
    }

    private void receiveText() throws IOException {
        int size = in.readInt();
        byte[] buf = new byte[size];
        in.readFully(buf, 0, size);
        String msg = new String(buf, 0, size);
        Platform.runLater(() -> areaMsg.appendText(msg + "\n"));
    }

    private void sendTextSafe() {
        String msg = txtMsg.getText().trim();
        if (msg.isEmpty()) return;
        try {
            sendText(msg);
            txtMsg.clear();
        } catch (IOException ex) {
            System.out.println("Send text failed: " + ex.getMessage());
        }
    }

    private void sendText(String text) throws IOException {
        byte[] tb = text.getBytes();
        out.write(TEXT);
        out.writeInt(tb.length);
        out.write(tb);
        out.flush();
    }

    private void sendPixelChanges() throws IOException {
        if (filledPixels.isEmpty()) return;
        out.write(PIXELS);
        out.writeInt(currentDrawColorARGB); // send the stroke color (pen or eraser)
        out.writeInt(filledPixels.size());
        for (Point p : filledPixels) {
            out.writeInt(p.x);
            out.writeInt(p.y);
        }
        out.flush();
        filledPixels.clear();
    }

    private void onCanvasSizeChange() {
        double w = canvas.getWidth();
        double h = canvas.getHeight();
        if (canvasWidthPixels <= 0 || canvasHeightPixels <= 0) return;

        double rawScale = Math.min(w / (double) canvasWidthPixels, h / (double) canvasHeightPixels);
        double scale = Math.floor(rawScale);
        if (scale < 1) scale = 1;

        pixelWidth = pixelHeight = scale;

        startX = (w - canvasWidthPixels * pixelWidth) / 2.0;
        startY = (h - canvasHeightPixels * pixelHeight) / 2.0;

        if (startX < 0) startX = 0;
        if (startY < 0) startY = 0;

        Platform.runLater(this::render);
    }

    private void render() {
        if (canvas == null || data == null) return;
        GraphicsContext gc = canvas.getGraphicsContext2D();
        gc.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());

        double cellSize = Math.min(pixelWidth, pixelHeight);
        double dotDiameter = Math.max(1.0, cellSize * 0.9);

        for (int row = 0; row < canvasHeightPixels; row++) {
            for (int col = 0; col < canvasWidthPixels; col++) {
                int argb = data[row][col];
                Color c = fromARGB(argb);

                double x = startX + col * pixelWidth + (pixelWidth - dotDiameter) / 2.0;
                double y = startY + row * pixelHeight + (pixelHeight - dotDiameter) / 2.0;

                // fill
                gc.setFill(c);
                gc.fillOval(x, y, dotDiameter, dotDiameter);

                // fixed gray outline
                gc.setStroke(Color.GRAY);
                gc.setLineWidth(Math.max(1.0, dotDiameter * 0.06));
                gc.strokeOval(x, y, dotDiameter, dotDiameter);
            }
        }
    }

    private Color fromARGB(int argb) {
        int a = (argb >> 24) & 0xFF;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        return Color.rgb(r, g, b, a / 255.0);
    }

    private void penToData(double mx, double my) {
        if (!inside(mx, my)) return;
        int row = (int)((my - startY) / pixelHeight);
        int col = (int)((mx - startX) / pixelWidth);
        if (row != lastRow || col != lastCol) {
            if (lastRow != -1 && lastCol != -1) {
                drawLine(lastCol, lastRow, col, row);
            } else {
                setPixel(col, row);
            }
            lastRow = row;
            lastCol = col;
            render();
        }
    }

    private void drawLine(int x0, int y0, int x1, int y1) {
        int dx = Math.abs(x1 - x0);
        int dy = Math.abs(y1 - y0);
        int sx = x0 < x1 ? 1 : -1;
        int sy = y0 < y1 ? 1 : -1;
        int err = dx - dy;
        while (true) {
            setPixel(x0, y0);
            if (x0 == x1 && y0 == y1) break;
            int e2 = err * 2;
            if (e2 > -dy) { err -= dy; x0 += sx; }
            if (e2 < dx) { err += dx; y0 += sy; }
        }
    }

    private void setPixel(int col, int row) {
        if (row >= 0 && row < canvasHeightPixels && col >= 0 && col < canvasWidthPixels) {
            data[row][col] = currentDrawColorARGB; // pen or eraser color
            filledPixels.add(new Point(col, row));
        }
    }

    public void paintArea(int col, int row) {
        int oriColor = data[row][col];
        if (oriColor == selectedColorARGB) return;
        LinkedList<Point> queue = new LinkedList<>();
        queue.add(new Point(col, row));
        while (!queue.isEmpty()) {
            Point p = queue.removeFirst();
            int x = p.x, y = p.y;
            if (x < 0 || x >= canvasWidthPixels || y < 0 || y >= canvasHeightPixels) continue;
            if (data[y][x] != oriColor) continue;
            data[y][x] = selectedColorARGB;
            filledPixels.add(new Point(x, y));
            queue.add(new Point(x+1, y));
            queue.add(new Point(x-1, y));
            queue.add(new Point(x, y+1));
            queue.add(new Point(x, y-1));
        }
    }

    private void bucketToData(double mx, double my) {
        if (!inside(mx, my)) return;
        int row = (int)((my - startY) / pixelHeight);
        int col = (int)((mx - startX) / pixelWidth);
        paintArea(col, row);
        render();
    }

    private boolean inside(double mx, double my) {
        return mx >= startX && mx < startX + canvasWidthPixels * pixelWidth &&
                my >= startY && my < startY + canvasHeightPixels * pixelHeight;
    }

    private void saveSketch() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Save Sketch");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        File file = fc.showSaveDialog(stage);
        if (file == null) return;
        WritableImage img = new WritableImage(canvasWidthPixels, canvasHeightPixels);
        PixelWriter pw = img.getPixelWriter();
        for (int r = 0; r < canvasHeightPixels; r++) {
            for (int c = 0; c < canvasWidthPixels; c++) {
                pw.setArgb(c, r, data[r][c]);
            }
        }
        try {
            ImageIO.write(SwingFXUtils.fromFXImage(img, null), "png", file);
        } catch (IOException e) {
            System.out.println("Save failed: " + e.getMessage());
        }
    }

    private void loadSketch() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Load Sketch");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG Image", "*.png"));
        File file = fc.showOpenDialog(stage);
        if (file == null) return;
        Image img;
        try {
            img = new Image(new FileInputStream(file));
        } catch (FileNotFoundException e) {
            System.out.println("Load failed: " + e.getMessage());
            return;
        }
        int w = (int) img.getWidth();
        int h = (int) img.getHeight();
        int[][] newData = new int[h][w];
        PixelReader pr = img.getPixelReader();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                newData[y][x] = pr.getArgb(x, y);
            }
        }
        data = newData;
        canvasWidthPixels = w;
        canvasHeightPixels = h;
        onCanvasSizeChange();
        render();
    }

    private void quit() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        try { stage.close(); } catch (Exception ignored) {}
    }
}
