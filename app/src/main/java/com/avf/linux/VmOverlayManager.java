package com.avf.linux;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Properties;

/**
 * Lightweight, event-driven floating VM status pill (TYPE_APPLICATION_OVERLAY).
 *
 * Design invariants:
 *   1. Zero periodic timers, animations, or WakeLocks: updates strictly on VmStatus
 *      transitions so screen-off standby (Suspend-to-RAM) incurs zero CPU/GPU wakeups.
 *   2. Never steals input/IME focus from foreground apps (FLAG_NOT_FOCUSABLE).
 *   3. Automatically detaches whenever the VM stops or overlay preference is turned off.
 *   4. Supports drag repositioning (persisted), tap to open AVF Linux, and long-press
 *      to toggle between compact label pill ("● VM · VPN") and minimal dot ("●").
 */
final class VmOverlayManager {
    private static final String TAG = "AvfLinux";
    private static final long LONG_PRESS_MS = 550L;

    private final Context mContext;
    private final VmConfigStore mStore;
    private final WindowManager mWindowManager;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final int mTouchSlop;

    private LinearLayout mRootView;
    private View mDotView;
    private TextView mLabelView;
    private WindowManager.LayoutParams mLayoutParams;

    private volatile boolean mAttached = false;
    private boolean mDisposed = false;
    private boolean mCompact = false;
    private int mSavedX = Integer.MIN_VALUE;
    private int mSavedY = Integer.MIN_VALUE;

    private String mLastRenderedSignature = "";

    VmOverlayManager(Context context, VmConfigStore store) {
        mContext = context.getApplicationContext();
        mStore = store;
        mWindowManager = mContext.getSystemService(WindowManager.class);
        mTouchSlop = ViewConfiguration.get(mContext).getScaledTouchSlop();
        if (mStore != null) {
            Properties p = mStore.loadSettings();
            mSavedX = mStore.getIntProp(p, "overlay_x", Integer.MIN_VALUE);
            mSavedY = mStore.getIntProp(p, "overlay_y", Integer.MIN_VALUE);
            mCompact = mStore.getBoolProp(p, "overlay_compact", false);
        }
    }

    boolean isAttached() {
        return mAttached;
    }

    boolean canDraw() {
        return Settings.canDrawOverlays(mContext);
    }

