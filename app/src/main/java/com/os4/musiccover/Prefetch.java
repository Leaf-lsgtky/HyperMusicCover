package com.os4.musiccover;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaDescription;
import android.media.session.MediaController;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * The cover of the track that has not been asked for yet.
 *
 * Measured on this phone: from a press landing to the player saying a track changed is 713-972ms,
 * and everything the cover does after that is ~110ms. So the swap is not slow - it starts late,
 * and no amount of work on the second number touches the first.
 *
 * A player that publishes a play QUEUE says what is coming before it is asked, which is what this
 * uses: the artwork either side of the current item is fetched and kept, and the press itself -
 * caught on TransportControls, ~0.8s before the player reports anything - hands the right one
 * straight to the wallpaper.
 *
 * Only Apple Music publishes one here. Measured 2026-09-17 with `op queue`: NetEase, Salt Player
 * and Bilibili all answer "no queue", so for them this does nothing at all and the cover waits
 * for the player exactly as it did. Nothing about it is Apple-specific though - the queue and the
 * artwork URI are both public MediaSession API, and any player that fills them in gets it too.
 *
 * Guessing is the whole idea, so being wrong has to be cheap: the prediction is only ever an
 * early push of a picture, and the player's own metadata arrives a moment later and is what
 * settles it. See Main.onMediaUpdate(), which pushes again whenever the two disagree.
 */
final class Prefetch {

    private Prefetch() {
    }

    private static final String TAG = "[MCPre] ";

    /**
     * How many items either side of the current one are worth holding.
     *
     * Two, not one, because pressing next twice quickly is the case this exists for: the second
     * press predicts from where the first one landed, and one item of reach would already be
     * behind it.
     */
    private static final int REACH = 2;
    /** Artwork is ~1MB decoded; this is a handful of tracks, not a library. */
    private static final int CACHE_MAX = 6;

    /** One queue item, reduced to what a cover needs. */
    private static final class Item {
        final long id;
        final String title;
        final Uri icon;

        Item(long id, String title, Uri icon) {
            this.id = id;
            this.title = title;
            this.icon = icon;
        }
    }

    private static volatile List<Item> sItems = new ArrayList<>();
    /** Where the player says it is in that list, or -1 when it does not say. */
    private static volatile int sIndex = -1;

    /** Decoded artwork, keyed on the URI it came from. Guarded by CACHE. */
    private static final java.util.LinkedHashMap<String, Bitmap> CACHE =
            new java.util.LinkedHashMap<>(8, 0.75f, true);

    private static Handler sWork;

    /**
     * The title this predicted for the press that has not been confirmed yet, and when it was
     * predicted. Read by Main to decide whether the player's own report needs another push.
     */
    private static volatile String sPredicted;
    private static volatile long sPredictedAt;

    /** A prediction older than this is not worth matching against - the player never got there. */
    private static final long PREDICTION_TTL_MS = 4000L;

    private static synchronized Handler work() {
        if (sWork == null) {
            HandlerThread t = new HandlerThread("mc-prefetch",
                    android.os.Process.THREAD_PRIORITY_BACKGROUND);
            t.start();
            sWork = new Handler(t.getLooper());
        }
        return sWork;
    }

    // ------------------------------------------------------------------ from Main

    /**
     * The player reported something: re-read its queue and fetch what sits either side of the
     * current track. Cheap when nothing moved - the fetches are keyed on the artwork URI and a
     * cached one does not go out again.
     */
    static void onTrack(final MediaController c) {
        if (c == null) {
            sItems = new ArrayList<>();
            sIndex = -1;
            return;
        }
        work().post(new Runnable() {
            @Override
            public void run() {
                try {
                    readQueue(c);
                    fetchAround();
                } catch (Throwable t) {
                    Xp.log(TAG + "queue read failed: " + t);
                }
            }
        });
    }

    /**
     * A skip was just asked for. Answers the artwork for where the queue says it lands, or null -
     * no queue, not fetched yet, or the queue has run out that way.
     *
     * Called on the thread that asked for the skip, so it only reads what is already in hand.
     */
    static Bitmap take(int dir) {
        List<Item> items = sItems;
        int at = sIndex;
        if (items.isEmpty() || at < 0) return null;
        int want = at + (dir < 0 ? -1 : 1);
        if (want < 0 || want >= items.size()) {
            // The ends are real: at the last track, next may wrap, stop, or do nothing at all,
            // and the queue does not say which. Not guessing is the cheaper mistake.
            return null;
        }
        Item it = items.get(want);
        if (it.icon == null) return null;
        Bitmap b;
        synchronized (CACHE) {
            b = CACHE.get(it.icon.toString());
        }
        if (b == null || b.isRecycled()) return null;
        // Moved here and now, so a second press within the burst predicts from the new place
        // rather than from where the player still thinks it is.
        sIndex = want;
        sPredicted = it.title;
        sPredictedAt = SystemClock.uptimeMillis();
        Xp.log(TAG + "predicting \"" + it.title + "\" for a skip " + (dir < 0 ? "back" : "on"));
        // The one after this one is now worth having.
        work().post(new Runnable() {
            @Override
            public void run() {
                fetchAround();
            }
        });
        return b;
    }

