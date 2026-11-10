/**
 * Copyright (c) 2016, Search Solution Corporation. All rights reserved.
 *
 * <p>Redistribution and use in source and binary forms, with or without modification, are
 * permitted provided that the following conditions are met:
 *
 * <p>* Redistributions of source code must retain the above copyright notice, this list of
 * conditions and the following disclaimer.
 *
 * <p>* Redistributions in binary form must reproduce the above copyright notice, this list of
 * conditions and the following disclaimer in the documentation and/or other materials provided
 * with the distribution.
 *
 * <p>* Neither the name of the copyright holder nor the names of its contributors may be used to
 * endorse or promote products derived from this software without specific prior written
 * permission.
 *
 * <p>THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS
 * OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE
 * GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.navercorp.cubridqa.cqt.console.util;

import com.navercorp.cubridqa.cqt.console.bean.Sql;
import com.navercorp.cubridqa.cqt.console.dao.ConsoleDAO;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.security.MessageDigest;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Per-statement timing for the sqlperf suite. Off unless the environment variable CQT_PERF_DIR
 * names a directory; then every statement CQT executes is written as one JSON line to
 * CQT_PERF_DIR/stmts.jsonl, and nothing CQT writes to its own results changes.
 *
 * <p>The time of a statement runs from prepare (or execute) to the end of fetching its last row or
 * update count. It leaves out what CQT does with the rows afterwards (logging the result text,
 * reading System.xml, query plan printing, server output). Rows are hashed from the formatted row
 * lines, order-insensitively and without the column header, so the hash compares rows across
 * versions. A read-only statement that is safe to repeat (see {@link #repeatable}) is executed
 * CQT_PERF_REPEAT more times (default 3) on a copy, whose results never reach CQT's result.
 */
public class PerfRecorder {

    public static final boolean ENABLED;
    private static final int REPEAT;
    private static final File OUT;
    private static BufferedWriter writer;

    static {
        String dir = System.getenv("CQT_PERF_DIR");
        ENABLED = dir != null && dir.trim().length() > 0;
        OUT = ENABLED ? new File(dir.trim(), "stmts.jsonl") : null;
        int r = 3;
        try {
            String s = System.getenv("CQT_PERF_REPEAT");
            if (s != null && s.trim().length() > 0) {
                r = Integer.parseInt(s.trim());
            }
        } catch (NumberFormatException e) {
            r = 3;
        }
        REPEAT = Math.max(0, r);
    }

    /** Per-statement state, kept on the Sql object while the hook is on. */
    public static class State {
        long start = -1;
        long excluded = 0;
        long nanos = -1;
        int err = 0;
        int rows = 0;
        boolean hasRows = false;
        List<String> lines = new ArrayList<String>();
    }

    public static void begin(Sql sql) {
        try {
            if (!ENABLED) {
                return;
            }
            State s = new State();
            sql.setPerf(s);
            s.start = System.nanoTime();
        } catch (Throwable t) {
            // never let the hook change what CQT does
        }
    }

    public static void end(Sql sql) {
        try {
            State s = state(sql);
            if (s != null && s.nanos < 0) {
                s.nanos = System.nanoTime() - s.start - s.excluded;
            }
        } catch (Throwable t) {
            // never let the hook change what CQT does
        }
    }

    /**
     * The statement failed. An exception after end() (printing the query plan, reading OUT
     * parameters) is CQT's, not the statement's, and leaves the record as it was.
     */
    public static void fail(Sql sql, int errorCode) {
        try {
            State s = state(sql);
            if (s != null && s.nanos < 0) {
                end(sql);
                s.err = errorCode == 0 ? -1 : errorCode;
            }
        } catch (Throwable t) {
            // never let the hook change what CQT does
        }
    }

    /** Called right after the fetch loop of a result set: rows read so far, then CQT's own work. */
    public static long pause(Sql sql, CharSequence text) {
        try {
            State s = state(sql);
            if (s == null) {
                return 0;
            }
            long now = System.nanoTime();
            String[] ls = text.toString().split("\r?\n", -1);
            // line 0 is the column header; the last element is what follows the final line separator
            for (int i = 1; i < ls.length - 1; i++) {
                s.lines.add(ls[i]);
                s.rows++;
            }
            s.hasRows = true;
            return now;
        } catch (Throwable t) {
            // never let the hook change what CQT does
            return 0;
        }
    }

