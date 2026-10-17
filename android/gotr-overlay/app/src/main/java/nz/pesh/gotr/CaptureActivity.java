package nz.pesh.gotr;

import android.app.Activity;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;

/** Invisible screen that asks Android for screen-capture consent, then hands the result to the overlay. */
public class CaptureActivity extends Activity {
    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), 7);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        Intent i = new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_CAPTURE);
        i.putExtra("code", result);
        if (result == RESULT_OK && data != null) i.putExtra("data", data);
        startForegroundService(i);
        finish();
    }
}
