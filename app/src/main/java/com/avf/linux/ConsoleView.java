package com.avf.linux;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.OverScroller;

import java.util.ArrayList;

/**
 * Virtualized Canvas console renderer for hvc0 output.
 *
 * Renders only the currently visible wrapped rows directly onto the viewport
 * Canvas with zero requestLayout() calls during output streaming, eliminating
 * TextView/ScrollView layout thrashing, auto-scroll races, and HWUI texture
 * height blanking during high-speed kernel boot floods.
 */
public final class ConsoleView extends View {
    public interface OnFollowChangedListener {
        void onFollowChanged(boolean followingTail);
    }

    private static final int[] EMPTY_INT = new int[0];
    private static final int[] EMPTY_LINE_WRAP = new int[]{0, 0};

    private final Paint mTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mPlaceholderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mCursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mThumbPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mThumbRect = new RectF();
    private final RectF mCursorRect = new RectF();
    private final OverScroller mScroller;
    private final GestureDetector mDetector;

    private final ArrayList<int[]> mLineWraps = new ArrayList<>(256);
    private int[] mPrefixRows = EMPTY_INT;
    private int mTotalVisualRows = 0;

    private ConsoleBuffer.Snapshot mSnap;
    private boolean mShowCursor = false;
    private boolean mLayoutDirty = true;
    private long mCachedEpoch = -1L;
    private long mCachedDropped = -1L;
    private int mCachedWidth = -1;

    private int mLineHeight = 16;
    private float mBaselineOffset = 12f;
    private float mCharWidth = 8f;
    private int mThumbWidthPx;
    private int mThumbMarginPx;
    private int mMinThumbHeightPx;

    private int mScrollY = 0;
    private boolean mFollowTail = true;
    private String mPlaceholder = "Tap here to type when VM is running.";
    private Runnable mOnTapListener;
    private Runnable mOnLongPressListener;
    private OnFollowChangedListener mOnFollowChangedListener;