    /** Called when getResult returns: the time since pause() was CQT's, not the statement's. */
    public static void resume(Sql sql, long pausedAt) {
        try {
            State s = state(sql);
            if (s != null && pausedAt > 0) {
                s.excluded += System.nanoTime() - pausedAt;
            }
        } catch (Throwable t) {
            // never let the hook change what CQT does
        }
    }

    public static void updateCount(Sql sql, int count) {
        try {
            State s = state(sql);
            if (s != null) {
                s.lines.add("#update:" + count);
            }
        } catch (Throwable t) {
            // never let the hook change what CQT does
        }
    }

    private static State state(Sql sql) {
        return ENABLED && sql != null ? (State) sql.getPerf() : null;
    }

    private static final Pattern NOT_REPEATABLE =
            Pattern.compile(
                    "\\b(NEXTVAL|NEXT_VALUE|INCR|DECR|SLEEP|RAND|RANDOM|DRAND|DRANDOM|SYS_GUID|INTO"
                            + "|SQL_CALC_FOUND_ROWS|ROW_COUNT|FOUND_ROWS|LAST_INSERT_ID|DBMS_OUTPUT|UPDATE|DELETE"
                            + "|INSERT|MERGE)\\b"
                            + "|\\bFOR\\s+UPDATE\\b");
    private static final Pattern READS_LAST_STATEMENT =
            Pattern.compile("^\\s*SHOW\\s+TRACE\\b|\\b(ROW_COUNT|FOUND_ROWS|LAST_INSERT_ID)\\s*\\(");

    /**
     * The statement's text with string literals, quoted identifiers and comments (not hints) replaced by a space, in
     * upper case. A linear scan: a regular expression with a repeated group recurses once per character in Java and
     * overflows the stack on a long literal (the test files hold statements of 60-80 KB).
     */
    static String code(String s) {
        int n = s.length(), i = 0;
        StringBuilder b = new StringBuilder(Math.min(n, 4096));
        while (i < n) {
            char c = s.charAt(i);
            if (c == '\'') {
                i++;
                while (i < n) {
                    if (s.charAt(i) == '\'') {
                        if (i + 1 < n && s.charAt(i + 1) == '\'') {
                            i += 2;
                            continue;
                        }
                        i++;
                        break;
                    }
                    i++;
                }
                b.append(' ');
            } else if (c == '"') {
                int j = s.indexOf('"', i + 1);
                i = j < 0 ? n : j + 1;
                b.append(' ');
            } else if (c == '-' && i + 1 < n && s.charAt(i + 1) == '-') {
                int j = s.indexOf('\n', i);
                i = j < 0 ? n : j;
                b.append(' ');
            } else if (c == '/' && i + 1 < n && s.charAt(i + 1) == '*' && !(i + 2 < n && s.charAt(i + 2) == '+')) {
                int j = s.indexOf("*/", i + 2);
                i = j < 0 ? n : j + 2;
                b.append(' ');
            } else {
                b.append(c);
                i++;
            }
        }
        return b.toString().toUpperCase();
    }

    /**
     * A statement is repeated in place only when that cannot change what the next statement sees: a
     * plain SELECT or WITH that ran without error, with no DML, no function that changes state
     * (serials, random values, sleep, DBMS_OUTPUT), not FOR UPDATE or INTO, and not followed by a
     * statement that reads the last statement's state (SHOW TRACE, ROW_COUNT(), FOUND_ROWS(),
     * LAST_INSERT_ID()).
     * Whether a whole file stays the same with repeats on is checked by a run, not assumed.
     */
    public static boolean repeatable(Sql sql, Sql next) {
        if (sql.getType() == Sql.TYPE_CALL) {
            return false;
        }
        String c = code(sql.getScript()).trim();
        if (!(c.startsWith("SELECT") || c.startsWith("WITH") || c.startsWith("(SELECT"))) {
            return false;
        }
        if (NOT_REPEATABLE.matcher(c).find()) {
            return false;
        }
        return next == null || !READS_LAST_STATEMENT.matcher(code(next.getScript())).find();
    }

    private static String kind(String script) {
        String c = code(script).trim();
        int i = 0;
        while (i < c.length() && (Character.isLetter(c.charAt(i)) || c.charAt(i) == '_')) {
            i++;
        }
        return c.substring(0, i).toLowerCase();
    }

