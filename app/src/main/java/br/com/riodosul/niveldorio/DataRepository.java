package br.com.riodosul.niveldorio;

import android.content.Context;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DataRepository {
    public static final String PORTAL_URL = "https://defesacivil.riodosul.sc.gov.br/";

    private DataRepository() {}

    public static Snapshot fetch(Context context) throws Exception {
        String html = httpGet(PORTAL_URL);
        String text = htmlToText(html);
        Snapshot s = parse(text);
        s.fetchedAt = System.currentTimeMillis();
        s.fromCache = false;
        if (s.rivers.isEmpty()) throw new IllegalStateException("Nenhuma leitura de rio encontrada no portal.");
        for (RiverReading r : s.rivers.values()) HistoryStore.add(context, r.bridge, r.levelMeters);
        AppCache.save(context, s);
        return s;
    }

    public static Snapshot fetchOrCache(Context context) {
        try { return fetch(context); }
        catch (Exception e) {
            Snapshot cached = AppCache.load(context);
            if (cached != null) return cached;
            return new Snapshot();
        }
    }

    static Snapshot parse(String text) {
        Snapshot s = new Snapshot();
        for (Bridge b : Bridge.values()) {
            String section = section(text, b.portalMarker, nextRiverMarker(b));
            RiverReading rr = parseRiver(b, section);
            if (rr != null) s.rivers.put(b, rr);
        }
        String dams = section(text, "Barragem Oeste", "Defesa Civil de Rio do Sul");
        s.taio = parseDam("Taió", dams, "Barragem Oeste", 7);
        String itupSection = section(text, "Barragem Sul", "Defesa Civil de Rio do Sul");
        s.ituporanga = parseDam("Ituporanga", itupSection, "Barragem Sul", 5);
        return s;
    }

    private static String nextRiverMarker(Bridge b) {
        switch (b) {
            case DOM_TITO: return "Ponte Ricardo Kanitz";
            case RICARDO_KANITZ: return "Ponte BR 470";
            default: return "Câmera ao Vivo";
        }
    }

    private static RiverReading parseRiver(Bridge b, String section) {
        if (section == null || section.isEmpty()) return null;
        Matcher lm = Pattern.compile("([0-9]{1,2}[,.][0-9]{1,2})\\s*m\\s*Nível do rio", Pattern.CASE_INSENSITIVE).matcher(section);
        if (!lm.find()) {
            lm = Pattern.compile("([0-9]{1,2}[,.][0-9]{1,2})\\s*m", Pattern.CASE_INSENSITIVE).matcher(section);
            if (!lm.find()) return null;
        }
        double level = number(lm.group(1));
        String status = firstGroup(section, "\\b(Normal|Atenção|Alerta!|Alerta|Emergência)\\b");
        if (status == null) status = statusFromLevel(level);
        String time = firstGroup(section, "Leitura:\\s*([^\\n]{5,40})");
        if (time == null) time = "horário não informado";
        return new RiverReading(b, level, status, time.trim());
    }

    private static DamReading parseDam(String name, String section, String marker, int gatesTotal) {
        if (section == null || section.isEmpty()) return null;
        Matcher lm = Pattern.compile("([0-9]{1,2}[,.][0-9]{1,2})\\s*m\\s*([0-9]{1,3}(?:[,.][0-9]{1,2})?)%\\s*da capacidade", Pattern.CASE_INSENSITIVE).matcher(section);
        if (!lm.find()) return null;
        double level = number(lm.group(1));
        double pct = number(lm.group(2));
        int open = 0;
        Matcher gm = Pattern.compile("(\\d+)\\s*de\\s*" + gatesTotal + "\\s*abertas", Pattern.CASE_INSENSITIVE).matcher(section);
        if (gm.find()) open = Integer.parseInt(gm.group(1));
        String age = firstGroup(section, "Leitura\\s+([^\\n]{3,40})");
        return new DamReading(name, level, pct, open, gatesTotal, age == null ? "" : age.trim());
    }

    private static String section(String text, String start, String end) {
        int a = indexOfIgnoreCase(text, start, 0);
        if (a < 0) return "";
        int b = end == null ? -1 : indexOfIgnoreCase(text, end, a + start.length());
        if (b < 0) b = Math.min(text.length(), a + 3000);
        return text.substring(a, b);
    }

    private static int indexOfIgnoreCase(String s, String find, int from) {
        return s.toLowerCase(Locale.ROOT).indexOf(find.toLowerCase(Locale.ROOT), from);
    }

    private static String firstGroup(String s, String regex) {
        Matcher m = Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(s);
        return m.find() ? m.group(1) : null;
    }

    private static double number(String s) {
        String n = s.trim();
        if (n.contains(",")) n = n.replace(".", "").replace(',', '.');
        return Double.parseDouble(n);
    }

    private static String statusFromLevel(double level) {
        if (level < 4.50) return "Normal";
        if (level < 5.50) return "Atenção";
        if (level < 6.50) return "Alerta";
        return "Emergência";
    }

    private static String httpGet(String urlString) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(urlString).openConnection();
        c.setConnectTimeout(12000);
        c.setReadTimeout(15000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("User-Agent", "NivelDoRioAndroid/0.1 (+Rio do Sul; uso informativo)");
        c.setRequestProperty("Accept", "text/html,application/xhtml+xml");
        int code = c.getResponseCode();
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int n;
        while ((n = br.read(buf)) >= 0) sb.append(buf, 0, n);
        br.close();
        c.disconnect();
        return sb.toString();
    }

    static String htmlToText(String html) {
        String x = html;
        x = x.replaceAll("(?is)<script[^>]*>.*?</script>", " ");
        x = x.replaceAll("(?is)<style[^>]*>.*?</style>", " ");
        x = x.replaceAll("(?i)<br\\s*/?>", "\\n");
        x = x.replaceAll("(?i)</(div|p|section|article|h1|h2|h3|h4|li|tr)>", "\\n");
        x = x.replaceAll("(?s)<[^>]+>", " ");
        x = x.replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"")
                .replace("&#039;", "'").replace("&aacute;", "á").replace("&atilde;", "ã")
                .replace("&ccedil;", "ç").replace("&eacute;", "é").replace("&iacute;", "í")
                .replace("&oacute;", "ó").replace("&uacute;", "ú");
        x = x.replaceAll("[\\t\\x0B\\f\\r ]+", " ");
        x = x.replaceAll(" *\\n *", "\\n");
        x = x.replaceAll("\\n{2,}", "\\n");
        return x.trim();
    }
}
