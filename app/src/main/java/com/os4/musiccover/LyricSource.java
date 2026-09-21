package com.os4.musiccover;

import android.media.MediaMetadata;
import android.media.session.MediaController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Where the lyric rows come from.
 *
 * Deliberately not a lyric module's API. The modules that do this on this device reach the
 * players by hooking them - and on this device all three of the players were broken at once by
 * app updates the module had not caught up with: a NoSuchFieldError on Apple Music, a
 * NoSuchClassError on Salt, a DexKit cache miss on NetEase. Nothing that inherits that is worth
 * building on.
 *
 * This route never touches the player. A song id comes out of the media session, and the AMLL
 * database - a community-built set of word-level TTML files, one per song, named by the
 * platform's own id - is asked for that id directly. Nothing here breaks when a player updates,
 * because nothing here knows a player's internals.
 *
 * What it does inherit is coverage and the network. Tens of thousands of songs against millions
 * of songs means a miss is normal, and the mirrors are GitHub-hosted, which from here is a coin
 * toss: measured on this device the authoritative copy has answered in 610ms and has also timed
 * out after 23s. Both are handled by being explicit rather than optimistic - a miss leaves the
 * lock screen without lyrics, which is what it had before this existed, and no request is given
 * long enough to be felt.
 */
final class LyricSource {

    /** Nothing was found, by any route. */
    static final int SRC_NONE = 0;
    /** The player published the whole lyric itself, or a provider module wrote it to the session. */
    static final int SRC_LYRIC_INFO = 1;
    /** The AMLL database, keyed by the platform's song id. */
    static final int SRC_DATABASE = 2;
    /** Found by name on NetEase - the fallback for a session carrying neither of the above. */
    static final int SRC_NETEASE = 3;

    interface Callback {
        /**
         * Always called on the main thread. lines is never null; empty means nothing was found.
         *
         * `source` is one of the SRC_ constants and is not decoration: the session's own lyrics
         * can arrive after the lyrics we settled for, and the caller upgrades to them when they
         * do - which it can only do if it knows what it is currently showing.
         */
        void onLines(List<LyricLine> lines, String why, int source);
    }

    /**
     * Whether the session is carrying a lyric worth using right now.
     *
     * Cheap enough to ask on every metadata change, which is what the caller does. A provider
     * module cannot write its lyric until the player has told it what is playing, so the session
     * routinely publishes lyricInfo a moment - or several seconds - after the track itself.
     * Before that, the field is either absent or an explicit empty shell: MeiLoX publishes
     * {"lyric":"","noLyric":true} while it is still looking.
     */
    static boolean hasLyricInfo(MediaController c) {
        String info = lyricInfoOf(c);
        if (info == null) {
            return false;
        }
        return textOfLyricInfo(info) != null || rawOfLyricInfo(info) != null;
    }

    private LyricSource() {
    }

    /**
     * The key OPlus's own lock screen reads its lyrics from, as a JSON string on the session's
     * metadata.
     *
     * This is the primary source and the ID lookup below is the fallback, because this one has
     * none of the ID route's problems: the lyrics are the player's own - for a local-file player
     * they are the file's own lyrics, which is the only thing that can be right for music that
     * is not in any online database - and they arrive with the session instead of after a network
     * round trip, so there is no coverage to miss and no mirror to be down.
     *
     * Nothing here talks to a player. The contract is a JSON object with a timed LRC string under
     * "lyric" (or, for word-level payloads, under "rawLyric"), plus songName/artist/album/ and a
     * translation under one of several names. It is written by whoever has the lyrics - the
     * player itself, or one of the provider modules that hook the players that do not.
     */
    private static final String KEY_LYRIC_INFO = "lyricInfo";

    /** LRC-style timing, bracketed with [] or <>. <> is the word-level (enhanced) form. */
    private static final java.util.regex.Pattern TIMED =
            java.util.regex.Pattern.compile("[\\[<][0-9]{1,3}:[0-9]{2}(?:[.:][0-9]{1,3})?[\\]>]");

