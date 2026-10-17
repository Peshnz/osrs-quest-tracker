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
    private float c1x, c2x, cy, half;  // circle positions as fractions of screen
    private boolean armed;
    private float[][] baseline;        // learned "waiting" look of each circle
    private int changedCount, idleCount;
    private boolean lastIdle = true;
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
        c1x = p.getFloat("c1x", 13.5f) / 100f;
        c2x = p.getFloat("c2x", 20.75f) / 100f;
        cy = p.getFloat("cy", 13.0f) / 100f;
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

    private void onAutoTapped() {
        if (armed) { disarm(); toast("Auto-start off"); return; }
        if (projection == null) {
            Intent i = new Intent(this, CaptureActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            return;
        }
        arm();
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
            // give the capture a moment to deliver frames, then arm
            h.postDelayed(this::arm, 600);
        } catch (Exception e) {
            toast("Couldn't start screen capture: " + e.getMessage());
            stopCapture();
        }
    }

    private void readScreenSize() {
        DisplayMetrics m = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 30) {
            Rect r = wm.getMaximumWindowMetrics().getBounds();
            scrW = r.width(); scrH = r.height();
        } else {
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

    /** Shows where it's looking for 3 s, then learns the current (waiting) look of the circles. */
    private void arm() {
        if (projection == null) return;
        armed = false;
        h.removeCallbacks(watch);
        auto.setTextColor(WARN);
        ((GradientDrawable) auto.getBackground()).setColor(Color.TRANSPARENT);
        showBoxes(true);
        toast("Learning the waiting-room altar icons...");
        h.postDelayed(() -> {
            showBoxes(false);
            h.postDelayed(() -> {
                float[][] s = sample();
                if (s == null) { toast("No screen picture yet, tap A again"); auto.setTextColor(MUTED); return; }
                baseline = s;
                armed = true;
                lastIdle = true;
                changedCount = 0;
                idleCount = 0;
                setButton(auto, "A", AUTO, Color.parseColor("#06121F"));
                toast("Auto-start armed");
                h.post(watch);
            }, 700);
        }, 3000);
    }

    private void disarm() {
        armed = false;
        h.removeCallbacks(watch);
        showBoxes(false);
        setButton(auto, "A", Color.TRANSPARENT, MUTED);
    }

    private void poll() {
        if (!armed) return;
        readScreenSize();
        if ((scrW > scrH) != (capW > capH)) { setupDisplay(); return; } // rotated: rebuild capture
        float[][] s = sample();
        if (s == null || baseline == null) return;
        float d1 = diff(s[0], baseline[0]), d2 = diff(s[1], baseline[1]);
        // either circle switching from the grey placeholder to a real altar icon starts the game
        boolean changed = d1 > 22 || d2 > 22;
        boolean idle = d1 < 9 && d2 < 9;

        if (!running) {
            if (changed && lastIdle) {
                if (++changedCount >= 2) { startRun(true); changedCount = 0; idleCount = 0; }
            } else {
                changedCount = 0;
            }
            if (idle) lastIdle = true; else if (!changed) lastIdle = false;
        } else if (startedByAuto) {
            // game over: circles back to the waiting look for 8 s
            if (idle) { if (++idleCount >= 16) { stopRun(); lastIdle = true; } } else idleCount = 0;
        }
    }

    /** A 4x4 grid of average colours over the middle of each circle, so both colour and icon shape count. */
    private float[][] sample() {
        if (reader == null) return null;
        Image img = null;
        try {
            img = reader.acquireLatestImage();
            if (img == null) return null;
            Image.Plane pl = img.getPlanes()[0];
            ByteBuffer buf = pl.getBuffer();
            int rs = pl.getRowStride(), ps = pl.getPixelStride();
            int w = img.getWidth(), hgt = img.getHeight();
            return new float[][]{patch(buf, rs, ps, w, hgt, c1x), patch(buf, rs, ps, w, hgt, c2x)};
        } catch (Exception e) {
            return null;
        } finally {
            if (img != null) img.close();
        }
    }

    private float[] patch(ByteBuffer buf, int rs, int ps, int w, int hgt, float fx) {
        final int G = 4;
        int cx = Math.round(fx * w), cyy = Math.round(cy * hgt);
        int r = Math.max(4, Math.round(half * w));
        float[] out = new float[G * G * 3];
        int[] cnt = new int[G * G];
        for (int y = cyy - r; y < cyy + r; y++) {
            if (y < 0 || y >= hgt) continue;
            int gy = Math.min(G - 1, (y - (cyy - r)) * G / (2 * r));
            for (int x = cx - r; x < cx + r; x++) {
                if (x < 0 || x >= w) continue;
                int gx = Math.min(G - 1, (x - (cx - r)) * G / (2 * r));
                int i = y * rs + x * ps, c = gy * G + gx;
                out[c * 3] += buf.get(i) & 0xff;
                out[c * 3 + 1] += buf.get(i + 1) & 0xff;
                out[c * 3 + 2] += buf.get(i + 2) & 0xff;
                cnt[c]++;
            }
        }
        for (int c = 0; c < G * G; c++) {
            if (cnt[c] == 0) continue;
            out[c * 3] /= cnt[c]; out[c * 3 + 1] /= cnt[c]; out[c * 3 + 2] /= cnt[c];
        }
        return out;
    }

    /** Average per-channel difference, 0 (identical) to 255. */
    private static float diff(float[] a, float[] b) {
        float s = 0;
        for (int i = 0; i < a.length; i++) s += Math.abs(a[i] - b[i]);
        return s / a.length;
    }

    /** Full-screen, touch-through overlay outlining the two spots being watched. */
    private void showBoxes(boolean show) {
        if (!show) {
            if (boxes != null) { try { wm.removeView(boxes); } catch (Exception ignored) {} boxes = null; }
            return;
        }
        if (boxes != null) return;
        readScreenSize();
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(dp(2));
        paint.setColor(WARN);
        boxes = new View(this) {
            @Override protected void onDraw(Canvas c) {
                int[] loc = new int[2];
                getLocationOnScreen(loc);
                int r = Math.round(half * scrW) + dp(3);
                for (float fx : new float[]{c1x, c2x}) {
                    int x = Math.round(fx * scrW) - loc[0], y = Math.round(cy * scrH) - loc[1];
                    c.drawRect(x - r, y - r, x + r, y + r, paint);
                }
            }
        };
        WindowManager.LayoutParams bp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        bp.gravity = Gravity.TOP | Gravity.START;
        if (Build.VERSION.SDK_INT >= 28) {
            bp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(boxes, bp);
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    private static String fmt(long ms) {
        long s = (ms + 999) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
