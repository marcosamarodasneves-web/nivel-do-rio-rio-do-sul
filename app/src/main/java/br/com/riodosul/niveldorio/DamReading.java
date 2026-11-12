package br.com.riodosul.niveldorio;

import java.util.ArrayList;
import java.util.List;

public class DamReading {
    public String name;
    public double levelMeters;
    public double capacityPercent;
    public int gatesOpen;
    public int gatesTotal;
    public String age;
    /** Estado individual das comportas, quando fornecido pela API. */
    public boolean[] gateStates;
    /** Variação do nível do reservatório nas últimas ~3 h, em cm (NaN se desconhecida). */
    public double levelChange3hCm = Double.NaN;
    /** Comportas abertas e fechadas nas últimas 24 h. */
    public int gatesOpened24h;
    public int gatesClosed24h;
    /** Manobras feitas entre 2 e 12 h atrás (as que podem estar chegando a Rio do Sul agora). */
    public int gatesOpenedArriving;
    public int gatesClosedArriving;
    /** Horas desde a última manobra de comporta (NaN se não houve nas últimas 24 h). */
    public double hoursSinceLastGateEvent = Double.NaN;

    /** Id da estação da barragem na API da Defesa Civil. */
    public String stationId = "";
    /** Posição do ponto de medição, usada para consultar a chuva sobre a barragem. */
    public double latitude = Double.NaN;
    public double longitude = Double.NaN;
    /** Série de 24 h do reservatório (nível, ocupação e comportas), em passos de ~15 min. */
    public final List<DamPoint> history = new ArrayList<>();
    /** Chuva do modelo meteorológico por hora nas últimas 24 h (value em mm). */
    public final List<Sample> rainHourly = new ArrayList<>();
    /** true quando a chuva veio do pluviômetro da barragem; false = estimativa do modelo Open-Meteo. */
    public boolean rainFromGauge;
    /** Total de chuva nas últimas 24 h sobre a barragem, em mm (NaN quando não se sabe). */
    public double rain24hMm = Double.NaN;

    public DamReading(String name, double levelMeters, double capacityPercent, int gatesOpen, int gatesTotal, String age) {
        this(name, levelMeters, capacityPercent, gatesOpen, gatesTotal, age, null);
    }

    public DamReading(String name, double levelMeters, double capacityPercent,
                      int gatesOpen, int gatesTotal, String age, boolean[] gateStates) {
        this.name = name;
        this.levelMeters = levelMeters;
        this.capacityPercent = capacityPercent;
        this.gatesOpen = Math.max(0, gatesOpen);
        this.gatesTotal = Math.max(0, gatesTotal);
        this.age = age;
        this.gateStates = gateStates == null ? null : gateStates.clone();
    }

    /** Há série de 24 h suficiente para desenhar o gráfico da barragem. */
    public boolean hasHistory() {
        return history.size() >= 2;
    }
}
