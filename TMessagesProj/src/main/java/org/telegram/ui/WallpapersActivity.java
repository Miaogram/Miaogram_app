/*
 * This is the source code of Telegram for Android v. 1.3.2.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013.
 */

package org.telegram.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.AdapterView;
import android.widget.ImageView;
import android.widget.ProgressBar;

import org.telegram.android.AndroidUtilities;
import org.telegram.android.ImageLoader;
import org.telegram.android.LocaleController;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.TLObject;
import org.telegram.messenger.TLRPC;
import org.telegram.messenger.ConnectionsManager;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.android.MessagesStorage;
import org.telegram.android.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.RPCRequest;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Adapters.BaseFragmentAdapter;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.HorizontalListView;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;

public class WallpapersActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate {

    private HorizontalListView listView;
    private ListAdapter listAdapter;
    private ImageView backgroundImage;
    private ProgressBar progressBar;
    private int selectedBackground;
    private int selectedColor;
    private ArrayList<TLRPC.WallPaper> wallPapers = new ArrayList<>();
    private HashMap<Integer, TLRPC.WallPaper> wallpappersByIds = new HashMap<>();
    private View doneButton;
    private String loadingFile = null;
    private File loadingFileObject = null;
    private TLRPC.PhotoSize loadingSize = null;
    private String currentPicturePath;

    private final static int done_button = 1;

