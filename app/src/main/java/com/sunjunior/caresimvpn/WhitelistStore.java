package com.sunjunior.caresimvpn;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public final class WhitelistStore {
    private WhitelistStore() {}

    /** 按 逗号/分号/空白/换行 切分 → 去空 → 小写 → 保序去重 */
    public static List<String> normalize(String raw) {
        if (raw == null) return new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String tok : raw.split("[,;\\s]+")) {
            String t = tok.trim().toLowerCase();
            if (!t.isEmpty()) seen.add(t);
        }
        return new ArrayList<>(seen);
    }

    public static List<String> load(Context ctx) {
        SharedPreferences p = ctx.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE);
        return normalize(p.getString("raw", null));
    }

    public static String loadRaw(Context ctx) {
        return ctx.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE).getString("raw", "");
    }

    public static void save(Context ctx, String raw) {
        ctx.getSharedPreferences("whitelist_prefs", Context.MODE_PRIVATE)
                .edit().putString("raw", raw == null ? "" : raw).apply();
    }
}
