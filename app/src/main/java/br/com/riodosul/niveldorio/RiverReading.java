package br.com.riodosul.niveldorio;

public class RiverReading {
    public Bridge bridge;
    public double levelMeters;
    public String status;
    public String readingTime;
    /** Instante da leitura (epoch ms) ou 0 quando a API não informa. */
    public long readingMillis;
    /** Id da estação que respondeu nesta leitura. */
    public String stationId;
    public Cotas cotas = new Cotas();

    public RiverReading(Bridge bridge, double levelMeters, String status, String readingTime) {
        this.bridge = bridge;
        this.levelMeters = levelMeters;
        this.status = status;
        this.readingTime = readingTime;
    }
}