    @Override
    public boolean onFragmentCreate() {
        super.onFragmentCreate();

        NotificationCenter.getInstance().addObserver(this, NotificationCenter.FileDidFailedLoad);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.FileDidLoaded);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.FileLoadProgressChanged);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.wallpapersDidLoaded);

        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
        selectedBackground = preferences.getInt("selectedBackground", 1000001);
        selectedColor = preferences.getInt("selectedColor", 0);
        MessagesStorage.getInstance().getWallpapers();
        File toFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "wallpaper-temp.jpg");
        toFile.delete();
        return true;
    }

    @Override
    public void onFragmentDestroy() {
        super.onFragmentDestroy();
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.FileDidFailedLoad);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.FileDidLoaded);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.FileLoadProgressChanged);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.wallpapersDidLoaded);
    }

    @Override
    public View createView(LayoutInflater inflater) {
        if (fragmentView == null) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle(LocaleController.getString("ChatBackground", R.string.ChatBackground));
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    } else if (id == done_button) {
                        boolean done;
                        TLRPC.WallPaper wallPaper = wallpappersByIds.get(selectedBackground);
                        if (wallPaper != null && wallPaper.id != 1000001 && wallPaper instanceof TLRPC.TL_wallPaper) {
                            int width = AndroidUtilities.displaySize.x;
                            int height = AndroidUtilities.displaySize.y;
                            if (width > height) {
                                int temp = width;
                                width = height;
                                height = temp;
                            }
                            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(wallPaper.sizes, Math.min(width, height));
                            String fileName = size.location.volume_id + "_" + size.location.local_id + ".jpg";
                            File f = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_CACHE), fileName);
                            File toFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "wallpaper.jpg");
                            try {
                                done = Utilities.copyFile(f, toFile);
                            } catch (Exception e) {
                                done = false;
                                FileLog.e("tmessages", e);
                            }
                        } else {
                            if (selectedBackground == -1) {
                                File fromFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "wallpaper-temp.jpg");
                                File toFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "wallpaper.jpg");
                                done = fromFile.renameTo(toFile);
                            } else {
                                done = true;
                            }
                        }

                        if (done) {
                            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE);
                            SharedPreferences.Editor editor = preferences.edit();
                            editor.putInt("selectedBackground", selectedBackground);
                            editor.putInt("selectedColor", selectedColor);
                            editor.commit();
                            ApplicationLoader.reloadWallpaper();
                        }
                        finishFragment();
                    }
                }
            });

            ActionBarMenu menu = actionBar.createMenu();
            doneButton = menu.addItemWithWidth(done_button, R.drawable.ic_done, AndroidUtilities.dp(56));

            fragmentView = inflater.inflate(R.layout.settings_wallpapers_layout, null, false);
            listAdapter = new ListAdapter(getParentActivity());

            progressBar = (ProgressBar)fragmentView.findViewById(R.id.action_progress);
            backgroundImage = (ImageView)fragmentView.findViewById(R.id.background_image);
            listView = (HorizontalListView)fragmentView.findViewById(R.id.listView);
            listView.setAdapter(listAdapter);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                    if (i == 0) {
                        if (getParentActivity() == null) {
                            return;
                        }
                        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());

                        CharSequence[] items = new CharSequence[] {LocaleController.getString("FromCamera", R.string.FromCamera), LocaleController.getString("FromGalley", R.string.FromGalley), LocaleController.getString("Cancel", R.string.Cancel)};

                        builder.setItems(items, new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialogInterface, int i) {
                                try {
                                    if (i == 0) {
                                        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
                                        File image = Utilities.generatePicturePath();
                                        if (image != null) {
                                            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, Uri.fromFile(image));
                                            currentPicturePath = image.getAbsolutePath();
                                        }
                                        startActivityForResult(takePictureIntent, 10);
                                    } else if (i == 1) {
                                        Intent photoPickerIntent = new Intent(Intent.ACTION_PICK);
                                        photoPickerIntent.setType("image/*");
                                        startActivityForResult(photoPickerIntent, 11);
                                    }
                                } catch (Exception e) {
                                    FileLog.e("tmessages", e);
                                }
                            }
                        });
                        showAlertDialog(builder);
                    } else {
                        TLRPC.WallPaper wallPaper = wallPapers.get(i - 1);
                        selectedBackground = wallPaper.id;
                        listAdapter.notifyDataSetChanged();
                        processSelectedBackground();
                    }
                }
            });

            processSelectedBackground();
        } else {
            ViewGroup parent = (ViewGroup)fragmentView.getParent();
            if (parent != null) {
                parent.removeView(fragmentView);
            }
        }
        return fragmentView;
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == 10) {
                Utilities.addMediaToGallery(currentPicturePath);
                FileOutputStream stream = null;
                try {
                    Point screenSize = AndroidUtilities.getRealScreenSize();
                    Bitmap bitmap = ImageLoader.loadBitmap(currentPicturePath, null, screenSize.x, screenSize.y, true);
                    File toFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "wallpaper-temp.jpg");
                    stream = new FileOutputStream(toFile);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 87, stream);
                    selectedBackground = -1;
                    selectedColor = 0;
                    backgroundImage.setImageBitmap(bitmap);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                } finally {
                    try {
                        if (stream != null) {
                            stream.close();
                        }
                    } catch (Exception e) {
                        FileLog.e("tmessages", e);
                    }
                }
                currentPicturePath = null;
            } else if (requestCode == 11) {
                if (data == null || data.getData() == null) {
                    return;
                }
                try {
                    Point screenSize = AndroidUtilities.getRealScreenSize();
                    Bitmap bitmap = ImageLoader.loadBitmap(null, data.getData(), screenSize.x, screenSize.y, true);
                    File toFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "wallpaper-temp.jpg");
                    FileOutputStream stream = new FileOutputStream(toFile);
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 87, stream);
                    selectedBackground = -1;
                    selectedColor = 0;
                    backgroundImage.setImageBitmap(bitmap);
                } catch (Exception e) {
                    FileLog.e("tmessages", e);
                }
            }
        }
    }

    @Override
    public void saveSelfArgs(Bundle args) {
        if (currentPicturePath != null) {
            args.putString("path", currentPicturePath);
        }
    }

    @Override
    public void restoreSelfArgs(Bundle args) {
        currentPicturePath = args.getString("path");
    }

    private void processSelectedBackground() {
        TLRPC.WallPaper wallPaper = wallpappersByIds.get(selectedBackground);
        if (selectedBackground != -1 && selectedBackground != 1000001 && wallPaper != null && wallPaper instanceof TLRPC.TL_wallPaper) {
            int width = AndroidUtilities.displaySize.x;
            int height = AndroidUtilities.displaySize.y;
            if (width > height) {
                int temp = width;
                width = height;
                height = temp;
            }
            TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(wallPaper.sizes, Math.min(width, height));
            String fileName = size.location.volume_id + "_" + size.location.local_id + ".jpg";
            File f = new File(FileLoader.getInstance().getDirectory(FileLoader.MEDIA_DIR_CACHE), fileName);
            if (!f.exists()) {
                progressBar.setProgress(0);
                loadingFile = fileName;
                loadingFileObject = f;
                doneButton.setEnabled(false);
                progressBar.setVisibility(View.VISIBLE);
                loadingSize = size;
                selectedColor = 0;
                FileLoader.getInstance().loadFile(size, true);
                backgroundImage.setBackgroundColor(0);
            } else {
                if (loadingFile != null) {
                    FileLoader.getInstance().cancelLoadFile(loadingSize);
                }
                loadingFileObject = null;
                loadingFile = null;
                loadingSize = null;
                try {
                    backgroundImage.setImageURI(Uri.fromFile(f));
                } catch (Throwable e) {
                    FileLog.e("tmessages", e);
                }
                backgroundImage.setBackgroundColor(0);
                selectedColor = 0;
                doneButton.setEnabled(true);
                progressBar.setVisibility(View.GONE);
            }
        } else {
            if (loadingFile != null) {
                FileLoader.getInstance().cancelLoadFile(loadingSize);
            }
            if (selectedBackground == 1000001) {
                backgroundImage.setImageResource(R.drawable.background_hd);
                backgroundImage.setBackgroundColor(0);
                selectedColor = 0;
            } else if (selectedBackground == -1) {
                File toFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "wallpaper-temp.jpg");
                if (!toFile.exists()) {
                    toFile = new File(ApplicationLoader.applicationContext.getFilesDir(), "wallpaper.jpg");
                }
                if (toFile.exists()) {
                    backgroundImage.setImageURI(Uri.fromFile(toFile));
                } else {
                    selectedBackground = 1000001;
                    processSelectedBackground();
                }
            } else {
                if (wallPaper == null) {
                    return;
                }
                if (wallPaper instanceof TLRPC.TL_wallPaperSolid) {
                    backgroundImage.setImageBitmap(null);
                    selectedColor = 0xff000000 | wallPaper.bg_color;
                    backgroundImage.setBackgroundColor(selectedColor);
                }
            }
            loadingFileObject = null;
            loadingFile = null;
            loadingSize = null;
            doneButton.setEnabled(true);
            progressBar.setVisibility(View.GONE);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, final Object... args) {
        if (id == NotificationCenter.FileDidFailedLoad) {
            String location = (String)args[0];
            if (loadingFile != null && loadingFile.equals(location)) {
                loadingFileObject = null;
                loadingFile = null;
                loadingSize = null;
                progressBar.setVisibility(View.GONE);
                doneButton.setEnabled(false);
            }
        } else if (id == NotificationCenter.FileDidLoaded) {
            String location = (String)args[0];
            if (loadingFile != null && loadingFile.equals(location)) {
                backgroundImage.setImageURI(Uri.fromFile(loadingFileObject));
                progressBar.setVisibility(View.GONE);
                backgroundImage.setBackgroundColor(0);
                doneButton.setEnabled(true);
                loadingFileObject = null;
                loadingFile = null;
                loadingSize = null;
            }
        } else if (id == NotificationCenter.FileLoadProgressChanged) {
            String location = (String)args[0];
            if (loadingFile != null && loadingFile.equals(location)) {
                Float progress = (Float)args[1];
                progressBar.setProgress((int)(progress * 100));
            }
        } else if (id == NotificationCenter.wallpapersDidLoaded) {
            AndroidUtilities.runOnUIThread(new Runnable() {
                @Override
                public void run() {
                    wallPapers = (ArrayList<TLRPC.WallPaper>)args[0];
                    wallpappersByIds.clear();
                    for (TLRPC.WallPaper wallPaper : wallPapers) {
                        wallpappersByIds.put(wallPaper.id, wallPaper);
                    }
                    if (listAdapter != null) {
                        listAdapter.notifyDataSetChanged();
                    }
                    if (!wallPapers.isEmpty() && backgroundImage != null) {
                        processSelectedBackground();
                    }
                    loadWallpapers();
                }
            });
        }
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        fixLayout();
    }

    private void loadWallpapers() {
        TLRPC.TL_account_getWallPapers req = new TLRPC.TL_account_getWallPapers();
        long reqId = ConnectionsManager.getInstance().performRpc(req, new RPCRequest.RPCRequestDelegate() {
            @Override
            public void run(final TLObject response, TLRPC.TL_error error) {
                if (error != null) {
                    return;
                }
                AndroidUtilities.runOnUIThread(new Runnable() {
                    @Override
                    public void run() {
                        wallPapers.clear();
                        TLRPC.Vector res = (TLRPC.Vector)response;
                        wallpappersByIds.clear();
                        for (Object obj : res.objects) {
                            wallPapers.add((TLRPC.WallPaper)obj);
                            wallpappersByIds.put(((TLRPC.WallPaper)obj).id, (TLRPC.WallPaper)obj);
                        }
                        if (listAdapter != null) {
                            listAdapter.notifyDataSetChanged();
                        }
                        if (backgroundImage != null) {
                            processSelectedBackground();
                        }
                        MessagesStorage.getInstance().putWallpapers(wallPapers);
                    }
                });
            }
        });
        ConnectionsManager.getInstance().bindRequestToGuid(reqId, classGuid);
    }

    private void fixLayout() {
        ViewTreeObserver obs = fragmentView.getViewTreeObserver();
        obs.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                fragmentView.getViewTreeObserver().removeOnPreDrawListener(this);
                if (listAdapter != null) {
                    listAdapter.notifyDataSetChanged();
                }
                if (listView != null) {
                    listView.post(new Runnable() {
                        @Override
                        public void run() {
                            listView.scrollTo(0);
                        }
                    });
                }
                return false;
            }
        });
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        processSelectedBackground();
        fixLayout();
    }

    private class ListAdapter extends BaseFragmentAdapter {
        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int i) {
            return true;
        }

        @Override
        public int getCount() {
            return 1 + wallPapers.size();
        }

        @Override
        public Object getItem(int i) {
            return null;
        }

        @Override
        public long getItemId(int i) {
            return i;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @Override
        public View getView(int i, View view, ViewGroup viewGroup) {
            int type = getItemViewType(i);
            if (type == 0) {
                if (view == null) {
                    LayoutInflater li = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    view = li.inflate(R.layout.settings_wallpapers_my_row, viewGroup, false);
                }
                View parentView = view.findViewById(R.id.parent);
                ImageView imageView = (ImageView)view.findViewById(R.id.image);
                View selection = view.findViewById(R.id.selection);
                if (i == 0) {
                    if (selectedBackground == -1 || selectedColor != 0 || selectedBackground == 1000001) {
                        imageView.setBackgroundColor(0x5A475866);
                    } else {
                        imageView.setBackgroundColor(0x5A000000);
                    }
                    imageView.setImageResource(R.drawable.ic_gallery_background);
                    if (selectedBackground == -1) {
                        selection.setVisibility(View.VISIBLE);
                    } else {
                        selection.setVisibility(View.INVISIBLE);
                    }
                } else {
                    imageView.setImageBitmap(null);
                    TLRPC.WallPaper wallPaper = wallPapers.get(i - 1);
                    imageView.setBackgroundColor(0xff000000 | wallPaper.bg_color);
                    if (wallPaper.id == selectedBackground) {
                        selection.setVisibility(View.VISIBLE);
                    } else {
                        selection.setVisibility(View.INVISIBLE);
                    }
                }
            } else if (type == 1) {
                if (view == null) {
                    LayoutInflater li = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    view = li.inflate(R.layout.settings_wallpapers_other_row, viewGroup, false);
                }
                BackupImageView image = (BackupImageView)view.findViewById(R.id.image);
                View selection = view.findViewById(R.id.selection);
                TLRPC.WallPaper wallPaper = wallPapers.get(i - 1);
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(wallPaper.sizes, AndroidUtilities.dp(100));
                if (size != null && size.location != null) {
                    image.setImage(size.location, "100_100", (Drawable)null);
                }
                if (wallPaper.id == selectedBackground) {
                    selection.setVisibility(View.VISIBLE);
                } else {
                    selection.setVisibility(View.INVISIBLE);
                }
            }
            return view;
        }

        @Override
        public int getItemViewType(int i) {
            if (i == 0) {
                return 0;
            }
            TLRPC.WallPaper wallPaper = wallPapers.get(i - 1);
            if (wallPaper instanceof TLRPC.TL_wallPaperSolid) {
                return 0;
            }
            return 1;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public boolean isEmpty() {
            return false;
        }
    }
}
