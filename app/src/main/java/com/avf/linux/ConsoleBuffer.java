package com.avf.linux;

import java.util.ArrayDeque;

/**
 * Thread-safe, streaming VT100/ANSI console ring buffer for serial (hvc0) output.
 *
 * Parses escape sequences incrementally across arbitrary read chunk boundaries,
 * handles CR/LF/BS/TAB and common CSI line-editing commands without erasing lines
 * on split CRLF, collapses excessive blank-line floods from U-Boot/GRUB screen
 * redraws, and produces lightweight immutable snapshots for Canvas rendering.
 */
public final class ConsoleBuffer {
    public static final int MAX_LINES = 2000;
    public static final int MAX_CHARS = 163840;
    private static final int MAX_LINE_LEN = 4096;
    private static final int MAX_CSI_LEN = 64;
    private static final int MAX_OSC_LEN = 2048;
    private static final int MAX_DCS_LEN = 4096;

    private static final int ST_GROUND = 0;
    private static final int ST_ESC = 1;
    private static final int ST_ESC_INT = 2;
    private static final int ST_CSI = 3;
    private static final int ST_OSC = 4;
    private static final int ST_OSC_ESC = 5;
    private static final int ST_DCS = 6;
    private static final int ST_DCS_ESC = 7;

    public static final class Snapshot {
        public final long seq;
        public final long clearEpoch;
        public final long droppedLines;
        public final String[] lines;
        public final int cursorCol;
        public final int totalChars;

        Snapshot(long seq, long clearEpoch, long droppedLines, String[] lines, int cursorCol, int totalChars) {
            this.seq = seq;
            this.clearEpoch = clearEpoch;
            this.droppedLines = droppedLines;
            this.lines = lines;
            this.cursorCol = cursorCol;
            this.totalChars = totalChars;
        }
    }

    private final ArrayDeque<String> mHistory = new ArrayDeque<>(256);
    private final StringBuilder mCur = new StringBuilder(256);
    private final StringBuilder mCsi = new StringBuilder(32);

    private int mCol = 0;
    private int mHistoryChars = 0;
    private long mDroppedLines = 0L;
    private long mClearEpoch = 0L;
    private long mSeq = 0L;

    private int mState = ST_GROUND;
    private int mStrSkipLen = 0;
    private int mConsecutiveBlankLines = 0;
    private int mLastCupRow = -1;

    public synchronized void clear() {
        mHistory.clear();
        mCur.setLength(0);
        mCsi.setLength(0);
        mCol = 0;
        mHistoryChars = 0;
        mDroppedLines = 0L;
        mState = ST_GROUND;
        mStrSkipLen = 0;
        mConsecutiveBlankLines = 0;
        mLastCupRow = -1;
        mClearEpoch++;
        mSeq++;
    }

    public synchronized long getSeq() {
        return mSeq;
    }

    public synchronized int getLength() {
        return mHistoryChars + activeLineLength();
    }

    public synchronized int getLineCount() {
        if (mHistory.isEmpty() && activeLineLength() == 0) {
            return 0;
        }
        return mHistory.size() + 1;
    }

    public synchronized boolean isEmpty() {
        return mHistory.isEmpty() && activeLineLength() == 0;
    }

    public synchronized void append(String text) {
        if (text == null || text.isEmpty()) return;
        int len = text.length();
        for (int i = 0; i < len; i++) {
            feedChar(text.charAt(i));
        }
        mSeq++;
    }

    public synchronized void append(char[] buf, int off, int len) {
        if (buf == null || len <= 0) return;
        int end = off + len;
        for (int i = off; i < end; i++) {
            feedChar(buf[i]);
        }
        mSeq++;
    }

    public synchronized Snapshot snapshot() {
        int activeLen = activeLineLength();
        if (mHistory.isEmpty() && activeLen == 0) {
            return new Snapshot(mSeq, mClearEpoch, mDroppedLines, new String[0], 0, 0);
        }
        int histCount = mHistory.size();
        String[] arr = new String[histCount + 1];
        int idx = 0;
        for (String s : mHistory) {
            arr[idx++] = s;
        }
        arr[idx] = buildActiveLine(activeLen);
        int curCol = Math.min(mCol, activeLen);
        return new Snapshot(mSeq, mClearEpoch, mDroppedLines, arr, curCol, mHistoryChars + activeLen);
    }

