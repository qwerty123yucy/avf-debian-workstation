package com.avf.linux;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.OpenableColumns;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.BaseInputConnection;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;

public class MainActivity extends Activity {
    private static final String PERM_MANAGE_VM = "android.permission.MANAGE_VIRTUAL_MACHINE";
    private static final String PERM_CUSTOM_VM = "android.permission.USE_CUSTOM_VIRTUAL_MACHINE";
    private static final String STATE_SHOWING_CONSOLE = "showing_console";
    private static final int REQ_PICK_IMAGE = 1001;

    private static final class Palette {
        final boolean dark;
        final int surface;
        final int surfaceContainer;
        final int surfaceContainerHigh;
        final int outlineVariant;
        final int onSurface;
        final int onSurfaceVariant;
        final int primary;
        final int onPrimary;
        final int primaryContainer;
        final int onPrimaryContainer;
        final int errorContainer;
        final int onErrorContainer;
        final int warnContainer;
        final int onWarnContainer;
        final int okContainer;
        final int onOkContainer;
        final int okDot;
        final int warnDot;
        final int errDot;
        final int idleDot;
        final int ripple;
        final int consoleBg;
        final int consoleText;
        final int keyBg;
        final int keyBorder;

        Palette(boolean isDark) {
            dark = isDark;
            if (isDark) {
                surface = 0xFF101418;
                surfaceContainer = 0xFF171D24;
                surfaceContainerHigh = 0xFF212933;
                outlineVariant = 0xFF2B3542;
                onSurface = 0xFFE6ECF2;
                onSurfaceVariant = 0xFF98A4B3;
                primary = 0xFF7CC8F8;
                onPrimary = 0xFF062E47;
                primaryContainer = 0xFF173A54;
                onPrimaryContainer = 0xFFCBE7FF;
                errorContainer = 0xFF492226;
                onErrorContainer = 0xFFFFB4AB;
                warnContainer = 0xFF3E3016;
                onWarnContainer = 0xFFF5D08A;
                okContainer = 0xFF16382A;
                onOkContainer = 0xFF8BE2BA;
                okDot = 0xFF4CD997;
                warnDot = 0xFFF2B84B;
                errDot = 0xFFFF6E6E;
                idleDot = 0xFF7D8A99;
                ripple = 0x26FFFFFF;
                consoleBg = 0xFF0B0F14;
                consoleText = 0xFFD5DFE9;
                keyBg = 0xFF1B232D;
                keyBorder = 0xFF2D3846;
            } else {
                surface = 0xFFF7F9FC;
                surfaceContainer = 0xFFFFFFFF;
                surfaceContainerHigh = 0xFFE8EEF6;
                outlineVariant = 0xFFD9E1EC;
                onSurface = 0xFF161C24;
                onSurfaceVariant = 0xFF5D6876;
                primary = 0xFF175CA8;
                onPrimary = 0xFFFFFFFF;
                primaryContainer = 0xFFDCEBFF;
                onPrimaryContainer = 0xFF0D3A6E;
                errorContainer = 0xFFFCE2E1;
                onErrorContainer = 0xFF9E2623;
                warnContainer = 0xFFFFF1D6;
                onWarnContainer = 0xFF7A510A;
                okContainer = 0xFFDDF5E9;
                onOkContainer = 0xFF14633D;
                okDot = 0xFF1C9E5F;
                warnDot = 0xFFD08700;
                errDot = 0xFFD63834;
                idleDot = 0xFF8C97A6;
                ripple = 0x1A000000;
                consoleBg = 0xFF12171F;
                consoleText = 0xFFDCE4EE;
                keyBg = 0xFFFFFFFF;
                keyBorder = 0xFFD2DBE6;
            }
        }
    }

    private final class TerminalInputView extends View {
        private String mComposing = "";

        TerminalInputView(Context ctx) {
            super(ctx);
            setFocusable(true);
            setFocusableInTouchMode(true);
        }

        void resetComposing() {
            mComposing = "";
        }

        private void applyComposingDelta(String next, boolean finish) {
            if (next == null) next = "";
            next = next.replace("\r\n", "\r").replace('\n', '\r');
            if (mCtrlArmed || mAltArmed || mShiftArmed) {
                String delta = next.startsWith(mComposing) ? next.substring(mComposing.length()) : next;
                mComposing = "";
                if (!delta.isEmpty()) sendTypedText(delta);
                return;
            }
            int common = 0;
            int minLen = Math.min(mComposing.length(), next.length());
            while (common < minLen && mComposing.charAt(common) == next.charAt(common)) {
                common++;
            }
            int backspaces = mComposing.length() - common;
            String added = next.substring(common);
            if (backspaces > 0 || !added.isEmpty()) {
                ByteArrayOutputStream bos = new ByteArrayOutputStream(backspaces + added.length() * 3);
                for (int i = 0; i < backspaces; i++) bos.write(0x7f);
                byte[] addBytes = added.getBytes(StandardCharsets.UTF_8);
                bos.write(addBytes, 0, addBytes.length);
                sendConsoleInput(bos.toByteArray());
                scrollConsoleToBottom();
            }
            mComposing = finish ? "" : next;
        }

        @Override
        public boolean onCheckIsTextEditor() {
            return true;
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            outAttrs.inputType = InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
            outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_FULLSCREEN | EditorInfo.IME_ACTION_NONE;
            mComposing = "";
            return new BaseInputConnection(this, false) {
                @Override
                public boolean commitText(CharSequence text, int newCursorPosition) {
                    applyComposingDelta(text != null ? text.toString() : "", true);
                    return true;
                }

                @Override
                public boolean setComposingText(CharSequence text, int newCursorPosition) {
                    applyComposingDelta(text != null ? text.toString() : "", false);
                    return true;
                }

                @Override
                public boolean finishComposingText() {
                    mComposing = "";
                    return true;
                }

                @Override
                public boolean deleteSurroundingText(int beforeLength, int afterLength) {
                    int count = Math.max(1, beforeLength);
                    if (!mComposing.isEmpty()) {
                        int keep = Math.max(0, mComposing.length() - count);
                        mComposing = mComposing.substring(0, keep);
                    }
                    byte[] del = new byte[count];
                    for (int i = 0; i < count; i++) del[i] = 0x7f;
                    sendConsoleInput(del);
                    scrollConsoleToBottom();
                    clearStickyModifiers();
                    return true;
                }

                @Override
                public boolean sendKeyEvent(KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        mComposing = "";
                        return handleKeyDown(event);
                    }
                    return true;
                }
            };
        }

