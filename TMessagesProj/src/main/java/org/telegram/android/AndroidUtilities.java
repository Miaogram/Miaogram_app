/*
 * This is the source code of Telegram for Android v. 1.4.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2014.
 */

package org.telegram.android;

import android.app.Activity;
import android.app.AlarmManager;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Environment;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.DisplayMetrics;
import android.util.Log;
import android.util.StateSet;
import android.view.Display;
import android.view.Surface;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.EdgeEffect;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.ConnectionsManager;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.TLRPC;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.Components.ForegroundDetector;
import org.telegram.ui.Components.NumberPicker;
import org.telegram.ui.Components.TypefaceSpan;
import org.telegram.ui.LaunchActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

public class AndroidUtilities {

    private static final Hashtable<String, Typeface> typefaceCache = new Hashtable<>();
    private static int prevOrientation = -10;
    private static boolean waitingForSms = false;
    private static final Object smsLock = new Object();

    public static int statusBarHeight = 0;
    public static float density = 1;
    public static Point displaySize = new Point();
    public static Integer photoSize = null;
    public static DisplayMetrics displayMetrics = new DisplayMetrics();
    private static Boolean isTablet = null;

    public static final String THEME_PREFS = "theme";
    private static final String TAG = "AndroidUtilities";
    public static final int defColor = 0xff58BCD5;//0xff43C3DB;//0xff2f8cc9;58BCD5//0xff55abd2
    public static int themeColor = getIntColor("themeColor");

    public static boolean needRestart = false;

    //public static boolean hideScreenshot = false;
    //public static boolean hideMobile = false;

    static {
        density = ApplicationLoader.applicationContext.getResources().getDisplayMetrics().density;
        checkDisplaySize();
    }