    public ConsoleView(Context context) {
        super(context);
        setFocusable(false);
        setClickable(true);

        float density = getResources().getDisplayMetrics().density;
        float scaledDensity = getResources().getDisplayMetrics().scaledDensity;

        mTextPaint.setTextSize(11f * scaledDensity);
        mTextPaint.setTypeface(Typeface.MONOSPACE);
        mPlaceholderPaint.setTextSize(11f * scaledDensity);
        mPlaceholderPaint.setTypeface(Typeface.MONOSPACE);

        mThumbWidthPx = Math.max(2, Math.round(3f * density));
        mThumbMarginPx = Math.max(2, Math.round(4f * density));
        mMinThumbHeightPx = Math.round(28f * density);

        recomputeFontMetrics();

        mScroller = new OverScroller(context);
        mDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                if (!mScroller.isFinished()) {
                    mScroller.forceFinished(true);
                }
                return true;
            }

            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                if (mOnTapListener != null) {
                    mOnTapListener.run();
                }
                return true;
            }

            @Override
            public void onLongPress(MotionEvent e) {
                if (mOnLongPressListener != null) {
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
                    mOnLongPressListener.run();
                }
            }

            @Override
            public boolean onScroll(MotionEvent e1, MotionEvent e2, float distanceX, float distanceY) {
                ensureLayoutCurrent();
                int maxScrollY = computeMaxScrollY();
                if (maxScrollY <= 0) {
                    setFollowTail(true);
                    return true;
                }
                int nextY = clamp(mScrollY + Math.round(distanceY), 0, maxScrollY);
                if (distanceY < 0 && nextY < maxScrollY - mLineHeight) {
                    setFollowTail(false);
                } else if (nextY >= maxScrollY - Math.max(4, mLineHeight / 2)) {
                    setFollowTail(true);
                }
                if (nextY != mScrollY) {
                    mScrollY = nextY;
                    invalidate();
                }
                return true;
            }

            @Override
            public boolean onFling(MotionEvent e1, MotionEvent e2, float velocityX, float velocityY) {
                ensureLayoutCurrent();
                int maxScrollY = computeMaxScrollY();
                if (maxScrollY <= 0) {
                    setFollowTail(true);
                    return true;
                }
                if (velocityY > 0 && mScrollY < maxScrollY) {
                    setFollowTail(false);
                }
                mScroller.fling(0, mScrollY, 0, Math.round(-velocityY), 0, 0, 0, maxScrollY);
                postInvalidateOnAnimation();
                return true;
            }
        });
    }

    public void configureStyle(Typeface mono, int textColor, int mutedColor, int cursorColor) {
        if (mono != null) {
            mTextPaint.setTypeface(mono);
            mPlaceholderPaint.setTypeface(mono);
        }
        mTextPaint.setColor(textColor);
        mPlaceholderPaint.setColor(mutedColor);
        mCursorPaint.setColor(cursorColor);
        mThumbPaint.setColor((mutedColor & 0x00FFFFFF) | 0x66000000);
        recomputeFontMetrics();
        mCachedWidth = -1;
        mLayoutDirty = true;
        invalidate();
    }

    public void setOnTapListener(Runnable listener) {
        mOnTapListener = listener;
    }

    public void setOnLongPressListener(Runnable listener) {
        mOnLongPressListener = listener;
    }

    public void setOnFollowChangedListener(OnFollowChangedListener listener) {
        mOnFollowChangedListener = listener;
    }

    public boolean isFollowingTail() {
        return mFollowTail;
    }

    public void scrollToBottom() {
        mScroller.forceFinished(true);
        setFollowTail(true);
        invalidate();
    }

    public void updateSnapshot(ConsoleBuffer.Snapshot snap, boolean showCursor) {
        boolean cleared = (snap != null && mSnap != null && snap.clearEpoch != mSnap.clearEpoch);
        mSnap = snap;
        mShowCursor = showCursor;
        mLayoutDirty = true;
        if (cleared) {
            mScroller.forceFinished(true);
            mScrollY = 0;
            setFollowTail(true);
        }
        invalidate();
    }

    private void ensureLayoutCurrent() {
        int contentW = getContentWidth();
        if (contentW > 0) {
            syncLayoutIfNeeded(contentW);
        }
    }

    private void recomputeFontMetrics() {
        float density = getResources().getDisplayMetrics().density;
        Paint.FontMetrics fm = mTextPaint.getFontMetrics();
        float rawH = fm.descent - fm.ascent;
        float extra = 2f * density;
        mLineHeight = Math.max(12, Math.round(rawH * 1.05f + extra));
        mBaselineOffset = -fm.ascent + (extra * 0.5f);
        mCharWidth = Math.max(4f, mTextPaint.measureText("M"));
    }

    private void setFollowTail(boolean follow) {
        if (mFollowTail != follow) {
            mFollowTail = follow;
            if (mOnFollowChangedListener != null) {
                mOnFollowChangedListener.onFollowChanged(follow);
            }
        }
    }

    private int getContentWidth() {
        return Math.max(0, getWidth() - getPaddingLeft() - getPaddingRight() - mThumbWidthPx - mThumbMarginPx);
    }

    private int getContentHeight() {
        return Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
    }

    private int computeMaxScrollY() {
        int contentH = getContentHeight();
        if (contentH <= 0 || mTotalVisualRows <= 0) return 0;
        return Math.max(0, mTotalVisualRows * mLineHeight - contentH);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (w != oldw) {
            mCachedWidth = -1;
            mLayoutDirty = true;
        }
        invalidate();
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        boolean handled = mDetector.onTouchEvent(event);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
        }
        return handled || super.onTouchEvent(event);
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
            ensureLayoutCurrent();
            int maxScrollY = computeMaxScrollY();
            int nextY = clamp(mScroller.getCurrY(), 0, maxScrollY);
            if (nextY != mScrollY) {
                mScrollY = nextY;
            }
            if (!mFollowTail && maxScrollY > 0
                    && mScrollY >= maxScrollY - Math.max(4, mLineHeight / 2)
                    && mScroller.getFinalY() >= maxScrollY) {
                setFollowTail(true);
            }
            postInvalidateOnAnimation();
        }
    }

    private void syncLayoutIfNeeded(int contentW) {
        if (!mLayoutDirty && contentW == mCachedWidth) {
            return;
        }
        mLayoutDirty = false;
        if (mSnap == null || mSnap.lines.length == 0) {
            mLineWraps.clear();
            mPrefixRows = EMPTY_INT;
            mTotalVisualRows = 0;
            mCachedWidth = contentW;
            if (mSnap != null && mSnap.clearEpoch != mCachedEpoch) {
                mCachedEpoch = mSnap.clearEpoch;
                mCachedDropped = mSnap.droppedLines;
                mScrollY = 0;
                mFollowTail = true;
            }
            return;
        }

        String[] lines = mSnap.lines;
        int lineCount = lines.length;
        boolean fullRebuild = (mCachedWidth != contentW)
            || (mSnap.clearEpoch != mCachedEpoch)
            || (mSnap.droppedLines < mCachedDropped)
            || mLineWraps.isEmpty();

        if (mSnap.clearEpoch != mCachedEpoch) {
            mScrollY = 0;
            mFollowTail = true;
        }

        if (fullRebuild) {
            mLineWraps.clear();
            mLineWraps.ensureCapacity(lineCount);
            for (int i = 0; i < lineCount; i++) {
                mLineWraps.add(wrapLine(lines[i], contentW));
            }
        } else {
            long droppedDelta = mSnap.droppedLines - mCachedDropped;
            int evicted = (droppedDelta > mLineWraps.size()) ? mLineWraps.size() : (int) droppedDelta;
            if (evicted > 0) {
                int evictedVisualRows = 0;
                for (int i = 0; i < evicted; i++) {
                    evictedVisualRows += mLineWraps.get(i).length >> 1;
                }
                mLineWraps.subList(0, evicted).clear();
                if (!mFollowTail && evictedVisualRows > 0) {
                    mScrollY = Math.max(0, mScrollY - evictedVisualRows * mLineHeight);
                }
            }
            // The previously-last line was the mutable active line; re-wrap it in case it changed.
            int prevSize = mLineWraps.size();
            if (prevSize > lineCount) {
                mLineWraps.subList(lineCount, prevSize).clear();
                prevSize = lineCount;
            }
            if (prevSize > 0) {
                int lastCached = prevSize - 1;
                mLineWraps.set(lastCached, wrapLine(lines[lastCached], contentW));
            }
            for (int i = prevSize; i < lineCount; i++) {
                mLineWraps.add(wrapLine(lines[i], contentW));
            }
        }

        mCachedEpoch = mSnap.clearEpoch;
        mCachedDropped = mSnap.droppedLines;
        mCachedWidth = contentW;

        if (mPrefixRows.length != lineCount + 1) {
            mPrefixRows = new int[lineCount + 1];
        }
        int sum = 0;
        mPrefixRows[0] = 0;
        for (int i = 0; i < lineCount; i++) {
            sum += (mLineWraps.get(i).length >> 1);
            mPrefixRows[i + 1] = sum;
        }
        mTotalVisualRows = sum;
    }

    private int[] wrapLine(String line, int maxWidthPx) {
        int len = (line == null) ? 0 : line.length();
        if (len == 0) {
            return EMPTY_LINE_WRAP;
        }
        if (maxWidthPx <= 0) {
            return new int[]{0, len};
        }
        // Fast path: check if entire line fits in one row
        int firstCount = mTextPaint.breakText(line, 0, len, true, maxWidthPx, null);
        if (firstCount >= len) {
            return new int[]{0, len};
        }
        if (firstCount <= 0) firstCount = 1;
        firstCount = adjustSurrogateBoundary(line, 0, firstCount, len);

        int[] tmp = new int[Math.max(8, ((len / Math.max(1, firstCount)) + 2) * 2)];
        int out = 0;
        tmp[out++] = 0;
        tmp[out++] = firstCount;

        int pos = firstCount;
        while (pos < len) {
            int count = mTextPaint.breakText(line, pos, len, true, maxWidthPx, null);
            if (count <= 0) count = 1;
            count = adjustSurrogateBoundary(line, pos, count, len);
            if (out + 2 > tmp.length) {
                int[] grown = new int[tmp.length * 2];
                System.arraycopy(tmp, 0, grown, 0, out);
                tmp = grown;
            }
            tmp[out++] = pos;
            tmp[out++] = pos + count;
            pos += count;
        }
        if (out == tmp.length) {
            return tmp;
        }
        int[] exact = new int[out];
        System.arraycopy(tmp, 0, exact, 0, out);
        return exact;
    }

    private static int adjustSurrogateBoundary(String s, int start, int count, int len) {
        int end = start + count;
        if (end < len && Character.isHighSurrogate(s.charAt(end - 1))) {
            if (count > 1) {
                return count - 1;
            }
            if (start + 2 <= len) {
                return 2;
            }
        }
        return count;
    }

    private int findLineForRow(int visualRow) {
        int lo = 0;
        int hi = mLineWraps.size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (mPrefixRows[mid + 1] <= visualRow) {
                lo = mid + 1;
            } else if (mPrefixRows[mid] > visualRow) {
                hi = mid - 1;
            } else {
                return mid;
            }
        }
        return Math.max(0, Math.min(lo, mLineWraps.size() - 1));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int padL = getPaddingLeft();
        int padT = getPaddingTop();
        int padR = getPaddingRight();
        int padB = getPaddingBottom();
        int contentW = getContentWidth();
        int contentH = getContentHeight();
        if (contentW <= 0 || contentH <= 0) {
            return;
        }

        syncLayoutIfNeeded(contentW);

        int maxScrollY = computeMaxScrollY();
        if (mFollowTail) {
            mScrollY = maxScrollY;
        } else {
            mScrollY = clamp(mScrollY, 0, maxScrollY);
        }

        int save = canvas.save();
        canvas.clipRect(padL, padT, getWidth() - padR, getHeight() - padB);

        if (mSnap == null || mTotalVisualRows == 0 || mSnap.lines.length == 0) {
            int[] pw = wrapLine(mPlaceholder, contentW);
            for (int i = 0; i < pw.length; i += 2) {
                float py = padT + (i >> 1) * mLineHeight + mBaselineOffset;
                canvas.drawText(mPlaceholder, pw[i], pw[i + 1], padL, py, mPlaceholderPaint);
            }
            canvas.restoreToCount(save);
            return;
        }

        String[] lines = mSnap.lines;
        int firstRow = Math.max(0, mScrollY / mLineHeight);
        int lastRow = Math.min(mTotalVisualRows - 1, (mScrollY + contentH) / mLineHeight + 1);

        int lineIdx = findLineForRow(firstRow);
        int row = firstRow;
        int lastLineIdx = lines.length - 1;

        while (row <= lastRow && lineIdx < lines.length) {
            int[] wraps = mLineWraps.get(lineIdx);
            int subRow = row - mPrefixRows[lineIdx];
            int subCount = wraps.length >> 1;
            String text = lines[lineIdx];

            while (subRow < subCount && row <= lastRow) {
                int s = wraps[subRow * 2];
                int e = wraps[subRow * 2 + 1];
                float topY = padT + (row * mLineHeight - mScrollY);

                // Draw cursor behind text so characters under cursor stay readable during editing
                if (mShowCursor && lineIdx == lastLineIdx) {
                    int cursorCol = clamp(mSnap.cursorCol, 0, text.length());
                    boolean onThisSubRow = (subRow == subCount - 1)
                        ? (cursorCol >= s && cursorCol <= e)
                        : (cursorCol >= s && cursorCol < e);
                    if (onThisSubRow) {
                        float cx = padL + (cursorCol > s ? mTextPaint.measureText(text, s, cursorCol) : 0f);
                        float cw = Math.max(3f, mCharWidth * 0.72f);
                        if (cx + cw > padL + contentW) {
                            cx = Math.max(padL, padL + contentW - cw);
                        }
                        mCursorRect.set(cx, topY + 2f, cx + cw, topY + mLineHeight - 2f);
                        canvas.drawRoundRect(mCursorRect, 2f, 2f, mCursorPaint);
                    }
                }

                if (e > s) {
                    canvas.drawText(text, s, e, padL, topY + mBaselineOffset, mTextPaint);
                }

                subRow++;
                row++;
            }
            lineIdx++;
        }

        // Thin vertical scrollbar thumb when history exceeds viewport
        if (maxScrollY > 0) {
            float totalH = mTotalVisualRows * (float) mLineHeight;
            float thumbH = Math.max(mMinThumbHeightPx, contentH * (contentH / totalH));
            thumbH = Math.min(thumbH, contentH);
            float trackSpan = Math.max(1f, contentH - thumbH);
            float thumbTop = padT + (mScrollY / (float) maxScrollY) * trackSpan;
            float thumbRight = getWidth() - padR - mThumbMarginPx * 0.5f;
            float thumbLeft = thumbRight - mThumbWidthPx;
            mThumbRect.set(thumbLeft, thumbTop, thumbRight, thumbTop + thumbH);
            float radius = mThumbWidthPx * 0.5f;
            canvas.drawRoundRect(mThumbRect, radius, radius, mThumbPaint);
        }

        canvas.restoreToCount(save);
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(v, hi));
    }
}
