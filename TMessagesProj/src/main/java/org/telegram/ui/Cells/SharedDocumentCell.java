/*
 * This is the source code of Telegram for Android v. 2.0.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2014.
 */

package org.telegram.ui.Cells;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import org.telegram.android.AndroidUtilities;
import org.telegram.android.ImageLoader;
import org.telegram.android.ImageReceiver;
import org.telegram.android.LocaleController;
import org.telegram.android.MediaController;
import org.telegram.android.MessageObject;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.R;
import org.telegram.messenger.TLRPC;
import org.telegram.messenger.Utilities;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CheckBox;
import org.telegram.ui.Components.LineProgressView;

import java.io.File;
import java.util.Date;

public class SharedDocumentCell extends FrameLayout  implements MediaController.FileDownloadProgressListener {

    private ImageView placeholderImabeView;
    private BackupImageView thumbImageView;
    private TextView nameTextView;
    private TextView extTextView;
    private TextView dateTextView;
    private ImageView statusImageView;
    private LineProgressView progressView;
    private CheckBox checkBox;

    private boolean needDivider;

    private static Paint paint;

    private int TAG;

    private MessageObject message;
    private boolean loading;
    private boolean loaded;

    private int icons[] = {
            R.drawable.media_doc_blue,
            R.drawable.media_doc_green,
            R.drawable.media_doc_red,
            R.drawable.media_doc_yellow
    };

