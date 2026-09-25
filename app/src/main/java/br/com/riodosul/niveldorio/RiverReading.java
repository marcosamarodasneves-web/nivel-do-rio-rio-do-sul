package br.com.riodosul.niveldorio;

public class RiverReading {
    public Bridge bridge;
    public double levelMeters;
    public String status;
    public String readingTime;

    public RiverReading(Bridge bridge, double levelMeters, String status, String readingTime) {
        this.bridge = bridge;
        this.levelMeters = levelMeters;
        this.status = status;
        this.readingTime = readingTime;
    }
}
