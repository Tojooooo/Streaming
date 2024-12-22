package server;

import model.VideoMetadata;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class ChildVideoServer {
    private final List<VideoMetadata> videos;
    private final Path storageDirectory;

    public ChildVideoServer(Path storageDirectory, String mediaType) {
        this.storageDirectory = storageDirectory;
        this.videos = scanMediasInDirectory(mediaType);
    }

    private List<VideoMetadata> scanMediasInDirectory(String mediaType) {
        List<VideoMetadata> foundVideos = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(storageDirectory)) {
            foundVideos = paths
                    .filter(path -> path.toString().toLowerCase().endsWith(mediaType))
                    .map(VideoMetadata::new)
                    .collect(Collectors.toList());
        } catch (IOException e) {
            e.printStackTrace();
        }
        return foundVideos;
    }

    public List<VideoMetadata> getAvailableVideos() {
        return videos;
    }

}