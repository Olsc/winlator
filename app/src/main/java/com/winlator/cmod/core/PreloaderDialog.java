package com.winlator.cmod.core;

import android.app.Activity;
import android.app.Dialog;
import android.view.Window;
import android.view.WindowManager;
import android.widget.TextView;

import com.winlator.cmod.R;

public class PreloaderDialog {
    private final Activity activity;
    private Dialog dialog;

    public PreloaderDialog(Activity activity) {
        this.activity = activity;
    }

    private void create() {
        if (dialog != null) return;
        dialog = new Dialog(activity, R.style.ContentDialog);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setContentView(R.layout.preloader_dialog);

        Window window = dialog.getWindow();
        if (window != null) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE);
            window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE);
        }
    }


    public synchronized void show(int textResId) {
        show(activity.getString(textResId), false);
    }

    public synchronized void show(int textResId, boolean showProgress) {
        show(activity.getString(textResId), showProgress);
    }

    public synchronized void show(CharSequence text) {
        show(text, false);
    }

    public synchronized void show(CharSequence text, boolean showProgress) {
        if (isShowing()) {
            if (dialog != null) {
                ((TextView)dialog.findViewById(R.id.TextView)).setText(text);
                updateProgressVisibility(showProgress);
            }
            return;
        }
        close();
        if (dialog == null) create();
        ((TextView)dialog.findViewById(R.id.TextView)).setText(text);
        updateProgressVisibility(showProgress);
        dialog.show();
    }

    private void updateProgressVisibility(boolean showProgress) {
        if (dialog == null) return;
        dialog.findViewById(R.id.ProgressBarIndeterminate).setVisibility(showProgress ? android.view.View.GONE : android.view.View.VISIBLE);
        dialog.findViewById(R.id.ProgressBar).setVisibility(showProgress ? android.view.View.VISIBLE : android.view.View.GONE);
        dialog.findViewById(R.id.TVProgress).setVisibility(showProgress ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    public void setProgress(int progress) {
        // NOTE: This method must be called on the UI thread.
        // ContainerManager.exportContainer / importContainer already use runOnUiThread
        // before invoking this, so do NOT wrap again here.
        if (dialog == null || !isShowing()) return;
        android.widget.ProgressBar progressBar = dialog.findViewById(R.id.ProgressBar);
        TextView tvProgress = dialog.findViewById(R.id.TVProgress);
        if (progressBar != null) progressBar.setProgress(progress);
        if (tvProgress != null) tvProgress.setText(progress + "%");
    }

    public void showOnUiThread(final int textResId) {
        activity.runOnUiThread(() -> show(textResId));
    }

    public void showOnUiThread(final int textResId, final boolean showProgress) {
        activity.runOnUiThread(() -> show(textResId, showProgress));
    }

    public void showOnUiThread(final CharSequence text) {
        activity.runOnUiThread(() -> show(text));
    }

    public void showOnUiThread(final CharSequence text, final boolean showProgress) {
        activity.runOnUiThread(() -> show(text, showProgress));
    }

    public synchronized void close() {
        try {
            if (dialog != null) {
                dialog.dismiss();
                dialog = null;
            }
        }
        catch (Exception e) {
            dialog = null;
        }
    }

    public void closeOnUiThread() {
        activity.runOnUiThread(this::close);
    }

    public boolean isShowing() {
        return dialog != null && dialog.isShowing();
    }
}