        @Override
        public boolean onKeyDown(int keyCode, KeyEvent event) {
            mComposing = "";
            if (handleKeyDown(event)) return true;
            return super.onKeyDown(keyCode, event);
        }
    }

    private VmConfigStore mStore;
    private VmStatus mFallbackStatus;
    private VmService mService;
    private boolean mBound = false;
    private boolean mResumed = false;
    private String mPendingAutoAction;
    private boolean mPendingAutoBackground = false;
    private Palette mPal;

    private View mStatusDot;
    private TextView mStatusLabel;
    private LinearLayout mStatusChip;

    private TextView mTabOverview;
    private TextView mTabConsole;
    private LinearLayout mOverviewPage;
    private LinearLayout mConsolePage;
    private boolean mShowingConsole = false;

    private TextView mPermBanner;
    private TextView mValCpu;
    private TextView mValMem;
    private TextView mValDisk;
    private TextView mValNet;
    private TextView mEndpointLine;
    private TextView mErrorLine;

    private TextView mBtnPrimary;
    private TextView mBtnRestart;
    private TextView mBtnImport;
    private TextView mBtnReset;
    private TextView mBtnWakeLock;
    private AlertDialog mConfirmDialog;

    private TextView mConsoleMeta;
    private TextView mBtnJumpBottom;
    private TextView mBtnKeyboard;
    private ConsoleView mConsoleView;
    private TerminalInputView mTermInput;

    private boolean mCtrlArmed = false;
    private boolean mAltArmed = false;
    private boolean mShiftArmed = false;
    private TextView mKeyCtrl;
    private TextView mKeyAlt;
    private TextView mKeyShift;
    private boolean mKeyboardVisible = false;

    private static final long CONSOLE_FLUSH_MIN_MS = 33L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean mFlushScheduled = new AtomicBoolean(false);
    private final VmController.Listener mVmListener = this::scheduleUiFlush;
    private volatile long mLastFlushUptimeMs = 0L;
    private long mLastStateSeq = -1L;
    private long mLastConsoleSeq = -1L;
    private Boolean mLastBatteryUnrestricted = null;
    private boolean mBatteryPromptShown = false;

    private final ServiceConnection mServiceConn = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            if (!(binder instanceof VmService.LocalBinder)) return;
            mService = ((VmService.LocalBinder) binder).getService();
            if (mResumed) {
                mService.getController().addListener(mVmListener);
                healServiceForegroundIfActive();
            }
            runPendingAutoActionIfReady();
            mLastStateSeq = -1L;
            mLastConsoleSeq = -1L;
            refreshUi();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            mService = null;
            mLastStateSeq = -1L;
            refreshUi();
        }
    };

    private final Runnable mUiFlushRunnable = new Runnable() {
        @Override
        public void run() {
            mFlushScheduled.set(false);
            mLastFlushUptimeMs = SystemClock.uptimeMillis();
            refreshUi();
        }
    };

    private void scheduleUiFlush() {
        if (mFlushScheduled.compareAndSet(false, true)) {
            long elapsed = SystemClock.uptimeMillis() - mLastFlushUptimeMs;
            long delay = (elapsed >= CONSOLE_FLUSH_MIN_MS) ? 0L : (CONSOLE_FLUSH_MIN_MS - elapsed);
            if (delay == 0L) {
                mHandler.post(mUiFlushRunnable);
            } else {
                mHandler.postDelayed(mUiFlushRunnable, delay);
            }
        }
    }

    private VmStatus currentStatus() {
        if (mService != null) {
            return mService.getStatus();
        }
        return mFallbackStatus;
    }

    private ConsoleBuffer currentConsole() {
        if (mService != null) {
            return mService.getController().getConsole();
        }
        return null;
    }

    private void sendConsoleInput(byte[] data) {
        if (mService != null) {
            mService.sendConsoleInput(data);
        }
    }

    private void scrollConsoleToBottom() {
        if (mConsoleView != null) {
            mConsoleView.scrollToBottom();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mStore = new VmConfigStore(this);
        mFallbackStatus = VmStatus.fromStore(0L, mStore);
        boolean isDark = (getResources().getConfiguration().uiMode
            & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        mPal = new Palette(isDark);
        configureSystemBars();
        buildUi();
        if (savedInstanceState != null && savedInstanceState.getBoolean(STATE_SHOWING_CONSOLE, false)) {
            switchTab(true);
        }
        captureAutoIntent(getIntent());
    }

    @Override
    protected void onStart() {
        super.onStart();
        Intent svc = new Intent(this, VmService.class);
        try {
            startService(svc);
        } catch (Throwable ignored) {}
        mBound = bindService(svc, mServiceConn, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putBoolean(STATE_SHOWING_CONSOLE, mShowingConsole);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        captureAutoIntent(intent);
        runPendingAutoActionIfReady();
    }

    private void configureSystemBars() {
        Window w = getWindow();
        w.setStatusBarColor(mPal.surface);
        w.setNavigationBarColor(mPal.surface);
        if (!mPal.dark) {
            View decor = w.getDecorView();
            decor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
    }

    private void captureAutoIntent(Intent intent) {
        if (intent == null) return;
        String action = intent.getStringExtra("action");
        String tok = intent.getStringExtra("token");
        if (action != null && mStore.getOrCreateToken().equals(tok)) {
            mPendingAutoAction = action;
            mPendingAutoBackground = intent.getBooleanExtra("background", false);
            intent.removeExtra("action");
        }
    }

    private void runPendingAutoActionIfReady() {
        if (mPendingAutoAction == null) return;
        String action = mPendingAutoAction;
        boolean goBack = mPendingAutoBackground;
        mPendingAutoAction = null;
        mPendingAutoBackground = false;

        if ("start".equals(action)) sendVmAction(VmService.ACTION_START);
        else if ("stop".equals(action)) sendVmAction(VmService.ACTION_STOP);
        else if ("restart".equals(action)) sendVmAction(VmService.ACTION_RESTART);
        else if ("import".equals(action)) sendVmAction(VmService.ACTION_IMPORT);
        else if ("reset".equals(action)) sendVmAction(VmService.ACTION_RESET);
        else if ("wakelock_on".equals(action)) sendWakeLockSet(true);
        else if ("wakelock_off".equals(action)) sendWakeLockSet(false);
        else if ("wakelock_toggle".equals(action)) sendVmAction(VmService.ACTION_TOGGLE_WAKELOCK);

        if (goBack) {
            moveTaskToBack(true);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        mLastStateSeq = -1L;
        mLastConsoleSeq = -1L;
        if (mService != null) {
            mService.getController().addListener(mVmListener);
            healServiceForegroundIfActive();
            runPendingAutoActionIfReady();
        }
        refreshUi();
    }

    @Override
    protected void onPause() {
        mResumed = false;
        if (mService != null) {
            mService.getController().removeListener(mVmListener);
        }
        mHandler.removeCallbacks(mUiFlushRunnable);
        mFlushScheduled.set(false);
        super.onPause();
    }

    @Override
    protected void onStop() {
        if (mService != null) {
            mService.getController().removeListener(mVmListener);
        }
        if (mBound) {
            try {
                unbindService(mServiceConn);
            } catch (Throwable ignored) {}
            mBound = false;
        }
        mService = null;
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (mConfirmDialog != null && mConfirmDialog.isShowing()) {
            mConfirmDialog.dismiss();
        }
        mConfirmDialog = null;
        super.onDestroy();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private GradientDrawable roundRect(int fill, int stroke, int radiusDp) {
        GradientDrawable gd = new GradientDrawable();
        gd.setColor(fill);
        gd.setCornerRadius(dp(radiusDp));
        if (stroke != 0) {
            gd.setStroke(dp(1), stroke);
        }
        return gd;
    }

    private RippleDrawable pillButtonBg(int fill, int stroke, int radiusDp) {
        GradientDrawable content = roundRect(fill, stroke, radiusDp);
        GradientDrawable mask = roundRect(0xFFFFFFFF, 0, radiusDp);
        return new RippleDrawable(ColorStateList.valueOf(mPal.ripple), content, mask);
    }

    private void buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mPal.surface);
        root.setPadding(dp(18), dp(14), dp(18), dp(10));
        root.setFitsSystemWindows(true);

        // 1. Minimal Material 3 Header
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout titleCol = new LinearLayout(this);
        titleCol.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("AVF Linux");
        title.setTextSize(22f);
        title.setTextColor(mPal.onSurface);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        titleCol.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Debian 13 · UEFI Self-Boot · Dual-Stack");
        subtitle.setTextSize(12f);
        subtitle.setTextColor(mPal.onSurfaceVariant);
        subtitle.setPadding(0, dp(1), 0, 0);
        titleCol.addView(subtitle);

        header.addView(titleCol, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mStatusChip = new LinearLayout(this);
        mStatusChip.setOrientation(LinearLayout.HORIZONTAL);
        mStatusChip.setGravity(Gravity.CENTER_VERTICAL);
        mStatusChip.setPadding(dp(12), dp(6), dp(14), dp(6));

        mStatusDot = new View(this);
        LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(7);
        mStatusChip.addView(mStatusDot, dotLp);

        mStatusLabel = new TextView(this);
        mStatusLabel.setTextSize(12.5f);
        mStatusLabel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mStatusChip.addView(mStatusLabel);

        header.addView(mStatusChip);
        root.addView(header);

        // 2. Material 3 Segmented Pill Switcher (Overview | Console)
        LinearLayout segTrack = new LinearLayout(this);
        segTrack.setOrientation(LinearLayout.HORIZONTAL);
        segTrack.setPadding(dp(4), dp(4), dp(4), dp(4));
        segTrack.setBackground(roundRect(mPal.surfaceContainerHigh, 0, 20));
        LinearLayout.LayoutParams segLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(42));
        segLp.topMargin = dp(14);

        mTabOverview = makeSegmentTab("Overview", v -> switchTab(false));
        mTabConsole = makeSegmentTab("Console", v -> switchTab(true));
        segTrack.addView(mTabOverview, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        segTrack.addView(mTabConsole, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        root.addView(segTrack, segLp);

        // 3. Overview Page
        mOverviewPage = new LinearLayout(this);
        mOverviewPage.setOrientation(LinearLayout.VERTICAL);

        mPermBanner = new TextView(this);
        mPermBanner.setTextSize(12f);
        mPermBanner.setTypeface(Typeface.MONOSPACE);
        mPermBanner.setPadding(dp(14), dp(10), dp(14), dp(10));
        mPermBanner.setTextColor(mPal.onWarnContainer);
        mPermBanner.setBackground(roundRect(mPal.warnContainer, 0, 14));
        mPermBanner.setLineSpacing(dp(2), 1f);
        mPermBanner.setVisibility(View.GONE);
        LinearLayout.LayoutParams bannerLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        bannerLp.topMargin = dp(12);
        mOverviewPage.addView(mPermBanner, bannerLp);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(16), dp(18), dp(16));
        card.setBackground(roundRect(mPal.surfaceContainer, mPal.outlineVariant, 20));
        LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardLp.topMargin = dp(14);

        LinearLayout gridRow1 = new LinearLayout(this);
        gridRow1.setOrientation(LinearLayout.HORIZONTAL);
        mValCpu = addMetricCell(gridRow1, "CPU");
        mValMem = addMetricCell(gridRow1, "MEMORY");
        card.addView(gridRow1);

        LinearLayout gridRow2 = new LinearLayout(this);
        gridRow2.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams gr2Lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        gr2Lp.topMargin = dp(14);
        mValDisk = addMetricCell(gridRow2, "STORAGE");
        mValNet = addMetricCell(gridRow2, "NETWORK");
        card.addView(gridRow2, gr2Lp);

        View divider = new View(this);
        divider.setBackgroundColor(mPal.outlineVariant);
        LinearLayout.LayoutParams divLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        divLp.topMargin = dp(14);
        divLp.bottomMargin = dp(12);
        card.addView(divider, divLp);

        mEndpointLine = new TextView(this);
        mEndpointLine.setTextSize(12f);
        mEndpointLine.setTypeface(loadMonoFont());
        mEndpointLine.setTextColor(mPal.onSurfaceVariant);
        mEndpointLine.setSingleLine(true);
        mEndpointLine.setEllipsize(TextUtils.TruncateAt.END);
        card.addView(mEndpointLine);

        mErrorLine = new TextView(this);
        mErrorLine.setTextSize(12f);
        mErrorLine.setTextColor(mPal.onErrorContainer);
        mErrorLine.setBackground(roundRect(mPal.errorContainer, 0, 10));
        mErrorLine.setPadding(dp(10), dp(8), dp(10), dp(8));
        mErrorLine.setVisibility(View.GONE);
        LinearLayout.LayoutParams errLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        errLp.topMargin = dp(10);
        card.addView(mErrorLine, errLp);

        mOverviewPage.addView(card, cardLp);

        // Primary Controls Row
        LinearLayout rowPrimary = new LinearLayout(this);
        rowPrimary.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row1Lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row1Lp.topMargin = dp(16);

        mBtnPrimary = makePillButton("Start VM", 48, v -> {
            boolean running = (currentStatus().state == VmStatus.State.RUNNING);
            if (!running) {
                promptBatteryExemptionOnceIfNeeded();
            }
            sendVmAction(running ? VmService.ACTION_STOP : VmService.ACTION_START);
        });
        LinearLayout.LayoutParams pLp = new LinearLayout.LayoutParams(0, dp(48), 1.35f);
        pLp.rightMargin = dp(10);
        rowPrimary.addView(mBtnPrimary, pLp);

        mBtnRestart = makePillButton("Restart", 48, v -> sendVmAction(VmService.ACTION_RESTART));
        rowPrimary.addView(mBtnRestart, new LinearLayout.LayoutParams(0, dp(48), 1f));
        mOverviewPage.addView(rowPrimary, row1Lp);

        // Utility Chips Row (Copy SSH | Copy Token | Reset)
        LinearLayout rowUtil = new LinearLayout(this);
        rowUtil.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row2Lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row2Lp.topMargin = dp(10);

        TextView btnCopySsh = makePillButton("Copy SSH", 40, v -> copyText("ssh avf", "Copied: ssh avf"));
        TextView btnCopyTok = makePillButton("Copy Token", 40, v -> copyText(mStore.getOrCreateToken(), "Control token copied"));
        mBtnReset = makePillButton("Reset VM", 40, v -> confirmResetVm());

        styleOutlinedChip(btnCopySsh);
        styleOutlinedChip(btnCopyTok);
        styleOutlinedChip(mBtnReset);

        rowUtil.addView(btnCopySsh, utilLp(0, 8, 1f));
        rowUtil.addView(btnCopyTok, utilLp(0, 8, 1f));
        rowUtil.addView(mBtnReset, utilLp(0, 0, 1f));
        mOverviewPage.addView(rowUtil, row2Lp);

        // Power & Image Management Row (WakeLock | Import | Settings)
        LinearLayout rowPower = new LinearLayout(this);
        rowPower.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams row3Lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        row3Lp.topMargin = dp(10);

        mBtnWakeLock = makePillButton("WakeLock: Off", 40, v -> toggleWakeLockPref());
        mBtnImport = makePillButton("Import", 40, v -> confirmImportVm());
        TextView btnAppInfo = makePillButton("Settings", 40, v -> openAppSettings());
        styleOutlinedChip(mBtnWakeLock);
        styleOutlinedChip(mBtnImport);
        styleOutlinedChip(btnAppInfo);
        btnAppInfo.setTextColor(mPal.onSurfaceVariant);

        rowPower.addView(mBtnWakeLock, utilLp(0, 8, 1.25f));
        rowPower.addView(mBtnImport, utilLp(0, 8, 0.85f));
        rowPower.addView(btnAppInfo, utilLp(0, 0, 0.9f));
        mOverviewPage.addView(rowPower, row3Lp);

        root.addView(mOverviewPage, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 4. Dedicated Full-Height Console Page
        mConsolePage = new LinearLayout(this);
        mConsolePage.setOrientation(LinearLayout.VERTICAL);
        mConsolePage.setVisibility(View.GONE);

        LinearLayout conHdr = new LinearLayout(this);
        conHdr.setOrientation(LinearLayout.HORIZONTAL);
        conHdr.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams chLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chLp.topMargin = dp(10);
        chLp.bottomMargin = dp(6);

        mConsoleMeta = new TextView(this);
        mConsoleMeta.setTextSize(12f);
        mConsoleMeta.setTextColor(mPal.onSurfaceVariant);
        mConsoleMeta.setSingleLine(true);
        mConsoleMeta.setEllipsize(TextUtils.TruncateAt.END);
        conHdr.addView(mConsoleMeta, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        mBtnJumpBottom = new TextView(this);
        mBtnJumpBottom.setText("↓ Live");
        mBtnJumpBottom.setTextSize(11.5f);
        mBtnJumpBottom.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        mBtnJumpBottom.setTextColor(mPal.onPrimaryContainer);
        mBtnJumpBottom.setBackground(pillButtonBg(mPal.primaryContainer, 0, 12));
        mBtnJumpBottom.setPadding(dp(10), dp(4), dp(10), dp(4));
        mBtnJumpBottom.setVisibility(View.GONE);
        mBtnJumpBottom.setOnClickListener(v -> scrollConsoleToBottom());
        LinearLayout.LayoutParams jbLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        jbLp.rightMargin = dp(4);
        conHdr.addView(mBtnJumpBottom, jbLp);

        mBtnKeyboard = makeHeaderAction("Keyboard", v -> toggleKeyboard());
        TextView clearBtn = makeHeaderAction("Clear", v -> {
            if (mService != null) {
                mService.getController().clearConsole();
            }
            mLastConsoleSeq = -1L;
            refreshUi();
        });
        conHdr.addView(mBtnKeyboard);
        conHdr.addView(clearBtn);
        mConsolePage.addView(conHdr, chLp);

        mConsoleView = new ConsoleView(this);
        mConsoleView.setBackground(roundRect(mPal.consoleBg, mPal.outlineVariant, 16));
        mConsoleView.setPadding(dp(12), dp(10), dp(12), dp(10));
        int cursorColor = (mPal.primary & 0x00FFFFFF) | 0x55000000;
        mConsoleView.configureStyle(loadMonoFont(), mPal.consoleText, mPal.onSurfaceVariant, cursorColor);
        mConsoleView.setOnTapListener(this::showKeyboard);
        mConsoleView.setOnLongPressListener(() -> {
            ConsoleBuffer con = currentConsole();
            String snap = (con != null) ? con.getTextSnapshot() : "";
            if (!snap.isEmpty()) {
                copyText(snap, "Copied console output");
            }
        });
        mConsoleView.setOnFollowChangedListener(following -> updateConsoleHeader());

        mConsolePage.addView(mConsoleView, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        mConsolePage.addView(buildExtraKeysBar());

        root.addView(mConsolePage, new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // 1px hidden focusable IME receiver
        mTermInput = new TerminalInputView(this);
        mTermInput.setBackgroundColor(Color.TRANSPARENT);
        root.addView(mTermInput, new LinearLayout.LayoutParams(1, 1));

        root.getViewTreeObserver().addOnGlobalLayoutListener(() -> {
            Rect r = new Rect();
            root.getWindowVisibleDisplayFrame(r);
            int screenH = root.getRootView().getHeight();
            boolean kbNow = (screenH - r.bottom) > screenH * 0.16;
            if (kbNow != mKeyboardVisible) {
                mKeyboardVisible = kbNow;
                mBtnKeyboard.setText(kbNow ? "Hide KB" : "Keyboard");
                if (kbNow && mShowingConsole && mConsoleView != null && mConsoleView.isFollowingTail()) {
                    mConsoleView.invalidate();
                }
            }
        });

        updateSegmentStyles();
        setContentView(root);
    }

    private TextView makeSegmentTab(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setClickable(true);
        tv.setOnClickListener(l);
        return tv;
    }

    private void switchTab(boolean toConsole) {
        if (mShowingConsole == toConsole) return;
        mShowingConsole = toConsole;
        if (!toConsole) {
            hideKeyboard();
        }
        mOverviewPage.setVisibility(toConsole ? View.GONE : View.VISIBLE);
        mConsolePage.setVisibility(toConsole ? View.VISIBLE : View.GONE);
        updateSegmentStyles();
        if (toConsole) {
            mLastConsoleSeq = -1L;
            refreshUi();
        }
    }

    private void updateSegmentStyles() {
        int activeBg = mPal.dark ? mPal.primaryContainer : mPal.surfaceContainer;
        int activeFg = mPal.dark ? mPal.onPrimaryContainer : mPal.onSurface;
        mTabOverview.setBackground(!mShowingConsole ? roundRect(activeBg, 0, 16) : null);
        mTabOverview.setTextColor(!mShowingConsole ? activeFg : mPal.onSurfaceVariant);
        mTabConsole.setBackground(mShowingConsole ? roundRect(activeBg, 0, 16) : null);
        mTabConsole.setTextColor(mShowingConsole ? activeFg : mPal.onSurfaceVariant);
    }

    private void confirmResetVm() {
        if (!mStore.hasSeed()) {
            Toast.makeText(this, "Seed image (seed.simg.gz) not found", Toast.LENGTH_SHORT).show();
            return;
        }
        showDestructiveDialog(
            "Reset Debian 13 Disk?",
            "This stops the VM and permanently erases all disk changes (packages, upgraded kernels, and files), restoring the initial Debian 13 seed image.",
            "Reset",
            10,
            () -> sendVmAction(VmService.ACTION_RESET));
    }

    private void confirmImportVm() {
        File staged = mStore.findStagingImage();
        if (staged == null) {
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType("*/*");
            try {
                startActivityForResult(pick, REQ_PICK_IMAGE);
            } catch (Throwable t) {
                Toast.makeText(this, "File picker unavailable; place seed.simg.gz in staging/", Toast.LENGTH_LONG).show();
            }
            return;
        }
        if (!mStore.hasImages()) {
            sendVmAction(VmService.ACTION_IMPORT);
            return;
        }
        showDestructiveDialog(
            "Overwrite Disk via Import?",
            "This stops the VM and replaces the existing Debian 13 disk with staged file '" + staged.getName() + "'.",
            "Import",
            3,
            () -> sendVmAction(VmService.ACTION_IMPORT));
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            String displayName = queryDisplayName(uri);
            if (!mStore.hasImages()) {
                sendImportUri(uri);
                return;
            }
            showDestructiveDialog(
                "Overwrite Disk via Import?",
                "This stops the VM and replaces the existing Debian 13 disk with '" + displayName + "'.",
                "Import",
                3,
                () -> sendImportUri(uri));
        }
    }

    private String queryDisplayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                String name = c.getString(0);
                if (name != null && !name.isEmpty()) return name;
            }
        } catch (Throwable ignored) {}
        String seg = uri.getLastPathSegment();
        return (seg != null && !seg.isEmpty()) ? seg : "selected image";
    }

    private void sendImportUri(Uri uri) {
        // Always dispatch via Intent with FLAG_GRANT_READ_URI_PERMISSION so Android's
        // UriGrantsManager binds the temporary SAF read grant to VmService's ServiceRecord,
        // surviving even if the user closes MainActivity before disk import finishes.
        Intent i = new Intent(this, VmService.class);
        i.setAction(VmService.ACTION_IMPORT);
        i.putExtra(VmService.EXTRA_TOKEN, mStore.getOrCreateToken());
        i.setData(uri);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startForegroundService(i);
    }

    private void showDestructiveDialog(
            String titleText, String bodyText, final String actionLabel, int countdownSec, final Runnable onConfirm) {
        if (mConfirmDialog != null && mConfirmDialog.isShowing()) {
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(22);
        box.setPadding(pad, dp(20), pad, dp(18));
        box.setBackground(roundRect(mPal.surfaceContainerHigh, mPal.outlineVariant, 24));

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextSize(18f);
        title.setTextColor(mPal.errDot);
        title.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(title);

        TextView msg = new TextView(this);
        msg.setText(bodyText);
        msg.setTextSize(13.5f);
        msg.setTextColor(mPal.onSurface);
        msg.setLineSpacing(dp(3), 1f);
        LinearLayout.LayoutParams msgLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        msgLp.topMargin = dp(10);
        box.addView(msg, msgLp);

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = dp(20);

        final int[] remSec = {countdownSec};
        final Runnable[] tickRef = new Runnable[1];

        TextView btnCancel = makePillButton("Cancel", 44, v -> {
            if (mConfirmDialog != null) mConfirmDialog.dismiss();
        });
        styleOutlinedChip(btnCancel);
        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(0, dp(44), 1f);
        cancelLp.rightMargin = dp(10);
        actions.addView(btnCancel, cancelLp);

        final int vividRed = mPal.dark ? 0xFFD9383A : 0xFFC62828;
        String initLabel = countdownSec > 0 ? (actionLabel + " (" + countdownSec + "s)") : ("Confirm " + actionLabel);
        final TextView btnConfirm = makePillButton(initLabel, 44, v -> {
            if (remSec[0] > 0) return;
            if (mConfirmDialog != null) mConfirmDialog.dismiss();
            onConfirm.run();
        });
        if (countdownSec > 0) {
            btnConfirm.setEnabled(false);
            btnConfirm.setAlpha(0.55f);
            btnConfirm.setTextColor(mPal.onErrorContainer);
            btnConfirm.setBackground(roundRect(mPal.errorContainer, 0, 22));
        } else {
            btnConfirm.setTextColor(0xFFFFFFFF);
            btnConfirm.setBackground(pillButtonBg(vividRed, 0, 22));
        }
        actions.addView(btnConfirm, new LinearLayout.LayoutParams(0, dp(44), 1.3f));

        box.addView(actions, actLp);

        final AlertDialog dialog = new AlertDialog.Builder(this)
            .setView(box)
            .setCancelable(true)
            .create();

        tickRef[0] = new Runnable() {
            @Override
            public void run() {
                remSec[0]--;
                if (remSec[0] > 0) {
                    btnConfirm.setText(actionLabel + " (" + remSec[0] + "s)");
                    mHandler.postDelayed(this, 1000);
                } else {
                    btnConfirm.setText("Confirm " + actionLabel);
                    btnConfirm.setEnabled(true);
                    btnConfirm.setAlpha(1f);
                    btnConfirm.setTextColor(0xFFFFFFFF);
                    btnConfirm.setBackground(pillButtonBg(vividRed, 0, 22));
                }
            }
        };

        dialog.setOnDismissListener(d -> {
            mHandler.removeCallbacks(tickRef[0]);
            if (mConfirmDialog == dialog) {
                mConfirmDialog = null;
            }
        });

        mConfirmDialog = dialog;
        dialog.show();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        if (countdownSec > 0) {
            mHandler.postDelayed(tickRef[0], 1000);
        }
    }

    private void toggleWakeLockPref() {
        boolean next = !currentStatus().keepAwake;
        if (next) {
            promptBatteryExemptionOnceIfNeeded();
        }
        sendWakeLockSet(next);
        Toast.makeText(this,
            next ? "WakeLock ON (CPU + High-Perf Wi-Fi locks held while VM runs)"
                 : "WakeLock OFF (host sleep allowed)",
            Toast.LENGTH_SHORT).show();
    }

    private void sendWakeLockSet(boolean enable) {
        if (mService != null) {
            mService.setKeepAwake(enable);
            return;
        }
        mStore.saveKeepAwake(enable);
        mFallbackStatus = mFallbackStatus.withKeepAwake(mFallbackStatus.seq + 1, enable, false);
        Intent i = new Intent(this, VmService.class);
        i.setAction(VmService.ACTION_SET_WAKELOCK);
        i.putExtra(VmService.EXTRA_TOKEN, mStore.getOrCreateToken());
        i.putExtra(VmService.EXTRA_ENABLE, enable);
        startForegroundService(i);
        refreshUi();
    }

    private boolean hasCoreVmPermissions() {
        return checkSelfPermission(PERM_MANAGE_VM) == PackageManager.PERMISSION_GRANTED
            && checkSelfPermission(PERM_CUSTOM_VM) == PackageManager.PERMISSION_GRANTED;
    }

    private boolean isBatteryOptimizationIgnored() {
        PowerManager pm = getSystemService(PowerManager.class);
        return pm == null || pm.isIgnoringBatteryOptimizations(getPackageName());
    }

    private void requestIgnoreBatteryOptimizations() {
        if (isBatteryOptimizationIgnored()) {
            refreshUi();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        } catch (Throwable ignored) {}
        try {
            startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            return;
        } catch (Throwable ignored) {}
        openAppSettings();
    }

    private void promptBatteryExemptionOnceIfNeeded() {
        if (!mBatteryPromptShown && hasCoreVmPermissions() && !isBatteryOptimizationIgnored()) {
            mBatteryPromptShown = true;
            requestIgnoreBatteryOptimizations();
        }
    }

    private void ensureServiceForeground() {
        try {
            Intent i = new Intent(this, VmService.class);
            i.setAction(VmService.ACTION_ENSURE_FOREGROUND);
            startForegroundService(i);
        } catch (Throwable ignored) {}
    }

    private void healServiceForegroundIfActive() {
        if (mService != null && mService.getStatus().state.isActive()
                && (!mService.isServiceStarted() || !mService.isForegroundActive())) {
            ensureServiceForeground();
        }
    }

    private void openAppSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Unable to open App Settings", Toast.LENGTH_SHORT).show();
        }
    }

    private View buildExtraKeysBar() {
        HorizontalScrollView hsv = new HorizontalScrollView(this);
        hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams hsvLp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        hsvLp.topMargin = dp(8);
        hsv.setLayoutParams(hsvLp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        bar.addView(makeKeyChip("ESC", v -> sendRawAndReset(new byte[]{0x1b})));
        bar.addView(makeKeyChip("TAB", v -> {
            if (mShiftArmed) sendRawAndReset("\u001b[Z".getBytes(StandardCharsets.US_ASCII));
            else sendRawAndReset(new byte[]{0x09});
        }));

        mKeyCtrl = makeKeyChip("CTRL", v -> {
            mCtrlArmed = !mCtrlArmed;
            updateModifierStyles();
        });
        bar.addView(mKeyCtrl);

        mKeyAlt = makeKeyChip("ALT", v -> {
            mAltArmed = !mAltArmed;
            updateModifierStyles();
        });
        bar.addView(mKeyAlt);

        mKeyShift = makeKeyChip("SHIFT", v -> {
            mShiftArmed = !mShiftArmed;
            updateModifierStyles();
        });
        bar.addView(mKeyShift);

        bar.addView(makeKeyChip("\u2191", v -> sendArrow('A')));
        bar.addView(makeKeyChip("\u2193", v -> sendArrow('B')));
        bar.addView(makeKeyChip("\u2190", v -> sendArrow('D')));
        bar.addView(makeKeyChip("\u2192", v -> sendArrow('C')));

        bar.addView(makeKeyChip("/", v -> sendTypedText("/")));
        bar.addView(makeKeyChip("-", v -> sendTypedText("-")));
        bar.addView(makeKeyChip("|", v -> sendTypedText("|")));
        bar.addView(makeKeyChip("~", v -> sendTypedText("~")));
        bar.addView(makeKeyChip("HOME", v -> sendRawAndReset("\u001b[H".getBytes(StandardCharsets.US_ASCII))));
        bar.addView(makeKeyChip("END", v -> sendRawAndReset("\u001b[F".getBytes(StandardCharsets.US_ASCII))));
        bar.addView(makeKeyChip("PGUP", v -> sendRawAndReset("\u001b[5~".getBytes(StandardCharsets.US_ASCII))));
        bar.addView(makeKeyChip("PGDN", v -> sendRawAndReset("\u001b[6~".getBytes(StandardCharsets.US_ASCII))));

        hsv.addView(bar);
        updateModifierStyles();
        return hsv;
    }

    private static Typeface loadMonoFont() {
        try {
            File f = new File("/system/fonts/DroidSansMono.ttf");
            if (f.isFile()) return Typeface.createFromFile(f);
        } catch (Throwable ignored) {}
        return Typeface.MONOSPACE;
    }

    private TextView makeKeyChip(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(11.5f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setMinWidth(dp(34));
        tv.setPadding(dp(8), 0, dp(8), 0);
        tv.setTextColor(mPal.onSurface);
        tv.setBackground(pillButtonBg(mPal.keyBg, mPal.keyBorder, 10));
        tv.setClickable(true);
        tv.setFocusable(false);
        tv.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, dp(34));
        lp.rightMargin = dp(5);
        tv.setLayoutParams(lp);
        return tv;
    }

    private void updateModifierStyles() {
        styleModifierChip(mKeyCtrl, mCtrlArmed);
        styleModifierChip(mKeyAlt, mAltArmed);
        styleModifierChip(mKeyShift, mShiftArmed);
    }

    private void styleModifierChip(TextView tv, boolean active) {
        if (tv == null) return;
        if (active) {
            tv.setTextColor(mPal.onPrimary);
            tv.setBackground(pillButtonBg(mPal.primary, 0, 10));
        } else {
            tv.setTextColor(mPal.onSurface);
            tv.setBackground(pillButtonBg(mPal.keyBg, mPal.keyBorder, 10));
        }
    }

    private void clearStickyModifiers() {
        if (mCtrlArmed || mAltArmed || mShiftArmed) {
            mCtrlArmed = false;
            mAltArmed = false;
            mShiftArmed = false;
            runOnUiThread(this::updateModifierStyles);
        }
    }

    private void sendRawAndReset(byte[] bytes) {
        if (mTermInput != null) mTermInput.resetComposing();
        sendConsoleInput(bytes);
        scrollConsoleToBottom();
        clearStickyModifiers();
    }

    private void sendArrow(char dir) {
        String seq;
        if (mCtrlArmed) seq = "\u001b[1;5" + dir;
        else if (mAltArmed) seq = "\u001b[1;3" + dir;
        else if (mShiftArmed) seq = "\u001b[1;2" + dir;
        else seq = "\u001b[" + dir;
        sendRawAndReset(seq.getBytes(StandardCharsets.US_ASCII));
    }

    private void sendTypedText(String text) {
        if (text == null || text.isEmpty()) return;
        boolean modActive = mCtrlArmed || mAltArmed || mShiftArmed;
        if (!modActive) {
            sendConsoleInput(text.getBytes(StandardCharsets.UTF_8));
            scrollConsoleToBottom();
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (i == 0) {
                if (mShiftArmed) ch = Character.toUpperCase(ch);
                if (mAltArmed) sb.append('\u001b');
                if (mCtrlArmed) {
                    if (ch >= 'a' && ch <= 'z') ch = (char) (ch - 'a' + 1);
                    else if (ch >= 'A' && ch <= 'Z') ch = (char) (ch - 'A' + 1);
                    else if (ch == '[') ch = 0x1b;
                    else if (ch == '\\') ch = 0x1c;
                    else if (ch == ']') ch = 0x1d;
                    else if (ch == ' ') ch = 0x00;
                }
            }
            sb.append(ch);
        }
        sendConsoleInput(sb.toString().getBytes(StandardCharsets.UTF_8));
        scrollConsoleToBottom();
        clearStickyModifiers();
    }

    private boolean handleKeyDown(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER) {
            sendRawAndReset(new byte[]{'\r'});
            return true;
        }
        if (code == KeyEvent.KEYCODE_DEL) {
            sendRawAndReset(new byte[]{0x7f});
            return true;
        }
        if (code == KeyEvent.KEYCODE_TAB) {
            if (mShiftArmed || event.isShiftPressed()) {
                sendRawAndReset("\u001b[Z".getBytes(StandardCharsets.US_ASCII));
            } else {
                sendRawAndReset(new byte[]{'\t'});
            }
            return true;
        }
        if (code == KeyEvent.KEYCODE_ESCAPE) {
            sendRawAndReset(new byte[]{0x1b});
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_UP) { sendArrow('A'); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_DOWN) { sendArrow('B'); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { sendArrow('C'); return true; }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT) { sendArrow('D'); return true; }

        int uni = event.getUnicodeChar();
        if (uni != 0) {
            if (event.isCtrlPressed()) mCtrlArmed = true;
            if (event.isAltPressed()) mAltArmed = true;
            sendTypedText(new String(Character.toChars(uni)));
            return true;
        }
        return false;
    }

    private void showKeyboard() {
        mTermInput.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.showSoftInput(mTermInput, InputMethodManager.SHOW_IMPLICIT);
        }
    }

    private void hideKeyboard() {
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) {
            imm.hideSoftInputFromWindow(mTermInput.getWindowToken(), 0);
        }
    }

    private void toggleKeyboard() {
        if (mKeyboardVisible) hideKeyboard();
        else showKeyboard();
    }

    private TextView makeHeaderAction(String label, View.OnClickListener l) {
        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(12.5f);
        tv.setTextColor(mPal.primary);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setPadding(dp(12), dp(4), dp(4), dp(4));
        tv.setOnClickListener(l);
        return tv;
    }

    private TextView addMetricCell(LinearLayout parent, String label) {
        LinearLayout cell = new LinearLayout(this);
        cell.setOrientation(LinearLayout.VERTICAL);

        TextView lbl = new TextView(this);
        lbl.setText(label);
        lbl.setTextSize(10.5f);
        lbl.setLetterSpacing(0.08f);
        lbl.setTextColor(mPal.onSurfaceVariant);
        lbl.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cell.addView(lbl);

        TextView val = new TextView(this);
        val.setTextSize(14.5f);
        val.setTextColor(mPal.onSurface);
        val.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        val.setPadding(0, dp(3), 0, 0);
        val.setSingleLine(true);
        val.setEllipsize(TextUtils.TruncateAt.END);
        cell.addView(val);

        parent.addView(cell, new LinearLayout.LayoutParams(
            0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return val;
    }

    private TextView makePillButton(String label, int heightDp, View.OnClickListener l) {
        TextView btn = new TextView(this);
        btn.setText(label);
        btn.setTextSize(heightDp >= 44 ? 14.5f : 12.0f);
        btn.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        btn.setGravity(Gravity.CENTER);
        btn.setSingleLine(true);
        btn.setClickable(true);
        btn.setFocusable(true);
        btn.setOnClickListener(l);
        return btn;
    }

    private void styleOutlinedChip(TextView tv) {
        tv.setTextColor(mPal.onSurface);
        tv.setBackground(pillButtonBg(mPal.surfaceContainer, mPal.outlineVariant, 20));
    }

    private LinearLayout.LayoutParams utilLp(int leftDp, int rightDp, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(40), weight);
        lp.leftMargin = dp(leftDp);
        lp.rightMargin = dp(rightDp);
        return lp;
    }

    private void sendVmAction(String action) {
        if (mService != null) {
            if (VmService.ACTION_START.equals(action)
                    || VmService.ACTION_RESTART.equals(action)
                    || VmService.ACTION_IMPORT.equals(action)
                    || VmService.ACTION_RESET.equals(action)) {
                ensureServiceForeground();
            }
            if (VmService.ACTION_START.equals(action)) mService.requestStart();
            else if (VmService.ACTION_STOP.equals(action)) mService.requestStop();
            else if (VmService.ACTION_RESTART.equals(action)) mService.requestRestart();
            else if (VmService.ACTION_IMPORT.equals(action)) mService.requestImportStaging();
            else if (VmService.ACTION_RESET.equals(action)) mService.requestReset();
            else if (VmService.ACTION_TOGGLE_WAKELOCK.equals(action)) mService.toggleKeepAwake();
            return;
        }
        Intent i = new Intent(this, VmService.class);
        i.setAction(action);
        i.putExtra(VmService.EXTRA_TOKEN, mStore.getOrCreateToken());
        startForegroundService(i);
    }

    private void copyText(String text, String msg) {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        if (cm != null) {
            cm.setPrimaryClip(ClipData.newPlainText("avf-linux", text));
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        }
    }

    private void refreshUi() {
        final VmStatus status = currentStatus();
        long stateSeq = status.seq;
        boolean batteryUnrestricted = isBatteryOptimizationIgnored();
        boolean stateChanged = (stateSeq != mLastStateSeq)
            || (mLastBatteryUnrestricted == null || mLastBatteryUnrestricted.booleanValue() != batteryUnrestricted);
        if (stateChanged) {
            mLastStateSeq = stateSeq;
            mLastBatteryUnrestricted = Boolean.valueOf(batteryUnrestricted);
            boolean hasManage = checkSelfPermission(PERM_MANAGE_VM) == PackageManager.PERMISSION_GRANTED;
            boolean hasCustom = checkSelfPermission(PERM_CUSTOM_VM) == PackageManager.PERMISSION_GRANTED;
            VmStatus.State st = status.state;
            long actualMb = mStore.getActualDiskBytes() / (1024L * 1024L);
            boolean imgs = mStore.hasImages();
            boolean hasSeed = mStore.hasSeed();

            if (!hasManage || !hasCustom) {
                mPermBanner.setVisibility(View.VISIBLE);
                mPermBanner.setTypeface(loadMonoFont());
                mPermBanner.setBackground(roundRect(mPal.warnContainer, 0, 14));
                mPermBanner.setOnClickListener(null);
                mPermBanner.setClickable(false);
                mPermBanner.setText("One-time ADB / wireless debugging grant required:\n"
                    + "adb shell pm grant com.avf.linux " + PERM_MANAGE_VM + "\n"
                    + "adb shell pm grant com.avf.linux " + PERM_CUSTOM_VM + "\n"
                    + "adb shell settings put global hidden_api_blacklist_exemptions 'Landroid/system/virtualmachine/'");
            } else if (!batteryUnrestricted) {
                mPermBanner.setVisibility(View.VISIBLE);
                mPermBanner.setTypeface(Typeface.SANS_SERIF);
                mPermBanner.setBackground(pillButtonBg(mPal.warnContainer, 0, 14));
                mPermBanner.setOnClickListener(v -> requestIgnoreBatteryOptimizations());
                mPermBanner.setText("Background optimization active — Android may throttle or freeze SSH & VM network in background. Tap here to allow unrestricted background usage.");
            } else {
                mPermBanner.setOnClickListener(null);
                mPermBanner.setClickable(false);
                mPermBanner.setVisibility(View.GONE);
            }

            boolean running = (st == VmStatus.State.RUNNING);
            boolean busy = st.isBusy();

            int chipBg, chipFg, dotColor;
            String niceState;
            if (running) {
                chipBg = mPal.okContainer; chipFg = mPal.onOkContainer; dotColor = mPal.okDot;
                niceState = "Running";
            } else if (busy) {
                chipBg = mPal.warnContainer; chipFg = mPal.onWarnContainer; dotColor = mPal.warnDot;
                if (st == VmStatus.State.STARTING) niceState = "Starting";
                else if (st == VmStatus.State.STOPPING) niceState = "Stopping";
                else if (st == VmStatus.State.RESETTING) niceState = "Resetting";
                else niceState = "Importing";
            } else if (st == VmStatus.State.ERROR) {
                chipBg = mPal.errorContainer; chipFg = mPal.onErrorContainer; dotColor = mPal.errDot;
                niceState = "Error";
            } else {
                chipBg = mPal.surfaceContainerHigh; chipFg = mPal.onSurfaceVariant; dotColor = mPal.idleDot;
                niceState = "Stopped";
            }
            mStatusChip.setBackground(roundRect(chipBg, 0, 16));
            mStatusLabel.setText(niceState);
            mStatusLabel.setTextColor(chipFg);
            mStatusDot.setBackground(roundRect(dotColor, 0, 4));

            int cores = Runtime.getRuntime().availableProcessors();
            mValCpu.setText(cores + " vCPU (Host)");
            mValMem.setText((status.memMib / 1024) + " GiB on-demand");
            if (actualMb > 0) {
                mValDisk.setText(String.format("%.1f / %d GiB", actualMb / 1024.0, status.diskGb));
            } else {
                mValDisk.setText(imgs ? (status.diskGb + " GiB sparse") : "Not imported");
            }
            mValNet.setText(running ? (status.vmNetConnected ? status.netMode : "Connecting...") : "Offline");

            mEndpointLine.setText("ssh 127.0.0.1:" + status.sshPort
                + "  ·  ctl :" + status.ctlPort);

            if (!status.lastError.isEmpty()) {
                mErrorLine.setVisibility(View.VISIBLE);
                mErrorLine.setText(status.lastError);
            } else {
                mErrorLine.setVisibility(View.GONE);
            }

            mBtnPrimary.setEnabled(!busy);
            mBtnPrimary.setAlpha(busy ? 0.5f : 1f);
            if (running) {
                mBtnPrimary.setText("Stop VM");
                mBtnPrimary.setTextColor(mPal.onErrorContainer);
                mBtnPrimary.setBackground(pillButtonBg(mPal.errorContainer, 0, 24));
            } else {
                mBtnPrimary.setText(busy ? niceState + "..." : "Start VM");
                mBtnPrimary.setTextColor(mPal.onPrimary);
                mBtnPrimary.setBackground(pillButtonBg(mPal.primary, 0, 24));
            }

            mBtnRestart.setEnabled(running && !busy);
            mBtnRestart.setAlpha((running && !busy) ? 1f : 0.45f);
            mBtnRestart.setTextColor(mPal.onPrimaryContainer);
            mBtnRestart.setBackground(pillButtonBg(mPal.primaryContainer, 0, 24));

            mBtnImport.setEnabled(!busy);
            mBtnImport.setAlpha(busy ? 0.45f : 1f);

            boolean canReset = hasSeed && !busy;
            mBtnReset.setEnabled(canReset);
            mBtnReset.setAlpha(canReset ? 1f : 0.45f);
            mBtnReset.setTextColor(mPal.errDot);
            mBtnReset.setBackground(pillButtonBg(mPal.surfaceContainer, mPal.outlineVariant, 20));

            if (status.keepAwake) {
                mBtnWakeLock.setText("WakeLock: On");
                mBtnWakeLock.setTextColor(mPal.onWarnContainer);
                mBtnWakeLock.setBackground(pillButtonBg(mPal.warnContainer, 0, 20));
            } else {
                mBtnWakeLock.setText("WakeLock: Off");
                styleOutlinedChip(mBtnWakeLock);
            }
        }

        if (mShowingConsole) {
            ConsoleBuffer con = currentConsole();
            long seq = (con != null) ? con.getSeq() : 0L;
            if (seq != mLastConsoleSeq || stateChanged) {
                mLastConsoleSeq = seq;
                ConsoleBuffer.Snapshot snap = (con != null) ? con.snapshot() : null;
                boolean showCursor = status.state.showsConsoleCursor();
                mConsoleView.updateSnapshot(snap, showCursor);
                updateConsoleHeader(con);
            }
        }
    }

    private void updateConsoleHeader() {
        updateConsoleHeader(currentConsole());
    }

    private void updateConsoleHeader(ConsoleBuffer con) {
        if (mConsoleMeta == null || mConsoleView == null) return;
        int lines = (con != null) ? con.getLineCount() : 0;
        boolean following = mConsoleView.isFollowingTail();
        if (lines == 0) {
            mConsoleMeta.setText("hvc0");
        } else if (!following) {
            mConsoleMeta.setText("hvc0 · " + lines + " ln · Paused");
        } else {
            int kb = Math.max(1, con.getLength() / 1024);
            mConsoleMeta.setText("hvc0 · " + lines + " ln · " + kb + " KB");
        }
        if (mBtnJumpBottom != null) {
            mBtnJumpBottom.setVisibility((lines > 0 && !following) ? View.VISIBLE : View.GONE);
        }
    }
}