    /**
     * The player's own lyrics, if the session carries them. Null when nothing has published any,
     * which is the normal state on a device with no provider modules installed.
     */
    // "lyricInfo" is not one of the framework's metadata keys and is not meant to be: it is the
    // players' and provider modules' own, and a Bundle lookup by any string is valid.
    @android.annotation.SuppressLint("WrongConstant")
    static String lyricInfoOf(MediaController c) {
        if (c == null) {
            return null;
        }
        try {
            MediaMetadata md = c.getMetadata();
            if (md == null) {
                return null;
            }
            return md.getString(KEY_LYRIC_INFO);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * The timed lyric text out of that payload, or null if there is none worth rendering.
     *
     * A payload without timing is rejected rather than shown: this view follows the singing, and
     * untimed text has nothing to follow. That is also the difference between the two fields -
     * "lyric" is the display form and is preferred, "rawLyric" is kept verbatim for word-level
     * renderers and is only used when "lyric" carries no timing at all.
     */
    static String textOfLyricInfo(String json) {
        if (json == null || json.isEmpty()) {
            return null;
        }
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            String lyric = jsonString(o, "lyric");
            String raw = jsonString(o, "rawLyric");
            if (lyric != null && TIMED.matcher(lyric).find()) {
                return lyric;
            }
            if (raw != null && TIMED.matcher(raw).find()) {
                return raw;
            }
            return null;
        } catch (Throwable t) {
            Xp.log("[MCLyric] lyricInfo is not the expected JSON: " + t);
            return null;
        }
    }

    /** The payload's rawLyric, verbatim - the form that keeps word timings, when there are any. */
    static String rawOfLyricInfo(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            String raw = jsonString(new org.json.JSONObject(json), "rawLyric");
            // Not tested against TIMED: word-timed formats (YRC, QRC, TTML) do not use LRC's
            // [mm:ss] tags at all. Whether it is usable is decided by parsing it.
            return raw == null || raw.trim().isEmpty() ? null : raw;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * A string field of a payload, or null when it is absent, null, or not a string at all.
     *
     * Not optString(key, ""), which answers a different question on Android than it does on the
     * JVM: there a JSON null is a sentinel object and optString returns String.valueOf(that) -
     * the string "null" - where the fallback was asked for. A player publishing an empty shell
     * while it looks the lyrics up writes exactly that, and reading it as a lyric whose text is
     * "null" made hasLyricInfo() report that the session had one when it did not, which is the
     * signal the caller uses to decide whether the lyrics it settled for are worth replacing.
     * The same rule, and the same trap, cost the NetEase route a whole song: see NcmLyrics.str.
     */
    private static String jsonString(org.json.JSONObject o, String key) {
        Object v = o.opt(key);
        return v instanceof String ? (String) v : null;
    }

    /**
     * The one directory this player's ids belong in.
     *
     * One, not a list to work through. An id is only meaningful inside its own platform's
     * namespace, and the package says which platform that is - Apple Music publishes an Apple
     * store id, a NetEase client a NetEase id. Asking the other directories for it cannot
     * succeed on purpose and can succeed by accident: the numbers are plain integers in the same
     * range, so a collision returns a real, well-formed, completely unrelated song's lyrics with
     * nothing to mark them as wrong. Silently wrong beats nothing only in a bug report.
     *
     * It was a list, and the cost was not only the risk: three directories meant three rounds of
     * racing four mirrors each, every one of them a guaranteed miss for the two wrong ones, and
     * the fallback below did not even start until they had all finished. That is the "lyrics take
     * a while to turn up" this fixed.
     */
    private static String dirFor(MediaController c) {
        String pkg = c == null ? "" : c.getPackageName();
        if (pkg.contains("apple")) {
            return "am-lyrics";
        }
        if (pkg.contains("spotify")) {
            return "spotify-lyrics";
        }
        if (pkg.contains("qqmusic")) {
            return "qq-lyrics";
        }
        // MeiLoX is a NetEase client under its own name and publishes NetEase ids - measured,
        // not assumed: MEDIA_ID 2717588324 with cover art from p2.music.126.net.
        if (pkg.contains("netease") || pkg.contains("cloudmusic") || pkg.contains("meilox")) {
            return "ncm-lyrics";
        }
        // Everyone else: no directory, so the database is not asked at all.
        //
        // The database has four directories and they are four platforms' id spaces. A player
        // outside them - Kugou, Qishui, Salt, Mi Music - publishes ids that mean nothing in any
        // of them, so a lookup is a wasted request at best and a wrong song at worst: the ids
        // are plain integers in overlapping ranges, and a collision returns a real, well-formed,
        // unrelated lyric with nothing about it to look wrong. Falling back to ncm-lyrics for
        // unknown packages did exactly that. These players go to the by-name route, which is
        // where they were always going to end up.
        return null;
    }

    /**
     * Every mirror is asked at once and the first one to have the file wins.
     *
     * Not a chain, which is what this started as and what the log showed the cost of: raw timed
     * out after 23s, ghfast after 16s, and only then did jsdelivr answer - so a song that was in
     * the database took the better part of a minute to appear, if the screen was still locked.
     * Racing them costs the same bandwidth as the chain's worst case and the latency of its
     * best. They are all the same file; there is nothing to be gained by preferring one.
     *
     * gitmirror is the exception and is kept last for the record: it does not resolve from this
     * network at all, which costs nothing to find out in parallel and would have cost the whole
     * timeout budget in a chain.
     */
    private static final String[][] MIRRORS = {
            {"raw", "https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/%s/%s.ttml"},
            {"jsdelivr", "https://cdn.jsdelivr.net/gh/amll-dev/amll-ttml-db@main/%s/%s.ttml"},
            {"ghfast", "https://ghfast.top/https://raw.githubusercontent.com/amll-dev/amll-ttml-db/main/%s/%s.ttml"},
            {"gitmirror", "https://raw.gitmirror.com/amll-dev/amll-ttml-db/main/%s/%s.ttml"},
    };

    /** FOUND carries a body; MISSING is GitHub saying the file is not there; UNREACHABLE is us. */
    private static final int FOUND = 1;
    private static final int MISSING = 2;
    private static final int UNREACHABLE = 3;

    /** Package-private so the probe can read the status as well as the body. */
    static final class Answer {
        final int status;
        final String body;

        Answer(int status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final ExecutorService POOL = Executors.newFixedThreadPool(4,
            new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "MCLyricFetch");
                    t.setDaemon(true);
                    return t;
                }
            });

    /**
     * The song id, if the session is publishing one.
     *
     * MEDIA_ID is the documented place, and it is what both a NetEase client and Apple Music
     * fill in - each with its own platform's id, which is exactly the key the database wants. It
     * is also frequently empty, and frequently an internal uri rather than an id, so it is only
     * taken when it is all digits. A local-file player publishes no id at all, and there is
     * nothing to guess from.
     */
    static String idOf(MediaController c) {
        if (c == null) {
            return null;
        }
        try {
            MediaMetadata md = c.getMetadata();
            if (md == null) {
                return null;
            }
            String id = md.getString(MediaMetadata.METADATA_KEY_MEDIA_ID);
            if (id == null || id.isEmpty()) {
                return null;
            }
            for (int i = 0; i < id.length(); i++) {
                if (!Character.isDigit(id.charAt(i))) {
                    return null;
                }
            }
            return id;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Fetch and parse, on a worker, then hand the rows to the main thread.
     *
     * The caller is a track change, i.e. SystemUI's main thread in the middle of a transition
     * that is already doing the cover and the clock - so nothing here touches the network or
     * parses a few tens of kilobytes on it.
     */
    static void load(final MediaController c, final Callback cb) {
        final String pkg = c == null ? "?" : c.getPackageName();

        final String info = lyricInfoOf(c);
        final String dir = dirFor(c);
        // An id is only worth having when there is a directory it belongs to; without one it
        // cannot be looked up anywhere, and pretending otherwise is how a lookup lands in the
        // wrong platform's id space.
        final String id = dir == null ? null : idOf(c);
        final NcmLyrics.Query q = NcmLyrics.queryOf(c);
        if (info == null && id == null && q == null) {
            Xp.log("[MCLyric] " + pkg + " publishes neither lyricInfo, a song id, nor a name");
            onMain(cb, java.util.Collections.<LyricLine>emptyList(),
                    "nothing to read from " + pkg, SRC_NONE);
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                Rows r = new Rows();
                // Three sources, best first, each one asked only because the one before it came
                // up empty. Every step falls through rather than stopping, which is the whole
                // shape of this: a source that is present but useless - a provider module that
                // wrote a lyricInfo it could not fill, an id the database does not have - used
                // to end the search, and the song played on with nothing on screen while a
                // perfectly good answer sat one step further down.
                if (info != null) {
                    session(info, r);
                }
                if (r.lines.isEmpty() && (id != null || q != null)) {
                    race(id, dir, q, r);
                }
                Xp.log("[MCLyric] " + pkg + " -> " + r.why);
                onMain(cb, r.lines, r.why, r.source);
            }
        }, "MCLyricSource").start();
    }

    /** Lines and the one-line account of where they came from, filled in by one source. */
    private static final class Rows {
        List<LyricLine> lines = java.util.Collections.emptyList();
        String why = "nothing to read";
        int source = SRC_NONE;
    }

    /** The whole lookup, including the mirrors and a miss. Nothing is waited for past this. */
    private static final long RACE_BUDGET_MS = 8000L;

    /**
     * How long NetEase's answer waits for the database's, once it has one of its own.
     *
     * The database is the better of the two when it has the song - its files are hand-checked and
     * carry things NetEase's do not, like which voice sings which line - so it is worth a short
     * pause to let it win. Short, because it usually does not have the song: measured here a hit
     * from jsdelivr lands in ~450ms against NetEase's ~320ms, so a few hundred milliseconds is
     * enough to prefer it when it is there, and nothing like long enough to wait out a miss.
     */
    private static final long DATABASE_GRACE_MS = 400L;

    /**
     * Both routes at once, the better one preferred but not waited out.
     *
     * These used to run in order, and that is what made the lyrics late: a player that publishes
     * an id - Apple Music does - spent the database's full miss before the fallback was even
     * started, and the fallback is the one that actually had the song. Run together, a miss on
     * one costs nothing on the other.
     */
    private static void race(final String id, final String dir, final NcmLyrics.Query q,
                             Rows out) {
        final Rows db = new Rows();
        final Rows ncm = new Rows();
        // Tags rather than the rows themselves: a row says nothing about which route produced it
        // once that route has come up empty, and "which one just finished" is the whole question.
        final BlockingQueue<Integer> done = new LinkedBlockingQueue<>();
        int pending = 0;
        if (id != null) {
            pending++;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        database(id, dir, db);
                    } finally {
                        done.offer(1);
                    }
                }
            }, "MCLyricDb").start();
        }
        if (q != null) {
            pending++;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        netease(q, ncm);
                    } finally {
                        done.offer(2);
                    }
                }
            }, "MCLyricNcm").start();
        }
        boolean haveNcm = false;
        long deadline = android.os.SystemClock.uptimeMillis() + RACE_BUDGET_MS;
        while (pending > 0) {
            long left = deadline - android.os.SystemClock.uptimeMillis();
            if (left <= 0) {
                break;
            }
            Integer tag;
            try {
                tag = done.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (tag == null) {
                break;
            }
            pending--;
            if (tag == 1 && !db.lines.isEmpty()) {
                out.lines = db.lines;
                out.why = join(out.why, db.why);
                out.source = db.source;
                return;
            }
            if (tag == 2 && !ncm.lines.isEmpty()) {
                haveNcm = true;
                // Hold the answer briefly in case the database is about to beat it on quality.
                long grace = android.os.SystemClock.uptimeMillis() + DATABASE_GRACE_MS;
                if (grace < deadline) {
                    deadline = grace;
                }
            }
        }
        if (haveNcm) {
            out.lines = ncm.lines;
            out.why = join(out.why, ncm.why);
            out.source = ncm.source;
            return;
        }
        // Neither had it. Both accounts are worth keeping - which one failed and how is the
        // first thing asked of a song that showed no lyrics.
        String both = join(db.why == null || id == null ? null : db.why,
                q == null ? null : ncm.why);
        out.why = join(out.why, both == null ? "nothing found" : both);
    }

    /**
     * The lyric the session is already carrying - the player's own, or a provider module's.
     *
     * The best of the three when it is there: no network, no matching, and for a local-file
     * player it is the file's own lyric, which is the only thing that can be right for music no
     * online catalogue has.
     */
    private static void session(String info, Rows r) {
        try {
            String text = textOfLyricInfo(info);
            String raw = rawOfLyricInfo(info);
            if (text == null && raw == null) {
                // The field is there and empty, which is what a provider module publishes while
                // it is still fetching - MeiLoX writes {"lyric":"","noLyric":true}.
                r.why = "the session's lyricInfo is empty";
                return;
            }
            // The word-timed copy when there is one: "lyric" is the display form and is often
            // line-timed even when "rawLyric" has every word's timing, and preferring it left a
            // word-timed song with no word fill at all.
            String used = "lyric";
            if (raw != null) {
                List<LyricLine> w = LyricParse.parse(raw);
                for (LyricLine l : w) {
                    if (l.hasWords()) {
                        r.lines = w;
                        used = "rawLyric";
                        break;
                    }
                }
            }
            if (r.lines.isEmpty() && text != null) {
                r.lines = LyricParse.parse(text);
            }
            if (r.lines.isEmpty()) {
                r.why = "lyricInfo parsed to nothing";
                return;
            }
            r.source = SRC_LYRIC_INFO;
            r.why = r.lines.size() + " lines from the session's own lyricInfo (" + used + ")";
        } catch (Throwable t) {
            Xp.log("[MCLyric] lyricInfo parse failed: " + t);
            r.lines = java.util.Collections.emptyList();
            r.why = "lyricInfo parse error";
        }
    }

    /** The AMLL database, by platform id, in the one directory that id can belong to. */
    private static void database(String id, String dir, Rows r) {
        String before = r.why;
        try {
            Answer a = fetch(dir, id);
            if (a.status != FOUND) {
                r.why = join(before, a.status == MISSING
                        ? "not in " + dir + " (" + id + ")"
                        : "could not reach the database");
                return;
            }
            r.lines = LyricParse.parse(a.body);
            r.why = r.lines.isEmpty()
                    ? join(before, "parsed to nothing (" + dir + ") " + shape(a.body))
                    : r.lines.size() + " lines from " + dir;
            if (!r.lines.isEmpty()) r.source = SRC_DATABASE;
        } catch (Throwable t) {
            Xp.log("[MCLyric] database lookup failed: " + t);
            r.why = join(before, "database error");
        }
    }

    /** By name, from NetEase - the only source that covers a player publishing no id at all. */
    private static void netease(NcmLyrics.Query q, Rows r) {
        String before = r.why;
        try {
            NcmLyrics.Found f = NcmLyrics.load(q);
            if (f == null) {
                r.why = join(before, "no match on NetEase");
                return;
            }
            r.lines = LyricParse.parse(f.body, f.translation);
            r.why = r.lines.isEmpty()
                    ? join(before, "NetEase " + f.id + " parsed to nothing " + shape(f.body))
                    : r.lines.size() + " lines from NetEase " + f.id
                    + " (" + (f.words ? "yrc" : "lrc") + ")";
            if (!r.lines.isEmpty()) r.source = SRC_NETEASE;
        } catch (Throwable t) {
            Xp.log("[MCLyric] NetEase lookup failed: " + t);
            r.why = join(before, "NetEase error");
        }
    }

    /** Both halves of why it failed, when more than one source was asked and all came up empty. */
    private static String join(String before, String now) {
        if (before == null || "nothing to read".equals(before)) {
            return now;
        }
        return now == null ? before : before + "; " + now;
    }

    private static final int SHAPE_HEAD = 60;

    /**
     * The shape of a body that parsed to nothing: how long it is, and what it starts with.
     *
     * "Parsed to nothing" alone is the one account here that cannot be acted on - it says the
     * parser refused without saying what it refused, and the parser is never where the fault is.
     * Every other line in this file says which route failed and how, on the argument that that is
     * the first thing asked of a song showing no lyrics; this one was the exception and it cost
     * an afternoon. The body behind it for 带你飞 was the four characters "null", handed over in
     * place of the song by a platform quirk (see NcmLyrics.str), and nothing in the account could
     * say so. Quoted and kept to one line, because the probe prints the whole account inline.
     */
    private static String shape(String body) {
        if (body == null) {
            return "(no body)";
        }
        String head = body.length() > SHAPE_HEAD ? body.substring(0, SHAPE_HEAD) + "..." : body;
        return body.length() + " chars, head=\"" + head.replace("\n", "\\n").replace("\r", "")
                + '"';
    }

    /**
     * The same thing for an id that came from somewhere other than a session - which is how the
     * effect gets tested without depending on either the network picking a song we have, or the
     * playing app publishing anything.
     */
    static void loadById(final String id, final boolean apple, final Callback cb) {
        load(id, apple ? "am-lyrics" : "ncm-lyrics", "id " + id, cb);
    }

    private static void load(final String id, final String dir, final String who,
                             final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                Rows r = new Rows();
                database(id, dir, r);
                Xp.log("[MCLyric] " + who + " id=" + id + " -> " + r.why);
                onMain(cb, r.lines, r.why, r.source);
            }
        }, "MCLyricSource").start();
    }

    private static void onMain(final Callback cb, final List<LyricLine> lines,
                               final String why, final int source) {
        Main.main().post(new Runnable() {
            @Override
            public void run() {
                cb.onLines(lines, why, source);
            }
        });
    }

    /** What the mirrors said about one directory. Every mirror is asked at once. */
    static Answer fetch(String dir, String id) {
        final BlockingQueue<Answer> answers = new LinkedBlockingQueue<>();
        for (final String[] m : MIRRORS) {
            POOL.execute(new Runnable() {
                @Override
                public void run() {
                    answers.offer(one(m, dir, id));
                }
            });
        }
        boolean sawMissing = false;
        // The budget is per directory and generous only relative to the per-request timeouts,
        // which are what actually bound this: whichever mirror answers first ends it, and four
        // that cannot be reached end it at the last of them.
        long deadline = android.os.SystemClock.uptimeMillis() + 7000L;
        while (true) {
            long left = deadline - android.os.SystemClock.uptimeMillis();
            if (left <= 0) {
                break;
            }
            Answer a;
            try {
                a = answers.poll(left, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (a == null) {
                break;
            }
            if (a.status == FOUND) {
                return a;
            }
            if (a.status == MISSING) {
                sawMissing = true;
            }
        }
        // Distinguishing the two is what keeps a wrong directory from looking like a dead
        // network: a 404 means this file is not in this directory and the next one is worth
        // asking, where a timeout means asking again will only cost another timeout.
        return new Answer(sawMissing ? MISSING : UNREACHABLE, null);
    }

    private static Answer one(String[] mirror, String dir, String id) {
        String url = String.format(mirror[1], dir, id);
        long started = android.os.SystemClock.uptimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            // Short on purpose. A lyric that arrives after the lock screen has been put away is
            // worth nothing, and four mirrors running at once means the slow one is never waited
            // for anyway - these bound the failure, not the success.
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "HyperMusicCover");
            int code = conn.getResponseCode();
            long ms = android.os.SystemClock.uptimeMillis() - started;
            if (code == 404) {
                Xp.log("[MCLyric] " + mirror[0] + " -> 404 in " + ms + "ms (" + dir + ")");
                return new Answer(MISSING, null);
            }
            if (code != 200) {
                Xp.log("[MCLyric] " + mirror[0] + " -> HTTP " + code + " in " + ms + "ms");
                return new Answer(UNREACHABLE, null);
            }
            String body = read(conn.getInputStream());
            Xp.log("[MCLyric] " + mirror[0] + " -> 200, " + body.length() + " chars in " + ms
                    + "ms (" + dir + "/" + id + ")");
            return new Answer(FOUND, body);
        } catch (Throwable t) {
            Xp.log("[MCLyric] " + mirror[0] + " failed after "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms: " + t);
            return new Answer(UNREACHABLE, null);
        } finally {
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Every key the session is publishing, with enough of each value to recognise it.
     *
     * Which players this feature covers is decided entirely by what they put on the session, and
     * that cannot be reasoned about - only read. Players differ in whether they publish lyrics at
     * all, under `lyricInfo` or a key of their own, whether MEDIA_ID is the platform's id or an
     * internal uri, and whether the title is the song or (for one local player) the current lyric
     * line. So this lists the keys rather than looking for the ones already known: a key nobody
     * here has heard of is exactly what widening the coverage needs to find.
     *
     * Values are cut short and bitmaps are reduced to their size, because a lyric payload is tens
     * of kilobytes and the point is to see WHICH keys carry something, not to read the words.
     */
    // Bundle.get is deprecated in favour of the typed getters, which is exactly what this cannot
    // use: the point is to print a key whose type nobody here knows yet.
    @SuppressWarnings("deprecation")
    static String dumpMetadata(MediaController c) {
        if (c == null) return "no session being watched";
        MediaMetadata md;
        try {
            md = c.getMetadata();
        } catch (Throwable t) {
            return c.getPackageName() + ": getMetadata threw " + t;
        }
        if (md == null) return c.getPackageName() + ": no metadata";
        StringBuilder sb = new StringBuilder(c.getPackageName());
        sb.append('\n');
        java.util.Set<String> keys;
        try {
            keys = md.keySet();
        } catch (Throwable t) {
            return sb.append("keySet threw ").append(t).toString();
        }
        java.util.List<String> sorted = new java.util.ArrayList<>(keys);
        java.util.Collections.sort(sorted);
        for (String k : sorted) {
            sb.append("  ").append(k).append(" = ").append(valueOf(md, k)).append('\n');
        }
        // The session's extras, which are a second place entirely - a Bundle the player sets on
        // the session rather than on the metadata. Nothing found here yet, but "we never looked"
        // and "there is nothing there" are different answers and this is the one worth having:
        // a player that puts its lyrics here would otherwise look identical to one publishing
        // nothing at all.
        try {
            android.os.Bundle ex = c.getExtras();
            if (ex == null || ex.isEmpty()) {
                sb.append("  (session extras: none)\n");
            } else {
                for (String k : ex.keySet()) {
                    Object v = ex.get(k);
                    String s = v == null ? "null" : v.toString();
                    sb.append("  extras.").append(k).append(" = ")
                            .append(s.length() > 160 ? s.substring(0, 160) + "..." : s)
                            .append('\n');
                }
            }
        } catch (Throwable t) {
            sb.append("  (session extras threw ").append(t).append(")\n");
        }
        sb.append("  -> id=").append(idOf(c))
                .append(" lyricInfo=").append(lyricInfoOf(c) == null ? "no" : "yes")
                .append("\n  -> by name: ").append(NcmLyrics.queryOf(c));
        return sb.toString();
    }

    /** One metadata value, short enough to read and typed enough to act on. */
    private static String valueOf(MediaMetadata md, String k) {
        try {
            android.graphics.Bitmap b = md.getBitmap(k);
            if (b != null) return "Bitmap[" + b.getWidth() + "x" + b.getHeight() + "]";
        } catch (Throwable ignored) {
        }
        try {
            CharSequence cs = md.getText(k);
            if (cs != null) {
                String s = cs.toString();
                String cut = s.length() > 160 ? s.substring(0, 160) + "..." : s;
                // Newlines would break the one-key-per-line shape a reader relies on.
                return "(" + s.length() + " chars) " + cut.replace("\n", "\\n");
            }
        } catch (Throwable ignored) {
        }
        try {
            long l = md.getLong(k);
            if (l != 0L) return String.valueOf(l);
        } catch (Throwable ignored) {
        }
        return "(empty or of another type)";
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }
}