    void sync(final VmStatus status) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            syncOnMain(status);
        } else {
            mMainHandler.post(() -> syncOnMain(status));
        }
    }

    void onConfigurationChanged() {
        mMainHandler.post(() -> {
            if (!mAttached || mRootView == null || mLayoutParams == null || mWindowManager == null) {
                return;
            }
            clampPosition(mLayoutParams);
            try {
                mWindowManager.updateViewLayout(mRootView, mLayoutParams);
            } catch (Throwable ignored) {}
        });
    }

    void dispose() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            mDisposed = true;
            detachNow();
        } else {
            mMainHandler.post(() -> {
                mDisposed = true;
                detachNow();
            });
        }
    }

    private void syncOnMain(VmStatus status) {
        boolean shouldShow = !mDisposed
            && mWindowManager != null
            && status != null
            && status.showOverlay
            && status.state.isActive()
            && Settings.canDrawOverlays(mContext);

        if (!shouldShow) {
            detachNow();
            return;
        }

        ensureViewCreated();
        renderStatusIfChanged(status, false);

        if (!mAttached) {
            clampPosition(mLayoutParams);
            try {
                mWindowManager.addView(mRootView, mLayoutParams);
                mAttached = true;
            } catch (Throwable t) {
                mAttached = false;
                Log.w(TAG, "Overlay addView failed: " + t.getMessage());
            }
        }
    }

    private void detachNow() {
        mLastRenderedSignature = "";
        if (mAttached && mRootView != null && mWindowManager != null) {
            try {
                mWindowManager.removeView(mRootView);
            } catch (Throwable ignored) {}
        }
        mAttached = false;
    }

    private int dp(float v) {
        float d = mContext.getResources().getDisplayMetrics().density;
        return Math.round(v * d);
    }

    private void ensureViewCreated() {
        if (mRootView != null) return;

        LinearLayout root = new LinearLayout(mContext);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);

        View dot = new View(mContext);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(7.5f), dp(7.5f));
        dot.setLayoutParams(dotLp);
        root.addView(dot);

        TextView label = new TextView(mContext);
        label.setTextSize(11.5f);
        label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        label.setSingleLine(true);
        LinearLayout.LayoutParams lblLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lblLp.leftMargin = dp(6);
        label.setLayoutParams(lblLp);
        root.addView(label);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        );
        lp.gravity = Gravity.TOP | Gravity.START;

        DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        lp.x = (mSavedX != Integer.MIN_VALUE) ? mSavedX : Math.max(dp(8), dm.widthPixels - dp(104));
        lp.y = (mSavedY != Integer.MIN_VALUE) ? mSavedY : dp(96);

        installTouchHandler(root);

        mRootView = root;
        mDotView = dot;
        mLabelView = label;
        mLayoutParams = lp;
    }

    private void installTouchHandler(final View view) {
        view.setOnTouchListener(new View.OnTouchListener() {
            private float downRawX;
            private float downRawY;
            private int startX;
            private int startY;
            private boolean dragging;
            private boolean longPressTriggered;

            private final Runnable longPressRunnable = () -> {
                if (!dragging && mAttached) {
                    longPressTriggered = true;
                    mCompact = !mCompact;
                    VmStatus latest = currentStatusTag();
                    if (latest != null) {
                        renderStatusIfChanged(latest, true);
                    }
                    clampPosition(mLayoutParams);
                    if (mAttached && mWindowManager != null) {
                        try { mWindowManager.updateViewLayout(mRootView, mLayoutParams); } catch (Throwable ignored) {}
                    }
                    persistGeometry();
                }
            };

            @Override
            public boolean onTouch(View v, MotionEvent event) {
                if (mLayoutParams == null || mWindowManager == null) return false;
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downRawX = event.getRawX();
                        downRawY = event.getRawY();
                        startX = mLayoutParams.x;
                        startY = mLayoutParams.y;
                        dragging = false;
                        longPressTriggered = false;
                        mMainHandler.removeCallbacks(longPressRunnable);
                        mMainHandler.postDelayed(longPressRunnable, LONG_PRESS_MS);
                        return true;

                    case MotionEvent.ACTION_MOVE: {
                        float dx = event.getRawX() - downRawX;
                        float dy = event.getRawY() - downRawY;
                        if (!dragging && Math.hypot(dx, dy) > mTouchSlop) {
                            dragging = true;
                            mMainHandler.removeCallbacks(longPressRunnable);
                        }
                        if (dragging && mAttached) {
                            mLayoutParams.x = startX + Math.round(dx);
                            mLayoutParams.y = startY + Math.round(dy);
                            clampPosition(mLayoutParams);
                            try {
                                mWindowManager.updateViewLayout(mRootView, mLayoutParams);
                            } catch (Throwable ignored) {}
                        }
                        return true;
                    }

                    case MotionEvent.ACTION_UP:
                        mMainHandler.removeCallbacks(longPressRunnable);
                        if (dragging) {
                            clampPosition(mLayoutParams);
                            try {
                                mWindowManager.updateViewLayout(mRootView, mLayoutParams);
                            } catch (Throwable ignored) {}
                            persistGeometry();
                        } else if (!longPressTriggered) {
                            openMainActivity();
                        }
                        return true;

                    case MotionEvent.ACTION_CANCEL:
                        mMainHandler.removeCallbacks(longPressRunnable);
                        return true;
                }
                return false;
            }
        });
    }

    private VmStatus currentStatusTag() {
        return (mRootView != null && mRootView.getTag() instanceof VmStatus)
            ? (VmStatus) mRootView.getTag()
            : null;
    }

    private void persistGeometry() {
        if (mLayoutParams == null) return;
        mSavedX = mLayoutParams.x;
        mSavedY = mLayoutParams.y;
        if (mStore != null) {
            mStore.saveOverlayGeometry(mSavedX, mSavedY, mCompact);
        }
    }

    private void clampPosition(WindowManager.LayoutParams lp) {
        DisplayMetrics dm = mContext.getResources().getDisplayMetrics();
        int estW = mCompact ? dp(24) : dp(92);
        int estH = dp(28);
        if (mRootView != null && mRootView.getWidth() > 0) {
            estW = mRootView.getWidth();
            estH = mRootView.getHeight();
        }
        int minX = dp(4);
        int maxX = Math.max(minX, dm.widthPixels - estW - dp(4));
        int minY = dp(28);
        int maxY = Math.max(minY, dm.heightPixels - estH - dp(32));
        lp.x = Math.max(minX, Math.min(maxX, lp.x));
        lp.y = Math.max(minY, Math.min(maxY, lp.y));
    }

    private void openMainActivity() {
        try {
            Intent i = new Intent(mContext, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            mContext.startActivity(i);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to launch MainActivity from overlay: " + t.getMessage());
        }
    }

    private void renderStatusIfChanged(VmStatus st, boolean force) {
        mRootView.setTag(st);

        int dotColor;
        int strokeColor;
        int textColor;
        String text;

        if (st.state == VmStatus.State.RUNNING) {
            dotColor = 0xFF4CD997;
            strokeColor = 0x554CD997;
            textColor = 0xFFE6F4EC;
            String net;
            if (!st.vmNetConnected) {
                net = "Boot";
            } else if ("Host VPN".equals(st.netMode)) {
                net = "VPN";
            } else if ("Host Net".equals(st.netMode)) {
                net = "Net";
            } else {
                net = "Off";
            }
            text = "VM · " + net + (st.keepAwake ? " · WL" : "");
        } else if (st.state.isBusy()) {
            dotColor = 0xFFF2B84B;
            strokeColor = 0x55F2B84B;
            textColor = 0xFFFCE8BD;
            if (st.state == VmStatus.State.STARTING) text = "VM · Start";
            else if (st.state == VmStatus.State.STOPPING) text = "VM · Stop";
            else if (st.state == VmStatus.State.RESETTING) text = "VM · Reset";
            else text = "VM · Import";
        } else if (st.state == VmStatus.State.ERROR) {
            dotColor = 0xFFFF6E6E;
            strokeColor = 0x66FF6E6E;
            textColor = 0xFFFFDAD6;
            text = "VM · Error";
        } else {
            dotColor = 0xFF7D8A99;
            strokeColor = 0x447D8A99;
            textColor = 0xFFC5CFD9;
            text = "VM · Idle";
        }

        String sig = st.state.name() + "|" + text + "|" + mCompact;
        if (!force && sig.equals(mLastRenderedSignature)) {
            return;
        }
        mLastRenderedSignature = sig;

        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setShape(GradientDrawable.OVAL);
        dotBg.setColor(dotColor);
        mDotView.setBackground(dotBg);

        GradientDrawable pillBg = new GradientDrawable();
        pillBg.setColor(0xDC11171F);
        pillBg.setCornerRadius(dp(16));
        pillBg.setStroke(dp(1), strokeColor);
        mRootView.setBackground(pillBg);

        if (mCompact) {
            mLabelView.setVisibility(View.GONE);
            int p = dp(7.5f);
            mRootView.setPadding(p, p, p, p);
        } else {
            mLabelView.setVisibility(View.VISIBLE);
            mLabelView.setText(text);
            mLabelView.setTextColor(textColor);
            mRootView.setPadding(dp(9.5f), dp(4.5f), dp(10.5f), dp(4.5f));
        }

        if (mAttached && mWindowManager != null && mLayoutParams != null) {
            try {
                mWindowManager.updateViewLayout(mRootView, mLayoutParams);
            } catch (Throwable ignored) {}
        }
    }
}
