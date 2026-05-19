package com.example.myapplication;

import android.os.Bundle;
import android.widget.EditText;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.List;

public class ChatActivity extends AppCompatActivity {
    private static ChatActivity instance;
    private static boolean active = false;
    private static String currentNodeId = "";

    private String targetNodeId;
    private MessageAdapter adapter;
    private RecyclerView recyclerView;
    private List<Message> messages = new ArrayList<>();

    public static ChatActivity getInstance() {
        return instance;
    }

    public static boolean isActive() {
        return active;
    }

    public static String getCurrentNodeId() {
        return currentNodeId;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.fragment_individual_chat);
        instance = this;
        active = true;

        targetNodeId = getIntent().getStringExtra("node_id");
        currentNodeId = targetNodeId;

        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setTitle(getString(R.string.chat_with, targetNodeId));
        toolbar.setNavigationOnClickListener(v -> finish());

        recyclerView = findViewById(R.id.recycler_messages);
        adapter = new MessageAdapter(messages);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);

        MainActivity mainActivity = MainActivity.getInstance();
        if (mainActivity != null) {
            updateMessages(mainActivity.getMessagesForNode(targetNodeId));
        }

        EditText editMessage = findViewById(R.id.edit_message);
        findViewById(R.id.btn_send).setOnClickListener(v -> {
            String msgText = editMessage.getText().toString().trim();
            if (!msgText.isEmpty() && mainActivity != null) {
                mainActivity.sendMeshMessage(targetNodeId, msgText);
                editMessage.setText("");
                updateMessages(mainActivity.getMessagesForNode(targetNodeId));
            }
        });
    }

    public void updateMessages(List<Message> newMessages) {
        runOnUiThread(() -> {
            this.messages.clear();
            this.messages.addAll(newMessages);
            if (adapter != null) {
                adapter.notifyDataSetChanged();
                recyclerView.scrollToPosition(messages.size() - 1);
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        active = false;
        instance = null;
        currentNodeId = "";
    }
}