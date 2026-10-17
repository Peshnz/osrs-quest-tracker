package nz.pesh.gotr;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.AudioManager;
import android.media.Image;
import android.media.ImageReader;
import android.media.ToneGenerator;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.nio.ByteBuffer;

/** The floating vertical strip drawn over other apps, with optional auto-start from the altar circles. */
public class OverlayService extends Service {
    static final String ACTION_CAPTURE = "CAPTURE";

    private static final int BAR = Color.parseColor("#E6151A24");
    private static final int LINE = Color.parseColor("#2A3242");
    private static final int FG = Color.parseColor("#E8ECF3");
    private static final int MUTED = Color.parseColor("#8A93A6");
    private static final int WARN = Color.parseColor("#FFD23F");
    private static final int WARN_INK = Color.parseColor("#1A1400");
    private static final int LIVE = Color.parseColor("#3DDC97");
    private static final int LIVE_INK = Color.parseColor("#04150D");
    private static final int STOP = Color.parseColor("#FF5C5C");
    private static final int AUTO = Color.parseColor("#4DA3FF");

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout root;
    private GradientDrawable rootBg;
    private TextView time, sync, toggle, auto, close;
    private View fill;
    private int trackH;
    private Notification notification;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running, startedByAuto;
    private long start, anchor = -1, beeped = -1;
    private ToneGenerator tone;
    private long first, every, warn, open;
    private boolean beepOn;

