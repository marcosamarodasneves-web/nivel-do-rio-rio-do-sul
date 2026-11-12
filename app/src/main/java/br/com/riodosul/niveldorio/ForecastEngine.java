package br.com.riodosul.niveldorio;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Previsão aproximada do nível do rio para a próxima hora.
 *
 * A base é a tendência medida na própria ponte (reta de mínimos quadrados sobre a última hora de
 * leituras). O peso de 0,9 foi calibrado com 21 dias de leituras reais das estações de Rio do Sul
 * (out/nov de 2026, incluindo uma cheia de quase 9 m): nas pontes Dom Tito Buss e BR-470 o erro
 * médio da previsão de +1 h ficou em torno de 1 a 2 cm, contra 4 a 5 cm de simplesmente repetir o
 * nível atual. Os demais fatores (rios a montante, barragens e chuva) entram como ajustes pequenos
 * e limitados, porque em uma hora eles ainda pesam pouco e não foram calibrados com histórico.
 *
 * Classe sem dependência do Android, para poder ser testada fora do aparelho.
 */
public final class ForecastEngine {
    private static final Locale BR = new Locale("pt", "BR");
    private static final long MIN = 60_000L;
    private static final long HOUR = 60L * MIN;

    /** Peso da tendência medida (calibrado). */
    static final double PESO_TENDENCIA = 0.90;
    /** Peso da tendência para 2 e 3 horas (calibrado com o mesmo histórico de 21 dias). */
    static final double PESO_TENDENCIA_LONGO = 0.85;
    /** Peso da diferença entre a tendência a montante e a local. */
    static final double PESO_MONTANTE = 0.15;
    static final double LIMITE_MONTANTE_CM = 3.0;
    /** cm por comporta aberta/fechada entre 2 e 12 h atrás (as que podem estar chegando agora). */
    static final double CM_POR_COMPORTA = 0.25;
    static final double LIMITE_BARRAGENS_CM = 1.0;
    /** cm por mm de chuva média na bacia na última hora. */
    static final double CM_POR_MM_OBSERVADO = 0.30;
    static final double LIMITE_CHUVA_OBS_CM = 4.0;
    /** cm por mm de chuva prevista para a próxima hora. */
    static final double CM_POR_MM_PREVISTO = 0.15;
    static final double LIMITE_CHUVA_PREV_CM = 3.0;

    private ForecastEngine() {}

    public static class Upstream {
        public final String name;
        public final List<Sample> samples;
        public Upstream(String name, List<Sample> samples) {
            this.name = name;
            this.samples = samples;
        }
    }

    public static class Inputs {
        public Bridge bridge;
        public List<Sample> samples;
        public double currentLevel = Double.NaN;
        public long readingMillis;
        public long now;
        public Cotas cotas = new Cotas();
        public final List<Upstream> upstream = new ArrayList<>();
        public DamReading taio;
        public DamReading ituporanga;
        /** Média e máximo da chuva da última hora nas estações da bacia (mm). */
        public double rainBasin1hMean = Double.NaN;
        public double rainBasin1hMax = Double.NaN;
        public double rainBasin24hMean = Double.NaN;
        public int rainStations;
        /** Chuva prevista (modelo meteorológico) para a próxima hora e próximas 3 h (mm). */
        public double rainNextHourMm = Double.NaN;
        public double rainNext2hMm = Double.NaN;
        public double rainNext3hMm = Double.NaN;
        public int rainProbability = -1;
        public String bulletin = "";
    }

    /** Reta ajustada: inclinação em cm/h, valor no fim da janela (m) e oscilação (cm). */
    static class Fit {
        double rateCmH;
        double endValue;
        double noiseCm;
        int n;
        long spanMs;
    }

