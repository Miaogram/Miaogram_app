/*
 * This is the source code of Telegram for Android v. 1.4.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2014.
 */

package org.telegram.ui;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Build;
import android.util.Base64;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.Surface;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.android.AndroidUtilities;
import org.telegram.android.LocaleController;
import org.telegram.android.MediaController;
import org.telegram.android.MessagesStorage;
import org.telegram.android.NotificationCenter;
import org.telegram.android.volley.AuthFailureError;
import org.telegram.android.volley.Request;
import org.telegram.android.volley.RequestQueue;
import org.telegram.android.volley.Response;
import org.telegram.android.volley.VolleyError;
import org.telegram.android.volley.toolbox.JsonObjectRequest;
import org.telegram.android.volley.toolbox.Volley;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.TLRPC;
import org.telegram.android.MessageObject;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Adapters.BaseFragmentAdapter;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Cells.PhotoPickerPhotoCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.PhotoPickerBottomLayout;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

public class PhotoPickerActivity extends BaseFragment implements NotificationCenter.NotificationCenterDelegate, PhotoViewer.PhotoViewerProvider {

    public static interface PhotoPickerActivityDelegate {
        public abstract void selectedPhotosChanged();
        public abstract void actionButtonPressed(boolean canceled);
    }

    private RequestQueue requestQueue;

    private int type;
    private HashMap<String, MediaController.SearchImage> selectedWebPhotos;
    private HashMap<Integer, MediaController.PhotoEntry> selectedPhotos;
    private ArrayList<MediaController.SearchImage> recentImages;

    private ArrayList<MediaController.SearchImage> searchResult = new ArrayList<>();
    private HashMap<String, MediaController.SearchImage> searchResultKeys = new HashMap<>();
    private HashMap<String, MediaController.SearchImage> searchResultUrls = new HashMap<>();

    private boolean searching;
    private String nextSearchBingString;
    private boolean giphySearchEndReached = true;
    private String lastSearchString;
    private boolean loadingRecent;

    private MediaController.AlbumEntry selectedAlbum;

    private GridView listView;
    private ListAdapter listAdapter;
    private PhotoPickerBottomLayout photoPickerBottomLayout;
    private FrameLayout progressView;
    private TextView emptyView;
    private ActionBarMenuItem searchItem;
    private int itemWidth = 100;
    private boolean sendPressed;
    private boolean singlePhoto;

    private PhotoPickerActivityDelegate delegate;

