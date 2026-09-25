package br.com.riodosul.niveldorio;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DailyCounter {
    private DailyCounter() {}

    public static int update(Context context) {
        try {
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            SharedPreferences p = AppPrefs.prefs(context);
            String lastDay = p.getString("counter_day", "");
            boolean alreadyCounted = day.equals(lastDay);
            String key = "riodosul-nivel-app-" + day;
            String endpoint = alreadyCounted
                    ? "https://countapi.mileshilliard.com/api/v1/get/" + key
                    : "https://countapi.mileshilliard.com/api/v1/hit/" + key;
            String json = get(endpoint);
            Matcher m = Pattern.compile("\\\"value\\\"\\s*:\\s*(\\d+)").matcher(json);
            int value = m.find() ? Integer.parseInt(m.group(1)) : -1;
            if (!alreadyCounted && value >= 0) p.edit().putString("counter_day", day).apply();
            if (value >= 0) p.edit().putInt("counter_value_" + day, value).apply();
            return value;
        } catch (Exception e) {
            String day = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());
            return AppPrefs.prefs(context).getInt("counter_value_" + day, -1);
        }
    }

    private static String get(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        c.setRequestProperty("User-Agent", "NivelDoRioAndroid/0.1");
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        String line;
        while ((line = br.readLine()) != null) sb.append(line);
        br.close();
        c.disconnect();
        return sb.toString();
    }
}
