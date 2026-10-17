package nz.pesh.gotr;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** The floating vertical strip drawn over other apps. */
public class OverlayService extends Service {
    private static final int BAR = Color.parseColor("#E6151A24");
    private static final int LINE = Color.parseColor("#2A3242");
    private static final int FG = Color.parseColor("#E8ECF3");
    private static final int MUTED = Color.parseColor("#8A93A6");
    private static final int WARN = Color.parseColor("#FFD23F");
    private static final int WARN_INK = Color.parseColor("#1A1400");
    private static final int LIVE = Color.parseColor("#3DDC97");
    private static final int LIVE_INK = Color.parseColor("#04150D");
    private static final int STOP = Color.parseColor("#FF5C5C");

    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private LinearLayout root;
    private GradientDrawable rootBg;
    private TextView time, sync, toggle, close;
    private View fill;
    private int trackH;

    private final Handler h = new Handler(Looper.getMainLooper());
    private boolean running;
    private long start, anchor = -1, beeped = -1;
    private ToneGenerator tone;
    private long first, every, warn, open;
    private boolean beepOn;

    private final Runnable tick = new Runnable() {
        @Override public void run() { update(); if (running) h.postDelayed(this, 200); }
    };

    @Override public IBinder onBind(Intent i) { return null; }

    @Override
    public void onCreate() {
        super.onCreate();
        startInForeground();
        loadPrefs();
        try { tone = new ToneGenerator(AudioManager.STREAM_MUSIC, 90); } catch (Exception e) { tone = null; }
        buildView();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "STOP".equals(intent.getAction())) { stopSelf(); return START_NOT_STICKY; }
        loadPrefs();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        h.removeCallbacks(tick);
        if (root != null) try { wm.removeView(root); } catch (Exception ignored) {}
        if (tone != null) tone.release();
        super.onDestroy();
    }

    private void startInForeground() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("gotr", "Floating timer", NotificationManager.IMPORTANCE_LOW));
        PendingIntent openApp = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent stop = PendingIntent.getService(this, 1,
                new Intent(this, OverlayService.class).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, "gotr")
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle("GOTR timer is showing")
                .setContentText("Tap to change timings")
                .setContentIntent(openApp)
                .addAction(new Notification.Action.Builder(null, "Close timer", stop).build())
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(1, n);
        }
    }

    private void loadPrefs() {
        SharedPreferences p = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        first = p.getInt("first", 160) * 1000L;
        every = p.getInt("every", 140) * 1000L;
        warn = p.getInt("warn", 10) * 1000L;
        open = p.getInt("open", 25) * 1000L;
        beepOn = p.getBoolean("beep", true);
    }

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

        trackH = dp(70);
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
        sync.setOnClickListener(v -> { if (!running) startRun(); anchor = System.currentTimeMillis(); beeped = -1; update(); });
        toggle = button("▶", LIVE, LIVE_INK, false);
        toggle.setOnClickListener(v -> { if (running) stopRun(); else startRun(); });
        close = new TextView(this);
        close.setText("✕");
        close.setTextColor(MUTED);
        close.setTextSize(13);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, dp(4), 0, 0);
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

        // Drag the strip by the time display
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

    private void startRun() {
        loadPrefs();
        running = true;
        start = System.currentTimeMillis();
        anchor = -1;
        beeped = -1;
        setButton(toggle, "■", STOP, Color.parseColor("#1A0505"));
        h.removeCallbacks(tick);
        h.post(tick);
    }

    private void stopRun() {
        running = false;
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
        GradientDrawable sb = (GradientDrawable) sync.getBackground();
        sb.setStroke(dp(1), bg == BAR ? LINE : text);
        sync.setTextColor(text);
    }

    private void setFill(float f) {
        f = Math.max(0f, Math.min(1f, f));
        FrameLayout.LayoutParams p = (FrameLayout.LayoutParams) fill.getLayoutParams();
        p.height = Math.round(trackH * f);
        fill.setLayoutParams(p);
    }

    private static String fmt(long ms) {
        long s = (ms + 999) / 1000;
        return (s / 60) + ":" + (s % 60 < 10 ? "0" : "") + (s % 60);
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
}
