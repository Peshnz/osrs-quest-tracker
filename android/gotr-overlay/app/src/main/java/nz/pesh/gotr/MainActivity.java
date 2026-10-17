package nz.pesh.gotr;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Settings screen: timings, overlay permission, show/close the floating timer. */
public class MainActivity extends Activity {
    static final String PREFS = "gotr";
    private EditText first, every, warn, open;
    private CheckBox beep;
    private Button permBtn;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(24);
        col.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("GOTR Portal Timer");
        title.setTextSize(24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        col.addView(title);

        TextView help = new TextView(this);
        help.setText("Show the floating timer, open OSRS, and tap ▶ when the rift opens. "
                + "Tap P when a portal appears to resync. Drag the time to move the strip. ✕ closes it.");
        help.setPadding(0, dp(8), 0, dp(16));
        help.setTextColor(Color.parseColor("#9AA3B5"));
        col.addView(help);

        first = field(col, "First portal (seconds)", p.getInt("first", 160));
        every = field(col, "Then every (seconds)", p.getInt("every", 140));
        warn = field(col, "Flash before (seconds)", p.getInt("warn", 10));
        open = field(col, "Portal open (seconds)", p.getInt("open", 25));

        beep = new CheckBox(this);
        beep.setText("Beep when it starts flashing");
        beep.setChecked(p.getBoolean("beep", true));
        col.addView(beep);

        permBtn = new Button(this);
        permBtn.setText("Allow display over other apps");
        permBtn.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()))));
        col.addView(permBtn);

        Button show = new Button(this);
        show.setText("Show floating timer");
        show.setOnClickListener(v -> {
            save();
            if (!Settings.canDrawOverlays(this)) {
                Toast.makeText(this, "Allow display over other apps first", Toast.LENGTH_LONG).show();
                return;
            }
            startForegroundService(new Intent(this, OverlayService.class));
            moveTaskToBack(true);
        });
        col.addView(show);

        Button hide = new Button(this);
        hide.setText("Close floating timer");
        hide.setOnClickListener(v -> stopService(new Intent(this, OverlayService.class)));
        col.addView(hide);

        ScrollView sv = new ScrollView(this);
        sv.addView(col);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        permBtn.setVisibility(Settings.canDrawOverlays(this) ? android.view.View.GONE : android.view.View.VISIBLE);
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
    }

    private void save() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt("first", num(first, 160))
                .putInt("every", num(every, 140))
                .putInt("warn", num(warn, 10))
                .putInt("open", num(open, 25))
                .putBoolean("beep", beep.isChecked())
                .apply();
    }

    private int num(EditText e, int def) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return v > 0 ? v : def;
        } catch (Exception ex) {
            return def;
        }
    }

    private EditText field(LinearLayout col, String label, int value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, dp(8), 0, 0);
        col.addView(l);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        e.setGravity(Gravity.START);
        col.addView(e);
        return e;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
