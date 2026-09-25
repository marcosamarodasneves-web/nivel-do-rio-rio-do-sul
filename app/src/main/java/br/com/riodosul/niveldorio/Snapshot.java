package br.com.riodosul.niveldorio;

import java.util.EnumMap;

public class Snapshot {
    public final EnumMap<Bridge, RiverReading> rivers = new EnumMap<>(Bridge.class);
    public DamReading taio;
    public DamReading ituporanga;
    public long fetchedAt;
    public boolean fromCache;
    public String errorMessage;
}
