package br.com.riodosul.niveldorio;

/** Um ponto do histórico de uma barragem (15 min, em média). */
public class DamPoint {
    public long timeMillis;
    /** Nível do reservatório em metros (cota do instrumento). */
    public double levelMeters;
    /** Ocupação do reservatório, em %. */
    public double capacityPercent;
    public int gatesOpen;
    public int gatesTotal;

    public DamPoint(long timeMillis, double levelMeters, double capacityPercent, int gatesOpen, int gatesTotal) {
        this.timeMillis = timeMillis;
        this.levelMeters = levelMeters;
        this.capacityPercent = capacityPercent;
        this.gatesOpen = gatesOpen;
        this.gatesTotal = gatesTotal;
    }
}
