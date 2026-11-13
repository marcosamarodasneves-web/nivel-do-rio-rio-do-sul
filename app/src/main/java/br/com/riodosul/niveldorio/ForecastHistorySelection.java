package br.com.riodosul.niveldorio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Seleção e paginação pura da grade de histórico de previsões. */
public final class ForecastHistorySelection {
    public static final int PAGE_SIZE = 12;

    private ForecastHistorySelection() {
        // Utility class.
    }

    /**
     * Retorna os grupos na ordem original, opcionalmente somente os totalmente conferidos.
     * A lista recebida nunca é modificada.
     */
    public static List<HydrologyForecastClient.HistoryGroup> filter(
            List<HydrologyForecastClient.HistoryGroup> groups, boolean onlyChecked) {
        if (groups == null || groups.isEmpty()) return new ArrayList<>();
        if (!onlyChecked) return new ArrayList<>(groups);

        List<HydrologyForecastClient.HistoryGroup> result = new ArrayList<>();
        for (HydrologyForecastClient.HistoryGroup group : groups) {
            if (isFullyChecked(group)) result.add(group);
        }
        return result;
    }

    /**
     * Retorna uma nova lista com no máximo os primeiros {@code limit} grupos, na ordem original.
     * Listas nulas e limites não positivos resultam em uma lista vazia.
     */
    public static List<HydrologyForecastClient.HistoryGroup> visible(
            List<HydrologyForecastClient.HistoryGroup> groups, int limit) {
        if (groups == null || groups.isEmpty() || limit <= 0) return new ArrayList<>();
        int end = Math.min(limit, groups.size());
        return new ArrayList<>(groups.subList(0, end));
    }

    /**
     * Um grupo está totalmente conferido quando os três horizontes têm resultado válido.
     * Nível, erro e demais campos não participam da decisão.
     */
    public static boolean isFullyChecked(HydrologyForecastClient.HistoryGroup group) {
        if (group == null || group.rows == null || group.rows.length < 3) return false;
        for (int i = 0; i < 3; i++) {
            HydrologyForecastClient.HistoryRow row = group.rows[i];
            if (row == null || !isCheckedOutcome(row.outcome)) return false;
        }
        return true;
    }

    private static boolean isCheckedOutcome(String outcome) {
        if (outcome == null) return false;
        String normalized = outcome.trim().toLowerCase(Locale.ROOT);
        return "dentro".equals(normalized)
                || "quase".equals(normalized)
                || "fora".equals(normalized);
    }
}
