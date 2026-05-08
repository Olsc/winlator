package com.winlator.cmod;

import static com.winlator.cmod.core.AppUtils.showToast;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.core.view.ViewCompat;
import androidx.documentfile.provider.DocumentFile;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.winlator.cmod.R;
import com.winlator.cmod.container.Container;
import com.winlator.cmod.container.ContainerManager;
import com.winlator.cmod.container.Shortcut;
import com.winlator.cmod.contentdialog.ContentDialog;
import com.winlator.cmod.contentdialog.StorageInfoDialog;
import com.winlator.cmod.core.AppUtils;
import com.winlator.cmod.core.FileUtils;
import com.winlator.cmod.core.PreloaderDialog;
import com.winlator.cmod.inputcontrols.ControllerManager;
import com.winlator.cmod.xenvironment.ImageFs;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

public class ContainersFragment extends Fragment {
    private static final int REQUEST_CODE_IMPORT_CONTAINER = 1070;
    private RecyclerView recyclerView;
    private TextView emptyTextView;
    private ContainerManager manager;
    private PreloaderDialog preloaderDialog;

    private MenuItem favoriteItem;

    private static final String PREF_FAVORITE_UUID = "favorite_uuid";

    private ImageView favoriteActionView;


    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        preloaderDialog = new PreloaderDialog(getActivity());
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        manager = new ContainerManager(getContext());
        loadContainersList();
        ((AppCompatActivity) getActivity()).getSupportActionBar().setTitle(R.string.containers);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        FrameLayout frameLayout = (FrameLayout) inflater.inflate(R.layout.containers_fragment, container, false);
        recyclerView = frameLayout.findViewById(R.id.RecyclerView);
        emptyTextView = frameLayout.findViewById(R.id.TVEmptyText);
        recyclerView.setLayoutManager(new LinearLayoutManager(recyclerView.getContext()));
        recyclerView.addItemDecoration(new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL));
        return frameLayout;
    }

    private void loadContainersList() {
        ArrayList<Container> containers = manager.getContainers();
        recyclerView.setAdapter(new ContainersAdapter(containers));
        emptyTextView.setVisibility(containers.isEmpty() ? View.VISIBLE : View.GONE);
    }


    @Override
    public void onResume() {
        super.onResume();
        // re-create the menu so the icon always matches current favorite
        requireActivity().invalidateOptionsMenu();
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater inflater) {
        menu.clear();
        inflater.inflate(R.menu.containers_menu, menu);

        // Other items tinting...
        MenuItem bigPictureItem = menu.findItem(R.id.action_big_picture_mode);
        if (bigPictureItem != null && bigPictureItem.getIcon() != null) {
            bigPictureItem.getIcon().mutate();
            bigPictureItem.getIcon().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        }

        favoriteItem = menu.findItem(R.id.action_favorite_star);
        if (favoriteItem != null) {
            // Ensure it shows in the toolbar
            favoriteItem.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);

            // Create an ImageView as the action view
            AppCompatImageView iv = new AppCompatImageView(requireContext());
            int pad = dp(8);
            iv.setPadding(pad, pad, pad, pad);
            iv.setAdjustViewBounds(true);
            iv.setClickable(true);
            iv.setFocusable(true);
            iv.setContentDescription(getString(R.string.app_name)); // or "Favorite shortcut"
            ViewCompat.setTooltipText(iv, getString(R.string.favorite));

            favoriteItem.setActionView(iv);
            favoriteActionView = iv;

            // Click => launch (or prompt to choose)
            iv.setOnClickListener(v -> handleFavoriteClick());

            // Long‑press => clear (if set)
            iv.setOnLongClickListener(v -> {
                v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                Shortcut fav = getFavoriteShortcut();
                if (fav == null) {
                    Toast.makeText(getContext(), R.string.no_favorite_assigned, Toast.LENGTH_SHORT).show();
                } else {
                    new AlertDialog.Builder(getContext())
                            .setTitle(R.string.clear_favorite)
                            .setMessage(R.string.remove_current_favorite)
                            .setPositiveButton(R.string.remove, (d, w) -> clearFavorite())
                            .setNegativeButton(R.string.cancel, null)
                            .show();
                }
                return true;
            });
        }

        // Finally, paint the current icon into the view
        applyFavoriteIcon();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem menuItem) {
        switch (menuItem.getItemId()) {
            case R.id.containers_menu_add:
                if (!ImageFs.find(getContext()).isValid()) return false;
                FragmentManager fragmentManager = getParentFragmentManager();
                fragmentManager.beginTransaction()
                        .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                        .addToBackStack(null)
                        .replace(R.id.FLFragmentContainer, new ContainerDetailFragment())
                        .commit();
                return true;

            case R.id.containers_menu_import:
                showImportInfoDialog();
                return true;

            case R.id.action_big_picture_mode:
                toggleBigPictureMode();
                return true;

//            case R.id.action_terminal:  // New case for TerminalActivity
//                openTerminal();
//                return true;

//            case R.id.action_copy_dev_libs:
//                copyDevLibs();
//                return true;

            case R.id.action_favorite_star: {
                Shortcut fav = getFavoriteShortcut();
                if (fav == null) {
                    new AlertDialog.Builder(getContext())
                            .setTitle(R.string.favorite)
                            .setMessage(R.string.choose_one_now)
                            .setPositiveButton(R.string.choose, (d, w) -> {
                                getParentFragmentManager().beginTransaction()
                                        .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down,
                                                R.anim.slide_in_down, R.anim.slide_out_up)
                                        .addToBackStack(null)
                                        .replace(R.id.FLFragmentContainer, new ShortcutsFragment())
                                        .commit();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } else {
                    runShortcut(fav);
                }
                return true;
            }


            default:
                return super.onOptionsItemSelected(menuItem);
        }
    }

    private void runShortcut(Shortcut s) {
        Intent intent = new Intent(getActivity(), XServerDisplayActivity.class);
        intent.setAction(Intent.ACTION_VIEW);
        intent.putExtra("container_id", s.container.id);
        intent.putExtra("shortcut_path", s.file.getPath());
        startActivity(intent);
    }

    private void copyDevLibs() {
        // Use requireActivity() or getActivity() to get the context
        android.content.res.AssetManager assetManager = requireActivity().getAssets();
        String sourceDir = "devLibs";
        String destDir = "/data/data/com.winlator.cmod/files/imagefs/opt/proton-9.0-arm64ec/lib/wine/aarch64-unix/";

        try {
            // Create the destination directory if it doesn't exist
            java.io.File destDirFile = new java.io.File(destDir);
            if (!destDirFile.exists()) {
                if (!destDirFile.mkdirs()) {
                    // If directory creation fails, show an error and exit
                    android.widget.Toast.makeText(requireActivity(), "Failed to create destination directory.", android.widget.Toast.LENGTH_SHORT).show();
                    return;
                }
            }

            // List all files in the assets/devLibs folder
            String[] files = assetManager.list(sourceDir);
            if (files == null || files.length == 0) {
                android.widget.Toast.makeText(requireActivity(), "No dev libs found in assets.", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }

            // Loop through each file and copy it
            for (String filename : files) {
                java.io.InputStream in = null;
                java.io.OutputStream out = null;
                try {
                    in = assetManager.open(sourceDir + "/" + filename);
                    java.io.File outFile = new java.io.File(destDir, filename);
                    out = new java.io.FileOutputStream(outFile);

                    // Copy the file in a buffered way
                    byte[] buffer = new byte[1024];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                } catch (java.io.IOException e) {
                    // Log the error for debugging
                    android.util.Log.e("CopyDevLibs", "Failed to copy asset file: " + filename, e);
                    throw new java.io.IOException("Failed to copy " + filename, e); // Rethrow to be caught by the outer catch block
                } finally {
                    // Ensure streams are closed
                    if (in != null) {
                        try {
                            in.close();
                        } catch (java.io.IOException e) {
                            // ignore
                        }
                    }
                    if (out != null) {
                        try {
                            out.flush();
                            out.close();
                        } catch (java.io.IOException e) {
                            // ignore
                        }
                    }
                }
            }

            // If the loop completes without throwing an exception, it was successful
            android.widget.Toast.makeText(requireActivity(), "Dev libs copied successfully!", android.widget.Toast.LENGTH_SHORT).show();

        } catch (java.io.IOException e) {
            // If any file fails, show a general error message
            android.util.Log.e("CopyDevLibs", "An error occurred during asset copying.", e);
            android.widget.Toast.makeText(requireActivity(), "Error copying dev libs.", android.widget.Toast.LENGTH_SHORT).show();
        }
    }


    private void openTerminal() {
        Intent intent = new Intent(getContext(), TerminalActivity.class);
        startActivity(intent);
    }


    // Show dialog to inform user about the import process
    private void showImportInfoDialog() {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.import_container);
        builder.setMessage(R.string.import_container_description);
        builder.setPositiveButton(R.string.ok, (dialog, which) -> {
            openFilePicker(); // Proceed to file picker
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    // Show confirmation dialog before importing the selected container
    private void showImportConfirmationDialog(Uri uri) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getContext());
        builder.setTitle(R.string.confirm_import);
        builder.setMessage(R.string.proceed_to_import_container);
        builder.setPositiveButton(R.string.import_label, (dialog, which) -> {
            importContainer(uri); // Proceed with the import
        });
        builder.setNegativeButton(R.string.cancel, (dialog, which) -> dialog.dismiss());
        builder.show();
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_CODE_IMPORT_CONTAINER && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                if (uri != null) {
                    showImportConfirmationDialog(uri);
                }
            }
        }
    }


    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQUEST_CODE_IMPORT_CONTAINER);
    }


    private void importContainer(Uri uri) {
        if (uri == null) return;

        preloaderDialog.show(R.string.importing_container, true);

        manager.importContainer(uri, (progress) -> {
            preloaderDialog.setProgress(progress);
        }, () -> {
            // This callback already runs on the UI thread (via ContainerManager.runOnUiThread)
            loadContainersList();
            AppUtils.showToast(getContext(), getString(R.string.container_imported_successfully));
            preloaderDialog.close();
        });
    }


    private boolean copyDocumentFileToDirectory(DocumentFile sourceDir, File targetDir) {
        if (!sourceDir.isDirectory()) return false;

        for (DocumentFile file : sourceDir.listFiles()) {
            File targetFile = new File(targetDir, file.getName());
            if (file.isDirectory()) {
                if (!targetFile.mkdirs()) return false;
                if (!copyDocumentFileToDirectory(file, targetFile)) return false;
            } else {
                try (InputStream in = getContext().getContentResolver().openInputStream(file.getUri());
                     OutputStream out = new FileOutputStream(targetFile)) {
                    byte[] buffer = new byte[1024];
                    int len;
                    while ((len = in.read(buffer)) > 0) {
                        out.write(buffer, 0, len);
                    }
                } catch (IOException e) {
                    return false;
                }
            }
        }
        return true;
    }



    private void toggleBigPictureMode() {
        // Start BigPictureActivity without passing shortcut data explicitly
        Intent intent = new Intent(getContext(), BigPictureActivity.class);
        startActivity(intent);
        getActivity().overridePendingTransition(R.anim.fade_in, R.anim.fade_out);
    }

    @Nullable
    private Shortcut getFavoriteShortcut() {
        SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(getContext());
        String uuid = sp.getString(PREF_FAVORITE_UUID, null);
        if (uuid == null || uuid.isEmpty()) return null;

        if (manager == null) manager = new ContainerManager(getContext());
        for (Shortcut sc : manager.loadShortcuts()) {
            if (uuid.equals(sc.getExtra("uuid"))) return sc;
        }
        return null;
    }

    private void setFavorite(@NonNull Shortcut sc) {
        if (sc.getExtra("uuid").isEmpty()) sc.genUUID();
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .edit().putString(PREF_FAVORITE_UUID, sc.getExtra("uuid")).apply();
        requireActivity().invalidateOptionsMenu();
    }

    private void clearFavorite() {
        PreferenceManager.getDefaultSharedPreferences(getContext())
                .edit().remove(PREF_FAVORITE_UUID).apply();
        requireActivity().invalidateOptionsMenu();
    }


    private class ContainersAdapter extends RecyclerView.Adapter<ContainersAdapter.ViewHolder> {
        private final List<Container> data;

        private class ViewHolder extends RecyclerView.ViewHolder {
            private final ImageView runButton; // Changed to ImageButton
            private final ImageView menuButton; // Changed to ImageButton
            private final ImageView imageView;
            private final TextView title;

            private static final String PREF_FAVORITE_UUID = "favorite_uuid";

            private ViewHolder(View view) {
                super(view);
                this.runButton = view.findViewById(R.id.BTRun); // Find by correct ID
                this.imageView = view.findViewById(R.id.ImageView);
                this.title = view.findViewById(R.id.TVTitle);
                this.menuButton = view.findViewById(R.id.BTMenu);
            }
        }

        public ContainersAdapter(List<Container> data) {
            this.data = data;
        }

        @Override
        public final ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new ViewHolder(LayoutInflater.from(parent.getContext()).inflate(R.layout.container_list_item, parent, false));
        }

        @Override
        public void onViewRecycled(@NonNull ViewHolder holder) {
            holder.runButton.setOnClickListener(null); // Remove listeners
            holder.menuButton.setOnClickListener(null); // Remove listeners
            super.onViewRecycled(holder);
        }

        @Override
        public void onBindViewHolder(final ViewHolder holder, int position) {
            final Container item = data.get(position); // Use 'item' instead of undefined 'container'
            holder.imageView.setImageResource(R.drawable.icon_container);
            holder.title.setText(item.getName());

            holder.runButton.setOnClickListener(view -> runContainer(item)); // Correct item reference

            holder.menuButton.setOnClickListener(view -> showListItemMenu(view, item));
        }

        @Override
        public final int getItemCount() {
            return data.size();
        }

        private void runContainer(Container container) {
            final Context context = getContext();
            final SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
            final String DONT_SHOW_KEY = "dont_show_controller_notice";

            // 1. Check if the user has opted out of the warning
            if (prefs.getBoolean(DONT_SHOW_KEY, false)) {
                proceedWithLaunch(container);
                return;
            }

            // 2. Check if any controllers are assigned
            ControllerManager controllerManager = ControllerManager.getInstance();
            boolean hasAssignedControllers = false;
            for (int i = 0; i < 4; i++) {
                // We only need to check if a device is assigned to any slot
                if (controllerManager.getAssignedDeviceForSlot(i) != null) {
                    hasAssignedControllers = true;
                    break;
                }
            }

            // 3. If controllers are already assigned, launch immediately
            if (hasAssignedControllers) {
                proceedWithLaunch(container);
                return;
            }

            // 4. If no controllers are assigned and the notice is enabled, show the dialog
            // Create a custom layout for the dialog programmatically
            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            int padding = (int) (20 * getResources().getDisplayMetrics().density); // 20dp padding
            layout.setPadding(padding, padding, padding, padding);

            TextView messageView = new TextView(context);
            messageView.setText(R.string.no_controllers_assigned);
            messageView.setTextSize(16f);
            layout.addView(messageView);

            CheckBox checkbox = new CheckBox(context);
            checkbox.setText(R.string.dont_show_again);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
            );
            params.topMargin = padding; // Add margin to the top of the checkbox
            checkbox.setLayoutParams(params);
            layout.addView(checkbox);

            new AlertDialog.Builder(context)
                    .setTitle(R.string.controller_notice)
                    .setView(layout)
                    .setPositiveButton(R.string.ok, (dialog, which) -> {
                        // If the user checks the box, save the preference
                        if (checkbox.isChecked()) {
                            prefs.edit().putBoolean(DONT_SHOW_KEY, true).apply();
                        }
                        // Proceed with the launch after the user clicks OK
                        proceedWithLaunch(container);
                    })
                    .setCancelable(false) // Prevent dismissing by tapping outside
                    .show();
        }
        private void proceedWithLaunch(Container container) {
            final Context context = getContext();



            if (!XrActivity.isEnabled(getContext())) {
                Intent intent = new Intent(context, XServerDisplayActivity.class);
                intent.putExtra("container_id", container.id);
                requireActivity().startActivity(intent);
            } else {
                XrActivity.openIntent(getActivity(), container.id, null);
            }
        }

        private void showListItemMenu(View anchorView, Container container) {
            final Context context = getContext();
            PopupMenu listItemMenu = new PopupMenu(context, anchorView);
            listItemMenu.inflate(R.menu.container_popup_menu);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) listItemMenu.setForceShowIcon(true);

            listItemMenu.setOnMenuItemClickListener((menuItem) -> {
                switch (menuItem.getItemId()) {
                    case R.id.container_edit:
                        FragmentManager fragmentManager = getParentFragmentManager();
                        fragmentManager.beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down, R.anim.slide_in_down, R.anim.slide_out_up)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new ContainerDetailFragment(container.id))
                                .commit();
                        break;
                    case R.id.container_duplicate:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_duplicate_this_container, () -> {
                            preloaderDialog.show(R.string.duplicating_container);
                            manager.duplicateContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_remove:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_remove_this_container, () -> {
                            preloaderDialog.show(R.string.removing_container);
                            for (Shortcut shortcut : manager.loadShortcuts()) {
                                if (shortcut.container == container)
                                    ShortcutsFragment.disableShortcutOnScreen(context, shortcut);
                            }
                            manager.removeContainerAsync(container, () -> {
                                preloaderDialog.close();
                                loadContainersList();
                            });
                        });
                        break;
                    case R.id.container_info:
                        (new StorageInfoDialog(getActivity(), container)).show();
                        break;
                    case R.id.container_reconfigure:
                        ContentDialog.confirm(getContext(), R.string.do_you_want_to_reconfigure_wine, () -> {
                            new File(container.getRootDir(), ".wine/.update-timestamp").delete();
                        });
                        break;
                    case R.id.container_export:
                        exportContainer(container);
                        break;
                }
                return true;
            });
            listItemMenu.show();
        }

        private void exportContainer(Container container) {
            File backupDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "Winlator/Backups/Containers");
            preloaderDialog.show(R.string.exporting_container, true);

            manager.exportContainer(container, (progress) -> {
                preloaderDialog.setProgress(progress);
            }, () -> {
                preloaderDialog.close(); // Ensure the dialog is closed after operation
                showToast(getString(R.string.container_exported_successfully, backupDir.getPath()));
            });
        }

        private void showToast(String message) {
            if (getActivity() != null) {
                getActivity().runOnUiThread(() -> {
                    Toast.makeText(getActivity(), message, Toast.LENGTH_SHORT).show();
                });
            }
        }
    }

    private void handleFavoriteClick() {
        Shortcut fav = getFavoriteShortcut();
        if (fav == null) {
            new AlertDialog.Builder(getContext())
                    .setTitle(R.string.favorite)
                    .setMessage(R.string.choose_one_now)
                    .setPositiveButton(R.string.choose, (d, w) -> {
                        getParentFragmentManager().beginTransaction()
                                .setCustomAnimations(R.anim.slide_in_up, R.anim.slide_out_down,
                                        R.anim.slide_in_down, R.anim.slide_out_up)
                                .addToBackStack(null)
                                .replace(R.id.FLFragmentContainer, new ShortcutsFragment())
                                .commit();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        } else {
            runShortcut(fav);
        }
    }

    private void applyFavoriteIcon() {
        if (favoriteItem == null) return;

        Shortcut hit = getFavoriteShortcut();
        Drawable drawable;
        if (hit != null && hit.icon != null) {
            int size = dp(24);
            Bitmap scaled = Bitmap.createScaledBitmap(hit.icon, size, size, true);
            drawable = new BitmapDrawable(getResources(), scaled);
            drawable.setTintList(null);
        } else {
            drawable = AppCompatResources.getDrawable(requireContext(), R.drawable.ic_star_border);
            drawable.mutate().setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        }

        if (favoriteActionView != null) {
            favoriteActionView.setImageDrawable(drawable);
        } else {
            favoriteItem.setIcon(drawable);
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}