    public PhotoPickerActivity(int type, MediaController.AlbumEntry selectedAlbum, HashMap<Integer, MediaController.PhotoEntry> selectedPhotos, HashMap<String, MediaController.SearchImage> selectedWebPhotos, ArrayList<MediaController.SearchImage> recentImages, boolean onlyOnePhoto) {
        super();
        this.selectedAlbum = selectedAlbum;
        this.selectedPhotos = selectedPhotos;
        this.selectedWebPhotos = selectedWebPhotos;
        this.type = type;
        this.recentImages = recentImages;
        this.singlePhoto = onlyOnePhoto;
    }

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.closeChats);
        NotificationCenter.getInstance().addObserver(this, NotificationCenter.recentImagesDidLoaded);
        if (selectedAlbum == null) {
            requestQueue = Volley.newRequestQueue(ApplicationLoader.applicationContext);
            if (recentImages.isEmpty()) {
                MessagesStorage.getInstance().loadWebRecent(type);
                loadingRecent = true;
            }
        }
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.closeChats);
        NotificationCenter.getInstance().removeObserver(this, NotificationCenter.recentImagesDidLoaded);
        if (requestQueue != null) {
            requestQueue.cancelAll("search");
            requestQueue.stop();
        }
        super.onFragmentDestroy();
    }

    @SuppressWarnings("unchecked")
    @Override
    public View createView(LayoutInflater inflater) {
        if (fragmentView == null) {
            actionBar.setBackgroundColor(0xff333333);
            actionBar.setItemsBackground(R.drawable.bar_selector_picker);
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            if (selectedAlbum != null) {
                actionBar.setTitle(selectedAlbum.bucketName);
            } else if (type == 0) {
                actionBar.setTitle(LocaleController.getString("SearchImagesTitle", R.string.SearchImagesTitle));
            } else if (type == 1) {
                actionBar.setTitle(LocaleController.getString("SearchGifsTitle", R.string.SearchGifsTitle));
            }
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        if (Build.VERSION.SDK_INT < 11) {
                            listView.setAdapter(null);
                            listView = null;
                            listAdapter = null;
                        }
                        finishFragment();
                    }
                }
            });

            if (selectedAlbum == null) {
                ActionBarMenu menu = actionBar.createMenu();
                searchItem = menu.addItem(0, R.drawable.ic_ab_search).setIsSearchField(true).setActionBarMenuItemSearchListener(new ActionBarMenuItem.ActionBarMenuItemSearchListener() {
                    @Override
                    public void onSearchExpand() {

                    }

                    @Override
                    public void onSearchCollapse() {
                        finishFragment();
                    }

                    @Override
                    public void onTextChanged(EditText editText) {
                        if (editText.getText().length() == 0) {
                            searchResult.clear();
                            searchResultKeys.clear();
                            lastSearchString = null;
                            nextSearchBingString = null;
                            giphySearchEndReached = true;
                            searching = false;
                            requestQueue.cancelAll("search");
                            if (type == 0) {
                                emptyView.setText(LocaleController.getString("NoRecentPhotos", R.string.NoRecentPhotos));
                            } else if (type == 1) {
                                emptyView.setText(LocaleController.getString("NoRecentGIFs", R.string.NoRecentGIFs));
                            }
                            updateSearchInterface();
                        }
                    }

                    @Override
                    public void onSearchPressed(EditText editText) {
                        if (editText.getText().toString().length() == 0) {
                            return;
                        }
                        searchResult.clear();
                        searchResultKeys.clear();
                        nextSearchBingString = null;
                        giphySearchEndReached = true;
                        if (type == 0) {
                            searchBingImages(editText.getText().toString(), 0, 53);
                        } else if (type == 1) {
                            searchGiphyImages(editText.getText().toString(), 0, 53);
                        }
                        lastSearchString = editText.getText().toString();
                        if (lastSearchString.length() == 0) {
                            lastSearchString = null;
                            if (type == 0) {
                                emptyView.setText(LocaleController.getString("NoRecentPhotos", R.string.NoRecentPhotos));
                            } else if (type == 1) {
                                emptyView.setText(LocaleController.getString("NoRecentGIFs", R.string.NoRecentGIFs));
                            }
                        } else {
                            emptyView.setText(LocaleController.getString("NoResult", R.string.NoResult));
                        }
                        updateSearchInterface();
                    }
                });
            }

            if (selectedAlbum == null) {
                if (type == 0) {
                    searchItem.getSearchField().setHint(LocaleController.getString("SearchImagesTitle", R.string.SearchImagesTitle));
                } else if (type == 1) {
                    searchItem.getSearchField().setHint(LocaleController.getString("SearchGifsTitle", R.string.SearchGifsTitle));
                }
            }

            fragmentView = new FrameLayout(getParentActivity());

            FrameLayout frameLayout = (FrameLayout) fragmentView;
            frameLayout.setBackgroundColor(0xff000000);

            listView = new GridView(getParentActivity());
            listView.setPadding(AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4), AndroidUtilities.dp(4));
            listView.setClipToPadding(false);
            listView.setDrawSelectorOnTop(true);
            listView.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
            listView.setHorizontalScrollBarEnabled(false);
            listView.setVerticalScrollBarEnabled(false);
            listView.setNumColumns(GridView.AUTO_FIT);
            listView.setVerticalSpacing(AndroidUtilities.dp(4));
            listView.setHorizontalSpacing(AndroidUtilities.dp(4));
            listView.setSelector(R.drawable.list_selector);
            frameLayout.addView(listView);
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) listView.getLayoutParams();
            layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
            layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
            layoutParams.bottomMargin = singlePhoto ? 0 : AndroidUtilities.dp(48);
            listView.setLayoutParams(layoutParams);
            listView.setAdapter(listAdapter = new ListAdapter(getParentActivity()));
            AndroidUtilities.setListViewEdgeEffectColor(listView, 0xff333333);
            listView.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                    ArrayList<Object> arrayList = null;
                    if (selectedAlbum != null) {
                        arrayList = (ArrayList) selectedAlbum.photos;
                    } else {
                        if (searchResult.isEmpty() && lastSearchString == null) {
                            arrayList = (ArrayList) recentImages;
                        } else {
                            arrayList = (ArrayList) searchResult;
                        }
                    }
                    if (i < 0 || i >= arrayList.size()) {
                        return;
                    }
                    PhotoViewer.getInstance().setParentActivity(getParentActivity());
                    PhotoViewer.getInstance().openPhotoForSelect(arrayList, i, singlePhoto ? 1 : 0, PhotoPickerActivity.this);
                }
            });

            emptyView = new TextView(getParentActivity());
            emptyView.setTextColor(0xff808080);
            emptyView.setTextSize(20);
            emptyView.setGravity(Gravity.CENTER);
            emptyView.setVisibility(View.GONE);
            if (selectedAlbum != null) {
                emptyView.setText(LocaleController.getString("NoPhotos", R.string.NoPhotos));
            } else {
                if (type == 0) {
                    emptyView.setText(LocaleController.getString("NoRecentPhotos", R.string.NoRecentPhotos));
                } else if (type == 1) {
                    emptyView.setText(LocaleController.getString("NoRecentGIFs", R.string.NoRecentGIFs));
                }
            }
            frameLayout.addView(emptyView);
            layoutParams = (FrameLayout.LayoutParams) emptyView.getLayoutParams();
            layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
            layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
            layoutParams.bottomMargin = singlePhoto ? 0 : AndroidUtilities.dp(48);
            emptyView.setLayoutParams(layoutParams);
            emptyView.setOnTouchListener(new View.OnTouchListener() {
                @Override
                public boolean onTouch(View v, MotionEvent event) {
                    return true;
                }
            });

            if (selectedAlbum == null) {
                listView.setOnScrollListener(new AbsListView.OnScrollListener() {
                    @Override
                    public void onScrollStateChanged(AbsListView absListView, int i) {
                        if (i == SCROLL_STATE_TOUCH_SCROLL) {
                            AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                        }
                    }

                    @Override
                    public void onScroll(AbsListView absListView, int firstVisibleItem, int visibleItemCount, int totalItemCount) {
                        if (visibleItemCount != 0 && firstVisibleItem + visibleItemCount > totalItemCount - 2 && !searching) {
                            if (type == 0 && nextSearchBingString != null) {
                                searchBingImages(lastSearchString, searchResult.size(), 54);
                            } else if (type == 1 && !giphySearchEndReached) {
                                searchGiphyImages(searchItem.getSearchField().getText().toString(), searchResult.size(), 54);
                            }
                        }
                    }
                });

                progressView = new FrameLayout(getParentActivity());
                progressView.setVisibility(View.GONE);
                frameLayout.addView(progressView);
                layoutParams = (FrameLayout.LayoutParams) progressView.getLayoutParams();
                layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
                layoutParams.height = FrameLayout.LayoutParams.MATCH_PARENT;
                layoutParams.bottomMargin = singlePhoto ? 0 : AndroidUtilities.dp(48);
                progressView.setLayoutParams(layoutParams);

                ProgressBar progressBar = new ProgressBar(getParentActivity());
                progressView.addView(progressBar);
                layoutParams = (FrameLayout.LayoutParams) progressBar.getLayoutParams();
                layoutParams.width = FrameLayout.LayoutParams.WRAP_CONTENT;
                layoutParams.height = FrameLayout.LayoutParams.WRAP_CONTENT;
                layoutParams.gravity = Gravity.CENTER;
                progressBar.setLayoutParams(layoutParams);

                updateSearchInterface();
            }

            photoPickerBottomLayout = new PhotoPickerBottomLayout(getParentActivity());
            frameLayout.addView(photoPickerBottomLayout);
            layoutParams = (FrameLayout.LayoutParams) photoPickerBottomLayout.getLayoutParams();
            layoutParams.width = FrameLayout.LayoutParams.MATCH_PARENT;
            layoutParams.height = AndroidUtilities.dp(48);
            layoutParams.gravity = Gravity.BOTTOM;
            photoPickerBottomLayout.setLayoutParams(layoutParams);
            photoPickerBottomLayout.cancelButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    delegate.actionButtonPressed(true);
                    finishFragment();
                }
            });
            photoPickerBottomLayout.doneButton.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    sendSelectedPhotos();
                }
            });
            if (singlePhoto) {
                photoPickerBottomLayout.setVisibility(View.GONE);
            }

            listView.setEmptyView(emptyView);
            photoPickerBottomLayout.updateSelectedCount(selectedPhotos.size() + selectedWebPhotos.size(), true);
        } else {
            ViewGroup parent = (ViewGroup)fragmentView.getParent();
            if (parent != null) {
                parent.removeView(fragmentView);
            }
        }
        return fragmentView;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        if (searchItem != null) {
            searchItem.openSearch();
            getParentActivity().getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN);
        }
        fixLayout();
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        fixLayout();
    }

    @SuppressWarnings("unchecked")
    @Override
    public void didReceivedNotification(int id, Object... args) {
        if (id == NotificationCenter.closeChats) {
            removeSelfFromStack();
        } else if (id == NotificationCenter.recentImagesDidLoaded) {
            if (selectedAlbum == null && type == (Integer) args[0]) {
                recentImages = (ArrayList<MediaController.SearchImage>) args[1];
                loadingRecent = false;
                updateSearchInterface();
            }
        }
    }

    private PhotoPickerPhotoCell getCellForIndex(int index) {
        int count = listView.getChildCount();

        for (int a = 0; a < count; a++) {
            View view = listView.getChildAt(a);
            if (view instanceof PhotoPickerPhotoCell) {
                PhotoPickerPhotoCell cell = (PhotoPickerPhotoCell) view;
                int num = (Integer)cell.photoImage.getTag();
                if (selectedAlbum != null) {
                    if (num < 0 || num >= selectedAlbum.photos.size()) {
                        continue;
                    }
                } else {
                    ArrayList<MediaController.SearchImage> array = null;
                    if (searchResult.isEmpty() && lastSearchString == null) {
                        array = recentImages;
                    } else {
                        array = searchResult;
                    }
                    if (num < 0 || num >= array.size()) {
                        continue;
                    }
                }
                if (num == index) {
                    return cell;
                }
            }
        }
        return null;
    }

    @Override
    public PhotoViewer.PlaceProviderObject getPlaceForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index) {
        PhotoPickerPhotoCell cell = getCellForIndex(index);
        if (cell != null) {
            int coords[] = new int[2];
            cell.photoImage.getLocationInWindow(coords);
            PhotoViewer.PlaceProviderObject object = new PhotoViewer.PlaceProviderObject();
            object.viewX = coords[0];
            object.viewY = coords[1] - AndroidUtilities.statusBarHeight;
            object.parentView = listView;
            object.imageReceiver = cell.photoImage.imageReceiver;
            object.thumb = object.imageReceiver.getBitmap();
            cell.checkBox.setVisibility(View.GONE);
            return object;
        }
        return null;
    }

    @Override
    public void updatePhotoAtIndex(int index) {
        PhotoPickerPhotoCell cell = getCellForIndex(index);
        if (cell != null) {
            if (selectedAlbum != null) {
                cell.photoImage.setOrientation(0, true);
                MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(index);
                if (photoEntry.thumbPath != null) {
                    cell.photoImage.setImage(photoEntry.thumbPath, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                } else if (photoEntry.path != null) {
                    cell.photoImage.setOrientation(photoEntry.orientation, true);
                    cell.photoImage.setImage("thumb://" + photoEntry.imageId + ":" + photoEntry.path, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                } else {
                    cell.photoImage.setImageResource(R.drawable.nophotos);
                }
            } else {
                ArrayList<MediaController.SearchImage> array = null;
                if (searchResult.isEmpty() && lastSearchString == null) {
                    array = recentImages;
                } else {
                    array = searchResult;
                }
                MediaController.SearchImage photoEntry = array.get(index);
                if (photoEntry.thumbPath != null) {
                    cell.photoImage.setImage(photoEntry.thumbPath, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                } else if (photoEntry.thumbUrl != null && photoEntry.thumbUrl.length() > 0) {
                    cell.photoImage.setImage(photoEntry.thumbUrl, null, cell.getContext().getResources().getDrawable(R.drawable.nophotos));
                } else {
                    cell.photoImage.setImageResource(R.drawable.nophotos);
                }
            }
        }
    }

    @Override
    public Bitmap getThumbForPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index) {
        PhotoPickerPhotoCell cell = getCellForIndex(index);
        if (cell != null) {
            return cell.photoImage.imageReceiver.getBitmap();
        }
        return null;
    }

    @Override
    public void willSwitchFromPhoto(MessageObject messageObject, TLRPC.FileLocation fileLocation, int index) {
        int count = listView.getChildCount();
        for (int a = 0; a < count; a++) {
            View view = listView.getChildAt(a);
            if (view.getTag() == null) {
                continue;
            }
            PhotoPickerPhotoCell cell = (PhotoPickerPhotoCell) view;
            int num = (Integer)view.getTag();
            if (selectedAlbum != null) {
                if (num < 0 || num >= selectedAlbum.photos.size()) {
                    continue;
                }
            } else {
                ArrayList<MediaController.SearchImage> array = null;
                if (searchResult.isEmpty() && lastSearchString == null) {
                    array = recentImages;
                } else {
                    array = searchResult;
                }
                if (num < 0 || num >= array.size()) {
                    continue;
                }
            }
            if (num == index) {
                cell.checkBox.setVisibility(View.VISIBLE);
                break;
            }
        }
    }

    @Override
    public void willHidePhotoViewer() {
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    @Override
    public boolean isPhotoChecked(int index) {
        if (selectedAlbum != null) {
            return !(index < 0 || index >= selectedAlbum.photos.size()) && selectedPhotos.containsKey(selectedAlbum.photos.get(index).imageId);
        } else {
            ArrayList<MediaController.SearchImage> array = null;
            if (searchResult.isEmpty() && lastSearchString == null) {
                array = recentImages;
            } else {
                array = searchResult;
            }
            return !(index < 0 || index >= array.size()) && selectedWebPhotos.containsKey(array.get(index).id);
        }
    }

    @Override
    public void setPhotoChecked(int index) {
        boolean add = true;
        if (selectedAlbum != null) {
            if (index < 0 || index >= selectedAlbum.photos.size()) {
                return;
            }
            MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(index);
            if (selectedPhotos.containsKey(photoEntry.imageId)) {
                selectedPhotos.remove(photoEntry.imageId);
                add = false;
            } else {
                selectedPhotos.put(photoEntry.imageId, photoEntry);
            }
        } else {
            MediaController.SearchImage photoEntry = null;
            ArrayList<MediaController.SearchImage> array = null;
            if (searchResult.isEmpty() && lastSearchString == null) {
                array = recentImages;
            } else {
                array = searchResult;
            }
            if (index < 0 || index >= array.size()) {
                return;
            }
            photoEntry = array.get(index);
            if (selectedWebPhotos.containsKey(photoEntry.id)) {
                selectedWebPhotos.remove(photoEntry.id);
                add = false;
            } else {
                selectedWebPhotos.put(photoEntry.id, photoEntry);
            }
        }
        int count = listView.getChildCount();
        for (int a = 0; a < count; a++) {
            View view = listView.getChildAt(a);
            int num = (Integer) view.getTag();
            if (num == index) {
                ((PhotoPickerPhotoCell) view).checkBox.setChecked(add, false);
                break;
            }
        }
        photoPickerBottomLayout.updateSelectedCount(selectedPhotos.size() + selectedWebPhotos.size(), true);
        delegate.selectedPhotosChanged();
    }

    @Override
    public void cancelButtonPressed() {
        delegate.actionButtonPressed(true);
        finishFragment();
    }

    @Override
    public void sendButtonPressed(int index) {
        if (selectedAlbum != null) {
            if (selectedPhotos.isEmpty()) {
                if (index < 0 || index >= selectedAlbum.photos.size()) {
                    return;
                }
                MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(index);
                selectedPhotos.put(photoEntry.imageId, photoEntry);
            }
        } else if (selectedPhotos.isEmpty()) {
            ArrayList<MediaController.SearchImage> array = null;
            if (searchResult.isEmpty() && lastSearchString == null) {
                array = recentImages;
            } else {
                array = searchResult;
            }
            if (index < 0 || index >= array.size()) {
                return;
            }
            MediaController.SearchImage photoEntry = array.get(index);
            selectedWebPhotos.put(photoEntry.id, photoEntry);
        }
        sendSelectedPhotos();
    }

    @Override
    public int getSelectedCount() {
        return selectedPhotos.size() + selectedWebPhotos.size();
    }

    @Override
    public void onOpenAnimationEnd() {
        super.onOpenAnimationEnd();
        if (searchItem != null) {
            AndroidUtilities.showKeyboard(searchItem.getSearchField());
        }
    }

    private void updateSearchInterface() {
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
        if (searching && searchResult.isEmpty() || loadingRecent && lastSearchString == null) {
            progressView.setVisibility(View.VISIBLE);
            listView.setEmptyView(null);
            emptyView.setVisibility(View.GONE);
        } else {
            progressView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            listView.setEmptyView(emptyView);
        }
    }

    private void searchGiphyImages(String query, int offset, final int count) {
        if (searching) {
            searching = false;
            requestQueue.cancelAll("search");
        }
        try {
            searching = true;
            String url = String.format(Locale.US, "https://api.giphy.com/v1/gifs/search?q=%s&offset=%d&limit=%d&api_key=141Wa2KDAfNfxu", URLEncoder.encode(query, "UTF-8"), offset, count);
            JsonObjectRequest jsonObjReq = new JsonObjectRequest(Request.Method.GET, url, null,
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            try {
                                JSONArray result = response.getJSONArray("data");
                                try {
                                    JSONObject pagination = response.getJSONObject("pagination");
                                    int total_count = pagination.getInt("total_count");
                                    giphySearchEndReached = searchResult.size() + result.length() >= total_count;
                                } catch (Exception e) {
                                    FileLog.e("tmessages", e);
                                }
                                boolean added = false;
                                for (int a = 0; a < result.length(); a++) {
                                    try {
                                        JSONObject object = result.getJSONObject(a);
                                        String id = object.getString("id");
                                        if (searchResultKeys.containsKey(id)) {
                                            continue;
                                        }
                                        added = true;
                                        JSONObject images = object.getJSONObject("images");
                                        JSONObject thumb = images.getJSONObject("downsized_still");
                                        JSONObject original = images.getJSONObject("original");
                                        MediaController.SearchImage bingImage = new MediaController.SearchImage();
                                        bingImage.id = id;
                                        bingImage.width = original.getInt("width");
                                        bingImage.height = original.getInt("height");
                                        bingImage.size = original.getInt("size");
                                        bingImage.imageUrl = original.getString("url");
                                        bingImage.thumbUrl = thumb.getString("url");
                                        bingImage.type = 1;
                                        searchResult.add(bingImage);
                                        searchResultKeys.put(id, bingImage);
                                    } catch (Exception e) {
                                        FileLog.e("tmessages", e);
                                    }
                                }
                                if (!added) {
                                    giphySearchEndReached = true;
                                }
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                            searching = false;
                            updateSearchInterface();
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            FileLog.e("tmessages", "Error: " + error.getMessage());
                            giphySearchEndReached = true;
                            searching = false;
                            updateSearchInterface();
                        }
                    });
            jsonObjReq.setTag("search");
            requestQueue.add(jsonObjReq);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    private void searchBingImages(String query, int offset, int count) {
        if (searching) {
            searching = false;
            requestQueue.cancelAll("search");
        }
        try {
            searching = true;
            String url;
            if (nextSearchBingString != null) {
                url = nextSearchBingString;
            } else {
                url = String.format(Locale.US, "https://api.datamarket.azure.com/Bing/Search/v1/Image?Query='%s'&$skip=%d&$top=%d&$format=json&Adult='Off'", URLEncoder.encode(query, "UTF-8"), offset, count);
            }
            JsonObjectRequest jsonObjReq = new JsonObjectRequest(Request.Method.GET, url, null,
                    new Response.Listener<JSONObject>() {
                        @Override
                        public void onResponse(JSONObject response) {
                            nextSearchBingString = null;
                            try {
                                JSONObject d = response.getJSONObject("d");
                                JSONArray result = d.getJSONArray("results");
                                try {
                                    nextSearchBingString = d.getString("__next");
                                } catch (Exception e) {
                                    nextSearchBingString = null;
                                    FileLog.e("tmessages", e);
                                }
                                for (int a = 0; a < result.length(); a++) {
                                    try {
                                        JSONObject object = result.getJSONObject(a);
                                        String id = Utilities.MD5(object.getString("MediaUrl"));
                                        if (searchResultKeys.containsKey(id)) {
                                            continue;
                                        }
                                        MediaController.SearchImage bingImage = new MediaController.SearchImage();
                                        bingImage.id = id;
                                        bingImage.width = object.getInt("Width");
                                        bingImage.height = object.getInt("Height");
                                        bingImage.size = object.getInt("FileSize");
                                        bingImage.imageUrl = object.getString("MediaUrl");
                                        JSONObject thumbnail = object.getJSONObject("Thumbnail");
                                        bingImage.thumbUrl = thumbnail.getString("MediaUrl");
                                        searchResult.add(bingImage);
                                        searchResultKeys.put(id, bingImage);
                                    } catch (Exception e) {
                                        FileLog.e("tmessages", e);
                                    }
                                }
                            } catch (Exception e) {
                                FileLog.e("tmessages", e);
                            }
                            searching = false;
                            if (nextSearchBingString != null && !nextSearchBingString.contains("json")) {
                                nextSearchBingString += "&$format=json";
                            }
                            updateSearchInterface();
                        }
                    },
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            FileLog.e("tmessages", "Error: " + error.getMessage());
                            nextSearchBingString = null;
                            searching = false;
                            updateSearchInterface();
                        }
                    }) {

                @Override
                public Map<String, String> getHeaders() throws AuthFailureError {
                    Map<String, String> headers = new HashMap<>();
                    String auth = "Basic " + Base64.encodeToString((BuildVars.BING_SEARCH_KEY + ":" + BuildVars.BING_SEARCH_KEY).getBytes(), Base64.NO_WRAP);
                    headers.put("Authorization", auth);
                    return headers;
                }
            };
            jsonObjReq.setTag("search");
            requestQueue.add(jsonObjReq);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
            nextSearchBingString = null;
            searching = false;
            updateSearchInterface();
        }
    }

    public void setDelegate(PhotoPickerActivityDelegate delegate) {
        this.delegate = delegate;
    }

    private void sendSelectedPhotos() {
        if (selectedPhotos.isEmpty() && selectedWebPhotos.isEmpty() || delegate == null || sendPressed) {
            return;
        }
        sendPressed = true;
        delegate.actionButtonPressed(false);
        finishFragment();
    }

    private void fixLayout() {
        if (listView != null) {
            ViewTreeObserver obs = listView.getViewTreeObserver();
            obs.addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    fixLayoutInternal();
                    if (listView != null) {
                        listView.getViewTreeObserver().removeOnPreDrawListener(this);
                    }
                    return false;
                }
            });
        }
    }

    private void fixLayoutInternal() {
        if (getParentActivity() == null) {
            return;
        }
        int position = listView.getFirstVisiblePosition();
        WindowManager manager = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Activity.WINDOW_SERVICE);
        int rotation = manager.getDefaultDisplay().getRotation();

        int columnsCount = 2;
        if (AndroidUtilities.isTablet()) {
            columnsCount = 3;
        } else {
            if (rotation == Surface.ROTATION_270 || rotation == Surface.ROTATION_90) {
                columnsCount = 5;
            } else {
                columnsCount = 3;
            }
        }
        listView.setNumColumns(columnsCount);
        if (AndroidUtilities.isTablet()) {
            itemWidth = (AndroidUtilities.dp(490) - ((columnsCount + 1) * AndroidUtilities.dp(4))) / columnsCount;
        } else {
            itemWidth = (AndroidUtilities.displaySize.x - ((columnsCount + 1) * AndroidUtilities.dp(4))) / columnsCount;
        }
        listView.setColumnWidth(itemWidth);

        listAdapter.notifyDataSetChanged();
        listView.setSelection(position);

        if (selectedAlbum == null) {
            emptyView.setPadding(0, 0, 0, (int)((AndroidUtilities.displaySize.y - AndroidUtilities.getCurrentActionBarHeight()) * 0.4f));
        }
    }

    private class ListAdapter extends BaseFragmentAdapter {
        private Context mContext;

        public ListAdapter(Context context) {
            mContext = context;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return selectedAlbum != null;
        }

        @Override
        public boolean isEnabled(int i) {
            if (selectedAlbum == null) {
                if (searchResult.isEmpty() && lastSearchString == null) {
                    return i < recentImages.size();
                } else {
                    return i < searchResult.size();
                }
            }
            return true;
        }

        @Override
        public int getCount() {
            if (selectedAlbum == null) {
                if (searchResult.isEmpty() && lastSearchString == null) {
                    return recentImages.size();
                } else if (type == 0) {
                    return searchResult.size() + (nextSearchBingString == null ? 0 : 1);
                } else if (type == 1) {
                    return searchResult.size() + (giphySearchEndReached ? 0 : 1);
                }
            }
            return selectedAlbum.photos.size();
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
            int viewType = getItemViewType(i);
            if (viewType == 0) {
                PhotoPickerPhotoCell cell = (PhotoPickerPhotoCell) view;
                if (view == null) {
                    view = new PhotoPickerPhotoCell(mContext);
                    cell = (PhotoPickerPhotoCell) view;
                    cell.checkFrame.setOnClickListener(new View.OnClickListener() {
                        @Override
                        public void onClick(View v) {
                            int index = (Integer) ((View) v.getParent()).getTag();
                            if (selectedAlbum != null) {
                                MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(index);
                                if (selectedPhotos.containsKey(photoEntry.imageId)) {
                                    selectedPhotos.remove(photoEntry.imageId);
                                    photoEntry.imagePath = null;
                                    photoEntry.thumbPath = null;
                                    updatePhotoAtIndex(index);
                                } else {
                                    selectedPhotos.put(photoEntry.imageId, photoEntry);
                                }
                                ((PhotoPickerPhotoCell) v.getParent()).checkBox.setChecked(selectedPhotos.containsKey(photoEntry.imageId), true);
                            } else {
                                AndroidUtilities.hideKeyboard(getParentActivity().getCurrentFocus());
                                MediaController.SearchImage photoEntry = null;
                                if (searchResult.isEmpty() && lastSearchString == null) {
                                    photoEntry = recentImages.get((Integer)((View)v.getParent()).getTag());
                                } else {
                                    photoEntry = searchResult.get((Integer)((View)v.getParent()).getTag());
                                }
                                if (selectedWebPhotos.containsKey(photoEntry.id)) {
                                    selectedWebPhotos.remove(photoEntry.id);
                                    photoEntry.imagePath = null;
                                    photoEntry.thumbPath = null;
                                    updatePhotoAtIndex(index);
                                } else {
                                    selectedWebPhotos.put(photoEntry.id, photoEntry);
                                }
                                ((PhotoPickerPhotoCell) v.getParent()).checkBox.setChecked(selectedWebPhotos.containsKey(photoEntry.id), true);
                            }
                            photoPickerBottomLayout.updateSelectedCount(selectedPhotos.size() + selectedWebPhotos.size(), true);
                            delegate.selectedPhotosChanged();
                        }
                    });
                    cell.checkFrame.setVisibility(singlePhoto ? View.GONE : View.VISIBLE);
                }
                cell.itemWidth = itemWidth;
                BackupImageView imageView = ((PhotoPickerPhotoCell) view).photoImage;
                imageView.setTag(i);
                view.setTag(i);
                boolean showing = false;
                imageView.setOrientation(0, true);

                if (selectedAlbum != null) {
                    MediaController.PhotoEntry photoEntry = selectedAlbum.photos.get(i);
                    if (photoEntry.thumbPath != null) {
                        imageView.setImage(photoEntry.thumbPath, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                    } else if (photoEntry.path != null) {
                        imageView.setOrientation(photoEntry.orientation, true);
                        imageView.setImage("thumb://" + photoEntry.imageId + ":" + photoEntry.path, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                    } else {
                        imageView.setImageResource(R.drawable.nophotos);
                    }
                    cell.checkBox.setChecked(selectedPhotos.containsKey(photoEntry.imageId), false);
                    showing = PhotoViewer.getInstance().isShowingImage(photoEntry.path);
                } else {
                    MediaController.SearchImage photoEntry = null;
                    if (searchResult.isEmpty() && lastSearchString == null) {
                        photoEntry = recentImages.get(i);
                    } else {
                        photoEntry = searchResult.get(i);
                    }
                    if (photoEntry.thumbPath != null) {
                        imageView.setImage(photoEntry.thumbPath, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                    } else if (photoEntry.thumbUrl != null && photoEntry.thumbUrl.length() > 0) {
                        imageView.setImage(photoEntry.thumbUrl, null, mContext.getResources().getDrawable(R.drawable.nophotos));
                    } else {
                        imageView.setImageResource(R.drawable.nophotos);
                    }
                    cell.checkBox.setChecked(selectedWebPhotos.containsKey(photoEntry.id), false);
                    showing = PhotoViewer.getInstance().isShowingImage(photoEntry.thumbUrl);
                }
                imageView.imageReceiver.setVisible(!showing, false);
                cell.checkBox.setVisibility(singlePhoto || showing ? View.GONE : View.VISIBLE);
            } else if (viewType == 1) {
                if (view == null) {
                    LayoutInflater li = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
                    view = li.inflate(R.layout.media_loading_layout, viewGroup, false);
                }
                ViewGroup.LayoutParams params = view.getLayoutParams();
                params.width = itemWidth;
                params.height = itemWidth;
                view.setLayoutParams(params);
            }
            return view;
        }

        @Override
        public int getItemViewType(int i) {
            if (selectedAlbum != null || searchResult.isEmpty() && lastSearchString == null && i < recentImages.size() || i < searchResult.size()) {
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
            if (selectedAlbum != null) {
                return selectedAlbum.photos.isEmpty();
            } else {
                if (searchResult.isEmpty() && lastSearchString == null) {
                    return recentImages.isEmpty();
                } else {
                    return searchResult.isEmpty();
                }
            }
        }
    }
}