    // ---- auto-start state ----
    private MediaProjection projection;
    private VirtualDisplay vdisplay;
    private ImageReader reader;
    private int capW, capH;            // capture size (half of screen)
    private int scrW, scrH;            // real screen size
    private float c1x, c1y, c2x, c2y, half; // circle centres as fractions of the screen
    private boolean armed, adjusting;
    private int presentCount, emptyCount;
    private boolean seenEmpty;              // only start after the circles have been empty
    private View boxes;

    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); if (running) h.postDelayed(this, 200); }
    };
    private final Runnable watch = new Runnable() {
        @Override public void run() { poll(); if (armed) h.postDelayed(this, 500); }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        buildNotification();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, notification);
        }
        loadPrefs();
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (Exception e) { tone = null; }
        buildView();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String a = intent == null ? null : intent.getAction();
        if ("STOP".equals(a)) { stopSelf(); return START_NOT_STICKY; }
        if (ACTION_CAPTURE.equals(a)) { onCaptureResult(intent); return START_NOT_STICKY; }
        loadPrefs();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacksAndMessages(null);
        stopCapture();
        if (root != null) try { wm.removeView(root); } catch (Exception ignored) {}
        if (boxes != null) try { wm.removeView(boxes); } catch (Exception ignored) {}
        if (tone != null) tone.release();
        super.onDestroy();
    }

    private void buildNotification() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("gotr", "Floating timer", NotificationManager.IMPORTANCE_LOW));
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        notification = new Notification.Builder(this, "gotr")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("GOTR timer is showing")
                .setContentText("Tap to change timings")
                .setContentIntent(openApp)
                .addAction(new Notification.Action.Builder(null, "Close timer", stop).build())
                .setOngoing(true)
                .build();
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        first = p.getInt("first", 160) * 1000L;
        every = p.getInt("every", 140) * 1000L;
        warn = p.getInt("warn", 10) * 1000L;
        open = p.getInt("open", 25) * 1000L;
        beepOn = p.getBoolean("beep", true);
        // Defaults measured from a 2000x923 landscape screenshot of the GOTR panel
        c1x = p.getFloat("c1x", 0.135f);
        c1y = p.getFloat("c1y", 0.13f);
        c2x = p.getFloat("c2x", 0.2075f);
        c2y = p.getFloat("c2y", 0.13f);
        pxf = p.getFloat("px", 0.2815f);
        pyf = p.getFloat("py", 0.192f);
        half = 0.009f;
    }

    // ================= view =================

    private void buildView() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(4), dp(6), dp(4), dp(6));
        rootBg = new GradientDrawable();
        rootBg.setCornerRadius(dp(8));
        rootBg.setColor(BAR);
        rootBg.setStroke(dp(1), LINE);
        root.setBackground(rootBg);

        time = new TextView(this);
        time.setText("--:--");
        time.setTextSize(15);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setFontFeatureSettings("tnum");
        time.setTextColor(FG);
        time.setGravity(Gravity.CENTER);
        time.setPadding(0, dp(2), 0, dp(4));
        root.addView(time, new LinearLayout.LayoutParams(dp(46), LinearLayout.LayoutParams.WRAP_CONTENT));

        trackH = dp(60);
        FrameLayout track = new FrameLayout(this);
        GradientDrawable tb = new GradientDrawable();
        tb.setColor(LINE);
        tb.setCornerRadius(dp(3));
        track.setBackground(tb);
        fill = new View(this);
        fill.setBackgroundColor(MUTED);
        track.addView(fill, new FrameLayout.LayoutParams(dp(6), 0, Gravity.BOTTOM));
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(dp(6), trackH);
        tlp.bottomMargin = dp(6);
        root.addView(track, tlp);

        sync = button("P", Color.TRANSPARENT, FG, true);
        sync.setOnClickListener(v -> { if (!running) startRun(false); anchor = System.currentTimeMillis(); beeped = -1; update(); });
        toggle = button("▶", LIVE, LIVE_INK, false);
        toggle.setOnClickListener(v -> { if (running) stopRun(); else startRun(false); });
        auto = button("A", Color.TRANSPARENT, MUTED, true);
        auto.setOnClickListener(v -> onAutoTapped());
        auto.setOnLongClickListener(v -> { startAdjust(); return true; });

        close = new TextView(this);
        close.setText("✕");
        close.setTextColor(MUTED);
        close.setTextSize(13);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(2), 0, 0);
        close.setOnClickListener(v -> stopSelf());
        root.addView(close, new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.WRAP_CONTENT));

        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        lp.x = p.getInt("x", dp(8));
        lp.y = p.getInt("y", dp(80));

        time.setOnTouchListener(new View.OnTouchListener() {
            int sx, sy; float tx, ty;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: sx = lp.x; sy = lp.y; tx = e.getRawX(); ty = e.getRawY(); return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = sx + Math.round(e.getRawX() - tx);
                        lp.y = sy + Math.round(e.getRawY() - ty);
                        wm.updateViewLayout(root, lp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit().putInt("x", lp.x).putInt("y", lp.y).apply();
                        return true;
                }
                return false;
            }
        });

        wm.addView(root, lp);
    }

    private TextView button(String label, int bg, int fg, boolean outline) {
        TextView b = new TextView(this);
        b.setText(label);
        b.setTextSize(16);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(fg);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setCornerRadius(dp(6));
        d.setColor(bg);
        if (outline) d.setStroke(dp(1), LINE);
        b.setBackground(d);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(40), dp(40));
        p.bottomMargin = dp(6);
        root.addView(b, p);
        return b;
    }

    private void setButton(TextView b, String label, int bg, int fg) {
        b.setText(label);
        b.setTextColor(fg);
        ((GradientDrawable) b.getBackground()).setColor(bg);
    }

    // ================= timer =================

    private void startRun(boolean byAuto) {
        loadPrefs();
        running = true;
        startedByAuto = byAuto;
        start = System.currentTimeMillis() - (byAuto ? 500 : 0); // detection lags about half a second
        anchor = -1;
        beeped = -1;
        setButton(toggle, "■", STOP, Color.parseColor("#1A0505"));
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private void stopRun() {
        running = false;
        startedByAuto = false;
        h.removeCallbacks(tick);
        time.setText("--:--");
        setColors(BAR, FG, MUTED);
        setFill(0);
        setButton(toggle, "▶", LIVE, LIVE_INK);
    }

    private long base() { return anchor >= 0 ? anchor : start + first; }

    private void update() {
        if (!running) return;
        long now = System.currentTimeMillis();
        long b = base();
        long next, len, prev;
        if (now < b) {
            next = b; len = anchor >= 0 ? every : first; prev = -1;
        } else {
            long n = (now - b) / every;
            prev = b + n * every;
            next = prev + every;
            len = every;
        }
        long left = next - now;
        time.setText(fmt(left));
        setFill(1f - (float) left / len);

        if (prev >= 0 && now - prev < open) {
            setColors(LIVE, LIVE_INK, LIVE_INK);
        } else if (left <= warn) {
            boolean on = (now / 500) % 2 == 0;
            if (on) setColors(WARN, WARN_INK, WARN_INK); else setColors(BAR, WARN, WARN);
            if (beeped != next) {
                beeped = next;
                if (beepOn && tone != null) tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 300);
            }
        } else {
            setColors(BAR, FG, MUTED);
        }
    }

    private void setColors(int bg, int text, int fillColor) {
        rootBg.setColor(bg);
        time.setTextColor(text);
        close.setTextColor(text == FG ? MUTED : text);
        fill.setBackgroundColor(fillColor);
        boolean plain = bg == BAR;
        ((GradientDrawable) sync.getBackground()).setStroke(dp(1), plain ? LINE : text);
        sync.setTextColor(text);
        ((GradientDrawable) auto.getBackground()).setStroke(dp(1), plain ? LINE : text);
        if (!armed) auto.setTextColor(plain ? MUTED : text);
    }

    private void setFill(float f) {
        f = Math.max(0f, Math.min(1f, f));
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) fill.getLayoutParams();
        p.height = Math.round(trackH * f);
        fill.setLayoutParams(p);
    }

    // ================= auto-start =================
    // Spot 0 and 1: the two altar circles. They are empty between games and show symbols during a game.
    // Spot 2: under the guardian helmet, where the orange portal spiral appears while a portal is open.

    private void onAutoTapped() {
        if (adjusting) { finishAdjust(); return; }
        if (armed) { disarm(); toast("Auto-start off"); return; }
        if (projection == null) { askCapture(); return; }
        arm();
    }

    private void askCapture() {
        startActivity(new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    private void onCaptureResult(Intent intent) {
        int code = intent.getIntExtra("code", 0);
        Intent data = intent.getParcelableExtra("data");
        if (code != Activity.RESULT_OK || data == null) { toast("Screen capture not allowed, auto-start is off"); return; }
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                        | ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            } else {
                startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
            }
            MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
            projection = mpm.getMediaProjection(code, data);
            projection.registerCallback(new MediaProjection.Callback() {
                @Override public void onStop() { h.post(() -> { stopCapture(); disarm(); }); }
            }, h);
            setupDisplay();
            h.postDelayed(this::arm, 600);
        } catch (Exception e) {
            toast("Couldn't start screen capture: " + e.getMessage());
            stopCapture();
        }
    }

    private void readScreenSize() {
        if (Build.VERSION.SDK_INT >= 30) {
            Rect r = wm.getMaximumWindowMetrics().getBounds();
            scrW = r.width(); scrH = r.height();
        } else {
            DisplayMetrics m = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(m);
            scrW = m.widthPixels; scrH = m.heightPixels;
        }
    }

    private void setupDisplay() {
        readScreenSize();
        capW = Math.max(2, scrW / 2);
        capH = Math.max(2, scrH / 2);
        int dpi = getResources().getDisplayMetrics().densityDpi;
        ImageReader old = reader;
        reader = ImageReader.newInstance(capW, capH, PixelFormat.RGBA_8888, 2);
        if (vdisplay == null) {
            vdisplay = projection.createVirtualDisplay("gotr-watch", capW, capH, dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, h);
        } else {
            vdisplay.resize(capW, capH, dpi);
            vdisplay.setSurface(reader.getSurface());
        }
        if (old != null) old.close();
    }

    private void stopCapture() {
        if (vdisplay != null) { vdisplay.release(); vdisplay = null; }
        if (reader != null) { reader.close(); reader = null; }
        if (projection != null) { try { projection.stop(); } catch (Exception ignored) {} projection = null; }
    }

    /** Flash the three spots for 3 s so you can check them, then start watching. */
    private void arm() {
        if (projection == null) return;
        armed = false;
        h.removeCallbacks(watch);
        auto.setTextColor(WARN);
        showBoxes(true, false);
        h.postDelayed(() -> {
            showBoxes(false, false);
            armed = true;
            seenEmpty = false;
            presentCount = 0;
            emptyCount = 0;
            portalWasOn = true; // don't resync on a portal that is already open when arming
            setButton(auto, "A", AUTO, Color.parseColor("#06121F"));
            toast("Auto-start armed");
            h.post(watch);
        }, 3000);
    }

    private void disarm() {
        armed = false;
        h.removeCallbacks(watch);
        showBoxes(false, false);
        setButton(auto, "A", Color.TRANSPARENT, MUTED);
    }

    private boolean portalWasOn;
    private int portalOnCount;

    private void poll() {
        if (!armed) return;
        readScreenSize();
        if ((scrW > scrH) != (capW > capH)) { setupDisplay(); return; } // rotated: rebuild capture
        float[] s = sample();
        if (s == null) return;
        boolean present = s[0] > 30 || s[1] > 30;  // icon detail in either circle
        boolean empty = s[0] < 15 && s[1] < 15;    // both circles plain
        boolean portal = s[2] > 0.08f;             // orange spiral showing

        if (empty) { if (++emptyCount >= 2) seenEmpty = true; } else emptyCount = 0;

        if (!running) {
            if (present && seenEmpty) {
                if (++presentCount >= 2) { startRun(true); presentCount = 0; seenEmpty = false; }
            } else presentCount = 0;
        } else if (startedByAuto && emptyCount >= 10) {
            stopRun(); // symbols gone for 5 s: game over, wait for the next one
        }

        // Portal spiral just appeared: resync the cycle to it
        if (portal) {
            if (++portalOnCount == 2 && !portalWasOn) {
                if (!running) startRun(true);
                anchor = System.currentTimeMillis() - 1000; // seen twice, 0.5 s apart, plus capture lag
                beeped = -1;
                update();
            }
            if (portalOnCount >= 2) portalWasOn = true;
        } else {
            portalOnCount = 0;
            portalWasOn = false;
        }
    }

    /** Returns [detail in circle 1, detail in circle 2, orange fraction at the portal spot]. */
    private float[] sample() {
        if (reader == null) return null;
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return null;
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int rs = pl.getRowStride(), ps = pl.getPixelStride();
            int w = img.getWidth(), hgt = img.getHeight();
            return new float[]{
                    detail(buf, rs, ps, w, hgt, c1x, c1y),
                    detail(buf, rs, ps, w, hgt, c2x, c2y),
                    orange(buf, rs, ps, w, hgt, pxf, pyf)};
        } catch (Exception e) {
            return null;
        } finally {
            if (img != null) img.close();
        }
    }

    /** Brightness spread: a plain circle is low, a symbol is high. */
    private float detail(ByteBuffer buf, int rs, int ps, int w, int hgt, float fx, float fy) {
        int cx = Math.round(fx * w), cy = Math.round(fy * hgt), r = Math.max(4, Math.round(half * w));
        double sl = 0, sl2 = 0; int n = 0;
        for (int y = Math.max(0, cy - r); y < Math.min(hgt, cy + r); y++) {
            for (int x = Math.max(0, cx - r); x < Math.min(w, cx + r); x++) {
                int i = y * rs + x * ps;
                double l = 0.299 * (buf.get(i) & 0xff) + 0.587 * (buf.get(i + 1) & 0xff) + 0.114 * (buf.get(i + 2) & 0xff);
                sl += l; sl2 += l * l; n++;
            }
        }
        if (n == 0) return 0;
        double m = sl / n;
        return (float) Math.sqrt(Math.max(0, sl2 / n - m * m));
    }

    /** Share of pixels that are portal-spiral orange. */
    private float orange(ByteBuffer buf, int rs, int ps, int w, int hgt, float fx, float fy) {
        int cx = Math.round(fx * w), cy = Math.round(fy * hgt), r = Math.max(4, Math.round(portalHalf * w));
        float[] hsv = new float[3];
        int n = 0, o = 0;
        for (int y = Math.max(0, cy - r); y < Math.min(hgt, cy + r); y++) {
            for (int x = Math.max(0, cx - r); x < Math.min(w, cx + r); x++) {
                int i = y * rs + x * ps;
                Color.RGBToHSV(buf.get(i) & 0xff, buf.get(i + 1) & 0xff, buf.get(i + 2) & 0xff, hsv);
                n++;
                if (hsv[0] >= 20 && hsv[0] <= 43 && hsv[1] > 0.65f && hsv[2] > 0.55f) o++;
            }
        }
        return n == 0 ? 0 : (float) o / n;
    }

    // ---- showing and moving the spots ----

    private float pxf, pyf;
    private final float portalHalf = 0.014f;
    private int dragging = -1;

    /** Long-press A: show the spots and let you drag them. Tap A to save. */
    private void startAdjust() {
        if (armed) disarm();
        adjusting = true;
        setButton(auto, "✓", WARN, WARN_INK);
        showBoxes(true, true);
        toast("Drag the boxes onto the icons, then tap ✓");
    }

    private void finishAdjust() {
        adjusting = false;
        showBoxes(false, false);
        getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE).edit()
                .putFloat("c1x", c1x).putFloat("c1y", c1y)
                .putFloat("c2x", c2x).putFloat("c2y", c2y)
                .putFloat("px", pxf).putFloat("py", pyf).apply();
        setButton(auto, "A", Color.TRANSPARENT, MUTED);
        toast("Spots saved");
        if (projection != null) arm();
    }

    private float[][] spots() { return new float[][]{{c1x, c1y, half}, {c2x, c2y, half}, {pxf, pyf, portalHalf}}; }

    private void showBoxes(boolean show, boolean movable) {
        if (boxes != null) { try { wm.removeView(boxes); } catch (Exception ignored) {} boxes = null; }
        if (!show) return;
        readScreenSize();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
        label.setTextSize(dp(11));
        label.setFakeBoldText(true);
        final String[] names = {"altar", "altar", "portal"};
        boxes = new View(this) {
            final int[] loc = new int[2];
            @Override protected void onDraw(Canvas c) {
                getLocationOnScreen(loc);
                float[][] sp = spots();
                for (int k = 0; k < 3; k++) {
                    int col = k == 2 ? Color.parseColor("#FF9F1C") : WARN;
                    paint.setColor(col); label.setColor(col);
                    int r = Math.round(sp[k][2] * scrW) + dp(3);
                    int x = Math.round(sp[k][0] * scrW) - loc[0], y = Math.round(sp[k][1] * scrH) - loc[1];
                    c.drawRect(x - r, y - r, x + r, y + r, paint);
                    c.drawText(names[k], x - r, y + r + dp(12), label);
                }
            }
            @Override public boolean onTouchEvent(MotionEvent e) {
                if (!adjusting) return false;
                getLocationOnScreen(loc);
                float rx = e.getRawX(), ry = e.getRawY();
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN: {
                        dragging = -1;
                        float best = dp(60);
                        float[][] sp = spots();
                        for (int k = 0; k < 3; k++) {
                            float d = Math.abs(rx - sp[k][0] * scrW) + Math.abs(ry - sp[k][1] * scrH);
                            if (d < best) { best = d; dragging = k; }
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_MOVE:
                        if (dragging < 0) return true;
                        float fx = Math.max(0, Math.min(1, rx / scrW)), fy = Math.max(0, Math.min(1, ry / scrH));
                        if (dragging == 0) { c1x = fx; c1y = fy; }
                        else if (dragging == 1) { c2x = fx; c2y = fy; }
                        else { pxf = fx; pyf = fy; }
                        invalidate();
                        return true;
                    case MotionEvent.ACTION_UP:
                        dragging = -1;
                        return true;
                }
                return true;
            }
        };
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!movable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams bp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        bp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            bp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(boxes, bp);
        // keep the strip on top so its buttons stay tappable while adjusting
        try { wm.removeView(root); wm.addView(root, lp); } catch (Exception ignored) {}
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String fmt(long ms) {
        long s = (ms + 999) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
