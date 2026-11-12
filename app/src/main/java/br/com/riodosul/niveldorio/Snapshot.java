package br.com.riodosul.niveldorio;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;

public class Snapshot {
    /** Chuva das últimas 24 h por município da bacia (tela do memorial, seção 4). */
    public final List<MunicipalRain> municipalRain = new ArrayList<>();
    public final EnumMap<Bridge, RiverReading> rivers = new EnumMap<>(Bridge.class);
    public final EnumMap<Bridge, List<Sample>> riverHistory = new EnumMap<>(Bridge.class);
    public final EnumMap<Bridge, Forecast> forecasts = new EnumMap<>(Bridge.class);
    public DamReading taio;
    public DamReading ituporanga;
    public long fetchedAt;
    public boolean fromCache;
    public String errorMessage;
}
