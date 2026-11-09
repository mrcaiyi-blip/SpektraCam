package org.spektracam;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

abstract class LocalizedActivity extends Activity {
    private static final String PREFS = "capture_settings";
    private static final String LANGUAGE = "app_language";
    private Locale appliedLocale;

    static String languageChoice(Context context) {
        return context.getSharedPreferences(PREFS, MODE_PRIVATE).getString(LANGUAGE, "system");
    }

    static void setLanguageChoice(Context context, String choice) {
        context.getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(LANGUAGE, choice).apply();
    }

    private static Locale appLocale(Context context) {
        String choice = languageChoice(context);
        if ("zh".equals(choice)) return Locale.SIMPLIFIED_CHINESE;
        if ("en".equals(choice)) return Locale.ENGLISH;
        Locale system = Resources.getSystem().getConfiguration().getLocales().get(0);
        String language = system == null ? "" : system.getLanguage();
        return switch (language) {
            case "zh", "yue", "cmn" -> Locale.SIMPLIFIED_CHINESE;
            default -> Locale.ENGLISH;
        };
    }

    @Override protected void attachBaseContext(Context base) {
        appliedLocale = appLocale(base);
        Configuration config = new Configuration();
        config.setLocale(appliedLocale);
        super.attachBaseContext(base.createConfigurationContext(config));
    }

    @Override protected void onResume() {
        super.onResume();
        if (!appliedLocale.equals(appLocale(this))) recreate();
    }

    @Override public void onConfigurationChanged(Configuration config) {
        super.onConfigurationChanged(config);
        if (!appliedLocale.equals(appLocale(this))) recreate();
    }
}
