package com.os4.musiccover;

import android.media.MediaMetadata;
import android.media.session.MediaController;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Lyrics for a song nobody published an id for: found by name, fetched from NetEase.
 *
 * This is the last of the three sources and the only one that covers a player which publishes
 * nothing but a title. The other two need something from the session - the player's own
 * lyricInfo, or a MEDIA_ID the AMLL database happens to be keyed by - and measured on this
 * device the two together cover two of the four installed players. NetEase official and Salt
 * publish neither, and no amount of waiting makes an id appear.
 *
 * The reason this is worth a network round trip at all is coverage, and the numbers are not
 * close: AMLL is a community-built set of about 69,000 songs, and both of the first two songs
 * tried against it on this device - neither of them obscure - were 404s. NetEase is the whole
 * catalogue. Its search and lyric endpoints are the ones its old web client used: plain GETs,
 * no signature, no cookie, no account. Measured from this phone, search answers in ~250ms and
 * the lyrics in ~110ms on a reused connection, so ~320ms end to end - faster, as it happens,
 * than the GitHub mirrors the id route races.
 *
 * What it buys with that is a matching problem the id route does not have. An id is exact; a
 * name is not, and the wrong lyrics are worse than none - they scroll, they are confidently
 * mistimed, and there is nothing on screen to say they belong to another song. So the rule
 * here is that a candidate has to be proven, not merely ranked: see pick().
 */
final class NcmLyrics {

    private NcmLyrics() {
    }

    /** The old web client's endpoints. No key, no signature - but also no promise they stay. */
    private static final String SEARCH =
            "https://music.163.com/api/search/get?s=%s&type=1&limit=10";
    private static final String LYRIC =
            "https://music.163.com/api/song/lyric/v1?id=%s&cp=false&lv=0&kv=0&tv=0&rv=0&yv=0"
                    + "&ytv=0&yrv=0";

    /**
     * How far a candidate's duration may sit from the session's and still be the same recording.
     *
     * Tight on purpose, because duration is what carries the whole match. Measured on this
     * device, Salt playing a local file reported 187675ms and NetEase's entry for the same
     * recording said 187675ms - identical to the millisecond - while the studio version of the
     * same song, same artists, same title, is 162586ms. A window of a few seconds tells those
     * apart; a window of thirty would not, and would hand the lock screen a live take's timings
     * over a studio recording.
     */
    private static final long DURATION_SLACK_MS = 3000L;

    /** What the session says about the song, reduced to the four things a match can use. */
    static final class Query {
        final String title;
        final String artist;
        final String album;
        final long durationMs;

        Query(String title, String artist, String album, long durationMs) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.durationMs = durationMs;
        }

        /** Stable across a track's lifetime, so it can key a cache. */
        String key() {
            return title + '|' + artist + '|' + album + '|' + durationMs;
        }

        @Override
        public String toString() {
            return "title=" + q(title) + " artist=" + q(artist) + " album=" + q(album)
                    + " dur=" + durationMs;
        }

