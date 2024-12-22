package client;

import inc.CSVReader;
import model.VideoMetadata;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.util.Duration;
import javafx.geometry.Pos;

import java.io.*;
import java.net.Socket;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class VideoStreamingClient extends Application {
    private String server_host;
    private int server_port;

    private Socket socket;
    private ObjectOutputStream out;
    private ObjectInputStream in;
    private MediaView mediaView;
    private MediaPlayer mediaPlayer;
    private Media media;
    private Stage videoStage;
    private ListView<VideoMetadata> videoList;

    // Chunked streaming variables
    private final String configPath = "etc/config/client.csv";
    private Path tempVideoFile;
    private BufferedOutputStream tempFileOutputStream;
    private volatile boolean isStreamingActive = false;

    @Override
    public void start(Stage primaryStage) {
        try {
            // import settings
            CSVReader.importConfig(configPath, this);

            // Establish socket connection
            socket = new Socket(server_host, server_port);
            out = new ObjectOutputStream(socket.getOutputStream());
            in = new ObjectInputStream(socket.getInputStream());

            // Setup video listing UI
            initializeVideoView();

            // Listen for server responses in a separate thread
            new Thread(this::listenForServerResponses).start();
            isStreamingActive = true;

            // Setup JavaFX UI
            initializePrimaryStage(primaryStage);

        } catch (IOException | ClassNotFoundException e) {
            e.printStackTrace();
            showErrorDialog("Connection Error", "Could not connect to the server.");
        }
    }

    private void listenForServerResponses() {
        try {
            while (true) {
                Object response = in.readObject();

                switch (response.toString()) {

                    case "VIDEO_START" -> {
                        // Prepare for video streaming
                        isStreamingActive = true;
                        String videoId = (String) in.readObject();
                        String mediaType = (String) in.readObject();
                        long fileSize = in.readLong();

                        // Create a temporary file for the video
                        tempVideoFile = Files.createTempFile("streaming", mediaType);
                        tempFileOutputStream = new BufferedOutputStream(new FileOutputStream(tempVideoFile.toFile()));

                        // Start a thread to handle progressive video playing
                        new Thread(() -> startProgressiveVideoPlayback()).start();
                    }
                    case "GET_PLAYBACK" -> {
                        if (mediaPlayer == null || media == null) { // if not playing the video/audio
                            out.writeDouble(0);
                            out.writeDouble(200);
                            out.flush();
                        } else { // if playing
                            try {
                                sendPlaybackDurations();
                            } catch (Exception e) {
                                break;
                            }
                        }
                    }
                    case "VIDEO_CHUNK" -> {
                        // Receive and save video chunk
                        if(isStreamingActive) {
                            int bytesRead = in.readInt();
                            byte[] buffer = new byte[bytesRead];
                            in.readFully(buffer);

                            if (tempFileOutputStream != null) {
                                tempFileOutputStream.write(buffer);
                                tempFileOutputStream.flush();
                            }
                        }
                        System.out.println("receiving data");
                    }
                    case "WAITING" -> {
                        try {
                            sendPlaybackDurations();
                        } catch (Exception e) {
                            break;
                        }
                        System.out.println("receiving suspended");
                    }
                    case "VIDEO_END" -> {
                        // Close the file stream when video is fully received
                        if (tempFileOutputStream != null) {
                            tempFileOutputStream.close();
                            tempFileOutputStream = null;
                        }
                        System.out.println("vita");
                    }
                    case "VIDEO_ERROR" -> Platform.runLater(() -> showErrorDialog("Streaming Error", "Could not stream the video."));
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            Platform.runLater(() -> {
                try {
                    isStreamingActive = false;
                    socket = new Socket(server_host, server_port);
                    out = new ObjectOutputStream(socket.getOutputStream());
                    in = new ObjectInputStream(socket.getInputStream());
                } catch (IOException ex) {
                    throw new RuntimeException(ex);
                }

                new Thread(this::listenForServerResponses).start();
            });
//            Platform.runLater(() -> showErrorDialog("Connection Lost", "Lost connection to the server."));
        }
    }

    private void startProgressiveVideoPlayback() {
        try {
            // Wait until some initial data is received
            while (tempFileOutputStream == null || tempVideoFile == null ||
                    Files.size(tempVideoFile) < 1024 * 1024) { // Wait until at least 1MB is downloaded
                Thread.sleep(100);
            }

            Platform.runLater(() -> {
                media = new Media(tempVideoFile.toUri().toString());

// initialisation videoStage
                if (videoStage == null) {
                    videoStage = new Stage();
                }

// configuration bouton pause
                Button button = new Button("| |");
                button.setOnMouseClicked(event -> {
                    if (mediaPlayer.getStatus() == MediaPlayer.Status.PLAYING) {
                        mediaPlayer.pause();
                        button.setText(">");
                    } else {
                        mediaPlayer.play();
                        button.setText("| |");
                    }
                });
                button.setStyle("-fx-background-color: #4CAF50; -fx-text-fill: white; -fx-font-size: 16px; -fx-padding: 10px 20px;");

// Configuration de la progressBar
                Slider progressBar = new Slider();
                progressBar.setMin(0);
                progressBar.setMax(100);
                progressBar.setPrefWidth(500);
                progressBar.setMajorTickUnit(10);
                progressBar.setShowTickMarks(false);
                progressBar.setShowTickLabels(false);
                progressBar.setStyle("-fx-accent: #4CAF50;"); // Couleur verte du progrès

                /// logique
                AtomicBoolean isDragging = new AtomicBoolean(false);

                progressBar.setOnMousePressed(event -> {
                    if (isStreamingActive) {
                        isDragging.set(true);
                    }
                });
                progressBar.setOnMouseReleased(event -> {
                    if (isStreamingActive) {
                        isDragging.set(false);
                        double newTime = progressBar.getValue() / 100 * mediaPlayer.getTotalDuration().toSeconds();
                        mediaPlayer.seek(Duration.seconds(newTime));
                    }
                });

                progressBar.setOnMouseDragged(event -> {
                    if (isStreamingActive) {
                        double newTime = progressBar.getValue() / 100 * mediaPlayer.getTotalDuration().toSeconds();
                        mediaPlayer.seek(Duration.seconds(newTime));
                    }
                });

// Configuration mediaView
                mediaView = new MediaView();
                mediaView.fitWidthProperty().bind(videoStage.widthProperty());
                mediaView.fitHeightProperty().bind(videoStage.heightProperty());
                mediaView.setPreserveRatio(true); // Preserve aspect ratio

// Configuration du MediaPlayer
                mediaPlayer = new MediaPlayer(media);
                mediaView.setMediaPlayer(mediaPlayer);

                mediaPlayer.currentTimeProperty().addListener((obs, oldTime, newTime) -> {
                    if (!isDragging.get() && isStreamingActive) {
                        double progress = newTime.toSeconds() / mediaPlayer.getTotalDuration().toSeconds();
                        progressBar.setValue(progress * 100);
                    }
                });

                mediaPlayer.play();


// Création de la disposition principale
                BorderPane rootComponent = new BorderPane();
                rootComponent.setCenter(mediaView);
                rootComponent.setStyle("-fx-background-color: #121212;");

// layout (boutons, progress bar)
                HBox optionsLayout = new HBox(20);
                optionsLayout.getChildren().addAll(button, progressBar);

                optionsLayout.setAlignment(Pos.CENTER); // Centrer les éléments
                optionsLayout.setPadding(new Insets(15)); // Espacement interne
                optionsLayout.setStyle("-fx-background-color: #2a2a2a;"); // Fond sombre


// Positionnement de l'optionsLayout dans le BorderPane
                rootComponent.setTop(optionsLayout);

// Création de la scène
                Scene scene = new Scene(rootComponent, 1000, 1000);

// Configuration du videoStage
                videoStage.setTitle("Vidéo");
                videoStage.setScene(scene);

                /// video closing actions
                videoStage.setOnCloseRequest(event -> {
                    // Stop stream
                    isStreamingActive = false;

                    // Stop and dispose of media player
                    if (mediaPlayer != null) {
                        mediaPlayer.stop();
                        mediaPlayer.dispose();
                        mediaPlayer = null;
                    }

                    // Close temporary file stream
                    try {
                        if (tempFileOutputStream != null) {
                            tempFileOutputStream.close();
                            tempFileOutputStream = null;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Delete temporary video file
                    try {
                        if (tempVideoFile != null) {
                            Files.deleteIfExists(tempVideoFile);
                            tempVideoFile = null;
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Send command to server
                    try {
                        out.writeObject("EXIT");
                        out.writeBoolean(true);
                        out.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Close video stage
                    videoStage.close();
                });

                videoStage.show();

            });

        } catch (Exception e) {
            e.printStackTrace();
            Platform.runLater(() -> showErrorDialog("Video Error", "Could not play the video."));
        }
    }

    private void initializePrimaryStage(Stage primaryStage) {
        VBox layout = new VBox(10);
        layout.getChildren().addAll(videoList);

        Scene scene = new Scene(layout, 800, 600);
        primaryStage.setTitle("Liste vidéos");
        primaryStage.setScene(scene);
        primaryStage.setOnCloseRequest(event -> {
            try {
                out.writeObject("EXIT");
                out.writeBoolean(false);
                socket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
            System.exit(0);
        });

        primaryStage.show();
    }

    private void initializeVideoView() throws IOException, ClassNotFoundException {
        // Receive available videos from server
        List<VideoMetadata> availableVideos = (List<VideoMetadata>) in.readObject();
        videoList = new ListView<>();
        Platform.runLater(() -> {
            videoList.getItems().addAll(availableVideos);
        });

        // Video selection event
        videoList.setOnMouseClicked(event -> {
            VideoMetadata selectedVideo = videoList.getSelectionModel().getSelectedItem();
            if (selectedVideo != null) {
                try {
                    // Reset previous streaming setup
                    if (tempVideoFile != null) {
                        Files.deleteIfExists(tempVideoFile);
                    }

                    // Request video streaming
                    out.writeObject("STREAM:" + selectedVideo.getId());
                    out.flush();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });
    }

    private void sendPlaybackDurations() throws Exception {
        // send playback durations
        double currentTime = mediaPlayer.getCurrentTime().toSeconds();
        double totalTime = media.getDuration().toSeconds();
        System.out.println(currentTime +"; "+ totalTime);
        out.writeDouble(currentTime);
        out.writeDouble(totalTime);
        out.flush();
    }

    private void showErrorDialog(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }

    public static void main(String[] args) {
        launch(args);
    }
}