    public SharedDocumentCell(Context context) {
        super(context);

        if (paint == null) {
            paint = new Paint();
            paint.setColor(0xffd9d9d9);
            paint.setStrokeWidth(1);
        }

        TAG = MediaController.getInstance().generateObserverTag();

        placeholderImabeView = new ImageView(context);
        addView(placeholderImabeView);
        LayoutParams layoutParams = (LayoutParams) placeholderImabeView.getLayoutParams();
        layoutParams.width = AndroidUtilities.dp(40);
        layoutParams.height = AndroidUtilities.dp(40);
        layoutParams.leftMargin = LocaleController.isRTL ? 0 : AndroidUtilities.dp(12);
        layoutParams.rightMargin = LocaleController.isRTL ? AndroidUtilities.dp(12) : 0;
        layoutParams.topMargin = AndroidUtilities.dp(8);
        layoutParams.gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        placeholderImabeView.setLayoutParams(layoutParams);

        extTextView = new TextView(context);
        extTextView.setTextColor(0xffffffff);
        extTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        extTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        extTextView.setLines(1);
        extTextView.setMaxLines(1);
        extTextView.setSingleLine(true);
        extTextView.setGravity(Gravity.CENTER);
        extTextView.setEllipsize(TextUtils.TruncateAt.END);
        addView(extTextView);
        layoutParams = (LayoutParams) extTextView.getLayoutParams();
        layoutParams.width = AndroidUtilities.dp(32);
        layoutParams.height = LayoutParams.WRAP_CONTENT;
        layoutParams.topMargin = AndroidUtilities.dp(22);
        layoutParams.leftMargin = LocaleController.isRTL ? 0 : AndroidUtilities.dp(16);
        layoutParams.rightMargin = LocaleController.isRTL ? AndroidUtilities.dp(16) : 0;
        layoutParams.gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        extTextView.setLayoutParams(layoutParams);

        thumbImageView = new BackupImageView(context);
        thumbImageView.imageReceiver.setDelegate(new ImageReceiver.ImageReceiverDelegate() {
            @Override
            public void didSetImage(ImageReceiver imageReceiver, boolean set, boolean thumb) {
                extTextView.setVisibility(set ? GONE : VISIBLE);
                placeholderImabeView.setVisibility(set ? GONE : VISIBLE);
            }
        });
        addView(thumbImageView);
        layoutParams = (LayoutParams) thumbImageView.getLayoutParams();
        layoutParams.width = AndroidUtilities.dp(40);
        layoutParams.height = AndroidUtilities.dp(40);
        layoutParams.leftMargin = LocaleController.isRTL ? 0 : AndroidUtilities.dp(12);
        layoutParams.rightMargin = LocaleController.isRTL ? AndroidUtilities.dp(12) : 0;
        layoutParams.topMargin = AndroidUtilities.dp(8);
        layoutParams.gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        thumbImageView.setLayoutParams(layoutParams);

        nameTextView = new TextView(context);
        nameTextView.setTextColor(0xff222222);
        nameTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        nameTextView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        nameTextView.setLines(1);
        nameTextView.setMaxLines(1);
        nameTextView.setSingleLine(true);
        nameTextView.setEllipsize(TextUtils.TruncateAt.END);
        nameTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        addView(nameTextView);
        layoutParams = (LayoutParams) nameTextView.getLayoutParams();
        layoutParams.width = LayoutParams.MATCH_PARENT;
        layoutParams.height = LayoutParams.WRAP_CONTENT;
        layoutParams.topMargin = AndroidUtilities.dp(5);
        layoutParams.leftMargin = LocaleController.isRTL ? AndroidUtilities.dp(8) : AndroidUtilities.dp(72);
        layoutParams.rightMargin = LocaleController.isRTL ? AndroidUtilities.dp(72) : AndroidUtilities.dp(8);
        layoutParams.gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        nameTextView.setLayoutParams(layoutParams);

        statusImageView = new ImageView(context);
        statusImageView.setVisibility(GONE);
        addView(statusImageView);
        layoutParams = (LayoutParams) statusImageView.getLayoutParams();
        layoutParams.width = LayoutParams.WRAP_CONTENT;
        layoutParams.height = LayoutParams.WRAP_CONTENT;
        layoutParams.topMargin = AndroidUtilities.dp(35);
        layoutParams.leftMargin = LocaleController.isRTL ? AndroidUtilities.dp(8) : AndroidUtilities.dp(72);
        layoutParams.rightMargin = LocaleController.isRTL ? AndroidUtilities.dp(72) : AndroidUtilities.dp(8);
        layoutParams.gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        statusImageView.setLayoutParams(layoutParams);

        dateTextView = new TextView(context);
        dateTextView.setTextColor(0xff999999);
        dateTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        dateTextView.setLines(1);
        dateTextView.setMaxLines(1);
        dateTextView.setSingleLine(true);
        dateTextView.setEllipsize(TextUtils.TruncateAt.END);
        dateTextView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
        addView(dateTextView);
        layoutParams = (LayoutParams) dateTextView.getLayoutParams();
        layoutParams.width = LayoutParams.MATCH_PARENT;
        layoutParams.height = LayoutParams.WRAP_CONTENT;
        layoutParams.topMargin = AndroidUtilities.dp(30);
        layoutParams.leftMargin = LocaleController.isRTL ? AndroidUtilities.dp(8) : AndroidUtilities.dp(72);
        layoutParams.rightMargin = LocaleController.isRTL ? AndroidUtilities.dp(72) : AndroidUtilities.dp(8);
        layoutParams.gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        dateTextView.setLayoutParams(layoutParams);

        progressView = new LineProgressView(context);
        addView(progressView);
        layoutParams = (LayoutParams) progressView.getLayoutParams();
        layoutParams.width = LayoutParams.MATCH_PARENT;
        layoutParams.height = AndroidUtilities.dp(2);
        layoutParams.topMargin = AndroidUtilities.dp(54);
        layoutParams.leftMargin = LocaleController.isRTL ? 0 : AndroidUtilities.dp(72);
        layoutParams.rightMargin = LocaleController.isRTL ? AndroidUtilities.dp(72) : 0;
        layoutParams.gravity = LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT;
        progressView.setLayoutParams(layoutParams);

        checkBox = new CheckBox(context, R.drawable.round_check2);
        checkBox.setVisibility(GONE);
        addView(checkBox);
        layoutParams = (LayoutParams) checkBox.getLayoutParams();
        layoutParams.width = AndroidUtilities.dp(22);
        layoutParams.height = AndroidUtilities.dp(22);
        layoutParams.topMargin = AndroidUtilities.dp(30);
        layoutParams.leftMargin = LocaleController.isRTL ? 0 : AndroidUtilities.dp(34);
        layoutParams.rightMargin = LocaleController.isRTL ? AndroidUtilities.dp(34) : 0;
        layoutParams.gravity = (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        checkBox.setLayoutParams(layoutParams);
    }

    private int getThumbForNameOrMime(String name, String mime) {
        if (name != null && name.length() != 0) {
            int color = -1;
            if (name.contains(".doc") || name.contains(".txt") || name.contains(".psd")) {
                color = 0;
            } else if (name.contains(".xls") || name.contains(".csv")) {
                color = 1;
            } else if (name.contains(".pdf") || name.contains(".ppt") || name.contains(".key")) {
                color = 2;
            } else if (name.contains(".zip") || name.contains(".rar") || name.contains(".ai") || name.contains(".mp3")  || name.contains(".mov") || name.contains(".avi")) {
                color = 3;
            }
            if (color == -1) {
                int idx;
                String ext = (idx = name.lastIndexOf(".")) == -1 ? "" : name.substring(idx + 1);
                if (ext.length() != 0) {
                    color = ext.charAt(0) % icons.length;
                } else {
                    color = name.charAt(0) % icons.length;
                }
            }
            return icons[color];
        }
        return icons[0];
    }

    public void setTextAndValueAndTypeAndThumb(String text, String value, String type, String thumb, int resId) {
        nameTextView.setText(text);
        dateTextView.setText(value);
        if (type != null) {
            extTextView.setVisibility(VISIBLE);
            extTextView.setText(type);
        } else {
            extTextView.setVisibility(GONE);
        }
        if (resId == 0) {
            placeholderImabeView.setImageResource(getThumbForNameOrMime(text, type));
            placeholderImabeView.setVisibility(VISIBLE);
        } else {
            placeholderImabeView.setVisibility(GONE);
        }
        if (thumb != null || resId != 0) {
            if (thumb != null) {
                thumbImageView.setImage(thumb, "40_40", null);
            } else  {
                thumbImageView.setImageResource(resId);
            }
            thumbImageView.setVisibility(VISIBLE);
        } else {
            thumbImageView.setVisibility(GONE);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        MediaController.getInstance().removeLoadingFileObserver(this);
    }

    public void setChecked(boolean checked, boolean animated) {
        if (checkBox.getVisibility() != VISIBLE) {
            checkBox.setVisibility(VISIBLE);
        }
        checkBox.setChecked(checked, animated);
    }

    public void setDocument(MessageObject document, boolean divider) {
        needDivider = divider;
        message = document;
        loaded = false;
        loading = false;

        if (document != null && document.messageOwner.media != null) {
            int idx = -1;
            String name = FileLoader.getDocumentFileName(document.messageOwner.media.document);
            placeholderImabeView.setVisibility(VISIBLE);
            extTextView.setVisibility(VISIBLE);
            placeholderImabeView.setImageResource(getThumbForNameOrMime(name, document.messageOwner.media.document.mime_type));
            nameTextView.setText(name);
            extTextView.setText((idx = name.lastIndexOf(".")) == -1 ? "" : name.substring(idx + 1).toLowerCase());
            if (document.messageOwner.media.document.thumb instanceof TLRPC.TL_photoSizeEmpty) {
                thumbImageView.setVisibility(GONE);
                thumbImageView.setImageBitmap(null);
            } else {
                thumbImageView.setVisibility(VISIBLE);
                thumbImageView.setImage(document.messageOwner.media.document.thumb.location, "40_40", (Drawable) null);
            }
            long date = (long) document.messageOwner.date * 1000;
            dateTextView.setText(String.format("%s, %s", Utilities.formatFileSize(document.messageOwner.media.document.size), LocaleController.formatString("formatDateAtTime", R.string.formatDateAtTime, LocaleController.formatterYear.format(new Date(date)), LocaleController.formatterDay.format(new Date(date)))));
        } else {
            nameTextView.setText("");
            extTextView.setText("");
            dateTextView.setText("");
            placeholderImabeView.setVisibility(VISIBLE);
            extTextView.setVisibility(VISIBLE);
            thumbImageView.setVisibility(GONE);
            thumbImageView.setImageBitmap(null);
        }

        setWillNotDraw(!needDivider);
        progressView.setProgress(0, false);
        updateFileExistIcon();
    }

    public void updateFileExistIcon() {
        if (message != null && message.messageOwner.media != null) {
            String fileName = null;
            File cacheFile = null;
            if (message.messageOwner.attachPath == null || message.messageOwner.attachPath.length() == 0 || !(new File(message.messageOwner.attachPath).exists())) {
                cacheFile = FileLoader.getPathToMessage(message.messageOwner);
                if (!cacheFile.exists()) {
                    fileName = FileLoader.getAttachFileName(message.messageOwner.media.document);
                }
            }
            loaded = false;
            if (fileName == null) {
                statusImageView.setVisibility(GONE);
                dateTextView.setPadding(0, 0, 0, 0);
                loading = false;
                loaded = true;
                MediaController.getInstance().removeLoadingFileObserver(this);
            } else {
                MediaController.getInstance().addLoadingFileObserver(fileName, this);
                loading = FileLoader.getInstance().isLoadingFile(fileName);
                statusImageView.setVisibility(VISIBLE);
                statusImageView.setImageResource(loading ? R.drawable.media_doc_pause : R.drawable.media_doc_load);
                dateTextView.setPadding(LocaleController.isRTL ? 0 : AndroidUtilities.dp(14), 0, LocaleController.isRTL ? AndroidUtilities.dp(14) : 0, 0);
                if (loading) {
                    progressView.setVisibility(VISIBLE);
                    Float progress = ImageLoader.getInstance().getFileProgress(fileName);
                    if (progress == null) {
                        progress = 0.0f;
                    }
                    progressView.setProgress(progress, false);
                } else {
                    progressView.setVisibility(GONE);
                }
            }
        } else {
            loading = false;
            loaded = true;
            progressView.setVisibility(GONE);
            progressView.setProgress(0, false);
            statusImageView.setVisibility(GONE);
            dateTextView.setPadding(0, 0, 0, 0);
            MediaController.getInstance().removeLoadingFileObserver(this);
        }
    }

    public MessageObject getDocument() {
        return message;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public boolean isLoading() {
        return loading;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(56) + (needDivider ? 1 : 0), MeasureSpec.EXACTLY));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (needDivider) {
            canvas.drawLine(AndroidUtilities.dp(72), getHeight() - 1, getWidth() - getPaddingRight(), getHeight() - 1, paint);
        }
    }

    @Override
    public void onFailedDownload(String name) {
        updateFileExistIcon();
    }

    @Override
    public void onSuccessDownload(String name) {
        progressView.setProgress(1, true);
        updateFileExistIcon();
    }

    @Override
    public void onProgressDownload(String fileName, float progress) {
        progressView.setProgress(progress, true);
    }

    @Override
    public void onProgressUpload(String fileName, float progress, boolean isEncrypted) {

    }

    @Override
    public int getObserverTag() {
        return TAG;
    }
}
