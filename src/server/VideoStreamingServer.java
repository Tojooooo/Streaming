/*
        -- windows
--module-path
D:/S3/progSys/javaExt/javafx-sdk-21.0.5/lib
--add-modules
javafx.controls,javafx.fxml,javafx.media
--add-exports
javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED

        -- ubuntu
--module-path
/home/itu/javaExt/openjfx-21.0.5_linux-x64_bin-sdk/javafx-sdk-21.0.5/lib
--add-modules
javafx.controls,javafx.fxml,javafx.media
--add-exports
javafx.graphics/com.sun.javafx.sg.prism=ALL-UNNAMED
*/
package server;

import model.VideoMetadata;
import inc.CSVReader;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class VideoStreamingServer {
    private int port;
    private int chunk_size;
    private int max_buffer_seconds; // Maximum buffer ahead of playback
    private String media_type;
    private String[] video_directories;

    private final CentralVideoServer centralServer;
    // Map to track client-specific streaming state
    private final ConcurrentHashMap<Socket, ClientStreamingState> clientStreamingStates = new ConcurrentHashMap<>();

    public VideoStreamingServer(String configPath) {
        centralServer = new CentralVideoServer();

        CSVReader.importConfig(configPath, this);

        for (String directory: video_directories) {
            centralServer.addDirectoryPath(directory, media_type);
        }
    }

    public void start() {
        try (
                ServerSocket serverSocket = new ServerSocket(port);
        ) {
            System.out.println("Video Streaming Server started on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                System.out.println("Client connected: " + clientSocket.getInetAddress());

                // Handle client connection in a new thread
                Boolean clientStreamingState = true;
                new Thread(() -> handleClientConnection(clientSocket, media_type, clientStreamingState)).start();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void handleClientConnection(Socket clientSocket, String media_type, Boolean clientStreamingState) {
        // Create a streaming state for this client
        ClientStreamingState streamingState = new ClientStreamingState();
        clientStreamingStates.put(clientSocket, streamingState);

        try (
                ObjectOutputStream out = new ObjectOutputStream(clientSocket.getOutputStream());
                ObjectInputStream in = new ObjectInputStream(clientSocket.getInputStream());
        ) {
            // Send available videos to the client
            List<VideoMetadata> availableVideos = centralServer.getAllAvailableVideos();
            out.writeObject(availableVideos);
            out.flush();

            // Wait for video selection or other commands
            while (true) {
                String command = (String) in.readObject();
                System.out.println(command);
                if ("EXIT".equals(command)) {
                    clientStreamingState = in.readBoolean();
                    System.out.println(clientStreamingState);
                    if (!clientStreamingState) {
                        break;
                    } else {
                        streamingState.reset();
                        continue;
                    }
                }

                if (command.startsWith("STREAM:")) {
                    String videoId = command.substring(7);
                    streamVideoToClient(videoId, out, in, clientSocket, media_type);
                } else if (command.startsWith("PLAYBACK_TIME:")) {
                    // Update client's current playback time
                    double playbackTime = Double.parseDouble(command.substring(14));
                    double totalTime = in.readDouble();
                    streamingState.setCurrentPlaybackTime(playbackTime);
                    streamingState.setTotalTime(totalTime);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            System.out.println("aborted");
        } finally {
            clientStreamingStates.remove(clientSocket);
            try {
                clientSocket.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void streamVideoToClient(String videoId, ObjectOutputStream out, ObjectInputStream in, Socket clientSocket, String media_type) {
        VideoMetadata video = centralServer.getAllAvailableVideos().stream()
                .filter(v -> v.getId().equals(videoId))
                .findFirst()
                .orElse(null);

        if (video == null) {
            try {
                out.writeObject("VIDEO_ERROR");
                out.flush();
                return;
            } catch (IOException e) {
                e.printStackTrace();
                return;
            }
        }

        ClientStreamingState streamingState = clientStreamingStates.get(clientSocket);

        try (
                BufferedInputStream fileInputStream = new BufferedInputStream(new FileInputStream(video.getFilePath()));
        ) {
            // Send video metadata first
            out.writeObject("VIDEO_START");
            out.writeObject(media_type);
            out.writeObject(videoId);
            long fileSize = Files.size(Paths.get(video.getFilePath()));
            out.writeLong(fileSize);

            // Stream video in chunks with buffer control
            byte[] buffer = new byte[chunk_size];
            int bytesRead;
            while ((bytesRead = fileInputStream.read(buffer)) != -1) {
                // Wait if buffer is too large (large distance between playback and chunks)
                while (streamingState.getBytesWritten() > (streamingState.getCurrentPlaybackTime() + max_buffer_seconds) * (fileSize / streamingState.getTotalTime())) {
                    out.writeObject("WAITING");
                    out.flush();
                    System.out.println("sending waiting");

                    try {
                        updateClientStreamingState(streamingState, out, in);
                    } catch (IOException e) {
                        break;
                    }

                    Thread.sleep(1000); // Wait and check again
                }


                if (streamingState.getLastPlaybackRequest() >= 100) {
                    out.writeObject("GET_PLAYBACK");
                    System.out.println(streamingState.getCurrentPlaybackTime() +";"+ streamingState.getTotalTime());
                    try {
                        updateClientStreamingState(streamingState, out, in);
                    } catch (IOException e) {
                        break;
                    }
                    streamingState.setLastPlaybackRequest(0);
                }

                // sending chunks if not waiting
                System.out.println("sending data");
                out.writeObject("VIDEO_CHUNK");
                out.writeInt(bytesRead);
                out.write(buffer, 0, bytesRead);
                out.flush();
                streamingState.incrementLastPlaybackRequest();

                // Update bytes written
                streamingState.incrementBytesWritten(bytesRead);

            }
            System.out.println("vita");

            // Signal end of video stream
            out.writeObject("VIDEO_END");
            out.flush();
        } catch (IOException | InterruptedException e) { // stream error case
            try {
                out.writeObject("VIDEO_ERROR");
                out.flush();
            } catch (IOException ex) {
                ex.printStackTrace();
            }
            e.printStackTrace();
        }
    }

    public void updateClientStreamingState(ClientStreamingState streamingState, ObjectOutputStream out, ObjectInputStream in) throws IOException {
        double playbackTime = in.readDouble();
        double totalTime = in.readDouble();
        streamingState.setCurrentPlaybackTime(playbackTime);
        streamingState.setTotalTime(totalTime);
    }

    // Inner class to track client-specific streaming state
    private static class ClientStreamingState {
        private volatile long bytesWritten = 0; // amount of data written to the file
        private volatile double currentPlaybackTime = 0;
        private volatile double totalTime = 0;
        private volatile int lastPlaybackRequest = 0;

        public synchronized void incrementBytesWritten(int bytes) {
            bytesWritten += bytes;
        }

        public synchronized void incrementLastPlaybackRequest() {
            lastPlaybackRequest++;
        }

        public synchronized long getBytesWritten() {
            return bytesWritten;
        }

        public synchronized void setCurrentPlaybackTime(double time) {
            this.currentPlaybackTime = time;
        }

        public synchronized double getCurrentPlaybackTime() {
            return currentPlaybackTime;
        }

        public synchronized double getTotalTime() {
            return totalTime;
        }

        public synchronized void setTotalTime(double totalTime) {
            this.totalTime = totalTime;
        }

        public synchronized int getLastPlaybackRequest() {
            return lastPlaybackRequest;
        }

        public synchronized void setLastPlaybackRequest(int lastPlaybackRequest) {
            this.lastPlaybackRequest = lastPlaybackRequest;
        }

        public synchronized void reset() {
            bytesWritten = 0;
            currentPlaybackTime = 0;
            totalTime = 0;
            lastPlaybackRequest = 0;
        }
    }

    public static void main(String[] args) {
        new VideoStreamingServer("etc/config/server.csv").start();
    }
}