    static Fit fit(List<Sample> all, long end, long windowMs) {
        if (all == null) return null;
        List<Sample> pts = new ArrayList<>();
        for (Sample s : all) {
            if (s.timeMillis <= end && s.timeMillis >= end - windowMs && s.value > 0) pts.add(s);
        }
        int n = pts.size();
        if (n < 3) return null;
        long first = pts.get(0).timeMillis, last = pts.get(n - 1).timeMillis;
        if (last - first < windowMs * 0.5) return null;
        double mt = 0, mv = 0;
        for (Sample s : pts) { mt += (s.timeMillis - first) / 3600000.0; mv += s.value; }
        mt /= n; mv /= n;
        double sxx = 0, sxy = 0;
        for (Sample s : pts) {
            double t = (s.timeMillis - first) / 3600000.0 - mt;
            sxx += t * t;
            sxy += t * (s.value - mv);
        }
        if (sxx <= 0) return null;
        double slope = sxy / sxx; // m/h
        double sq = 0;
        for (Sample s : pts) {
            double t = (s.timeMillis - first) / 3600000.0 - mt;
            double r = s.value - (mv + slope * t);
            sq += r * r;
        }
        Fit f = new Fit();
        f.rateCmH = slope * 100.0;
        f.endValue = mv + slope * ((last - first) / 3600000.0 - mt);
        f.noiseCm = Math.sqrt(sq / n) * 100.0;
        f.n = n;
        f.spanMs = last - first;
        return f;
    }

