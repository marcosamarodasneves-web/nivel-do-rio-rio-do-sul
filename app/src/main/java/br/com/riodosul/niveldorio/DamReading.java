package br.com.riodosul.niveldorio;

public class DamReading {
    public String name;
    public double levelMeters;
    public double capacityPercent;
    public int gatesOpen;
    public int gatesTotal;
    public String age;
    /** Estado individual das comportas, quando fornecido pela API. */
    public boolean[] gateStates;

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
}
