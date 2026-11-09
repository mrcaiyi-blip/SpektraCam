package org.spektracam;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONException;
import org.json.JSONObject;

public final class FilmSettingsActivity extends LocalizedActivity {
    private static final int BG = 0xff0b0d11, CARD = 0xff171b21, TEXT = 0xfff4f5f6;
    private static final int MUTED = 0xff9aa3ad, ACCENT = 0xffe6bc70;
    private static final String[] IDS = {"histogram_middle_gray", "center_weighted", "matrix", "multi_zone",
            "partial", "highlight_weighted", "median", "average"};
    private static final int[] TITLES = {R.string.histogram_gray, R.string.center_weighted,
            R.string.matrix_meter, R.string.multi_zone, R.string.partial_meter,
            R.string.highlight_weighted, R.string.median_meter, R.string.average_meter};
    private static final String[] LANGUAGE_CHOICES = {"system", "zh", "en"};
    private static final String UPSTREAM_ANDROID = "https://github.com/thetechgeekko/Spektrafilm-android";
    private static final String UPSTREAM_MODEL = "https://github.com/andreavolpato/spektrafilm";
    private SharedPreferences preferences;
    private LinearLayout content;
    private boolean subpage;
    private JSONObject supportLinks;

    static String meter(SharedPreferences preferences) { return IDS[meterIndex(preferences)]; }

    private static int meterIndex(SharedPreferences preferences) {
        String method = preferences.getString("film_exposure_method", IDS[0]);
        for (int i = 0; i < IDS.length; ++i) if (IDS[i].equals(method)) return i;
        return 0;
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        preferences = getSharedPreferences("capture_settings", MODE_PRIVATE);
        supportLinks = loadSupportLinks();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().setNavigationBarColor(BG);
        settings();
    }

    private void settings() {
        page(getString(R.string.settings_title), false, this::finish);
        LinearLayout film = section(getString(R.string.film_settings));
        row(film, getString(R.string.auto_exposure), getString(TITLES[meterIndex(preferences)]) + "  ›", this::meterPage);
        note(film, getString(R.string.meter_description));
        LinearLayout language = section(getString(R.string.language));
        row(language, getString(R.string.language), languageTitle(languageChoice(this)) + "  ›", this::languagePage);
        addSupportSection();
        LinearLayout legal = section(getString(R.string.licenses));
        row(legal, "Spektrafilm for Android · Akshay Sharma", "↗", () -> open(UPSTREAM_ANDROID));
        row(legal, "spektrafilm · Andrea Volpato", "↗", () -> open(UPSTREAM_MODEL));
        row(legal, "GPLv3", "›", () -> license("GPLv3", "LICENSE"));
        row(legal, getString(R.string.profile_license), "›", () -> license(getString(R.string.profile_license), "PROFILE_LICENSE.txt"));
        row(legal, getString(R.string.third_party_credits), "›", () -> license(getString(R.string.third_party_credits), "NOTICE.md"));
        LinearLayout samples = section(getString(R.string.sample_photo));
        row(samples, "Google HDR+ Burst Photography Dataset", "↗",
                () -> open("https://www.hdrplusdata.org/dataset.html"));
        note(samples, getString(R.string.sample_photo_credit));
        row(samples, "CC BY-SA 4.0", "↗", () -> open("https://creativecommons.org/licenses/by-sa/4.0/"));
    }

    private JSONObject loadSupportLinks() {
        try (InputStream stream = getAssets().open("support-links.local.json");
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[1024];
            for (int count; (count = stream.read(buffer)) != -1; ) bytes.write(buffer, 0, count);
            return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
        } catch (IOException | JSONException error) {
            return new JSONObject();
        }
    }

    private void addSupportSection() {
        String account = supportLinks.optString("alipay_account");
        String group = supportLinks.optString("qq_group");
        Uri paymentCode = paymentCode();
        boolean hasAccount = account.matches("1[0-9]{10}");
        boolean hasCommunity = group.matches("[1-9][0-9]{4,11}");
        if (paymentCode == null && !hasAccount && !hasCommunity) return;

        LinearLayout support = section(getString(R.string.support_community));
        if (paymentCode != null || hasAccount) {
            Uri.Builder alipay = new Uri.Builder().scheme("alipays").authority("platformapi").path("startapp");
            Uri donation = paymentCode != null
                    ? alipay.appendQueryParameter("saId", "10000007")
                            .appendQueryParameter("clientVersion", "3.7.0.0718")
                            .appendQueryParameter("qrcode", paymentCode.toString()).build()
                    : alipay.appendQueryParameter("appId", "09999988")
                            .appendQueryParameter("actionType", "toAccount")
                            .appendQueryParameter("account", account).build();
            row(support, getString(R.string.donate), getString(R.string.alipay) + " ↗", () -> openSupport(donation, "com.eg.android.AlipayGphone",
                    paymentCode,
                    hasAccount ? getString(R.string.alipay_transfer_help) : getString(R.string.alipay_link_help),
                    hasAccount ? getString(R.string.copy_phone) : getString(R.string.copy_payment_link),
                    hasAccount ? account : paymentCode.toString()));
        }
        if (hasCommunity) {
            Uri join = new Uri.Builder().scheme("mqqapi").authority("card").path("show_pslcard")
                    .appendQueryParameter("src_type", "internal").appendQueryParameter("version", "1")
                    .appendQueryParameter("card_type", "group").appendQueryParameter("source", "external")
                    .appendQueryParameter("uin", group).build();
            row(support, getString(R.string.community), getString(R.string.qq_group) + " ↗", () -> openSupport(join, "com.tencent.mobileqq", null,
                    getString(R.string.qq_join_help), getString(R.string.copy_qq_group), group));
        }
    }