    public static Forecast compute(Inputs in) {
        Forecast f = new Forecast();
        f.bridge = in.bridge;
        f.cotas = in.cotas == null ? new Cotas() : in.cotas;
        f.currentLevel = in.currentLevel;
        f.bulletinSummary = in.bulletin == null ? "" : in.bulletin;
        f.rainBasin1hMm = in.rainBasin1hMean;
        f.rainBasin24hMm = in.rainBasin24hMean;
        f.rainNextHourMm = in.rainNextHourMm;
        f.rainNext2hMm = in.rainNext2hMm;
        f.rainNext3hMm = in.rainNext3hMm;
        f.rainProbability = in.rainProbability;

        List<Sample> samples = in.samples;
        if (samples == null || samples.size() < 3 || Double.isNaN(in.currentLevel) || in.currentLevel <= 0) {
            f.available = false;
            f.unavailableReason = "Ainda não há leituras suficientes desta ponte para calcular a tendência.";
            return f;
        }

        long last = samples.get(samples.size() - 1).timeMillis;
        long base = in.readingMillis > 0 ? Math.max(in.readingMillis, last) : last;
        f.baseTime = base;
        f.targetTime = base + HOUR;

        // 1) Tendência medida na própria ponte.
        Fit f60 = fit(samples, last, 60 * MIN);
        Fit f180 = fit(samples, last, 180 * MIN);
        if (f60 == null) f60 = fit(samples, last, 90 * MIN);
        if (f60 == null) f60 = f180;
        if (f60 == null) {
            f.available = false;
            f.unavailableReason = "As leituras da última hora desta ponte estão incompletas.";
            return f;
        }
        Fit used = f60;
        double weight = PESO_TENDENCIA;
        double start = in.currentLevel;
        int window = 60;
        // Sensor com muita oscilação: janela maior e partida da reta ajustada, não da última leitura.
        if (f60.noiseCm > 2.5) {
            Fit f120 = fit(samples, last, 120 * MIN);
            if (f120 != null) {
                used = f120;
                window = 120;
                weight = 0.85;
                start = f120.endValue;
                f.notes.add("O sensor desta ponte oscila bastante entre uma leitura e outra; a tendência foi "
                        + "calculada sobre 2 horas e a margem de erro ficou maior.");
            }
        }
        f.rate60 = used.rateCmH;
        f.rate180 = f180 == null ? Double.NaN : f180.rateCmH;
        f.noiseCm = used.noiseCm;
        f.samplesUsed = used.n;
        f.windowMinutes = window;

        double trendCm = weight * used.rateCmH + (start - in.currentLevel) * 100.0;
        f.factors.add(new Forecast.Factor(Forecast.Factor.TENDENCIA,
                "Tendência medida na ponte", trendCm,
                String.format(BR, "%d leituras em %d min mostram o rio %s a %s cm/h. A previsão aproveita %.0f%% desse ritmo, "
                                + "porque a velocidade costuma diminuir um pouco de uma hora para a outra.",
                        used.n, window, verbo(used.rateCmH), cm(Math.abs(used.rateCmH)), weight * 100)));

        // 2) Rios a montante.
        double upSum = 0;
        int upN = 0;
        StringBuilder upText = new StringBuilder();
        for (Upstream u : in.upstream) {
            if (u.samples == null || u.samples.isEmpty()) continue;
            Fit uf = fit(u.samples, u.samples.get(u.samples.size() - 1).timeMillis, 60 * MIN);
            if (uf == null || uf.noiseCm > 4.0) continue;
            upSum += uf.rateCmH;
            upN++;
            if (upText.length() > 0) upText.append("; ");
            upText.append(u.name).append(' ').append(verbo(uf.rateCmH));
            if (Math.abs(uf.rateCmH) >= 0.5) upText.append(" a ").append(cm(Math.abs(uf.rateCmH))).append(" cm/h");
        }
        double upCm = 0;
        double upRawPerHour = 0;
        if (upN > 0) {
            double upMean = upSum / upN;
            upRawPerHour = PESO_MONTANTE * (upMean - used.rateCmH);
            upCm = clamp(upRawPerHour, LIMITE_MONTANTE_CM);
            f.upstreamSummary = upText.toString() + ".";
            f.factors.add(new Forecast.Factor(Forecast.Factor.MONTANTE, "Rios a montante", upCm,
                    "A água que passa rio acima chega aqui em seguida. " + f.upstreamSummary
                            + (Math.abs(upCm) < 0.05
                            ? " O ritmo lá em cima é praticamente o mesmo daqui, então não muda a conta."
                            : (upCm > 0 ? " Como lá em cima o rio está mais \"forte\" que aqui, a previsão sobe um pouco."
                            : " Como lá em cima o rio está mais \"fraco\" que aqui, a previsão desce um pouco."))));
        } else {
            f.upstreamSummary = "Sem leituras confiáveis de estações a montante nesta consulta.";
            f.factors.add(new Forecast.Factor(Forecast.Factor.MONTANTE, "Rios a montante", 0, f.upstreamSummary));
        }

        // 3) Barragens.
        boolean usaTaio = in.bridge == Bridge.DOM_TITO || in.bridge == Bridge.BR470;
        boolean usaItup = in.bridge == Bridge.DOM_TITO || in.bridge == Bridge.RICARDO_KANITZ;
        double damCm = 0;
        StringBuilder damText = new StringBuilder();
        if (usaTaio) damCm += damTerm(in.taio, "Taió (Rio Itajaí do Oeste)", damText);
        if (usaItup) damCm += damTerm(in.ituporanga, "Ituporanga (Rio Itajaí do Sul)", damText);
        double damRawPerHour = damCm;
        damCm = clamp(damCm, LIMITE_BARRAGENS_CM);
        f.damSummary = damText.length() == 0 ? "Sem dados das barragens nesta consulta." : damText.toString().trim();
        f.factors.add(new Forecast.Factor(Forecast.Factor.BARRAGENS, "Barragens", damCm,
                f.damSummary + " A água liberada ou segurada nas barragens leva várias horas para chegar a Rio do Sul. "
                        + "Por isso só entram na conta as manobras feitas entre 2 e 12 horas atrás, com peso pequeno (no máximo "
                        + cm(LIMITE_BARRAGENS_CM) + " cm): as mais antigas já aparecem na tendência medida e as mais novas ainda não chegaram."));

        // 4) Chuva observada.
        double obsCm = 0;
        if (!Double.isNaN(in.rainBasin1hMean)) {
            obsCm = Math.min(LIMITE_CHUVA_OBS_CM, CM_POR_MM_OBSERVADO * Math.max(0, in.rainBasin1hMean));
            f.rainObservedSummary = String.format(BR,
                    "Média de %s mm na última hora em %d pluviômetros da bacia (máximo de %s mm em um deles)%s.",
                    mm(in.rainBasin1hMean), in.rainStations, mm(nz(in.rainBasin1hMax)),
                    Double.isNaN(in.rainBasin24hMean) ? "" : String.format(BR, "; %s mm de média em 24 h", mm(in.rainBasin24hMean)));
        } else {
            f.rainObservedSummary = "Sem leitura de pluviômetros nesta consulta.";
        }
        f.factors.add(new Forecast.Factor(Forecast.Factor.CHUVA_OBSERVADA, "Chuva que já caiu", obsCm,
                f.rainObservedSummary + (obsCm < 0.05
                        ? " Sem chuva relevante, não acrescenta nada."
                        : " Chuva recente ainda está escoando para o rio e acrescenta um pouco à previsão.")));

        // 5) Chuva prevista.
        double fcCm = 0;
        if (!Double.isNaN(in.rainNextHourMm)) {
            fcCm = Math.min(LIMITE_CHUVA_PREV_CM, CM_POR_MM_PREVISTO * Math.max(0, in.rainNextHourMm));
            f.rainForecastSummary = String.format(BR,
                    "O modelo meteorológico indica %s mm na próxima hora, %s mm em 2 horas e %s mm em 3 horas (média de pontos da bacia)%s.",
                    mm(in.rainNextHourMm), mm(nz(in.rainNext2hMm)), mm(nz(in.rainNext3hMm)),
                    in.rainProbability < 0 ? "" : ", com " + in.rainProbability + "% de probabilidade de chuva");
        } else {
            f.rainForecastSummary = "Previsão meteorológica horária indisponível nesta consulta.";
        }
        f.factors.add(new Forecast.Factor(Forecast.Factor.CHUVA_PREVISTA, "Chuva prevista", fcCm,
                f.rainForecastSummary + (fcCm < 0.05
                        ? " Não altera a previsão de uma hora."
                        : " Chuva que cai agora demora a virar nível de rio; por isso o peso é pequeno.")));

        // Resultado.
        f.deltaCm = f.sumCm();
        f.predictedLevel = Math.max(0, in.currentLevel + f.deltaCm / 100.0);

        // Margem de erro: oscilação do sensor + parte da variação prevista + incerteza da chuva.
        double band = Math.max(3.0, 2.0 * used.noiseCm + 0.35 * Math.abs(f.deltaCm)) + 0.5 * (obsCm + fcCm);
        long age = in.now > 0 ? in.now - last : 0;
        if (age > 40 * MIN) {
            band += Math.min(10.0, (age - 40 * MIN) / (double) HOUR * Math.max(2.0, Math.abs(used.rateCmH)));
            f.notes.add(String.format(BR, "A última leitura desta ponte tem %d minutos; a previsão parte dela e fica menos precisa.",
                    age / MIN));
        }
        f.bandCm = Math.ceil(band);
        f.confidence = f.bandCm <= 4 ? 2 : f.bandCm <= 8 ? 1 : 0;
        if (age > 90 * MIN) f.confidence = 0;

        // Horizontes de 1, 2 e 3 horas. O de 1 h repete exatamente a conta acima.
        double startOffsetCm = (start - in.currentLevel) * 100.0;
        double obsPerHourMm = Double.isNaN(in.rainBasin1hMean) ? 0 : Math.max(0, in.rainBasin1hMean);
        for (int h = 1; h <= 3; h++) {
            Forecast.Horizon hz = new Forecast.Horizon();
            hz.hours = h;
            hz.targetTime = base + h * HOUR;
            if (h == 1) {
                hz.trendCm = trendCm;
                hz.upstreamCm = upCm;
                hz.damCm = damCm;
                hz.rainObservedCm = obsCm;
                hz.rainForecastCm = fcCm;
                hz.deltaCm = f.deltaCm;
                hz.predictedLevel = f.predictedLevel;
                hz.bandCm = f.bandCm;
                hz.confidence = f.confidence;
            } else {
                double w = Math.min(weight, PESO_TENDENCIA_LONGO);
                hz.trendCm = w * used.rateCmH * h + startOffsetCm;
                hz.upstreamCm = clamp(upRawPerHour * h, LIMITE_MONTANTE_CM * h);
                hz.damCm = clamp(damRawPerHour * h, LIMITE_BARRAGENS_CM * h);
                hz.rainObservedCm = Math.min(LIMITE_CHUVA_OBS_CM * h, CM_POR_MM_OBSERVADO * obsPerHourMm * h);
                double rainAhead = h == 2 ? in.rainNext2hMm : in.rainNext3hMm;
                hz.rainForecastCm = Double.isNaN(rainAhead) ? 0
                        : Math.min(LIMITE_CHUVA_PREV_CM * h, CM_POR_MM_PREVISTO * Math.max(0, rainAhead));
                hz.deltaCm = hz.trendCm + hz.upstreamCm + hz.damCm + hz.rainObservedCm + hz.rainForecastCm;
                hz.predictedLevel = Math.max(0, in.currentLevel + hz.deltaCm / 100.0);
                double root = Math.sqrt(h);
                double hb = Math.max(3.0 * root, 2.0 * used.noiseCm * root + 0.45 * Math.abs(hz.deltaCm))
                        + 0.5 * (hz.rainObservedCm + hz.rainForecastCm);
                if (age > 40 * MIN) {
                    hb += Math.min(10.0 * h, (age - 40 * MIN) / (double) HOUR * Math.max(2.0, Math.abs(used.rateCmH)) * h);
                }
                hz.bandCm = Math.ceil(hb);
                hz.confidence = hz.bandCm <= 4 * root ? 2 : hz.bandCm <= 8 * root ? 1 : 0;
                if (age > 90 * MIN) hz.confidence = 0;
            }
            f.horizons.add(hz);
        }

        int faixaAgora = f.cotas.faixa(in.currentLevel);
        int faixaDepois = f.cotas.faixa(f.predictedLevel);
        if (faixaDepois > faixaAgora) {
            f.notes.add("Mantido esse ritmo, o rio deve passar da cota de "
                    + Cotas.nomeDaFaixa(faixaDepois).toLowerCase(BR) + " na próxima hora.");
        } else if (faixaDepois < faixaAgora) {
            f.notes.add("Mantido esse ritmo, o rio deve voltar para baixo da cota de "
                    + Cotas.nomeDaFaixa(faixaAgora).toLowerCase(BR) + " na próxima hora.");
        }
        f.available = true;
        return f;
    }

