package com.example.myapplication;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;


public class SettingsFragment extends Fragment {

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);
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
}