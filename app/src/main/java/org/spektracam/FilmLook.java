package org.spektracam;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A capture stock and the print medium selected for it. Reversal film scans directly. */
final class FilmLook {
    static final String PREFIX = "spektrafilm/";

    record Print(String id, String title, int order) {}

    final String id, title, paper, finishTitle;
    final int category;
    final List<Print> prints;
    final int order;

    private String keyForPrint() { return paper.equals(defaultPaper()) ? id : id + "--" + paper; }
    private String defaultPaper() { return defaultPrint.id(); }

    // Keep the default separate from the current selection so asset paths and fallback agree.
    private FilmLook(FilmLook base, Print selected) {
        this(base.id, base.title, base.category, base.order, base.prints, base.defaultPrint, selected);
    }

    private final Print defaultPrint;

    private FilmLook(String id, String title, int category, int order, List<Print> prints,
                     Print defaultPrint, Print selected) {
        this.id = id;
        this.title = title;
        this.category = category;
        this.order = order;
        this.prints = prints;
        this.defaultPrint = defaultPrint;
        paper = selected.id();
        finishTitle = selected.title();
    }

    FilmLook selectingPaper(String id) {
        Print selected = prints.stream().filter(option -> option.id().equals(id)).findFirst().orElse(defaultPrint);
        return paper.equals(selected.id()) ? this : new FilmLook(this, selected);
    }

    String previewLut() {
        return "spektra/" + (paper.equals(defaultPaper()) ? "preview_luts/" : "print_variants/") + keyForPrint() + ".plut";
    }

    String sampleAsset() {
        return "spektra/" + (paper.equals(defaultPaper()) ? "samples/" : "film_samples/") + keyForPrint() + ".jpg";
    }

    String finishTitle(android.content.Context context) {
        return paper.isEmpty() ? context.getString(R.string.direct_scan) : finishTitle;
    }

    String key() { return PREFIX + id; }

    static List<FilmLook> load(AssetManager assets) throws Exception {
        JSONObject catalog = read(assets, "spektra/catalog.json").getJSONObject("stocks");
        JSONObject preview = read(assets, "spektra/preview_luts/manifest.json");
        JSONObject alternatives = read(assets, "spektra/print_variants/manifest.json");
        if (preview.getInt("schema") != 1 || preview.getInt("size") != 65 || alternatives.getInt("schema") != 1) {
            throw new IOException("Unsupported Spektrafilm preview LUT contract");
        }
        Map<String, String> defaults = new HashMap<>();
        JSONArray entries = preview.getJSONArray("looks");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            if (defaults.put(entry.getString("film"), entry.getString("print")) != null) {
                throw new IOException("Duplicate Spektrafilm preview LUT");
            }
        }

        Map<String, Set<String>> variants = new HashMap<>();
        entries = alternatives.getJSONArray("looks");
        if (entries.length() != 60) throw new IOException("Unexpected Spektrafilm print catalog");
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.getJSONObject(i);
            String film = entry.getString("film");
            if (!defaults.containsKey(film)
                    || !variants.computeIfAbsent(film, ignored -> new HashSet<>()).add(entry.getString("print"))) {
                throw new IOException("Invalid Spektrafilm print variant: " + film);
            }
        }

        List<Print> papers = printsIn(catalog, "print_paper");
        List<Print> printFilms = printsIn(catalog, "print_film");
        List<FilmLook> looks = new ArrayList<>();
        for (Map.Entry<String, String> entry : defaults.entrySet()) {
            String id = entry.getKey();
            JSONObject stock = catalog.getJSONObject(id);
            int category;
            int groupOrder;
            List<Print> prints;
            switch (stock.getString("group")) {
                case "color_negative": category = R.string.color_negative; groupOrder = 0; prints = papers; break;
                case "color_reversal": category = R.string.color_reversal; groupOrder = 100; prints = Collections.emptyList(); break;
                case "motion_picture_negative": category = R.string.motion_picture_negative; groupOrder = 200; prints = printFilms; break;
                default: throw new IOException("Unsupported Spektrafilm stock: " + id);
            }
            JSONObject profile = read(assets, "spektra/profiles/" + id + ".json").getJSONObject("info");
            String paper = "positive".equals(profile.getString("type")) ? "" : profile.optString("target_print", "");
            Print selected = prints.stream().filter(option -> option.id().equals(paper)).findFirst().orElse(null);
            if (prints.isEmpty() && paper.isEmpty()) selected = new Print("", "", 0);
            Set<String> expected = new HashSet<>();
            for (Print option : prints) if (!option.id().equals(paper)) expected.add(option.id());
            if (!paper.equals(entry.getValue()) || selected == null
                    || !expected.equals(variants.getOrDefault(id, Collections.emptySet()))) {
                throw new IOException("Invalid Spektrafilm print pairing: " + id);
            }
            looks.add(new FilmLook(id, stock.getString("name"), category, groupOrder + stock.getInt("order"),
                    prints, selected, selected));
        }
        looks.sort(Comparator.comparingInt((FilmLook look) -> look.order).thenComparing(look -> look.id));
        if (looks.size() != 20) throw new IOException("Unexpected Spektrafilm capture catalog");
        return looks;
    }

    private static List<Print> printsIn(JSONObject catalog, String group) throws Exception {
        List<Print> prints = new ArrayList<>();
        java.util.Iterator<String> keys = catalog.keys();
        while (keys.hasNext()) {
            String id = keys.next();
            JSONObject stock = catalog.getJSONObject(id);
            if (group.equals(stock.getString("group"))) {
                prints.add(new Print(id, stock.getString("name"), stock.getInt("order")));
            }
        }
        prints.sort(Comparator.comparingInt(Print::order).thenComparing(Print::id));
        return Collections.unmodifiableList(prints);
    }

    private static JSONObject read(AssetManager assets, String path) throws Exception {
        try (InputStream stream = assets.open(path)) {
            java.io.ByteArrayOutputStream result = new java.io.ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            for (int count; (count = stream.read(chunk)) != -1; ) result.write(chunk, 0, count);
            return new JSONObject(new String(result.toByteArray(), StandardCharsets.UTF_8));
        }
    }
}
