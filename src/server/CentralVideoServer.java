package server;

import model.VideoMetadata;

import java.nio.file.Paths;
import java.util.*;
import java.util.stream.*;

public class CentralVideoServer {
    private final List<ChildVideoServer> childServers;

    public CentralVideoServer() {
        childServers = new ArrayList<>();
    }

    public void addDirectoryPath(String path, String mediaType) {
        path = "etc/media/"+ path;
        childServers.add(new ChildVideoServer(Paths.get(path), mediaType));
    }

    public List<VideoMetadata> getAllAvailableVideos() {
        return childServers.stream()
                .flatMap(server -> server.getAvailableVideos().stream())
                .collect(Collectors.toList());
    }
}