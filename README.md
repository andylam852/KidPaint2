# KidPaint 2

A collaborative drawing app built with JavaFX. Users can connect to a shared local server, create or join studios, draw together in real time, and chat with each other.

## Features

- Real-time collaborative canvas updates
- Studio creation and joining
- Shared drawing workspace
- Text chat and emoji support
- Save and load sketches
- Pen, eraser, and bucket tools
- Color picker and custom palette

## Tech Stack

- Java 24
- JavaFX 24
- Socket-based networking
- IntelliJ IDEA

## Getting Started

### Requirements

- JDK 24+
- JavaFX SDK 24.0.2
- IntelliJ IDEA or another Java IDE

### Run

1. Open the project in IntelliJ IDEA.
2. Configure the JavaFX SDK path to `javafx-sdk-24.0.2/`.
3. Run either `src/KidPaint.java` or `src/KidPaint2.java`.

## Project Structure

```text
KidPaint2/
├── src/
├── javafx-sdk-24.0.2/
├── color_map.png
├── kidpaint2_screenshot.png
├── README.md
└── .gitignore
```

## Notes

- Default local server port: `12345`
- Discovery port: `54321`

## License

This project is provided as-is for demonstration and personal or team use.
