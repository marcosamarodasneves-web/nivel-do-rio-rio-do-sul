package br.com.riodosul.niveldorio;

import java.util.ArrayList;
import java.util.List;

/** Resultado da previsão para a próxima hora, com tudo o que o memorial descritivo precisa. */
public class Forecast {
    /** Parcela (em cm) que cada fator somou ou tirou da previsão. */
    public static class Factor {
        public static final String TENDENCIA = "tendencia";
        public static final String MONTANTE = "montante";
        public static final String BARRAGENS = "barragens";
        public static final String CHUVA_OBSERVADA = "chuva_obs";
        public static final String CHUVA_PREVISTA = "chuva_prev";

        public String key;
        public String title;
        public double cm;
        public String detail;

        public Factor(String key, String title, double cm, String detail) {
            this.key = key;
            this.title = title;
            this.cm = cm;
            this.detail = detail;
        }
    }

    /** Previsão para um horizonte (1, 2 ou 3 horas à frente), com as mesmas parcelas da conta. */
    public static class Horizon {
        public int hours;
        public long targetTime;
        public double predictedLevel;
        public double deltaCm;
        public double bandCm;
        /** 2 = alta, 1 = média, 0 = baixa. */
        public int confidence;
        public double trendCm;
        public double upstreamCm;
        public double damCm;
        public double rainObservedCm;
        public double rainForecastCm;

        public String arrow() {
            if (deltaCm >= 1.0) return "↗";
            if (deltaCm <= -1.0) return "↘";
            return "→";
        }

        public String confidenceLabel() {
            return confidence >= 2 ? "alta" : confidence == 1 ? "média" : "baixa";
        }
    }

    public Bridge bridge;
    /** Previsões de 1, 2 e 3 horas (a primeira repete os campos principais desta classe). */
    public final List<Horizon> horizons = new ArrayList<>();
    /** false quando não havia leituras suficientes para calcular. */
    public boolean available;
    public String unavailableReason = "";

    public double currentLevel;
    public double predictedLevel;
    public double deltaCm;
    /** Margem de erro estimada (±, em cm). */
    public double bandCm;
    /** 2 = alta, 1 = média, 0 = baixa. */
    public int confidence;
    public long baseTime;
    public long targetTime;

    /** Velocidade medida, em cm/h (positiva = subindo). */
    public double rate60;
    public double rate180;
    /** Oscilação das leituras em torno da reta de tendência (cm). */
    public double noiseCm;
    public int samplesUsed;
    public int windowMinutes;

    public String upstreamSummary = "";
    public String damSummary = "";
    public String rainObservedSummary = "";
    public String rainForecastSummary = "";
    public String bulletinSummary = "";

    public double rainBasin1hMm = Double.NaN;
    public double rainBasin24hMm = Double.NaN;
    public double rainNextHourMm = Double.NaN;
    public double rainNext2hMm = Double.NaN;
    public double rainNext3hMm = Double.NaN;
    public int rainProbability = -1;

    public Cotas cotas = new Cotas();
    public final List<Factor> factors = new ArrayList<>();
    /** Avisos: leitura antiga, dado faltando, cota que deve ser cruzada etc. */
    public final List<String> notes = new ArrayList<>();

    /** Horizonte pedido (1 a 3 h) ou null quando não foi calculado. */
    public Horizon horizon(int hours) {
        for (Horizon h : horizons) if (h.hours == hours) return h;
        return null;
    }

    public double sumCm() {
        double s = 0;
        for (Factor f : factors) s += f.cm;
        return s;
    }

    public String confidenceLabel() {
        return confidence >= 2 ? "alta" : confidence == 1 ? "média" : "baixa";
    }

    /** "subindo", "descendo" ou "estável", pela variação prevista. */
    public String direction() {
        if (deltaCm >= 1.0) return "subindo";
        if (deltaCm <= -1.0) return "descendo";
        return "estável";
    }

    public String arrow() {
        if (deltaCm >= 1.0) return "↗";
        if (deltaCm <= -1.0) return "↘";
        return "→";
    }
}