    public static void lockOrientation(Activity activity) {
        if (activity == null || prevOrientation != -10 || Build.VERSION.SDK_INT < 9) {
            return;
        }
        try {
            prevOrientation = activity.getRequestedOrientation();
            WindowManager manager = (WindowManager)activity.getSystemService(Activity.WINDOW_SERVICE);
            if (manager != null && manager.getDefaultDisplay() != null) {
                int rotation = manager.getDefaultDisplay().getRotation();
                int orientation = activity.getResources().getConfiguration().orientation;
                int SCREEN_ORIENTATION_REVERSE_LANDSCAPE = 8;
                int SCREEN_ORIENTATION_REVERSE_PORTRAIT = 9;
                if (Build.VERSION.SDK_INT < 9) {
                    SCREEN_ORIENTATION_REVERSE_LANDSCAPE = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                    SCREEN_ORIENTATION_REVERSE_PORTRAIT = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                }

                if (rotation == Surface.ROTATION_270) {
                    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    } else {
                        activity.setRequestedOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                    }
                } else if (rotation == Surface.ROTATION_90) {
                    if (orientation == Configuration.ORIENTATION_PORTRAIT) {
                        activity.setRequestedOrientation(SCREEN_ORIENTATION_REVERSE_PORTRAIT);
                    } else {
                        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    }
                } else if (rotation == Surface.ROTATION_0) {
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
                    } else {
                        activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
                    }
                } else {
                    if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                        activity.setRequestedOrientation(SCREEN_ORIENTATION_REVERSE_LANDSCAPE);
                    } else {
                        activity.setRequestedOrientation(SCREEN_ORIENTATION_REVERSE_PORTRAIT);
                    }
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public static void unlockOrientation(Activity activity) {
        if (activity == null || Build.VERSION.SDK_INT < 9) {
            return;
        }
        try {
            if (prevOrientation != -10) {
                activity.setRequestedOrientation(prevOrientation);
                prevOrientation = -10;
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public static Typeface getTypeface(String assetPath) {
        synchronized (typefaceCache) {
            if (!typefaceCache.containsKey(assetPath)) {
                try {
                    Typeface t = Typeface.createFromAsset(ApplicationLoader.applicationContext.getAssets(), assetPath);
                    typefaceCache.put(assetPath, t);
                } catch (Exception e) {
                    FileLog.e("Typefaces", "Could not get typeface '" + assetPath + "' because " + e.getMessage());
                    return null;
                }
            }
            return typefaceCache.get(assetPath);
        }
    }

    public static boolean isWaitingForSms() {
        boolean value = false;
        synchronized (smsLock) {
            value = waitingForSms;
        }
        return value;
    }

    public static void setWaitingForSms(boolean value) {
        synchronized (smsLock) {
            waitingForSms = value;
        }
    }

    public static void showKeyboard(View view) {
        if (view == null) {
            return;
        }
        InputMethodManager inputManager = (InputMethodManager)view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        inputManager.showSoftInput(view, InputMethodManager.SHOW_IMPLICIT);

        ((InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE)).showSoftInput(view, 0);
    }

    public static boolean isKeyboardShowed(View view) {
        if (view == null) {
            return false;
        }
        InputMethodManager inputManager = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        return inputManager.isActive(view);
    }

    public static void hideKeyboard(View view) {
        if (view == null) {
            return;
        }
        InputMethodManager imm = (InputMethodManager) view.getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
        if (!imm.isActive()) {
            return;
        }
        imm.hideSoftInputFromWindow(view.getWindowToken(), 0);
    }

    public static File getCacheDir() {
        String state = null;
        try {
            state = Environment.getExternalStorageState();
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        if (state == null || state.startsWith(Environment.MEDIA_MOUNTED)) {
            try {
                File file = ApplicationLoader.applicationContext.getExternalCacheDir();
                if (file != null) {
                    return file;
                }
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        }
        try {
            File file = ApplicationLoader.applicationContext.getCacheDir();
            if (file != null) {
                return file;
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return new File("");
    }

    public static int dp(float value) {
        return (int)Math.ceil(density * value);
    }

    public static float dpf2(float value) {
        return density * value;
    }

    public static void checkDisplaySize() {
        try {
            WindowManager manager = (WindowManager)ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
            if (manager != null) {
                Display display = manager.getDefaultDisplay();
                if (display != null) {
                    display.getMetrics(displayMetrics);
                    if(android.os.Build.VERSION.SDK_INT < 13) {
                        displaySize.set(display.getWidth(), display.getHeight());
                    } else {
                        display.getSize(displaySize);
                    }
                    FileLog.e("tmessages", "display size = " + displaySize.x + " " + displaySize.y + " " + displayMetrics.xdpi + "x" + displayMetrics.ydpi);
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public static float getPixelsInCM(float cm, boolean isX) {
        return (cm / 2.54f) * (isX ? displayMetrics.xdpi : displayMetrics.ydpi);
    }

    public static long makeBroadcastId(int id) {
        return 0x0000000100000000L | ((long)id & 0x00000000FFFFFFFFL);
    }

    public static int getMyLayerVersion(int layer) {
        return layer & 0xffff;
    }

    public static int getPeerLayerVersion(int layer) {
        return (layer >> 16) & 0xffff;
    }

    public static int setMyLayerVersion(int layer, int version) {
        return layer & 0xffff0000 | version;
    }

    public static int setPeerLayerVersion(int layer, int version) {
        return layer & 0x0000ffff | (version << 16);
    }

    public static void runOnUIThread(Runnable runnable) {
        runOnUIThread(runnable, 0);
    }

    public static void runOnUIThread(Runnable runnable, long delay) {
        if (delay == 0) {
            ApplicationLoader.applicationHandler.post(runnable);
        } else {
            ApplicationLoader.applicationHandler.postDelayed(runnable, delay);
        }
    }

    public static void cancelRunOnUIThread(Runnable runnable) {
        ApplicationLoader.applicationHandler.removeCallbacks(runnable);
    }

    public static boolean isTablet() {
        if (isTablet == null) {
            isTablet = ApplicationLoader.applicationContext.getResources().getBoolean(R.bool.isTablet);
        }
        return isTablet;
    }

    public static boolean isSmallTablet() {
        float minSide = Math.min(displaySize.x, displaySize.y) / density;
        return minSide <= 700;
    }

    public static int getMinTabletSide() {
        if (!isSmallTablet()) {
            int smallSide = Math.min(displaySize.x, displaySize.y);
            int leftSide = smallSide * 35 / 100;
            if (leftSide < dp(320)) {
                leftSide = dp(320);
            }
            return smallSide - leftSide;
        } else {
            int smallSide = Math.min(displaySize.x, displaySize.y);
            int maxSide = Math.max(displaySize.x, displaySize.y);
            int leftSide = maxSide * 35 / 100;
            if (leftSide < dp(320)) {
                leftSide = dp(320);
            }
            return Math.min(smallSide, maxSide - leftSide);
        }
    }

    public static int getPhotoSize() {
        if (photoSize == null) {
            if (Build.VERSION.SDK_INT >= 16) {
                photoSize = 1280;
            } else {
                photoSize = 800;
            }
        }
        return photoSize;
    }

    public static String formatTTLString(int ttl) {
        if (ttl < 60) {
            return LocaleController.formatPluralString("Seconds", ttl);
        } else if (ttl < 60 * 60) {
            return LocaleController.formatPluralString("Minutes", ttl / 60);
        } else if (ttl < 60 * 60 * 24) {
            return LocaleController.formatPluralString("Hours", ttl / 60 / 60);
        } else if (ttl < 60 * 60 * 24 * 7) {
            return LocaleController.formatPluralString("Days", ttl / 60 / 60 / 24);
        } else {
            int days = ttl / 60 / 60 / 24;
            if (ttl % 7 == 0) {
                return LocaleController.formatPluralString("Weeks", days / 7);
            } else {
                return String.format("%s %s", LocaleController.formatPluralString("Weeks", days / 7), LocaleController.formatPluralString("Days", days % 7));
            }
        }
    }

    public static AlertDialog.Builder buildTTLAlert(final Context context, final TLRPC.EncryptedChat encryptedChat) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle(LocaleController.getString("MessageLifetime", R.string.MessageLifetime));
        final NumberPicker numberPicker = new NumberPicker(context);
        numberPicker.setMinValue(0);
        numberPicker.setMaxValue(20);
        if (encryptedChat.ttl > 0 && encryptedChat.ttl < 16) {
            numberPicker.setValue(encryptedChat.ttl);
        } else if (encryptedChat.ttl == 30) {
            numberPicker.setValue(16);
        } else if (encryptedChat.ttl == 60) {
            numberPicker.setValue(17);
        } else if (encryptedChat.ttl == 60 * 60) {
            numberPicker.setValue(18);
        } else if (encryptedChat.ttl == 60 * 60 * 24) {
            numberPicker.setValue(19);
        } else if (encryptedChat.ttl == 60 * 60 * 24 * 7) {
            numberPicker.setValue(20);
        } else if (encryptedChat.ttl == 0) {
            numberPicker.setValue(0);
        }
        numberPicker.setFormatter(new NumberPicker.Formatter() {
            @Override
            public String format(int value) {
                if (value == 0) {
                    return LocaleController.getString("ShortMessageLifetimeForever", R.string.ShortMessageLifetimeForever);
                } else if (value >= 1 && value < 16) {
                    return AndroidUtilities.formatTTLString(value);
                } else if (value == 16) {
                    return AndroidUtilities.formatTTLString(30);
                } else if (value == 17) {
                    return AndroidUtilities.formatTTLString(60);
                } else if (value == 18) {
                    return AndroidUtilities.formatTTLString(60 * 60);
                } else if (value == 19) {
                    return AndroidUtilities.formatTTLString(60 * 60 * 24);
                } else if (value == 20) {
                    return AndroidUtilities.formatTTLString(60 * 60 * 24 * 7);
                }
                return "";
            }
        });
        builder.setView(numberPicker);
        builder.setNegativeButton(LocaleController.getString("Done", R.string.Done), new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                int oldValue = encryptedChat.ttl;
                which = numberPicker.getValue();
                if (which >= 0 && which < 16) {
                    encryptedChat.ttl = which;
                } else if (which == 16) {
                    encryptedChat.ttl = 30;
                } else if (which == 17) {
                    encryptedChat.ttl = 60;
                } else if (which == 18) {
                    encryptedChat.ttl = 60 * 60;
                } else if (which == 19) {
                    encryptedChat.ttl = 60 * 60 * 24;
                } else if (which == 20) {
                    encryptedChat.ttl = 60 * 60 * 24 * 7;
                }
                if (oldValue != encryptedChat.ttl) {
                    SecretChatHelper.getInstance().sendTTLMessage(encryptedChat, null);
                    MessagesStorage.getInstance().updateEncryptedChatTTL(encryptedChat);
                }
            }
        });
        return builder;
    }

    public static void clearCursorDrawable(EditText editText) {
        if (editText == null || Build.VERSION.SDK_INT < 12) {
            return;
        }
        try {
            Field mCursorDrawableRes = TextView.class.getDeclaredField("mCursorDrawableRes");
            mCursorDrawableRes.setAccessible(true);
            mCursorDrawableRes.setInt(editText, 0);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public static void setProgressBarAnimationDuration(ProgressBar progressBar, int duration) {
        if (progressBar == null) {
            return;
        }
        try {
            Field mCursorDrawableRes = ProgressBar.class.getDeclaredField("mDuration");
            mCursorDrawableRes.setAccessible(true);
            mCursorDrawableRes.setInt(progressBar, duration);
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
    }

    public static int getViewInset(View view) {
        if (view == null || Build.VERSION.SDK_INT < 21) {
            return 0;
        }
        try {
            Field mAttachInfoField = View.class.getDeclaredField("mAttachInfo");
            mAttachInfoField.setAccessible(true);
            Object mAttachInfo = mAttachInfoField.get(view);
            if (mAttachInfo != null) {
                Field mStableInsetsField = mAttachInfo.getClass().getDeclaredField("mStableInsets");
                mStableInsetsField.setAccessible(true);
                Rect insets = (Rect)mStableInsetsField.get(mAttachInfo);
                return insets.bottom;
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return 0;
    }

    public static int getCurrentActionBarHeight() {
        if (isTablet()) {
            return dp(64);
        } else if (ApplicationLoader.applicationContext.getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            return dp(48);
        } else {
            return dp(56);
        }
    }

    public static Point getRealScreenSize() {
        Point size = new Point();
        try {
            WindowManager windowManager = (WindowManager) ApplicationLoader.applicationContext.getSystemService(Context.WINDOW_SERVICE);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
                windowManager.getDefaultDisplay().getRealSize(size);
            } else {
                try {
                    Method mGetRawW = Display.class.getMethod("getRawWidth");
                    Method mGetRawH = Display.class.getMethod("getRawHeight");
                    size.set((Integer) mGetRawW.invoke(windowManager.getDefaultDisplay()), (Integer) mGetRawH.invoke(windowManager.getDefaultDisplay()));
                } catch (Exception e) {
                    size.set(windowManager.getDefaultDisplay().getWidth(), windowManager.getDefaultDisplay().getHeight());
                    FileLog.e("tmessages", e);
                }
            }
        } catch (Exception e) {
            FileLog.e("tmessages", e);
        }
        return size;
    }

    public static void setListViewEdgeEffectColor(AbsListView listView, int color) {
        if (Build.VERSION.SDK_INT >= 21) {
            try {
                Field field = AbsListView.class.getDeclaredField("mEdgeGlowTop");
                field.setAccessible(true);
                EdgeEffect mEdgeGlowTop = (EdgeEffect) field.get(listView);
                if (mEdgeGlowTop != null) {
                    mEdgeGlowTop.setColor(color);
                }

                field = AbsListView.class.getDeclaredField("mEdgeGlowBottom");
                field.setAccessible(true);
                EdgeEffect mEdgeGlowBottom = (EdgeEffect) field.get(listView);
                if (mEdgeGlowBottom != null) {
                    mEdgeGlowBottom.setColor(color);
                }
            } catch (Exception e) {
                FileLog.e("tmessages", e);
            }
        }
    }

    public static void clearDrawableAnimation(View view) {
        if (Build.VERSION.SDK_INT < 21 || view == null) {
            return;
        }
        Drawable drawable = null;
        if (view instanceof ListView) {
            drawable = ((ListView) view).getSelector();
            if (drawable != null) {
                drawable.setState(StateSet.NOTHING);
            }
        } else {
            drawable = view.getBackground();
            if (drawable != null) {
                drawable.setState(StateSet.NOTHING);
                drawable.jumpToCurrentState();
            }
        }
    }

    public static Spannable replaceBold(String str) {
        int start;
        ArrayList<Integer> bolds = new ArrayList<>();
        while ((start = str.indexOf("<b>")) != -1) {
            int end = str.indexOf("</b>") - 3;
            str = str.replaceFirst("<b>", "").replaceFirst("</b>", "");
            bolds.add(start);
            bolds.add(end);
        }
        SpannableStringBuilder stringBuilder = new SpannableStringBuilder(str);
        for (int a = 0; a < bolds.size() / 2; a++) {
            TypefaceSpan span = new TypefaceSpan(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            stringBuilder.setSpan(span, bolds.get(a * 2), bolds.get(a * 2 + 1), Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        }
        return stringBuilder;
    }

    public static boolean needShowPasscode(boolean reset) {
        boolean wasInBackground;
        if (Build.VERSION.SDK_INT >= 14) {
            wasInBackground = ForegroundDetector.getInstance().isWasInBackground(reset);
            if (reset) {
                ForegroundDetector.getInstance().resetBackgroundVar();
            }
        } else {
            wasInBackground = UserConfig.lastPauseTime != 0;
        }
        return UserConfig.passcodeHash.length() > 0 && wasInBackground &&
                (UserConfig.appLocked || UserConfig.autoLockIn != 0 && UserConfig.lastPauseTime != 0 && !UserConfig.appLocked && (UserConfig.lastPauseTime + UserConfig.autoLockIn) <= ConnectionsManager.getInstance().getCurrentTime());
    }

    /*public static void turnOffHardwareAcceleration(Window window) {
        if (window == null || Build.MODEL == null || Build.VERSION.SDK_INT < 11) {
            return;
        }
        if (Build.MODEL.contains("GT-S5301") ||
                Build.MODEL.contains("GT-S5303") ||
                Build.MODEL.contains("GT-B5330") ||
                Build.MODEL.contains("GT-S5302") ||
                Build.MODEL.contains("GT-S6012B") ||
                Build.MODEL.contains("MegaFon_SP-AI")) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }
    }*/
    //NEW
    public static int getIntColor(String key){
        SharedPreferences themePrefs = ApplicationLoader.applicationContext.getSharedPreferences(THEME_PREFS, Activity.MODE_PRIVATE);
        return themePrefs.getInt(key, defColor);
    }

    public static int getIntDef(String key, int def){
        SharedPreferences themePrefs = ApplicationLoader.applicationContext.getSharedPreferences(THEME_PREFS, Activity.MODE_PRIVATE);
        return themePrefs.getInt(key, def);
    }

    public static int getIntAlphaColor(String key, float factor){
        SharedPreferences themePrefs = ApplicationLoader.applicationContext.getSharedPreferences(THEME_PREFS, Activity.MODE_PRIVATE);
        int color = themePrefs.getInt(key, defColor);
        int alpha = Math.round(Color.alpha(color) * factor);
        int red = Color.red(color);
        int green = Color.green(color);
        int blue = Color.blue(color);
        return Color.argb(alpha, red, green, blue);
    }

    public static int getIntDarkerColor(String key, int factor){
        SharedPreferences themePrefs = ApplicationLoader.applicationContext.getSharedPreferences(THEME_PREFS, Activity.MODE_PRIVATE);
        int color = themePrefs.getInt(key, defColor);
        return setDarkColor(color, factor);
    }

    public static int setDarkColor(int color, int factor){
        int red = Color.red(color) - factor;
        int green = Color.green(color) - factor;
        int blue = Color.blue(color) - factor;
        if(factor < 0){
            red = (red > 0xff) ? 0xff : red;
            green = (green > 0xff) ? 0xff : green;
            blue = (blue > 0xff) ? 0xff : blue;
            if(red == 0xff && green == 0xff && blue == 0xff){
                red = factor;
                green = factor;
                blue = factor;
            }
        }
        if(factor > 0){
            red = (red < 0) ? 0 : red;
            green = (green < 0) ? 0 : green;
            blue = (blue < 0) ? 0 : blue;
            if(red == 0 && green == 0 && blue == 0){
                red = factor;
                green = factor;
                blue = factor;
            }
        }
        return Color.argb(0xff, red, green, blue);
    }

    public static void setIntColor(String key, int value){
        SharedPreferences themePrefs = ApplicationLoader.applicationContext.getSharedPreferences(THEME_PREFS, Activity.MODE_PRIVATE);
        SharedPreferences.Editor e = themePrefs.edit();
        e.putInt(key, value);
        e.commit();
    }

    public static void setBoolPref(Context context, String key, Boolean b){
        SharedPreferences sharedPref = context.getSharedPreferences(THEME_PREFS, 0);
        SharedPreferences.Editor e = sharedPref.edit();
        e.putBoolean(key, b);
        e.commit();
    }

    public static void setStringPref(Context context, String key, String s){
        SharedPreferences sharedPref = context.getSharedPreferences(THEME_PREFS, 0);
        SharedPreferences.Editor e = sharedPref.edit();
        e.putString(key, s);
        e.commit();
    }

    public static boolean getBoolPref(Context context,String key){
        boolean s = false;
        if (context.getSharedPreferences(THEME_PREFS, 0).getBoolean(key, false)) s=true;
        return s;
    }

    public static boolean getBoolMain(String key){
        boolean s = false;
        if (ApplicationLoader.applicationContext.getSharedPreferences("mainconfig", Activity.MODE_PRIVATE).getBoolean(key, false)) s=true;
        return s;
    }

    public static int getIntPref(Context context,String key){
        int i=0;
        if(key.contains("picker")){
            int intColor = context.getSharedPreferences(THEME_PREFS, 0).getInt(key, Color.WHITE );
            i=intColor;
        }
        return i;
    }
/*
    public static int getIntColorDef(Context context, String key, int def){
        int i=def;
        if(context.getSharedPreferences(THEME_PREFS, 0).getBoolean(key, false)){
            i = context.getSharedPreferences(THEME_PREFS, 0).getInt(key.replace("_check", "_picker"), def);
        }
        return i;
    }*/

    public static void setTVTextColor(Context ctx, TextView tv, String key, int def){
        if(tv==null)return;
        if(getBoolPref(ctx, key))
            def = getIntPref(ctx, key.replace("_check", "_picker"));
        tv.setTextColor(def);
    }

    public static int getSizePref(Context context,String key, int def){
        if(key.contains("picker"))return context.getSharedPreferences(THEME_PREFS, 0).getInt(key, def);
        return def;
    }

    public static void paintActionBarHeader(Activity a, ActionBar ab, String hdCheck, String gdMode){
        if(ab==null)return;
        ab.setBackgroundDrawable(new ColorDrawable(Color.parseColor("#54759E")));
        if(getBoolPref(a, hdCheck)){
            int i = getIntPref(a, hdCheck.replace("_check", "_picker"));
            Drawable d = new ColorDrawable(i);
            try{
                ab.setBackgroundDrawable(d);
                d = paintGradient(a,i,gdMode.replace("mode","color_picker"),gdMode);
                if(d!=null)ab.setBackgroundDrawable(d);
            } catch (Exception e) {
                Log.e(TAG, e.toString());
            }
        }
    }

    public static Drawable paintGradient(Context c, int mainColor, String gColor, String g){
        GradientDrawable gd = null;
        int[] a ={mainColor,getIntPref(c,gColor)};
        int gType = Integer.parseInt(c.getSharedPreferences(THEME_PREFS, 0).getString(g, "0"));
        if(gType==2) gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,a);
        if(gType==1) gd = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM ,a);
        return gd;
    }

    public static Drawable paintDrawable(Context c, int resId, int resIdW, String color){
        Drawable d = c.getResources().getDrawable(resId);
        if(color.contains("_check")){
            if(getBoolPref(c, color)){
                d = c.getResources().getDrawable(resIdW);
                d.setColorFilter(getIntPref(c, color.replace("_check", "_picker")), PorterDuff.Mode.MULTIPLY);
            }
        }
        return d;
    }

    public static void restartApp(){
        Intent mRestartApp = new Intent(ApplicationLoader.applicationContext, LaunchActivity.class);
        int mPendingIntentId = 123456;
        PendingIntent mPendingIntent = PendingIntent.getActivity(ApplicationLoader.applicationContext, mPendingIntentId, mRestartApp, PendingIntent.FLAG_CANCEL_CURRENT);
        AlarmManager mgr = (AlarmManager)ApplicationLoader.applicationContext.getSystemService(Context.ALARM_SERVICE);
        mgr.set(AlarmManager.RTC, System.currentTimeMillis() + 100, mPendingIntent);
        System.exit(0);
    }

    public static void savePreferencesToSD(Context context, String prefName, String tName, boolean toast){
        String folder = "/Telegram/Themes";
        File dataF = new File (findPrefFolder(context),prefName);
        if(checkSDStatus() > 1){
            File f = new File (Environment.getExternalStorageDirectory(), folder);
            f.mkdirs();
            File sdF = new File(f, tName);
            String s = getError(copyFile(dataF,sdF,true));
            if (s.equalsIgnoreCase("4")) {
                if(toast && sdF.getName()!="")Toast.makeText(context,context.getString(R.string.SavedTo,sdF.getName(),folder),Toast.LENGTH_SHORT ).show();
            }else if (s.contains("0")) {
                s = context.getString(R.string.SaveErrorMsg0);
                Toast.makeText(context,"ERROR: "+ s ,Toast.LENGTH_LONG ).show();
            }else{
                Toast.makeText(context,"ERROR: "+s,Toast.LENGTH_LONG ).show();
                Toast.makeText(context,dataF.getAbsolutePath(),Toast.LENGTH_LONG ).show();
            }
        }else{
            Toast.makeText(context,"ERROR: " + context.getString(R.string.NoMediaMessage) , Toast.LENGTH_LONG ).show();
        }
    }

    public static void copyWallpaperToSD(Context context, String tName, boolean toast){
        String folder = "/Telegram/Themes";
        String nFile = "wallpaper.jpg";
        if(checkSDStatus()>0){
            File f1 = context.getFilesDir();
            f1 = new File (f1.getAbsolutePath(), nFile);
            File f2 = new File (Environment.getExternalStorageDirectory(), folder);
            f2.mkdirs();
            f2 = new File(f2, tName+"_"+nFile);
            if(f1.length()>1){
                String s = getError(copyFile(f1,f2,true));
                if(s.contains("4")){
                    if(toast && f2.getName()!="" && folder !="")Toast.makeText(context,context.getString(R.string.SavedTo,f2.getName(),folder),Toast.LENGTH_SHORT ).show();
                    if(f2.getName()=="" || folder =="") Toast.makeText(context,"ERROR: "+s,Toast.LENGTH_SHORT ).show();

                }else{
                    Toast.makeText(context,"ERROR: "+s+"\n"+f1.getAbsolutePath(),Toast.LENGTH_LONG ).show();
                }
            }
        }
    }

    static String findPrefFolder(Context context){
        File f = context.getFilesDir();
        String appDir = f.getAbsolutePath();
        File SPDir = new File (appDir.substring(0,appDir.lastIndexOf('/')+1)+ "shared_prefs/");
        if(!SPDir.exists()) {// && SPDir.isDirectory()) {
            String pck = context.getPackageName();
            SPDir=new File ("/dbdata/databases/"+pck+"/shared_prefs/");
        }
        //Log.i("TAG", SPDir.getAbsolutePath());
        return SPDir.getAbsolutePath();
    }

    static int checkSDStatus(){
        int b=0;
        String s = Environment.getExternalStorageState();
        if (s.equals(Environment.MEDIA_MOUNTED))b=2;
        else if (s.equals(Environment.MEDIA_MOUNTED_READ_ONLY))b=1;
        return b;
    }

    static String getError(int i){
        String s="-1";
        if(i==0)s="0: SOURCE FILE DOESN'T EXIST";
        if(i==1)s="1: DESTINATION FILE DOESN'T EXIST";
        if(i==2)s="2: NULL SOURCE & DESTINATION FILES";
        if(i==3)s="3: NULL SOURCE FILE";
        if(i==4)s="4";
        return s;
    }

    //0: source file doesn't exist
    //1: dest file doesn't exist
    //2: source & dest = NULL
    //3: source = NULL
    //4: dest = NULL
    static int copyFile(File sourceFile, File destFile, boolean save) {
        int i=-1;
        try{
            if (!sourceFile.exists()) {
                return i+1;
            }
            if (!destFile.exists()) {
                if(save)i=i+2;
                destFile.createNewFile();
            }
            FileChannel source = null;
            FileChannel destination = null;
            FileInputStream fileInputStream = new FileInputStream(sourceFile);
            source = fileInputStream.getChannel();
            FileOutputStream fileOutputStream = new FileOutputStream(destFile);
            destination = fileOutputStream.getChannel();
            if (destination != null && source != null) {
                destination.transferFrom(source, 0, source.size());
                i=2;
            }
            if (source != null) {
                source.close();
                i=3;
            }
            if (destination != null) {
                destination.close();
                i=4;
            }
            fileInputStream.close();
            fileOutputStream.close();
        }catch (Exception e)
        {
            System.err.println("Error saving preferences: " + e.getMessage());
            Log.e(e.getMessage() , e.toString());
        }
        return i;
    }

    public static int loadPrefFromSD(Context context, String prefPath){
        File dataF = new File (findPrefFolder(context), THEME_PREFS + ".xml");
        File prefFile = new File (prefPath);
        String s = getError(copyFile(prefFile, dataF, false));
        if (s.contains("0")) {
            Toast.makeText(context,"ERROR: "+ context.getString(R.string.restoreErrorMsg, prefFile.getAbsolutePath()) , Toast.LENGTH_LONG ).show();
        }
        return Integer.parseInt(s);
    }

    public static int loadWallpaperFromSDPath(Context context, String wPath){
        String nFile = "wallpaper.jpg";
        File f1 = context.getFilesDir();
        f1= new File (f1.getAbsolutePath(), nFile);
        //Log.i("f1", f1.getAbsolutePath());
        File wFile = new File (wPath);
        //Log.i("wPath", wPath);
        //Log.i("wFile", wFile.getAbsolutePath());
        String s = "-1";
        if (wFile.exists()){
            s = getError(copyFile(wFile,f1,false));
            if (s.contains("0")) {
                Toast.makeText(context,"ERROR: "+ context.getString(R.string.restoreErrorMsg,wFile.getAbsolutePath()) ,Toast.LENGTH_LONG ).show();
            }else{
                Toast.makeText(context,"ERROR: "+s+"\n"+wFile.getAbsolutePath(),Toast.LENGTH_LONG ).show();
            }
        }
        return Integer.parseInt(s);
    }
/*
    static void modifyXMLfile(File preffile,String sname){
        try {
            File file = preffile;
            //Log.e("modifyXMLfile",preffile.getAbsolutePath());
            //Log.e("modifyXMLfile",preffile.exists()+"");
            List<String> lines = new ArrayList<String>();
            // first, read the file and store the changes
            BufferedReader in = new BufferedReader(new FileReader(file));
            String line = in.readLine();
            while (line != null) {
                if (!line.contains(sname))lines.add(line);
                //Log.e("modifyXMLfile",line);
                line = in.readLine();
            }
            in.close();
            // now, write the file again with the changes
            PrintWriter out = new PrintWriter(file);
            for (String l : lines)
                out.println(l);
            out.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }*/
}
