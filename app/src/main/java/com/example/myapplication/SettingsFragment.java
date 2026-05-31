package com.example.myapplication;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;


public class SettingsFragment extends Fragment {

    private TextView tvUsername;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
        
        tvUsername = view.findViewById(R.id.tv_username);
        updateUsernameDisplay();

        view.findViewById(R.id.username_section).setOnClickListener(v -> showEditUsernameDialog());

        view.findViewById(R.id.about_details).setOnClickListener(v->{
            View dialogView = getLayoutInflater().inflate(R.layout.modal_about, null);

            // Build the dialog
            androidx.appcompat.app.AlertDialog alertDialog = new com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
                    .setView(dialogView)
                    .setCancelable(true)
                    .create();

            // Fix the white corners by making the window background transparent
            if (alertDialog.getWindow() != null) {
                alertDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
            }

            alertDialog.show();

            // Handle the close button inside your custom layout
            android.widget.Button closeButton = dialogView.findViewById(R.id.btn_close);
            closeButton.setOnClickListener(closeView -> {
                alertDialog.dismiss();
            });
        });
        return view;
    }

    private void updateUsernameDisplay() {
        MainActivity activity = MainActivity.getInstance();
        if (activity != null) {
            tvUsername.setText(activity.getUserName() + " (" + activity.getMyShortId() + ")");
        }
    }

    private void showEditUsernameDialog() {
        MainActivity activity = MainActivity.getInstance();
        if (activity == null) return;

        EditText editText = new EditText(requireContext());
        editText.setText(activity.getUserName());
        editText.setSelection(editText.getText().length());

        new MaterialAlertDialogBuilder(requireContext())
                .setTitle("Edit Username")
                .setView(editText)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName = editText.getText().toString().trim();
                    if (!newName.isEmpty()) {
                        saveUsername(newName);
                        updateUsernameDisplay();
                        activity.refreshNearby();
                        Toast.makeText(requireContext(), "Username updated", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void saveUsername(String name) {
        SharedPreferences prefs = requireContext().getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(MainActivity.KEY_USERNAME, name).apply();
    }
}
