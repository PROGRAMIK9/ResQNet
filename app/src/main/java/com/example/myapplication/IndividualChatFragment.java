package com.example.myapplication;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.appbar.MaterialToolbar;
import java.util.ArrayList;
import java.util.List;

public class IndividualChatFragment extends Fragment {
    private String targetNodeId;
    private MessageAdapter adapter;
    private RecyclerView recyclerView;
    private List<Message> messages = new ArrayList<>();

    public static IndividualChatFragment newInstance(String nodeId) {
        IndividualChatFragment fragment = new IndividualChatFragment();
        Bundle args = new Bundle();
        args.putString("node_id", nodeId);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            targetNodeId = getArguments().getString("node_id");
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_individual_chat, container, false);
        
        MaterialToolbar toolbar = view.findViewById(R.id.toolbar);
        // Use the string resource for branding consistency
        toolbar.setTitle(getString(R.string.chat_with, targetNodeId));
        toolbar.setNavigationOnClickListener(v -> getParentFragmentManager().popBackStack());

        recyclerView = view.findViewById(R.id.recycler_messages);
        adapter = new MessageAdapter(messages);
        LinearLayoutManager layoutManager = new LinearLayoutManager(getContext());
        layoutManager.setStackFromEnd(true);
        recyclerView.setLayoutManager(layoutManager);
        recyclerView.setAdapter(adapter);

        EditText editMessage = view.findViewById(R.id.edit_message);
        view.findViewById(R.id.btn_send).setOnClickListener(v -> {
            String msgText = editMessage.getText().toString().trim();
            if (!msgText.isEmpty()) {
                MainActivity activity = (MainActivity) getActivity();
                if (activity != null) {
                    activity.sendMeshMessage(targetNodeId, msgText);
                    editMessage.setText("");
                }
            }
        });

        MainActivity activity = (MainActivity) getActivity();
        if (activity != null) {
            updateMessages(activity.getMessagesForNode(targetNodeId));
        }

        return view;
    }

    public void updateMessages(List<Message> newMessages) {
        this.messages.clear();
        this.messages.addAll(newMessages);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
            recyclerView.scrollToPosition(messages.size() - 1);
        }
    }

    public String getTargetNodeId() {
        return targetNodeId;
    }
}