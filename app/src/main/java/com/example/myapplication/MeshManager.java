package com.example.myapplication;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Singleton to manage mesh network state across different activities.
 */
public class MeshManager {
    private static MeshManager instance;
    
    private final Map<String, List<Message>> chatHistory = new HashMap<>();
    private final List<String> discoveredNodes = new ArrayList<>();
    private final List<String> connectedNodes = new ArrayList<>();
    
    private MeshManager() {}
    
    public static synchronized MeshManager getInstance() {
        if (instance == null) {
            instance = new MeshManager();
        }
        return instance;
    }
    
    public void addMessage(String nodeId, Message message) {
        if (!chatHistory.containsKey(nodeId)) {
            chatHistory.put(nodeId, new ArrayList<>());
        }
        chatHistory.get(nodeId).add(message);
    }
    
    public List<Message> getMessages(String nodeId) {
        return chatHistory.getOrDefault(nodeId, new ArrayList<>());
    }
    
    public void setDiscoveredNodes(List<String> nodes) {
        this.discoveredNodes.clear();
        this.discoveredNodes.addAll(nodes);
    }
    
    public List<String> getDiscoveredNodes() {
        return new ArrayList<>(discoveredNodes);
    }
    
    public void setConnectedNodes(List<String> nodes) {
        this.connectedNodes.clear();
        this.connectedNodes.addAll(nodes);
    }
    
    public List<String> getConnectedNodes() {
        return new ArrayList<>(connectedNodes);
    }
}