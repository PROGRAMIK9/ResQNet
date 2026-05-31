package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.fragment.app.Fragment;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import android.os.Handler;
import android.os.Looper;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MeshApp";
    // Nearby service ID must be a valid package-style identifier.
    private static final String SERVICE_ID = "com.example.myapplication";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;
    private static final int MAX_SEEN_MESSAGE_IDS = 5000;
    public static final String PREFS_NAME = "ResQNetPrefs";
    public static final String KEY_USERNAME = "username";

    private static MainActivity instance;

    private String myShortId;
    private String currentStatus = "Idle";
    
    private final Set<String> connectedEndpoints = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> endpointIdToNodeMap = new ConcurrentHashMap<>();
    private final Set<String> seenMessageIds = Collections.synchronizedSet(new LinkedHashSet<>());
    private final Set<String> pendingConnectionRequests = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> discoveredEndpointNames = new ConcurrentHashMap<>();
    private final Map<String, Integer> connectionRetryCounts = new ConcurrentHashMap<>();
    private static final int MAX_CONN_RETRIES = 3;
    private static final long INITIAL_RETRY_DELAY_MS = 1000L; // 1s
    
    private final Map<String, List<Message>> chatHistory = new ConcurrentHashMap<>();
    private final Map<String, String> nodeIdToDisplayName = new ConcurrentHashMap<>();
    private final List<String> discoveredNodeNames = Collections.synchronizedList(new ArrayList<>());

    private final String[] REQUIRED_PERMISSIONS;

    {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
            }
        } else {
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        REQUIRED_PERMISSIONS = permissions.toArray(new String[0]);
    }

    public static MainActivity getInstance() {
        return instance;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View mainView = findViewById(R.id.main);
        if (mainView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return WindowInsetsCompat.CONSUMED;
            });
        }

        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = UUID.randomUUID().toString();
        myShortId = androidId.substring(Math.max(0, androidId.length() - 4)).toUpperCase();

        BottomNavigationView navView = findViewById(R.id.bottom_navigation);
        navView.setOnItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            int id = item.getItemId();
            if (id == R.id.nav_discovery) {
                selectedFragment = new DiscoveryFragment();
            } else if (id == R.id.nav_chats) {
                selectedFragment = new ChatListFragment();
            } else if (id == R.id.nav_settings) {
                selectedFragment = new SettingsFragment();
            }
            
            if (selectedFragment != null) {
                getSupportFragmentManager().beginTransaction()
                        .setCustomAnimations(android.R.anim.fade_in, android.R.anim.fade_out)
                        .replace(R.id.fragment_container, selectedFragment)
                        .commit();
                return true;
            }
            return false;
        });

        if (savedInstanceState == null) {
            getSupportFragmentManager().beginTransaction()
                    .replace(R.id.fragment_container, new DiscoveryFragment())
                    .commit();
        }

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 1001);
        } else {
            startNearby();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (instance == this) instance = null;
    }

    public String getMyShortId() { return myShortId; }

    public String getUserName() {
        return getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getString(KEY_USERNAME, "User");
    }

    public String getDisplayName() {
        return getUserName() + " (" + myShortId + ")";
    }

    public String getStatus() { return currentStatus; }

    public List<String> getDiscoveredNodeNames() {
        return new ArrayList<>(discoveredNodeNames);
    }

    public List<String> getConnectedNodeNames() {
        List<String> names = new ArrayList<>();
        synchronized (connectedEndpoints) {
            for (String id : connectedEndpoints) {
                String name = endpointIdToNodeMap.get(id);
                if (name != null && !names.contains(name)) names.add(name);
            }
        }
        return names;
    }

    public void connectToNodeByName(String nodeName) {
        if (nodeName == null || nodeName.trim().isEmpty()) {
            Toast.makeText(this, "Invalid node", Toast.LENGTH_SHORT).show();
            return;
        }

        String endpointId = null;
        for (Map.Entry<String, String> entry : discoveredEndpointNames.entrySet()) {
            if (nodeName.equals(entry.getValue())) {
                endpointId = entry.getKey();
                break;
            }
        }

        if (endpointId == null) {
            Toast.makeText(this, "Node not available", Toast.LENGTH_SHORT).show();
            return;
        }

        if (connectedEndpoints.contains(endpointId)) {
            Toast.makeText(this, "Already connected to " + nodeName, Toast.LENGTH_SHORT).show();
            return;
        }

        if (pendingConnectionRequests.contains(endpointId)) {
            Toast.makeText(this, "Connecting to " + nodeName + "...", Toast.LENGTH_SHORT).show();
            return;
        }

        final String finalEndpointId = endpointId;
        pendingConnectionRequests.add(finalEndpointId);
        Nearby.getConnectionsClient(this).requestConnection(myShortId, finalEndpointId, connectionLifecycleCallback)
                .addOnSuccessListener(unused -> runOnUiThread(() -> Toast.makeText(this, "Connection requested: " + nodeName, Toast.LENGTH_SHORT).show()))
                .addOnFailureListener(e -> {
                    pendingConnectionRequests.remove(finalEndpointId);
                    Log.e(TAG, "Manual connection request failed to " + finalEndpointId, e);
                    runOnUiThread(() -> Toast.makeText(this, "Failed to connect: " + nodeName, Toast.LENGTH_SHORT).show());
                });
    }

    public List<Message> getMessagesForNode(String nodeIdOrName) {
        String id = extractId(nodeIdOrName);
        List<Message> history = chatHistory.get(id);
        if (history == null) return new ArrayList<>();
        synchronized (history) {
            return new ArrayList<>(history);
        }
    }

    public void openIndividualChat(String nodeIdOrName) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("node_id", nodeIdOrName);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    public String getDisplayNameForNode(String nodeId) {
        String name = nodeIdToDisplayName.get(nodeId);
        return name != null ? name : nodeId;
    }

    private String extractId(String nameOrId) {
        if (nameOrId == null || nameOrId.isEmpty()) return nameOrId;
        int lastParen = nameOrId.lastIndexOf('(');
        int endParen = nameOrId.lastIndexOf(')');
        if (lastParen != -1 && endParen > lastParen) {
            return nameOrId.substring(lastParen + 1, endParen);
        }
        return nameOrId;
    }

    private boolean hasPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean isMessageSeen(String msgId) {
        synchronized (seenMessageIds) {
            return seenMessageIds.contains(msgId);
        }
    }

    private void markMessageSeen(String msgId) {
        synchronized (seenMessageIds) {
            seenMessageIds.add(msgId);
            if (seenMessageIds.size() > MAX_SEEN_MESSAGE_IDS) {
                String oldest = seenMessageIds.iterator().next();
                seenMessageIds.remove(oldest);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (hasPermissions()) {
            startNearby();
        }
    }

    public void refreshNearby() {
        stopNearby();
        startNearby();
    }

    public void startNearby() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean locationOn = (lm != null) && (lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER));
        BluetoothManager bm = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter adapter = (bm != null) ? bm.getAdapter() : null;
        boolean bluetoothOn = (adapter != null && adapter.isEnabled());

        if (!locationOn || !bluetoothOn) {
            updateStatus("Hardware Off (GPS/BT)");
            return;
        }

        Nearby.getConnectionsClient(this).stopAdvertising();
        Nearby.getConnectionsClient(this).stopDiscovery();

        String displayName = getDisplayName();
        AdvertisingOptions advOptions = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        Nearby.getConnectionsClient(this).startAdvertising(displayName, SERVICE_ID, connectionLifecycleCallback, advOptions)
                .addOnSuccessListener(unused -> updateStatus("Active"))
                .addOnFailureListener(e -> {
                    String details = getNearbyFailureDetails(e);
                    Log.e(TAG, "Advertising failed: " + details, e);
                    updateStatus("Adv Failed: " + details);
                    runOnUiThread(() -> Toast.makeText(this, "Advertising failed: " + details, Toast.LENGTH_LONG).show());
                });

        DiscoveryOptions discOptions = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
                .addOnSuccessListener(unused -> Log.d(TAG, "Discovery started"))
                .addOnFailureListener(e -> {
                    String details = getNearbyFailureDetails(e);
                    Log.e(TAG, "Discovery failed: " + details, e);
                    runOnUiThread(() -> Toast.makeText(this, "Discovery failed: " + details, Toast.LENGTH_LONG).show());
                });
    }

    private String getNearbyFailureDetails(Exception e) {
        if (e instanceof ApiException) {
            ApiException apiException = (ApiException) e;
            return "code=" + apiException.getStatusCode();
        }
        String message = e.getMessage();
        return message != null ? message : "unknown";
    }

    private void stopNearby() {
        Nearby.getConnectionsClient(this).stopAllEndpoints();
        Nearby.getConnectionsClient(this).stopAdvertising();
        Nearby.getConnectionsClient(this).stopDiscovery();
        connectedEndpoints.clear();
        endpointIdToNodeMap.clear();
        discoveredNodeNames.clear();
        updateStatus("Stopped");
        notifyFragmentsDataChanged();
    }

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(@NonNull String endpointId, @NonNull ConnectionInfo info) {
            String remoteName = info.getEndpointName();
            endpointIdToNodeMap.put(endpointId, remoteName);
            if (remoteName != null) {
                nodeIdToDisplayName.put(extractId(remoteName), remoteName);
            }
            // If we had a pending request for this endpoint, clear it
            pendingConnectionRequests.remove(endpointId);
            Nearby.getConnectionsClient(MainActivity.this).acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                Log.d(TAG, "Connected to " + endpointId);
                connectedEndpoints.add(endpointId);
                String name = endpointIdToNodeMap.get(endpointId);
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Connected: " + (name != null ? name : endpointId), Toast.LENGTH_SHORT).show());
                notifyFragmentsDataChanged();
            } else {
                Log.w(TAG, "Connection failed: " + result.getStatus().getStatusMessage());
                endpointIdToNodeMap.remove(endpointId);
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            Log.d(TAG, "Disconnected from " + endpointId);
            String name = endpointIdToNodeMap.remove(endpointId);
            connectedEndpoints.remove(endpointId);
            runOnUiThread(() -> Toast.makeText(MainActivity.this, "Disconnected: " + (name != null ? name : endpointId), Toast.LENGTH_SHORT).show());
            notifyFragmentsDataChanged();
        }
    };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            String remoteName = info.getEndpointName();
            Log.d(TAG, "Endpoint found: " + remoteName + " (" + endpointId + ")");

            // Record discovered endpoint id -> name so we can remove it if lost
            if (remoteName != null) {
                discoveredEndpointNames.put(endpointId, remoteName);
            }

            // Ignore endpoints that advertise our own name
            if (remoteName != null && remoteName.equalsIgnoreCase(getDisplayName())) {
                Log.d(TAG, "Ignoring discovered endpoint that matches our own name: " + endpointId);
                return;
            }

            if (remoteName != null && !discoveredNodeNames.contains(remoteName)) {
                discoveredNodeNames.add(remoteName);
                notifyFragmentsDataChanged();
            }

            // Avoid duplicate or concurrent requests
            if (connectedEndpoints.contains(endpointId) || pendingConnectionRequests.contains(endpointId)) {
                Log.d(TAG, "Already connected or pending: " + endpointId);
                return;
            }

            pendingConnectionRequests.add(endpointId);
            Nearby.getConnectionsClient(MainActivity.this).requestConnection(getDisplayName(), endpointId, connectionLifecycleCallback)
                    .addOnSuccessListener(unused -> {
                        Log.d(TAG, "Connection request sent to " + endpointId);
                    })
                    .addOnFailureListener(e -> {
                        Log.e(TAG, "Connection request failed to " + endpointId, e);
                        pendingConnectionRequests.remove(endpointId);
                        // schedule retry with exponential backoff
                        int tries = connectionRetryCounts.getOrDefault(endpointId, 0);
                        if (tries < MAX_CONN_RETRIES) {
                            connectionRetryCounts.put(endpointId, tries + 1);
                            long delay = INITIAL_RETRY_DELAY_MS * (1L << tries);
                            Log.d(TAG, "Scheduling retry #" + (tries + 1) + " for " + endpointId + " in " + delay + "ms");
                            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                                // ensure we don't duplicate pending requests
                                if (!connectedEndpoints.contains(endpointId) && !pendingConnectionRequests.contains(endpointId)) {
                                    pendingConnectionRequests.add(endpointId);
                                    Nearby.getConnectionsClient(MainActivity.this).requestConnection(getDisplayName(), endpointId, connectionLifecycleCallback)
                                            .addOnSuccessListener(u -> Log.d(TAG, "Retry connection request sent to " + endpointId))
                                            .addOnFailureListener(err -> {
                                                Log.e(TAG, "Retry failed for " + endpointId, err);
                                                pendingConnectionRequests.remove(endpointId);
                                            });
                                }
                            }, delay);
                        } else {
                            Log.w(TAG, "Max retries reached for " + endpointId);
                            connectionRetryCounts.remove(endpointId);
                        }
                    });
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {
            // Remove discovery state for this endpoint id
            String name = discoveredEndpointNames.remove(endpointId);
            if (name != null) {
                discoveredNodeNames.remove(name);
                notifyFragmentsDataChanged();
                Log.d(TAG, "Endpoint lost: " + name + " (" + endpointId + ")");
            } else {
                Log.d(TAG, "Endpoint lost (unknown name): " + endpointId);
            }
            // Also clear pending requests if any
            pendingConnectionRequests.remove(endpointId);
        }
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                byte[] bytes = payload.asBytes();
                if (bytes != null) {
                    processReceivedData(new String(bytes, StandardCharsets.UTF_8), endpointId);
                }
            }
        }
        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
    };

    public void sendMeshMessage(String targetIdOrName, String message) {
        if (connectedEndpoints.isEmpty()) {
            Toast.makeText(this, "No connected nodes!", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msgId = UUID.randomUUID().toString();
            markMessageSeen(msgId);
            
            String targetId = extractId(targetIdOrName);
            String display = getDisplayName();
            JSONObject json = new JSONObject();
            json.put("msgId", msgId);
            json.put("sender",display );
            json.put("target", targetId);
            String encryptedBody = CryptoUtils.encrypt(message);
            if (encryptedBody == null) {
                Toast.makeText(this, "Message encryption failed", Toast.LENGTH_SHORT).show();
                return;
            }
            json.put("body", encryptedBody);

            broadcastToNeighbors(json.toString(), null);
            addMessageToHistory(targetId, new Message(myShortId, message, true));
        } catch (Exception e) {
            Log.e(TAG, "Error sending message", e);
        }
    }

    private void processReceivedData(String data, String fromEndpointId) {
        try {
            JSONObject json = new JSONObject(data);
            String msgId = json.getString("msgId");
            if (isMessageSeen(msgId)) return;
            markMessageSeen(msgId);

            String sender = json.getString("sender");
            String target = json.getString("target");
            String body = json.getString("body");

            if (target.equals("ALL") || target.equalsIgnoreCase(myShortId)) {
                String clearText = CryptoUtils.decrypt(body);
                if (clearText != null) {

                    addMessageToHistory(sender, new Message(sender, clearText, false));
                }
            }
            broadcastToNeighbors(data, fromEndpointId);
        } catch (Exception e) {
            Log.e(TAG, "Error processing received data", e);
        }
    }

    private void addMessageToHistory(String nodeIdOrName, Message message) {
        String nodeId = extractId(nodeIdOrName);
        List<Message> history = chatHistory.computeIfAbsent(nodeId, key -> Collections.synchronizedList(new ArrayList<>()));
        history.add(message);
        
        final List<Message> historySnapshot;
        synchronized (history) {
            historySnapshot = new ArrayList<>(history);
        }

        runOnUiThread(() -> {
            String currentChatNodeId = extractId(ChatActivity.getCurrentNodeId());
            if (ChatActivity.isActive() && nodeId.equalsIgnoreCase(currentChatNodeId)) {
                ChatActivity activity = ChatActivity.getInstance();
                if (activity != null) {
                    activity.updateMessages(historySnapshot);
                }
            }
            
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (current instanceof ChatListFragment) {
                ((ChatListFragment) current).updateConnectedNodes(getConnectedNodeNames());
            }
        });
    }

    private void broadcastToNeighbors(String data, String excludeId) {
        List<String> targets;
        synchronized (connectedEndpoints) {
            targets = new ArrayList<>(connectedEndpoints);
        }
        if (excludeId != null) targets.remove(excludeId);
        
        if (!targets.isEmpty()) {
            Log.d(TAG, "Broadcasting payload to " + targets.size() + " endpoints: " + targets);
            Nearby.getConnectionsClient(this).sendPayload(targets, Payload.fromBytes(data.getBytes(StandardCharsets.UTF_8)))
                .addOnSuccessListener(unused -> {
                    Log.d(TAG, "Payload broadcast succeeded to " + targets.size() + " endpoints");
                    runOnUiThread(() -> updateStatus("Sent to " + targets.size() + " peers"));
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Payload broadcast failed", e);
                    runOnUiThread(() -> {
                        Toast.makeText(MainActivity.this, "Message send failed", Toast.LENGTH_SHORT).show();
                        updateStatus("Send Failed");
                    });
                });
        }
    }

    private void updateStatus(String status) {
        currentStatus = status;
        runOnUiThread(() -> {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (current instanceof DiscoveryFragment) {
                ((DiscoveryFragment) current).updateStatus(status);
            }
        });
    }

    private void notifyFragmentsDataChanged() {
        runOnUiThread(() -> {
            Fragment current = getSupportFragmentManager().findFragmentById(R.id.fragment_container);
            if (current instanceof DiscoveryFragment) {
                ((DiscoveryFragment) current).updateDiscoveredNodes(getDiscoveredNodeNames());
            } else if (current instanceof ChatListFragment) {
                ((ChatListFragment) current).updateConnectedNodes(getConnectedNodeNames());
            }
        });
    }
}