    /**
     * Whether the track the player has now is the one already pushed for. Consumes the
     * prediction either way: it has been answered.
     */
    static boolean wasPredicted(String title) {
        String p = sPredicted;
        long at = sPredictedAt;
        sPredicted = null;
        if (p == null || title == null) return false;
        if (SystemClock.uptimeMillis() - at > PREDICTION_TTL_MS) return false;
        boolean hit = p.equals(title);
        Xp.log(TAG + (hit ? "prediction held: " : "prediction missed: predicted \"" + p
                + "\", the player went to ") + "\"" + title + "\"");
        return hit;
    }

    /** For `op queue` and the settings page: whether this can do anything for the player. */
    static String describe() {
        List<Item> items = sItems;
        int n;
        synchronized (CACHE) {
            n = CACHE.size();
        }
        return "queue=" + items.size() + " at=" + sIndex + " cached=" + n
                + " predicted=" + sPredicted;
    }

    // ------------------------------------------------------------------ internals

    private static void readQueue(MediaController c) {
        List<MediaSession.QueueItem> q = c.getQueue();
        if (q == null || q.isEmpty()) {
            sItems = new ArrayList<>();
            sIndex = -1;
            return;
        }
        List<Item> items = new ArrayList<>(q.size());
        for (MediaSession.QueueItem qi : q) {
            MediaDescription d = qi.getDescription();
            items.add(new Item(qi.getQueueId(),
                    d == null || d.getTitle() == null ? null : d.getTitle().toString(),
                    d == null ? null : d.getIconUri()));
        }
        long active = -1L;
        PlaybackState ps = c.getPlaybackState();
        if (ps != null) active = ps.getActiveQueueItemId();
        int at = -1;
        for (int n = 0; n < items.size(); n++) {
            if (items.get(n).id == active) {
                at = n;
                break;
            }
        }
        sItems = items;
        sIndex = at;
    }

    /** Fetches the artwork either side of where we think we are, newest need first. */
    private static void fetchAround() {
        List<Item> items = sItems;
        int at = sIndex;
        if (items.isEmpty() || at < 0) return;
        for (int d = 1; d <= REACH; d++) {
            fetch(items, at + d);
            fetch(items, at - d);
        }
        trim();
    }

    private static void fetch(List<Item> items, int at) {
        if (at < 0 || at >= items.size()) return;
        Item it = items.get(at);
        if (it.icon == null) return;
        String key = it.icon.toString();
        synchronized (CACHE) {
            Bitmap have = CACHE.get(key);
            if (have != null && !have.isRecycled()) return;
        }
        long t0 = SystemClock.uptimeMillis();
        Bitmap b = load(it.icon);
        if (b == null) return;
        synchronized (CACHE) {
            CACHE.put(key, b);
        }
        Xp.log(TAG + "fetched \"" + it.title + "\" " + b.getWidth() + "x" + b.getHeight()
                + " in " + (SystemClock.uptimeMillis() - t0) + "ms");
    }

    private static void trim() {
        synchronized (CACHE) {
            while (CACHE.size() > CACHE_MAX) {
                java.util.Iterator<String> it = CACHE.keySet().iterator();
                if (!it.hasNext()) return;
                it.next();
                it.remove();
            }
        }
    }

    /**
     * The artwork behind one URI. http(s) goes over the network - SystemUI holds INTERNET, and
     * this runs on the prefetch thread - and anything else goes through the resolver, so a player
     * that publishes content:// artwork is served the same way.
     */
    private static Bitmap load(Uri uri) {
        String scheme = uri.getScheme();
        try {
            if ("http".equals(scheme) || "https".equals(scheme)) {
                java.net.HttpURLConnection conn =
                        (java.net.HttpURLConnection) new java.net.URL(uri.toString())
                                .openConnection();
                try {
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(5000);
                    conn.setInstanceFollowRedirects(true);
                    InputStream in = conn.getInputStream();
                    try {
                        return BitmapFactory.decodeStream(in);
                    } finally {
                        in.close();
                    }
                } finally {
                    conn.disconnect();
                }
            }
            Context ctx = Main.appContext();
            if (ctx == null) return null;
            InputStream in = ctx.getContentResolver().openInputStream(uri);
            if (in == null) return null;
            try {
                return BitmapFactory.decodeStream(in);
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            Xp.log(TAG + "could not read " + uri + ": " + t);
            return null;
        }
    }
}