    private static String hash(State s) {
        List<String> sorted = new ArrayList<String>(s.lines);
        Collections.sort(sorted);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            for (String l : sorted) {
                md.update(l.getBytes("UTF-8"));
                md.update((byte) '\n');
            }
            byte[] d = md.digest();
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", d[i] & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Writes the statement's line after CQT executed it; repeats it first when that is safe. pass is
     * CQT's sql run index (sqlRunTime), ord the statement's position in the file.
     */
    public static void record(
            ConsoleDAO dao,
            Connection conn,
            String caseFile,
            int pass,
            int ord,
            Sql sql,
            Sql next,
            boolean serverMessageOn) {
        try {
            recordUnguarded(dao, conn, caseFile, pass, ord, sql, next, serverMessageOn);
        } catch (Throwable t) {
            // The hook must never change what CQT does: an error here loses this statement's record only. The
            // record that says so keeps the file's count of records honest.
            sql.setPerf(null);
            hookErrors++;
            if (hookErrors <= 5) {
                System.err.println("[PerfRecorder] no record for " + caseFile + " #" + ord + ": " + t);
            }
            write("{\"file\":" + str(caseFile) + ",\"pass\":" + pass + ",\"ord\":" + ord + ",\"hook_error\":"
                    + str(String.valueOf(t)) + "}");
        }
    }

    private static int hookErrors = 0;

    private static void recordUnguarded(
            ConsoleDAO dao,
            Connection conn,
            String caseFile,
            int pass,
            int ord,
            Sql sql,
            Sql next,
            boolean serverMessageOn) {
        State s = state(sql);
        if (s == null) {
            return;
        }
        String h = hash(s);
        StringBuilder reps = new StringBuilder();
        String same = "null";
        if (REPEAT > 0 && s.err == 0 && !serverMessageOn && repeatable(sql, next)) {
            boolean allSame = true;
            for (int r = 0; r < REPEAT; r++) {
                Sql copy =
                        new Sql(
                                sql.getConnId(),
                                sql.getScript(),
                                sql.getParamList(),
                                sql.getType() == Sql.TYPE_CALL);
                dao.execute(conn, copy, false);
                State c = state(copy);
                if (c == null) {
                    break;
                }
                if (reps.length() > 0) {
                    reps.append(',');
                }
                reps.append(c.nanos);
                allSame &= c.err == 0 && c.rows == s.rows && hash(c).equals(h);
            }
            same = allSame ? "true" : "false";
        }
        StringBuilder j = new StringBuilder(256);
        j.append("{\"file\":").append(str(caseFile));
        j.append(",\"pass\":").append(pass);
        j.append(",\"ord\":").append(ord);
        j.append(",\"conn\":").append(str(sql.getConnId()));
        j.append(",\"type\":")
                .append(
                        str(
                                sql.getType() == Sql.TYPE_CALL
                                        ? "call"
                                        : sql.getType() == Sql.TYPE_PRE_STMT ? "prep" : "stmt"));
        j.append(",\"kind\":").append(str(kind(sql.getScript())));
        j.append(",\"ns\":").append(s.nanos);
        j.append(",\"rep_ns\":[").append(reps).append(']');
        j.append(",\"rep_same\":").append(same);
        j.append(",\"rows\":").append(s.hasRows ? s.rows : -1);
        j.append(",\"hash\":").append(str(h));
        j.append(",\"err\":").append(s.err);
        j.append(",\"plan\":").append(sql.isQueryplan() || sql.isFullplan() || sql.isJoingraph());
        j.append('}');
        write(j.toString());
        sql.setPerf(null);
    }

    /** One line per file pass: how many statements the file has, to tell an aborted file apart. */
    public static void fileEnd(String caseFile, int pass, int statements, long millis) {
        if (!ENABLED) {
            return;
        }
        write(
                "{\"file\":"
                        + str(caseFile)
                        + ",\"pass\":"
                        + pass
                        + ",\"end\":true,\"statements\":"
                        + statements
                        + ",\"ms\":"
                        + millis
                        + "}");
    }

    private static synchronized void write(String line) {
        try {
            if (writer == null) {
                OUT.getParentFile().mkdirs();
                writer =
                        new BufferedWriter(
                                new OutputStreamWriter(new FileOutputStream(OUT, true), "UTF-8"));
            }
            writer.write(line);
            writer.write('\n');
            writer.flush();
        } catch (IOException e) {
            System.err.println("[PerfRecorder] cannot write " + OUT + ": " + e.getMessage());
        }
    }

    private static String str(String v) {
        if (v == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(v.length() + 2).append('"');
        for (int i = 0; i < v.length(); i++) {
            char ch = v.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
