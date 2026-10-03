/*
 * T65 — crash report screen. Runs in the dedicated ":crash" process so it
 * SURVIVES the crash-looping main process: whatever killed the app last time
 * cannot kill this screen. The user can then share the exact stack trace
 * through any messenger with one tap.
 *
 * Fully programmatic UI (no layout resources, no app themes, no framework
 * singletons) so this screen has no dependency on whatever crashed.
 */
package org.telegram.messenger;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

public class XoCrashReportActivity extends Activity {

    private String reportText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        reportText = readReport();
        String header;
        if (reportText != null) {
            int firstLineEnd = reportText.indexOf('\n');
            int secondLineEnd = reportText.indexOf('\n', Math.max(0, firstLineEnd + 1));
            header = reportText.substring(0,
                    secondLineEnd > 0 ? secondLineEnd : reportText.length());
        } else {
            header = "Hermes — no crash report found";
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(Color.WHITE);

        TextView title = new TextView(this);
        title.setText("گزارش خطا — Hermes");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.BLACK);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView subtitle = new TextView(this);
        subtitle.setText("برنامه در اجرای قبلی متوقف شده است. جزئیات فنی زیر با دکمهٔ پایین قابل ارسال است.");
        subtitle.setTextSize(14);
        subtitle.setTextColor(Color.DKGRAY);
        subtitle.setPadding(0, pad / 2, 0, pad / 2);
        root.addView(subtitle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView body = new TextView(this);
        body.setText(reportText != null ? reportText : "(empty)");
        body.setTextSize(11);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextColor(Color.BLACK);
        body.setMovementMethod(new ScrollingMovementMethod());
        ScrollView scroll = new ScrollView(this);
        scroll.addView(body);
        LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        bodyLp.topMargin = pad / 2;
        root.addView(scroll, bodyLp);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        buttons.setGravity(Gravity.END);

        Button close = new Button(this);
        close.setText("بستن");
        close.setOnClickListener(v -> {
            XoCrash.clearAll();
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                finishAndRemoveTask();
            } else {
                finish();
            }
        });
        buttons.addView(close, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        Button share = new Button(this);
        share.setText("ارسال گزارش");
        share.setOnClickListener(v -> shareReport());
        buttons.addView(share, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(buttons, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private String readReport() {
        // 1) the intent extra (race-free: ApplicationLoader consumed the live
        //    file into the archive before launching this process)
        try {
            String extra = getIntent().getStringExtra("report");
            if (extra != null && extra.length() > 0) {
                return extra;
            }
        } catch (Throwable ignore) {
        }
        // 2) the live file (report screen opened while the crash is fresh)
        return XoCrash.readLiveReport();
    }

    private void shareReport() {
        try {
            Intent intent = new Intent(Intent.ACTION_SEND);
            intent.setType("text/plain");
            intent.putExtra(Intent.EXTRA_SUBJECT, "Hermes crash report");
            intent.putExtra(Intent.EXTRA_TEXT,
                    reportText != null ? reportText : "(no report text)");
            startActivity(Intent.createChooser(intent, "ارسال گزارش خطا"));
        } catch (Throwable ignore) {
        }
    }

    @Override
    public void onBackPressed() {
        XoCrash.clearAll();
        super.onBackPressed();
    }
}
