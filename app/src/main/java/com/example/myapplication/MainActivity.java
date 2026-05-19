package com.example.myapplication;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MeshApp";
    private static final String SERVICE_ID = "com.example.myapplication.MESH_SERVICE";
    private static final Strategy STRATEGY = Strategy.P2P_CLUSTER;

    private String myShortId;
    private TextView statusText, nodeIdText, chatLog;
    private EditText editTargetName, editMessage;
    private Button btnSend, btnRefresh, btnConnect;
    private LinearLayout nodesContainer;

    private final Set<String> connectedEndpoints = new HashSet<>();
    private final Map<String, String> endpointIdToNodeMap = new HashMap<>();
    private final Set<String> seenMessageIds = new HashSet<>();

    private final String[] REQUIRED_PERMISSIONS;

    {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
        permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
            permissions.add(Manifest.permission.NEARBY_WIFI_DEVICES);
        } else {
            permissions.add(Manifest.permission.BLUETOOTH);
            permissions.add(Manifest.permission.BLUETOOTH_ADMIN);
        }
        REQUIRED_PERMISSIONS = permissions.toArray(new String[0]);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        View mainView = findViewById(R.id.main);
        if (mainView != null) {
            ViewCompat.setOnApplyWindowInsetsListener(mainView, (v, insets) -> {
                Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
                v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
                return insets;
            });
        }

        String androidId = Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
        if (androidId == null) androidId = UUID.randomUUID().toString();
        myShortId = androidId.substring(Math.max(0, androidId.length() - 4)).toUpperCase();

        statusText = findViewById(R.id.status_text);
        nodeIdText = findViewById(R.id.node_id_text);
        chatLog = findViewById(R.id.chat_log);
        nodesContainer = findViewById(R.id.discovered_nodes_container);
        editTargetName = findViewById(R.id.edit_target_name);
        editMessage = findViewById(R.id.edit_message);
        btnSend = findViewById(R.id.btn_send);
        btnRefresh = findViewById(R.id.btn_refresh);
        btnConnect = findViewById(R.id.btn_connect);

        nodeIdText.setText("My Node ID: " + myShortId);
        statusText.setText("Status: Initializing");

        btnSend.setOnClickListener(v -> {
            String target = editTargetName.getText().toString().trim().toUpperCase();
            String msg = editMessage.getText().toString().trim();
            if (!msg.isEmpty()) {
                sendMeshMessage(target.isEmpty() ? "ALL" : target, msg);
                editMessage.setText("");
            }
        });

        btnRefresh.setOnClickListener(v -> {
            logToChat("Resetting mesh network...");
            stopNearby();
            startNearby();
        });

        btnConnect.setOnClickListener(v -> {
            logToChat("Checking connectivity...");
            startNearby();
        });

        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, 1001);
        } else {
            startNearby();
        }
    }

    private boolean hasPermissions() {
        for (String permission : REQUIRED_PERMISSIONS) {
            if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private boolean isSystemReady() {
        LocationManager lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        boolean gpsEnabled = false;
        try { gpsEnabled = lm.isProviderEnabled(LocationManager.GPS_PROVIDER); } catch(Exception ignored) {}
        
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        boolean btEnabled = (ba != null && ba.isEnabled());

        if (!gpsEnabled) logToChat("CRITICAL: Location Services (GPS) are OFF. Please turn them ON.");
        if (!btEnabled) logToChat("CRITICAL: Bluetooth is OFF. Please turn it ON.");
        
        return gpsEnabled && btEnabled;
    }

    private void startNearby() {
        if (!isSystemReady()) {
            statusText.setText("Status: System Hardware Not Ready");
            return;
        }

        logToChat("Starting Mesh Services...");
        
        AdvertisingOptions advOptions = new AdvertisingOptions.Builder().setStrategy(STRATEGY).build();
        Nearby.getConnectionsClient(this).startAdvertising(myShortId, SERVICE_ID, connectionLifecycleCallback, advOptions)
                .addOnSuccessListener(unused -> {
                    statusText.setText("Status: Visible as " + myShortId);
                    logToChat("Now advertising to nearby devices.");
                })
                .addOnFailureListener(e -> logToChat("Advertising Failed: " + e.getMessage()));

        DiscoveryOptions discOptions = new DiscoveryOptions.Builder().setStrategy(STRATEGY).build();
        Nearby.getConnectionsClient(this).startDiscovery(SERVICE_ID, endpointDiscoveryCallback, discOptions)
                .addOnSuccessListener(unused -> logToChat("Scanning for neighbors..."))
                .addOnFailureListener(e -> logToChat("Discovery Failed: " + e.getMessage()));
    }

    private void stopNearby() {
        Nearby.getConnectionsClient(this).stopAllEndpoints();
        Nearby.getConnectionsClient(this).stopAdvertising();
        Nearby.getConnectionsClient(this).stopDiscovery();
        connectedEndpoints.clear();
        endpointIdToNodeMap.clear();
        updateNodesUI();
        statusText.setText("Status: Stopped");
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
                logToChat("CONNECTED to neighbor: " + endpointIdToNodeMap.get(endpointId));
                updateNodesUI();
            } else {
                logToChat("Connection to " + endpointId + " failed.");
                endpointIdToNodeMap.remove(endpointId);
            }
        }

        @Override
        public void onDisconnected(@NonNull String endpointId) {
            String nodeName = endpointIdToNodeMap.remove(endpointId);
            connectedEndpoints.remove(endpointId);
            logToChat("Disconnected from: " + (nodeName != null ? nodeName : endpointId));
            updateNodesUI();
        }
    };

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(@NonNull String endpointId, @NonNull DiscoveredEndpointInfo info) {
            logToChat("Node Detected: " + info.getEndpointName() + ". Connecting...");
            Nearby.getConnectionsClient(MainActivity.this).requestConnection(myShortId, endpointId, connectionLifecycleCallback)
                    .addOnFailureListener(e -> Log.e(TAG, "Request connection failed", e));
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

    private void sendMeshMessage(String targetId, String message) {
        if (connectedEndpoints.isEmpty()) {
            logToChat("No neighbors connected! Try refreshing.");
            return;
        }
        try {
            String msgId = UUID.randomUUID().toString();
            seenMessageIds.add(msgId);

            JSONObject json = new JSONObject();
            json.put("msgId", msgId);
            json.put("sender", myShortId);
            json.put("target", targetId);
            json.put("body", CryptoUtils.encrypt(message));

            broadcastToNeighbors(json.toString(), null);
            logToChat("ME -> " + targetId + ": " + message);
        } catch (Exception e) {
            Log.e(TAG, "Send Error", e);
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
            String encryptedBody = json.getString("body");

            if (target.equals("ALL") || target.equalsIgnoreCase(myShortId)) {
                String decrypted = CryptoUtils.decrypt(encryptedBody);
                logToChat("[" + sender + "]: " + (decrypted != null ? decrypted : "[SECURE MSG]"));
            }

            broadcastToNeighbors(data, fromEndpointId);
        } catch (Exception e) {
            Log.e(TAG, "Process Error", e);
        }
    }

    private void broadcastToNeighbors(String data, String excludeId) {
        List<String> targets = new ArrayList<>(connectedEndpoints);
        if (excludeId != null) targets.remove(excludeId);
        if (targets.isEmpty()) return;

        Nearby.getConnectionsClient(this).sendPayload(targets, Payload.fromBytes(data.getBytes(StandardCharsets.UTF_8)));
    }

    private void updateNodesUI() {
        runOnUiThread(() -> {
            nodesContainer.removeAllViews();
            for (Map.Entry<String, String> entry : endpointIdToNodeMap.entrySet()) {
                if (!connectedEndpoints.contains(entry.getKey())) continue;
                TextView tv = new TextView(this);
                tv.setText("● Neighbor: " + entry.getValue());
                tv.setPadding(8, 8, 8, 8);
                tv.setOnClickListener(v -> editTargetName.setText(entry.getValue()));
                nodesContainer.addView(tv);
            }
        });
    }

    private void logToChat(String msg) {
        runOnUiThread(() -> chatLog.append(msg + "\n"));
    }
}
