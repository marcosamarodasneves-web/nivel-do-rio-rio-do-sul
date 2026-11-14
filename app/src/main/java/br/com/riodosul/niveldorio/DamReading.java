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

    /** Nome de cada comporta na mesma ordem de gateStates (C1, C2... CE), quando a API informa. */
    public String[] gateNames;

    /**
     * Comporta auxiliar é a que não segue a numeração das comportas de fundo (C1, C2...), como a "CE".
     * Regra genérica: nome sem número. Se a Defesa Civil acrescentar outra (por exemplo "CD"), ela cai aqui sozinha.
     */
    public static boolean isAuxiliaryName(String name) {
        if (name == null) return false;
        String n = name.trim();
        if (n.isEmpty()) return false;
        for (int i = 0; i < n.length(); i++) if (Character.isDigit(n.charAt(i))) return false;
        return true;
    }

    private boolean hasNames() {
        return gateNames != null && gateStates != null && gateNames.length == gateStates.length;
    }

    /** Comportas de fundo (numeradas) no total. Sem nomes, usa o total geral. */
    public int mainTotal() {
        if (!hasNames()) return gatesTotal;
        int n = 0;
        for (String name : gateNames) if (!isAuxiliaryName(name)) n++;
        return n;
    }

    /** Comportas de fundo (numeradas) abertas. Sem nomes, usa o total geral de abertas. */
    public int mainOpen() {
        if (!hasNames()) return gatesOpen;
        int n = 0;
        for (int i = 0; i < gateNames.length; i++) if (!isAuxiliaryName(gateNames[i]) && gateStates[i]) n++;
        return n;
    }

    /** Estado das comportas auxiliares, por exemplo "CE fechada" (vazio quando não há nenhuma). */
    public String auxiliaryText() {
        if (!hasNames()) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < gateNames.length; i++) {
            if (!isAuxiliaryName(gateNames[i])) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(gateNames[i].trim()).append(gateStates[i] ? " aberta" : " fechada");
        }
        return sb.toString();
    }

    /** "7/7 abertas" mais, se houver, " + CE fechada". */
    public String gatesLabel() {
        String aux = auxiliaryText();
        return mainOpen() + "/" + mainTotal() + " abertas" + (aux.isEmpty() ? "" : " + " + aux);
    }

    /** Há série de 24 h suficiente para desenhar o gráfico da barragem. */
    public boolean hasHistory() {
        return history.size() >= 2;
    }
}