    private static double damTerm(DamReading d, String label, StringBuilder text) {
        if (d == null) return 0;
        text.append(label).append(": ")
                .append(d.gatesOpen).append(" de ").append(d.gatesTotal).append(" comportas abertas, ")
                .append(String.format(BR, "%.0f%% de ocupação", d.capacityPercent));
        if (!Double.isNaN(d.levelChange3hCm)) {
            text.append(String.format(BR, ", reservatório %s %s cm em 3 h",
                    d.levelChange3hCm > 0.5 ? "subiu" : d.levelChange3hCm < -0.5 ? "baixou" : "variou",
                    cm(Math.abs(d.levelChange3hCm))));
        }
        int net = d.gatesOpenedArriving - d.gatesClosedArriving;
        if (d.gatesOpened24h + d.gatesClosed24h > 0) {
            text.append(String.format(BR, "; nas últimas 24 h abriu %d e fechou %d", d.gatesOpened24h, d.gatesClosed24h));
            if (!Double.isNaN(d.hoursSinceLastGateEvent)) {
                text.append(String.format(BR, " (última manobra há %.0f h)", d.hoursSinceLastGateEvent));
            }
        } else {
            text.append("; sem manobras nas últimas 24 h");
        }
        text.append(". ");
        return CM_POR_COMPORTA * net;
    }

    private static double clamp(double v, double limit) {
        return Math.max(-limit, Math.min(limit, v));
    }

    private static double nz(double v) { return Double.isNaN(v) ? 0 : v; }

    static String verbo(double rateCmH) {
        if (rateCmH >= 0.5) return "subindo";
        if (rateCmH <= -0.5) return "descendo";
        return "estável";
    }

    static String cm(double v) {
        return String.format(BR, "%.1f", v);
    }

    static String mm(double v) {
        return String.format(BR, "%.1f", v);
    }
}