    public synchronized String getTextSnapshot() {
        int activeLen = activeLineLength();
        if (mHistory.isEmpty() && activeLen == 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(mHistoryChars + activeLen + 16);
        for (String line : mHistory) {
            sb.append(line).append('\n');
        }
        if (activeLen > 0) {
            sb.append(mCur, 0, activeLen);
        }
        return sb.toString();
    }

    private int activeLineLength() {
        int len = mCur.length();
        while (len > mCol && mCur.charAt(len - 1) == ' ') {
            len--;
        }
        return len;
    }

    private String buildActiveLine(int activeLen) {
        if (activeLen <= 0) return "";
        return mCur.substring(0, activeLen);
    }

    private void feedChar(char c) {
        switch (mState) {
            case ST_GROUND:
                handleGround(c);
                return;
            case ST_ESC:
                handleEsc(c);
                return;
            case ST_ESC_INT:
                if (c == 0x1B) {
                    mState = ST_ESC;
                } else if (c < 0x20 || c > 0x2F) {
                    mState = ST_GROUND;
                }
                return;
            case ST_CSI:
                handleCsiChar(c);
                return;
            case ST_OSC:
                if (c == 0x07) {
                    mState = ST_GROUND;
                } else if (c == 0x1B) {
                    mState = ST_OSC_ESC;
                } else if (++mStrSkipLen > MAX_OSC_LEN) {
                    mState = ST_GROUND;
                }
                return;
            case ST_OSC_ESC:
                if (c == '\\') {
                    mState = ST_GROUND;
                } else if (c != 0x1B) {
                    mState = ST_GROUND;
                    handleEsc(c);
                }
                return;
            case ST_DCS:
                if (c == 0x1B) {
                    mState = ST_DCS_ESC;
                } else if (++mStrSkipLen > MAX_DCS_LEN) {
                    mState = ST_GROUND;
                }
                return;
            case ST_DCS_ESC:
                if (c == '\\') {
                    mState = ST_GROUND;
                } else if (c != 0x1B) {
                    mState = ST_GROUND;
                    handleEsc(c);
                }
                return;
            default:
                mState = ST_GROUND;
                handleGround(c);
        }
    }

    private void handleGround(char c) {
        if (c == 0x1B) {
            mState = ST_ESC;
            return;
        }
        if (c == 0x9B) {
            mCsi.setLength(0);
            mState = ST_CSI;
            return;
        }
        if (c == '\r') {
            mCol = 0;
            return;
        }
        if (c == '\n') {
            commitLine();
            mLastCupRow = -1;
            return;
        }
        if (c == '\b') {
            if (mCol > 0) mCol--;
            return;
        }
        if (c == '\t') {
            int next = Math.min(MAX_LINE_LEN, ((mCol / 8) + 1) * 8);
            ensureCurLen(next);
            mCol = next;
            return;
        }
        if (c < 0x20 || (c >= 0x7F && c <= 0x9F)) {
            return;
        }
        if (mCol >= MAX_LINE_LEN) {
            commitLine();
        }
        ensureCurLen(mCol);
        if (mCol < mCur.length()) {
            mCur.setCharAt(mCol, c);
        } else {
            mCur.append(c);
        }
        mCol++;
        if (c != ' ') {
            mConsecutiveBlankLines = 0;
        }
    }

    private void handleEsc(char c) {
        if (c == '[') {
            mCsi.setLength(0);
            mState = ST_CSI;
        } else if (c == ']') {
            mStrSkipLen = 0;
            mState = ST_OSC;
        } else if (c == 'P' || c == '^' || c == '_' || c == 'X') {
            mStrSkipLen = 0;
            mState = ST_DCS;
        } else if (c >= 0x20 && c <= 0x2F) {
            // Intermediate bytes such as ESC ( B, ESC ) 0, ESC # 8
            mState = ST_ESC_INT;
        } else if (c == 'E') {
            // NEL (Next Line)
            commitLine();
            mLastCupRow = -1;
            mState = ST_GROUND;
        } else {
            mState = ST_GROUND;
        }
    }

    private void handleCsiChar(char c) {
        if (c >= 0x40 && c <= 0x7E) {
            executeCsi(c);
            mState = ST_GROUND;
            return;
        }
        if (c == 0x1B) {
            mState = ST_ESC;
            return;
        }
        if (c == '\r' || c == '\n' || c == '\b') {
            mState = ST_GROUND;
            handleGround(c);
            return;
        }
        if (mCsi.length() < MAX_CSI_LEN) {
            mCsi.append(c);
        } else {
            mState = ST_GROUND;
        }
    }

    private void executeCsi(char cmd) {
        int len = mCsi.length();
        boolean priv = false;
        int start = 0;
        if (len > 0) {
            char first = mCsi.charAt(0);
            if (first == '?' || first == '>' || first == '<' || first == '=') {
                priv = true;
                start = 1;
            }
        }
        if (priv) {
            return;
        }

        int p0 = parseCsiParam(start, 0);
        int p1 = parseCsiParam(start, 1);

        switch (cmd) {
            case 'K': {
                // EL - Erase in Line
                if (p0 == 0) {
                    if (mCol < mCur.length()) {
                        mCur.setLength(mCol);
                    }
                } else if (p0 == 1) {
                    int end = Math.min(mCol + 1, mCur.length());
                    for (int i = 0; i < end; i++) {
                        mCur.setCharAt(i, ' ');
                    }
                } else if (p0 == 2) {
                    mCur.setLength(0);
                    mCol = 0;
                }
                break;
            }
            case 'J': {
                // ED - Erase in Display (preserve scrollback; commit active non-blank text)
                if (hasNonSpace(mCur)) {
                    commitLine();
                } else {
                    mCur.setLength(0);
                    mCol = 0;
                }
                mLastCupRow = -1;
                break;
            }
            case 'H':
            case 'f': {
                // CUP - Cursor Position (convert U-Boot/GRUB row jumps into clean single line breaks)
                int row = (p0 <= 0) ? 1 : p0;
                int col = (p1 <= 0) ? 1 : p1;
                if (mLastCupRow != -1 && row != mLastCupRow && hasNonSpace(mCur)) {
                    commitLine();
                }
                mCol = (col <= 1) ? 0 : Math.min(MAX_LINE_LEN, col - 1);
                mLastCupRow = row;
                break;
            }
            case 'C': {
                int n = (p0 <= 0) ? 1 : p0;
                mCol = Math.min(MAX_LINE_LEN, mCol + n);
                break;
            }
            case 'D': {
                int n = (p0 <= 0) ? 1 : p0;
                mCol = Math.max(0, mCol - n);
                break;
            }
            case 'G': {
                int col = (p0 <= 0) ? 1 : p0;
                mCol = Math.min(MAX_LINE_LEN, col - 1);
                break;
            }
            case 'P': {
                // DCH - Delete Characters
                int n = (p0 <= 0) ? 1 : p0;
                if (mCol < mCur.length()) {
                    int delEnd = Math.min(mCur.length(), mCol + n);
                    mCur.delete(mCol, delEnd);
                }
                break;
            }
            case '@': {
                // ICH - Insert Blank Characters
                int n = Math.min((p0 <= 0) ? 1 : p0, MAX_LINE_LEN - mCol);
                if (n > 0) {
                    ensureCurLen(mCol);
                    for (int i = 0; i < n; i++) {
                        mCur.insert(mCol, ' ');
                    }
                    if (mCur.length() > MAX_LINE_LEN) {
                        mCur.setLength(MAX_LINE_LEN);
                    }
                }
                break;
            }
            case 'X': {
                // ECH - Erase Characters
                int n = (p0 <= 0) ? 1 : p0;
                int end = Math.min(mCur.length(), mCol + n);
                for (int i = mCol; i < end; i++) {
                    mCur.setCharAt(i, ' ');
                }
                break;
            }
            default:
                break;
        }
    }

    private int parseCsiParam(int start, int targetIndex) {
        int len = mCsi.length();
        int idx = 0;
        int val = 0;
        boolean hasDigit = false;
        for (int i = start; i < len; i++) {
            char ch = mCsi.charAt(i);
            if (ch == ';') {
                if (idx == targetIndex) {
                    return hasDigit ? val : 0;
                }
                idx++;
                val = 0;
                hasDigit = false;
            } else if (ch >= '0' && ch <= '9') {
                if (idx == targetIndex) {
                    hasDigit = true;
                    if (val < 100000) {
                        val = val * 10 + (ch - '0');
                    }
                }
            }
        }
        return (idx == targetIndex && hasDigit) ? val : 0;
    }

    private void ensureCurLen(int target) {
        int cap = Math.min(target, MAX_LINE_LEN);
        while (mCur.length() < cap) {
            mCur.append(' ');
        }
    }

    private static boolean hasNonSpace(StringBuilder sb) {
        for (int i = 0; i < sb.length(); i++) {
            char c = sb.charAt(i);
            if (c != ' ' && c != '\t') return true;
        }
        return false;
    }

    private void commitLine() {
        int end = mCur.length();
        while (end > 0) {
            char c = mCur.charAt(end - 1);
            if (c == ' ' || c == '\t') {
                end--;
            } else {
                break;
            }
        }
        String line = (end == 0) ? "" : mCur.substring(0, end);
        mCur.setLength(0);
        mCol = 0;

        if (line.isEmpty()) {
            mConsecutiveBlankLines++;
            if (mConsecutiveBlankLines > 1) {
                return;
            }
        } else {
            mConsecutiveBlankLines = 0;
        }

        mHistory.addLast(line);
        mHistoryChars += line.length() + 1;
        while (mHistory.size() > MAX_LINES || (mHistoryChars > MAX_CHARS && mHistory.size() > 1)) {
            String removed = mHistory.removeFirst();
            mHistoryChars -= (removed.length() + 1);
            mDroppedLines++;
        }
    }
}
