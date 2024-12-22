package model;

import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;

import java.io.Serializable;
import java.nio.file.Path;
import java.util.UUID;

public class VideoMetadata implements Serializable {
    private String id;
    private String title;
    private String filePath;
    private long fileSize;
    double duration;

    public VideoMetadata() {
        this.id = UUID.randomUUID().toString();
    }

    // Constructeurs, getters et setters
    public VideoMetadata(Path videoPath) {
        this.id = UUID.randomUUID().toString();
        this.title = videoPath.getFileName().toString();
        this.filePath = videoPath.toString();
        try {
            this.fileSize = java.nio.file.Files.size(videoPath);
        } catch (java.io.IOException e) {
            this.fileSize = -1;
        }
    }

    // Getters et setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }
    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    @Override
    public String toString() {
        return title;
    }
}