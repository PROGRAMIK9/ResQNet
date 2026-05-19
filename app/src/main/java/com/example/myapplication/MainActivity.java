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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MeshApp";
    private static final String SERVICE_ID = "com.example.myapplication.MESH_SERVICE";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

    private static MainActivity instance;

    private String myShortId;
    private String currentStatus = "Idle";
    
    private final Set<String> connectedEndpoints = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, String> endpointIdToNodeMap = new ConcurrentHashMap<>();
    private final Set<String> seenMessageIds = Collections.synchronizedSet(new HashSet<>());
    
    private final Map<String, List<Message>> chatHistory = new ConcurrentHashMap<>();
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

    public List<Message> getMessagesForNode(String nodeId) {
        List<Message> history = chatHistory.get(nodeId);
        return history != null ? new ArrayList<>(history) : new ArrayList<>();
    }

    public void openIndividualChat(String nodeId) {
        Intent intent = new Intent(this, ChatActivity.class);
        intent.putExtra("node_id", nodeId);
        startActivity(intent);
        overridePendingTransition(R.anim.slide_in_right, R.anim.slide_out_left);
    }

    private boolean hasPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
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

        AdvertisingOptions advOptions = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        Nearby.getConnectionsClient(this).startAdvertising(myShortId, SERVICE_ID, connectionLifecycleCallback, advOptions)
                .addOnSuccessListener(unused -> updateStatus("Active (" + myShortId + ")"))
                .addOnFailureListener(e -> updateStatus("Adv Failed"));

        DiscoveryOptions discOptions = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
                .addOnSuccessListener(unused -> Log.d(TAG, "Discovery started"))
                .addOnFailureListener(e -> Log.e(TAG, "Discovery failed", e));
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
            endpointIdToNodeMap.put(endpointId, info.getEndpointName());
            Nearby.getConnectionsClient(MainActivity.this).acceptConnection(endpointId, payloadCallback);
        }

        @Override
        public void onConnectionResult(@NonNull String endpointId, @NonNull ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                connectedEndpoints.add(endpointId);
                notifyFragmentsDataChanged();
            } else {
                endpointIdToNodeMap.remove(endpointId);
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            endpointIdToNodeMap.remove(endpointId);
            connectedEndpoints.remove(endpointId);
            notifyFragmentsDataChanged();
        }
    };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            if (!discoveredNodeNames.contains(info.getEndpointName())) {
                discoveredNodeNames.add(info.getEndpointName());
                notifyFragmentsDataChanged();
            }
            Nearby.getConnectionsClient(MainActivity.this).requestConnection(myShortId, endpointId, connectionLifecycleCallback);
        }

        @Override
        public void onEndpointLost(@NonNull String endpointId) {}
    };

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(@NonNull String endpointId, @NonNull Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                processReceivedData(new String(payload.asBytes(), StandardCharsets.UTF_8), endpointId);
            }
        }
        @Override
        public void onPayloadTransferUpdate(@NonNull String endpointId, @NonNull PayloadTransferUpdate update) {}
    };

    public void sendMeshMessage(String targetId, String message) {
        if (connectedEndpoints.isEmpty()) {
            Log.w(TAG, "No connected endpoints to send message");
            return;
        }
        try {
            String msgId = UUID.randomUUID().toString();
            seenMessageIds.add(msgId);
            
            JSONObject json = new JSONObject();
            json.put("msgId", msgId);
            json.put("sender", myShortId);
            json.put("target", targetId);
            String encryptedBody = CryptoUtils.encrypt(message);
            json.put("body", encryptedBody != null ? encryptedBody : "");

            broadcastToNeighbors(json.toString(), null);
            addMessageToHistory(targetId, new Message(myShortId, message, true));
        } catch (Exception e) {
            Log.e(TAG, "Error creating/sending message", e);
        }
    }

    private void processReceivedData(String data, String fromEndpointId) {
        try {
            JSONObject json = new JSONObject(data);
            String msgId = json.getString("msgId");
            if (seenMessageIds.contains(msgId)) return;
            seenMessageIds.add(msgId);

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

    private void addMessageToHistory(String nodeId, Message message) {
        List<Message> history = chatHistory.get(nodeId);
        if (history == null) {
            history = Collections.synchronizedList(new ArrayList<>());
            chatHistory.put(nodeId, history);
        }
        history.add(message);
        
        final List<Message> finalHistory = new ArrayList<>(history);
        runOnUiThread(() -> {
            if (ChatActivity.isActive() && nodeId.equals(ChatActivity.getCurrentNodeId())) {
                ChatActivity activity = ChatActivity.getInstance();
                if (activity != null) {
                    activity.updateMessages(finalHistory);
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
            Nearby.getConnectionsClient(this).sendPayload(targets, Payload.fromBytes(data.getBytes(StandardCharsets.UTF_8)));
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