    private Uri paymentCode() {
        Uri uri = Uri.parse(supportLinks.optString("alipay_qr_url").trim());
        String path = uri.getPath();
        return "https".equalsIgnoreCase(uri.getScheme())
                && "qr.alipay.com".equalsIgnoreCase(uri.getEncodedAuthority())
                && path != null && path.length() > 1 ? uri : null;
    }

    private boolean launchSupport(Uri uri, String packageName) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, uri).setPackage(packageName));
            return true;
        } catch (android.content.ActivityNotFoundException | SecurityException error) {
            return false;
        }
    }

    private void openSupport(Uri uri, String packageName, Uri webFallback, String message,
                             String copyLabel, String value) {
        if (launchSupport(uri, packageName) || (webFallback != null && launchSupport(webFallback, null))) return;
        new AlertDialog.Builder(this).setMessage(message)
                .setNegativeButton(getString(R.string.cancel), null)
                .setPositiveButton(copyLabel, (dialog, button) ->
                        getSystemService(ClipboardManager.class).setPrimaryClip(
                                ClipData.newPlainText(copyLabel, value)))
                .show();
    }

    private String languageTitle(String choice) {
        return switch (choice) {
            case "zh" -> "简体中文";
            case "en" -> "English";
            default -> getString(R.string.follow_system);
        };
    }

    private void languagePage() {
        page(getString(R.string.language), true, this::settings);
        LinearLayout choices = section(getString(R.string.language));
        String current = languageChoice(this);
        for (String choice : LANGUAGE_CHOICES) {
            row(choices, languageTitle(choice), choice.equals(current) ? "✓" : "", () -> {
                if (!choice.equals(current)) {
                    setLanguageChoice(this, choice);
                    recreate();
                } else settings();
            });
        }
    }

    private void meterPage() {
        page(getString(R.string.film_auto_exposure), true, this::settings);
        LinearLayout choices = section(getString(R.string.raw_metering));
        int current = meterIndex(preferences);
        for (int i = 0; i < IDS.length; ++i) {
            int choice = i;
            row(choices, getString(TITLES[i]), i == current ? "✓" : "", () -> {
                preferences.edit().putString("film_exposure_method", IDS[choice]).apply();
                settings();
            });
        }
    }

    private void license(String title, String file) {
        page(title, true, this::settings);
        LinearLayout box = section(getString(R.string.license_text));
        try (InputStream stream = getAssets().open("spektrafilm/" + file);
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            for (int count; (count = stream.read(buffer)) >= 0; ) bytes.write(buffer, 0, count);
            TextView body = text(new String(bytes.toByteArray(), StandardCharsets.UTF_8), 12, TEXT);
            body.setTextIsSelectable(true);
            box.addView(body);
        } catch (IOException error) {
            note(box, getString(R.string.license_unavailable));
        }
    }

    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
        catch (android.content.ActivityNotFoundException error) {
            android.widget.Toast.makeText(this, url, android.widget.Toast.LENGTH_LONG).show();
        }
    }

    private void page(String title, boolean subpage, Runnable goBack) {
        this.subpage = subpage;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(12), 0, dp(20), 0);
        TextView back = text("‹", 32, ACCENT);
        back.setGravity(Gravity.CENTER);
        back.setContentDescription(getString(R.string.back));
        back.setOnClickListener(view -> goBack.run());
        bar.addView(back, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView heading = text(title, 20, TEXT);
        heading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
        bar.addView(text("SPEKTRACAM", 11, MUTED));
        root.addView(bar, new LinearLayout.LayoutParams(-1, dp(58)));
        ScrollView scroll = new ScrollView(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(14), dp(16), dp(16));
        scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }

    private LinearLayout section(String title) {
        TextView heading = text(title, 12, MUTED);
        heading.setPadding(dp(5), 0, 0, dp(8));
        content.addView(heading);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(15), dp(8), dp(15), dp(8));
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(CARD);
        shape.setCornerRadius(dp(20));
        box.setBackground(shape);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(18);
        content.addView(box, params);
        return box;
    }

    private void row(LinearLayout group, String name, String value, Runnable action) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(48));
        row.addView(text(name, 15, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text(value, 13, ACCENT));
        row.setOnClickListener(view -> action.run());
        row.setFocusable(true);
        row.setContentDescription(value.isEmpty() ? name : name + " " + value);
        group.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }

    private void note(LinearLayout group, String text) {
        TextView label = text(text, 12, MUTED);
        label.setPadding(0, dp(4), 0, dp(8));
        group.addView(label);
    }

    private TextView text(String value, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        return view;
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    @Override public void onBackPressed() {
        if (subpage) settings();
        else super.onBackPressed();
    }
}
