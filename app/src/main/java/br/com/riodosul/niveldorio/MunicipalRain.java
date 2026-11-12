package br.com.riodosul.niveldorio;

/** Chuva acumulada nas últimas 24 h em um município da bacia. */
public class MunicipalRain {
    public static final String FONTE_PLUVIOMETRO = "pluviometro";
    public static final String FONTE_ESTIMATIVA = "estimativa";

    public String city;
    /** Chuva acumulada em 24 h (mm); NaN quando não há leitura nem estimativa. */
    public double mm24 = Double.NaN;
    /** Chuva da última hora (mm), só para pluviômetros. */
    public double mm1h = Double.NaN;
    /** Quantos pluviômetros do município entraram na média. */
    public int gauges;
    /** Pluviômetros do município que não enviaram leitura recente. */
    public int silentGauges;
    /** {@link #FONTE_PLUVIOMETRO}, {@link #FONTE_ESTIMATIVA} ou vazio (sem dado). */
    public String source = "";
    public double latitude = Double.NaN;
    public double longitude = Double.NaN;

    public MunicipalRain(String city) {
        this.city = city;
    }

    public boolean hasValue() {
        return !Double.isNaN(mm24);
    }

    public boolean measured() {
        return FONTE_PLUVIOMETRO.equals(source);
    }
}