        private static String q(String s) {
            return s == null || s.isEmpty() ? "(none)" : '"' + s + '"';
        }
    }

    /** The session's metadata, read out and handed to build() to be made searchable. */
    static Query queryOf(MediaController c) {
        if (c == null) {
            return null;
        }
        MediaMetadata md;
        try {
            md = c.getMetadata();
        } catch (Throwable t) {
            return null;
        }
        if (md == null) {
            return null;
        }
        String title = str(md, MediaMetadata.METADATA_KEY_TITLE);
        String artist = str(md, MediaMetadata.METADATA_KEY_ARTIST);
        String album = str(md, MediaMetadata.METADATA_KEY_ALBUM);
        if (artist.isEmpty()) {
            artist = str(md, MediaMetadata.METADATA_KEY_ALBUM_ARTIST);
        }
        long dur = 0L;
        try {
            dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION);
        } catch (Throwable ignored) {
        }
        return build(title, artist, album, dur);
    }

    /**
     * The four raw fields, rearranged into something searchable.
     *
     * Split out from queryOf so the diagnostic can drive it with fields typed by hand: this is
     * where every player-specific quirk lives, so it is the part a "no lyrics for this song"
     * report needs to be able to re-run.
     */
    static Query build(String title, String artist, String album, long dur) {
        title = title == null ? "" : title.trim();
        artist = artist == null ? "" : artist.trim();
        album = album == null ? "" : album.trim();
        // Salt Player publishes "Artist - Song" in ARTIST and leaves TITLE empty, so the song
        // name is in there and nowhere else. The first " - " splits it: a dash inside the song
        // name ("i'm so tired... (Stripped - Live in LA)") comes after the one that matters, and
        // taking the first keeps the whole remainder as the title.
        int dash = artist.indexOf(" - ");
        if (dash > 0) {
            String head = artist.substring(0, dash).trim();
            String tail = artist.substring(dash + 3).trim();
            if (!head.isEmpty() && !tail.isEmpty()) {
                if (title.isEmpty()) {
                    title = tail;
                    artist = head;
                } else if (tail.equals(title) || tail.equals(album)) {
                    // The tail is only repeating what we already know; drop it so the search
                    // gets a clean artist. An artist whose own name contains a dash and whose
                    // title is published properly keeps it.
                    artist = head;
                }
            }
        }
        // Only now, and only as a last resort: a single's album is the single, which is true
        // often enough to be worth trying and not often enough to be tried first. It was tried
        // first once, and Salt playing 逃跑计划's 夜空中最亮的星 off the album 世界 went looking
        // for a song called 世界 and found nothing.
        if (title.isEmpty()) {
            title = album;
        }
        if (title.isEmpty() && artist.isEmpty()) {
            return null;
        }
        return new Query(title, artist, album, dur);
    }

    private static String str(MediaMetadata md, String key) {
        try {
            String s = md.getString(key);
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }

    /** What came back for one song: the timed body, and the translation if there is one. */
    static final class Found {
        final String id;
        final String body;
        /** A plain LRC of the translated lines, or null. Merged in by the parser. */
        final String translation;
        /** True when body is word-timed yrc rather than line-timed lrc. */
        final boolean words;

        Found(String id, String body, String translation, boolean words) {
            this.id = id;
            this.body = body;
            this.translation = translation;
            this.words = words;
        }
    }

    /**
     * Cache, keyed by the session's own description of the track.
     *
     * Misses are cached as well as hits, and that is the more useful half: a song NetEase does
     * not have is looked up once and then costs nothing for the rest of its play, where without
     * this every screen-on would spend the round trip again to learn the same thing. Small
     * because the entries are whole lyric files and this lives in SystemUI.
     */
    private static final int CACHE_MAX = 16;
    private static final Map<String, Found> CACHE =
            new LinkedHashMap<String, Found>(CACHE_MAX + 1, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Found> eldest) {
                    return size() > CACHE_MAX;
                }
            };

    /** A miss, so the map can hold "asked, nothing there" as distinct from "never asked". */
    private static final Found NONE = new Found(null, null, null, false);

    /**
     * Search, match, fetch. Blocking: callers are already on a worker.
     *
     * Returns null when nothing could be proven to be this song - which is a normal outcome and
     * not an error, and is cached as such.
     */
    static Found load(Query q) {
        if (q == null) {
            return null;
        }
        String key = q.key();
        synchronized (CACHE) {
            Found hit = CACHE.get(key);
            if (hit != null) {
                Xp.log("[MCNcm] cached: " + (hit == NONE ? "no match" : hit.id));
                return hit == NONE ? null : hit;
            }
        }
        Found got = null;
        try {
            got = fetch(q);
        } catch (Throwable t) {
            Xp.log("[MCNcm] failed: " + t);
        }
        // A thrown request is not cached: the next attempt may be on a working network, and
        // caching the network's bad minute as "this song has no lyrics" would outlast it.
        if (got != null || !networkFailed) {
            synchronized (CACHE) {
                CACHE.put(key, got == null ? NONE : got);
            }
        }
        return got;
    }

    /** Set by the last request to fail on the network rather than on its answer. */
    private static volatile boolean networkFailed;

    private static Found fetch(Query q) throws Exception {
        networkFailed = false;
        String terms = terms(q);
        if (terms.isEmpty()) {
            return null;
        }
        long started = android.os.SystemClock.uptimeMillis();
        String json = get(String.format(SEARCH, URLEncoder.encode(terms, "UTF-8")));
        if (json == null) {
            return null;
        }
        String id = pick(json, q);
        if (id == null) {
            Xp.log("[MCNcm] no candidate matched " + q + " (searched \"" + terms + "\")");
            return null;
        }
        String lyric = get(String.format(LYRIC, id));
        if (lyric == null) {
            return null;
        }
        org.json.JSONObject o = new org.json.JSONObject(lyric);
        String yrc = body(o, "yrc");
        String lrc = body(o, "lrc");
        String tlyric = body(o, "tlyric");
        String use = yrc != null ? yrc : lrc;
        if (use == null) {
            Xp.log("[MCNcm] " + id + " has no lyrics at all");
            return null;
        }
        Xp.log("[MCNcm] " + id + " -> " + (yrc != null ? "yrc" : "lrc") + " " + use.length()
                + " chars" + (tlyric != null ? " + translation" : "") + " in "
                + (android.os.SystemClock.uptimeMillis() - started) + "ms");
        return new Found(id, use, tlyric, yrc != null);
    }

    /** One of the response's lyric slots, or null when it is absent or empty. */
    private static String body(org.json.JSONObject o, String field) {
        org.json.JSONObject slot = o.optJSONObject(field);
        if (slot == null) {
            return null;
        }
        String s = slot.optString("lyric", "");
        return s.trim().isEmpty() ? null : s;
    }

    /** Title and artist, which is what the search endpoint ranks on. */
    private static String terms(Query q) {
        StringBuilder sb = new StringBuilder();
        if (!q.title.isEmpty()) {
            sb.append(q.title);
        }
        if (!q.artist.isEmpty()) {
            // Only the first credited name: "LAUV/Troye Sivan" as a whole matches nothing, and
            // the search ranks on the primary artist anyway.
            String first = q.artist;
            int slash = first.indexOf('/');
            if (slash > 0) {
                first = first.substring(0, slash);
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(first.trim());
        }
        return sb.toString().trim();
    }

    /**
     * Which of the search results is this recording - or none of them.
     *
     * The title has to match. This started out the other way round, scoring duration alone on
     * the argument that titles differ in punctuation and capitalisation while a duration is
     * exact - and it picked the wrong song the first time it met a real one. Playing 邓紫棋's
     * 新的心跳, the candidates included the title track at 561ms off and 多远都要在一起 - a
     * different song from the same album - at 120ms off. Duration alone took the closer number
     * and put the wrong lyrics on screen. Two recordings of the same song are indeed never three
     * seconds apart, but two tracks on one album routinely are, and the album name cannot
     * separate them because it is the same album.
     *
     * So the order is: title first, then album, then duration as the tie-break. Titles are
     * compared with everything that varies between catalogues removed - case, spacing,
     * punctuation, brackets - and an exact match outranks one string containing the other, which
     * is what keeps the studio take ahead of "...（翻自 Lauv）" and "..._Purplepick" when all
     * three are the same length.
     *
     * Duration stays a hard gate rather than a score: outside the window a candidate is not
     * considered at all, whatever its title says. That is what separates a live take from its
     * studio version - same title, same artists, 25 seconds apart.
     *
     * Nothing matching is a real answer. The caller shows no lyrics, which is what it did
     * before this source existed.
     */
    private static String pick(String json, Query q) {
        if (q.durationMs <= 0) {
            // Without a duration there is nothing here that can prove a match, and the
            // first search result is a guess, not an answer.
            Xp.log("[MCNcm] session publishes no duration; not guessing");
            return null;
        }
        org.json.JSONArray songs;
        try {
            songs = new org.json.JSONObject(json).getJSONObject("result").getJSONArray("songs");
        } catch (Throwable t) {
            return null;
        }
        String wanted = norm(q.title);
        if (wanted.isEmpty()) {
            Xp.log("[MCNcm] session publishes no title to match on");
            return null;
        }
        String best = null;
        int bestScore = 0;
        long bestDiff = Long.MAX_VALUE;
        for (int i = 0; i < songs.length(); i++) {
            org.json.JSONObject s = songs.optJSONObject(i);
            if (s == null) {
                continue;
            }
            long dur = s.optLong("duration", 0L);
            if (dur <= 0) {
                continue;
            }
            long diff = Math.abs(dur - q.durationMs);
            if (diff > DURATION_SLACK_MS) {
                continue;
            }
            int score = titleScore(wanted, norm(s.optString("name", "")));
            if (score == 0) {
                continue;
            }
            org.json.JSONObject al = s.optJSONObject("album");
            if (!q.album.isEmpty() && al != null
                    && norm(q.album).equals(norm(al.optString("name", "")))) {
                score++;
            }
            if (best == null || score > bestScore || (score == bestScore && diff < bestDiff)) {
                best = String.valueOf(s.optLong("id", 0L));
                bestScore = score;
                bestDiff = diff;
            }
        }
        if (best != null) {
            Xp.log("[MCNcm] matched " + best + " (score " + bestScore + ", " + bestDiff
                    + "ms off)");
        }
        return best;
    }

    /** 4 for the same title, 2 when one contains the other, 0 when they are unrelated. */
    private static int titleScore(String wanted, String got) {
        if (got.isEmpty()) {
            return 0;
        }
        if (wanted.equals(got)) {
            return 4;
        }
        return wanted.contains(got) || got.contains(wanted) ? 2 : 0;
    }

    /**
     * A title with everything that varies between catalogues taken out.
     *
     * Case, spaces, punctuation and brackets all differ for the same song depending on who typed
     * it in - "i'm so tired... (Stripped - Live in LA)" against "i'm so tired...(Stripped - Live
     * in LA)" is the same recording with one space missing. What is left is letters and digits,
     * in order, which is enough to tell two titles apart and not enough to be upset by a comma.
     */
    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isLetterOrDigit(ch)) {
                sb.append(Character.toLowerCase(ch));
            }
        }
        return sb.toString();
    }

    /**
     * One GET, with the connection left open for the next.
     *
     * disconnect() is deliberately not called: both requests here go to the same host, and
     * leaving the connection in the keep-alive pool is what makes the second one cost 48-66ms
     * instead of the ~250ms the first one does. The saving is the DNS lookup, the TCP handshake
     * and the TLS handshake, measured at ~50ms of TLS alone on this device.
     */
    private static String get(String url) throws Exception {
        long started = android.os.SystemClock.uptimeMillis();
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(4000);
            conn.setReadTimeout(6000);
            conn.setRequestProperty("User-Agent", "HyperMusicCover");
            int code = conn.getResponseCode();
            if (code != 200) {
                Xp.log("[MCNcm] HTTP " + code + " in "
                        + (android.os.SystemClock.uptimeMillis() - started) + "ms");
                networkFailed = true;
                return null;
            }
            return read(conn.getInputStream());
        } catch (Throwable t) {
            Xp.log("[MCNcm] request failed after "
                    + (android.os.SystemClock.uptimeMillis() - started) + "ms: " + t);
            networkFailed = true;
            // Only a connection that failed is torn down; a healthy one stays pooled.
            if (conn != null) {
                try {
                    conn.disconnect();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream(32768);
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        // Read to the end and closed, not disconnected: that is the condition for the socket to
        // go back to the pool rather than be thrown away.
        in.close();
        return new String(out.toByteArray(), "UTF-8");
    }

    /**
     * Diagnostics: what the match would do for a session's metadata, without touching the view.
     *
     * Takes the four fields rather than reading them, so a report of "this song got no lyrics"
     * can be reproduced without owning the song or the player - the fields are all the matcher
     * ever sees, and a metadump from the reporter carries them.
     */
    static String describe(String title, String artist, String album, long durationMs) {
        Query q = build(title, artist, album, durationMs);
        return q == null ? "nothing to search for" : describe(q);
    }

    /** Diagnostics: what the match would do for the playing session, without touching the view. */
    static String describe(MediaController c) {
        Query q = queryOf(c);
        if (q == null) {
            return "no session / no metadata";
        }
        return describe(q);
    }

    private static String describe(Query q) {
        StringBuilder sb = new StringBuilder(q.toString());
        sb.append("\n  search terms: \"").append(terms(q)).append('"');
        try {
            String json = get(String.format(SEARCH, URLEncoder.encode(terms(q), "UTF-8")));
            if (json == null) {
                return sb.append("\n  search failed").toString();
            }
            org.json.JSONArray songs =
                    new org.json.JSONObject(json).getJSONObject("result").getJSONArray("songs");
            for (int i = 0; i < songs.length(); i++) {
                org.json.JSONObject s = songs.optJSONObject(i);
                if (s == null) {
                    continue;
                }
                long dur = s.optLong("duration", 0L);
                sb.append("\n  ").append(s.optLong("id", 0L)).append(' ').append(dur)
                        .append("ms (").append(dur - q.durationMs).append(") ")
                        .append(s.optString("name", ""));
                org.json.JSONObject al = s.optJSONObject("album");
                if (al != null) {
                    sb.append(" | ").append(al.optString("name", ""));
                }
            }
            sb.append("\n  -> picked ").append(pick(json, q));
        } catch (Throwable t) {
            sb.append("\n  ").append(t);
        }
        return sb.toString();
    }
}
