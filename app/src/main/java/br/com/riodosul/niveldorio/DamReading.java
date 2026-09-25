package br.com.riodosul.niveldorio;

public class DamReading {
    public String name;
    public double levelMeters;
    public double capacityPercent;
    public int gatesOpen;
    public int gatesTotal;
    public String age;

    public DamReading(String name, double levelMeters, double capacityPercent, int gatesOpen, int gatesTotal, String age) {
        this.name = name;
        this.levelMeters = levelMeters;
        this.capacityPercent = capacityPercent;
        this.gatesOpen = gatesOpen;
        this.gatesTotal = gatesTotal;
        this.age = age;
    }
}
