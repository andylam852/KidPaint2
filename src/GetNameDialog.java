// java
import javafx.event.Event;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.io.IOException;

public class GetNameDialog {
    @FXML
    TextField nameField;

    @FXML
    Button goButton;

    @FXML
    Button cancelButton;

    Stage stage;
    String playername;

    public GetNameDialog(String title) throws IOException {
        FXMLLoader loader = new FXMLLoader(getClass().getResource("getNameUI.fxml"));
        loader.setController(this);
        Parent root = loader.load();
        Scene scene = new Scene(root);

        stage = new Stage();
        stage.setScene(scene);
        stage.setTitle(title);
        stage.setMinWidth(scene.getWidth());
        stage.setMinHeight(scene.getHeight());

        goButton.setOnMouseClicked(this::OnButtonClick);
        cancelButton.setOnMouseClicked(this::OnCancelClick);

        stage.showAndWait();
    }

    @FXML
    void OnButtonClick(Event event) {
        playername = nameField.getText().trim();
        if (playername.length() > 0)
            stage.close();
    }

    @FXML
    void OnCancelClick(Event event) {
        playername = null;
        stage.close();
    }

    public String getPlayername() {
        return playername;
    }
}
