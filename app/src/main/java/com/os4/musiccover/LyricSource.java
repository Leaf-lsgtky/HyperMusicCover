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

    interface Callback {
        /** Always called on the main thread. lines is never null; empty means nothing was found. */
        void onLines(List<LyricLine> lines, String why);
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
            String lyric = o.optString("lyric", "");
            String raw = o.optString("rawLyric", "");
            if (TIMED.matcher(lyric).find()) {
                return lyric;
            }
            if (TIMED.matcher(raw).find()) {
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
            String raw = new org.json.JSONObject(json).optString("rawLyric", "");
            // Not tested against TIMED: word-timed formats (YRC, QRC, TTML) do not use LRC's
            // [mm:ss] tags at all. Whether it is usable is decided by parsing it.
            return raw.trim().isEmpty() ? null : raw;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * One directory per platform, each keyed by that platform's own song id.
     *
     * Which directory an id belongs in cannot be read off the number - a NetEase id and an Apple
     * id are both plausible-looking integers - so the owning package is asked first and the rest
     * are tried in order. Measured on this device: Apple Music publishes its store id in
     * MEDIA_ID, and looking that up under ncm-lyrics finds nothing, which is exactly what a
     * wrong-directory lookup looks like.
     */
    private static String[] dirsFor(MediaController c) {
        String pkg = c == null ? "" : c.getPackageName();
        if (pkg.contains("apple")) {
            return new String[]{"am-lyrics", "ncm-lyrics", "qq-lyrics"};
        }
        if (pkg.contains("spotify")) {
            return new String[]{"spotify-lyrics", "ncm-lyrics", "am-lyrics"};
        }
        return new String[]{"ncm-lyrics", "am-lyrics", "qq-lyrics"};
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

        // The player's own lyrics first. Everything the id route below cannot do - a player that
        // publishes no id, a song no online database has, a mirror that is down - is something
        // this route does not need: the lyrics came in with the session, and for a local-file
        // player they are the file's own.
        final String info = lyricInfoOf(c);
        final String text = textOfLyricInfo(info);
        final String raw = rawOfLyricInfo(info);
        if (text != null || raw != null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    List<LyricLine> lines;
                    String why;
                    try {
                        // The word-timed copy when there is one: "lyric" is the display form and
                        // is often line-timed even when "rawLyric" has every word's timing, and
                        // preferring it left a word-timed song with no word fill at all.
                        lines = java.util.Collections.emptyList();
                        String used = "lyric";
                        if (raw != null) {
                            List<LyricLine> r = LyricParse.parse(raw);
                            for (LyricLine l : r) {
                                if (l.hasWords()) {
                                    lines = r;
                                    used = "rawLyric";
                                    break;
                                }
                            }
                        }
                        if (lines.isEmpty() && text != null) lines = LyricParse.parse(text);
                        why = lines.isEmpty() ? "lyricInfo parsed to nothing"
                                : lines.size() + " lines from the player's own lyricInfo (" + used + ")";
                    } catch (Throwable t) {
                        Xp.log("[MCLyric] lyricInfo parse failed: " + t);
                        lines = java.util.Collections.emptyList();
                        why = "lyricInfo parse error";
                    }
                    Xp.log("[MCLyric] " + pkg + " -> " + why);
                    onMain(cb, lines, why);
                }
            }, "MCLyricParse").start();
            return;
        }

        final String id = idOf(c);
        if (id == null) {
            Xp.log("[MCLyric] " + pkg + " publishes neither lyricInfo nor a song id");
            onMain(cb, java.util.Collections.<LyricLine>emptyList(),
                    "nothing to read from " + pkg);
            return;
        }
        load(id, dirsFor(c), pkg, cb);
    }

    /**
     * The same thing for an id that came from somewhere other than a session - which is how the
     * effect gets tested without depending on either the network picking a song we have, or the
     * playing app publishing anything.
     */
    static void loadById(final String id, final boolean apple, final Callback cb) {
        load(id, apple
                ? new String[]{"am-lyrics", "ncm-lyrics"}
                : new String[]{"ncm-lyrics", "am-lyrics"}, "id " + id, cb);
    }

    private static void load(final String id, final String[] dirs, final String who,
                             final Callback cb) {
        new Thread(new Runnable() {
            @Override
            public void run() {
                List<LyricLine> lines = java.util.Collections.emptyList();
                String why;
                try {
                    Answer a = null;
                    String foundIn = null;
                    for (String dir : dirs) {
                        a = fetch(dir, id);
                        if (a.status == FOUND) {
                            foundIn = dir;
                            break;
                        }
                        // A 404 means this directory is not the one this id belongs to, so the
                        // next is worth asking. An unreachable mirror means the network, and
                        // asking a second directory would only spend another timeout to learn
                        // nothing.
                        if (a.status == UNREACHABLE) {
                            break;
                        }
                    }
                    if (a == null || a.status != FOUND) {
                        why = "not in the database (" + id + ")";
                    } else {
                        lines = LyricParse.parse(a.body);
                        why = lines.isEmpty()
                                ? "parsed to nothing (" + foundIn + ")"
                                : lines.size() + " lines from " + foundIn;
                    }
                } catch (Throwable t) {
                    Xp.log("[MCLyric] load failed: " + t);
                    why = "error";
                }
                Xp.log("[MCLyric] " + who + " id=" + id + " -> " + why);
                onMain(cb, lines, why);
            }
        }, "MCLyricSource").start();
    }

    private static void onMain(final Callback cb, final List<LyricLine> lines,
                               final String why) {
        Main.main().post(new Runnable() {
            @Override
            public void run() {
                cb.onLines(lines, why);
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
