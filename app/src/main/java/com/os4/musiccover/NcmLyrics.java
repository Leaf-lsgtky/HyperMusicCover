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
    /** The album search, and the album itself: the route taken when the song search proves nothing. */
    private static final String ALBUM_SEARCH =
            "https://music.163.com/api/search/get?s=%s&type=10&limit=10";
    private static final String ALBUM = "https://music.163.com/api/album/%s";

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

    /**
     * The window for a candidate that is on the session's own album, which is a wider one.
     *
     * The tight window above separates two recordings of a song, and the difference it is there
     * to catch is large: the live take it was measured against is 25 seconds from the studio one.
     * What it also rejects, being tight, is the same recording the session is playing - because
     * one release is pressed and mastered differently by different catalogues, and three seconds
     * is inside that noise. Measured 2026-09-20: Apple Music's 勇敢 (张惠妹) is 239964ms and every
     * copy of it on NetEase is longer, the album's own at 244746ms and two compilations at
     * 243000ms, all four with the exact title and the right artist. The song played with no lyrics
     * at all, twice over: two of the four missed the three-second window by 36 milliseconds.
     *
     * The album is what tells the two situations apart. A different take is a different release
     * and does not sit on the album the session says it is playing; a different pressing of it
     * does, and its lyrics are the same lines timed against the same performance. So a candidate
     * whose album matches gets six seconds and a candidate whose album does not is left where it
     * was, still bounded by the window that was measured against real takes of real songs.
     */
    private static final long SAME_ALBUM_SLACK_MS = 6000L;

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
        String url = String.format(SEARCH, URLEncoder.encode(terms, "UTF-8"));
        String json = get(url);
        if (json == null) {
            return null;
        }
        String id = pick(json, q);
        // The endpoint answers with songs that have nothing to do with the query for minutes at a
        // time: measured 2026-09-20, "勇敢 张惠妹" coming back as a page of FM-84 and then as itself
        // again a few minutes later, same phone, same headers, same URL. A search that proves
        // nothing is worth asking twice and no more - the miss is what gets cached, so without
        // this a bad minute on the other side costs the song its lyrics for the whole track, and
        // with it a song that genuinely is not there costs one extra request, once.
        if (id == null && q.durationMs > 0 && !norm(q.title).isEmpty()) {
            Xp.log("[MCNcm] nothing in the results for " + q + "; asking again");
            json = get(url);
            if (json == null) {
                return null;
            }
            id = pick(json, q);
        }
        if (id == null) {
            // The search answered, and what it answered with was not this song - which it also
            // does when it is about to answer with something quite different. See byAlbum.
            id = byAlbum(q);
        }
        if (id == null) {
            Xp.log("[MCNcm] nothing matched " + q + " (searched \"" + terms
                    + "\", and its album)");
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
        return slot == null ? null : str(slot, "lyric");
    }

    /**
     * A string field, or null when the field is absent, null, or not a string at all.
     *
     * Deliberately not optString(key, ""), which is not the same question on the two platforms
     * this code runs on. On the JVM a JSON null answers the fallback; on Android it is a sentinel
     * object, and optString answers String.valueOf(that) - the four characters "null" - so a
     * field that is explicitly null reads as a string that says "null".
     *
     * That is not a hypothetical: it is what this endpoint sends for the yrc slot of every song
     * whose lyric is the newer rich kind, and 带你飞 is one. The "null" came back as a lyric, and
     * being neither null nor blank it won the yrc-over-lrc choice in fetch() over the lrc beside
     * it, which held all 24 lines of the song. What the lock screen got was a four-character body
     * that parses to nothing, and because the answer is cached per track, the song played out
     * with no lyrics at all. Measured against the live response on the device, not reasoned.
     */
    private static String str(org.json.JSONObject o, String key) {
        Object v = o.opt(key);
        if (!(v instanceof String)) {
            return null;
        }
        String s = ((String) v).trim();
        return s.isEmpty() ? null : s;
    }

    /** Title and artist, which is what the search endpoint ranks on. */
    private static String terms(Query q) {
        return joined(q.title, firstArtist(q.artist));
    }

    /** The album's name and its artist, which is what the album search ranks on. */
    private static String albumTerms(Query q) {
        return joined(q.album, firstArtist(q.artist));
    }

    /** Two search terms, either of which may be missing, in the one order the endpoint wants. */
    private static String joined(String a, String b) {
        if (a == null || a.isEmpty()) {
            return b == null ? "" : b;
        }
        return b == null || b.isEmpty() ? a : a + ' ' + b;
    }

    /**
     * The first credited name out of a session's artist string.
     *
     * Only the first, for a search: "LAUV/Troye Sivan" as a whole matches nothing, and the search
     * ranks on the primary artist anyway. Whether a result is by this artist is a different
     * question and is asked of the whole string - see byArtist.
     */
    private static String firstArtist(String artist) {
        int slash = artist.indexOf('/');
        return (slash > 0 ? artist.substring(0, slash) : artist).trim();
    }

    /** The songs out of a search response, matched - or null when the response holds none. */
    private static String pick(String json, Query q) {
        try {
            return choose(new org.json.JSONObject(json).getJSONObject("result")
                    .getJSONArray("songs"), q);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Which of these songs is the session's recording - or none of them.
     *
     * Asked of both ways in: the song search's results, and an album's own track list, which is
     * the same question put to a shorter and much more certain list.
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
     * So the order is: title first, then artist, then album, then duration as the tie-break.
     * Titles are compared with everything that varies between catalogues removed - case, spacing,
     * punctuation, brackets - and an exact match outranks one string containing the other, which
     * is what keeps the studio take ahead of "...（翻自 Lauv）" and "..._Purplepick" when all
     * three are the same length.
     *
     * Duration stays a hard gate rather than a score: outside the window a candidate is not
     * considered at all, whatever its title says. That is what separates a live take from its
     * studio version - same title, same artists, 25 seconds apart. There are two windows rather
     * than one and the album picks between them, because the same release is pressed and mastered
     * differently by different catalogues; see SAME_ALBUM_SLACK_MS for the measurement.
     *
     * Nothing matching is a real answer, and a better one than the wrong song: the caller shows
     * no lyrics, which is what it did before this source existed, and byAlbum gets a second
     * chance at it.
     */
    private static String choose(org.json.JSONArray songs, Query q) {
        if (songs == null) {
            return null;
        }
        if (q.durationMs <= 0) {
            // Without a duration there is nothing here that can prove a match, and the
            // first search result is a guess, not an answer.
            Xp.log("[MCNcm] session publishes no duration; not guessing");
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
            // A result with no name is not a candidate: scored, "null" would only ever have been
            // a title that matches nothing. See str() for why it has to be asked this way.
            String name = str(s, "name");
            if (name == null) {
                continue;
            }
            int score = nameScore(wanted, norm(name));
            if (score == 0) {
                continue;
            }
            if (!byArtist(q.artist, s)) {
                continue;
            }
            org.json.JSONObject al = s.optJSONObject("album");
            String albumName = al == null ? null : str(al, "name");
            boolean sameAlbum = albumName != null && !q.album.isEmpty()
                    && norm(q.album).equals(norm(albumName));
            // Which window applies depends on the album, so the album has to be read before the
            // window rather than after it as the tie-break it used to be. See SAME_ALBUM_SLACK_MS.
            long diff = Math.abs(dur - q.durationMs);
            if (diff > (sameAlbum ? SAME_ALBUM_SLACK_MS : DURATION_SLACK_MS)) {
                continue;
            }
            if (sameAlbum) {
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

    /**
     * The same song, looked for inside the album the session names.
     *
     * The search endpoint does not always answer with what it would have answered a minute
     * earlier, and nothing in the response says which kind of answer it is. Measured 2026-09-20,
     * playing 田馥甄's 余波荡漾: the search came back as that title by other artists and as that
     * artist's other songs, with the song itself missing, while the album it is on answered for it
     * 44 milliseconds from the session's duration and carries 27 lines plus a word-timed copy of
     * them. The same query answered properly, three times running, a few minutes later.
     *
     * An album has nothing to rank, which is why it is the route taken here: ask for one by name
     * and artist and the answer is its track list, every track with its own duration. Two
     * requests, spent on the path where the answer so far is that there is nothing.
     */
    private static String byAlbum(Query q) {
        if (q.album.isEmpty() || q.title.isEmpty() || q.durationMs <= 0) {
            // Nothing to look an album up by, so this is not a second chance at anything.
            return null;
        }
        try {
            String json = get(String.format(ALBUM_SEARCH,
                    URLEncoder.encode(albumTerms(q), "UTF-8")));
            if (json == null) {
                return null;
            }
            String album = albumOf(json, q);
            if (album == null) {
                Xp.log("[MCNcm] no album of " + q.artist + "'s called " + q.album);
                return null;
            }
            json = get(String.format(ALBUM, album));
            if (json == null) {
                return null;
            }
            org.json.JSONObject found = new org.json.JSONObject(json).optJSONObject("album");
            String id = found == null ? null : choose(found.optJSONArray("songs"), q);
            Xp.log("[MCNcm] album " + album + " (" + q.album + ") -> " + id);
            return id;
        } catch (Throwable t) {
            Xp.log("[MCNcm] album lookup failed: " + t);
            return null;
        }
    }

    /**
     * Which of the album search's results is the album the session names - or none of them.
     *
     * Its name first and its artist second, the way choose() asks it of a song and for the same
     * reason: more than one artist has an album called 日常, and the session has said whose.
     */
    private static String albumOf(String json, Query q) {
        org.json.JSONArray albums;
        try {
            albums = new org.json.JSONObject(json).getJSONObject("result").getJSONArray("albums");
        } catch (Throwable t) {
            return null;
        }
        String wanted = norm(q.album);
        String best = null;
        int bestScore = 0;
        for (int i = 0; i < albums.length(); i++) {
            org.json.JSONObject a = albums.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String name = str(a, "name");
            if (name == null || !byArtist(q.artist, a)) {
                continue;
            }
            int score = nameScore(wanted, norm(name));
            if (score > bestScore) {
                best = String.valueOf(a.optLong("id", 0L));
                bestScore = score;
            }
        }
        return best;
    }

    /**
     * 4 when the names are the same, 2 when one contains the other, 0 when they are unrelated.
     *
     * Asked of titles and of album names alike, which are the same problem: one name typed by two
     * catalogues.
     */
    private static int nameScore(String wanted, String got) {
        if (got.isEmpty()) {
            return 0;
        }
        if (wanted.equals(got)) {
            return 4;
        }
        return wanted.contains(got) || got.contains(wanted) ? 2 : 0;
    }

    /**
     * Whether this result is by the artist the session says it is.
     *
     * The search ranks on title and artist together, but it returns other people's songs under the
     * same title, and until this existed an exact title with a duration inside the window was
     * enough to be chosen whatever name was on it. Measured 2026-09-20, playing 田馥甄's 余波荡漾:
     * the search answered with a page of that title by other artists and a page of that artist's
     * other songs, and not with the song, which is on the album it is on. pick() took 粟丹sudan's
     * piano version - exact title, 1673ms from the session. That one happened to carry no lyric,
     * so the screen stayed empty; the copy 158ms from the session was a cover, and it would have
     * been shown had the ranking put it first. Another singer's name is not a near miss.
     *
     * Compared with the latitude the titles get - everything that varies between catalogues
     * removed, and either name containing the other - which is what lets a session's
     * "LAUV/Troye Sivan" match a catalogue crediting only one of them.
     *
     * With nothing on either side to compare, the answer is yes: this can rule a candidate out,
     * it cannot find one, and a player publishing no artist should not lose its lyrics to it.
     */
    private static boolean byArtist(String wanted, org.json.JSONObject s) {
        String want = norm(wanted);
        org.json.JSONArray ar = s.optJSONArray("artists");
        if (want.isEmpty() || ar == null || ar.length() == 0) {
            return true;
        }
        for (int i = 0; i < ar.length(); i++) {
            org.json.JSONObject a = ar.optJSONObject(i);
            if (a == null) {
                continue;
            }
            String got = norm(str(a, "name"));
            if (!got.isEmpty() && (got.contains(want) || want.contains(got))) {
                return true;
            }
        }
        return false;
    }

    /**
     * A title with everything that varies between catalogues taken out.
     *
     * Case, spaces, punctuation and brackets all differ for the same song depending on who typed
     * it in - "i'm so tired... (Stripped - Live in LA)" against "i'm so tired...(Stripped - Live
     * in LA)" is the same recording with one space missing. What is left is letters and digits,
     * in order, which is enough to tell two titles apart and not enough to be upset by a comma.
     *
     * The script is the other thing that varies, and it is folded first - see folded().
     */
    private static String norm(String s) {
        if (s == null) {
            return "";
        }
        s = folded(s);
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
     * The same name written in simplified Chinese, because the two catalogues do not agree on the
     * script and neither of them says so.
     *
     * Measured 2026-09-20, playing 林忆莲's 是你治癒了我的孤单: Apple Music publishes the traditional
     * 癒 and NetEase the simplified 愈, and the one candidate that was the right song - 681ms from
     * the session's duration, right artist - differed from the session's title by that character
     * alone. Nothing else about them disagreed, norm() read them as two different songs, and the
     * song played with no lyrics while its lyrics sat one row down the same response. The split
     * runs through the names of people too (林憶蓮 against 林忆莲), which is why the whole name is
     * folded rather than the title.
     *
     * ICU's own transform, which this device has and which does exactly this. If a build ever
     * lacks it the name is compared as it is: that is the comparison that was here before, and it
     * is a better failure than every song with a character variant losing its lyrics.
     */
    private static String folded(String s) {
        android.icu.text.Transliterator t = TRANSLIT;
        if (t == null) {
            return s;
        }
        try {
            // Not thread-safe, and the lookups arrive on more than one thread.
            synchronized (t) {
                return t.transliterate(s);
            }
        } catch (Throwable e) {
            return s;
        }
    }

    /** Built once: getInstance parses the rules, and norm() runs a dozen times per candidate. */
    private static final android.icu.text.Transliterator TRANSLIT = transliterator();

    private static android.icu.text.Transliterator transliterator() {
        try {
            return android.icu.text.Transliterator.getInstance("Traditional-Simplified");
        } catch (Throwable t) {
            Xp.log("[MCNcm] no traditional-to-simplified transform here: " + t);
            return null;
        }
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
