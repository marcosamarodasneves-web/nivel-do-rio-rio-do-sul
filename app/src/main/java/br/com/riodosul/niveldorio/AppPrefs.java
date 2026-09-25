package br.com.riodosul.niveldorio;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    private static final String PREFS = "river_prefs";
    private static final String KEY_BRIDGE = "selected_bridge";

    private AppPrefs() {}

    public static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Bridge getSelectedBridge(Context c) {
        int idx = prefs(c).getInt(KEY_BRIDGE, 0);
        Bridge[] values = Bridge.values();
        if (idx < 0 || idx >= values.length) idx = 0;
        return values[idx];
    }

    public static void setSelectedBridge(Context c, Bridge bridge) {
        prefs(c).edit().putInt(KEY_BRIDGE, bridge.ordinal()).apply();
    }

    public static Bridge nextBridge(Context c) {
        Bridge current = getSelectedBridge(c);
        Bridge[] all = Bridge.values();
        Bridge next = all[(current.ordinal() + 1) % all.length];
        setSelectedBridge(c, next);
        return next;
    }
}
