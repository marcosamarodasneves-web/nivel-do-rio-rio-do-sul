package br.com.riodosul.niveldorio;

public enum Bridge {
    DOM_TITO("Ponte Dom Tito Buss", "Rio Itajaí-Açu", "Ponte Dom Tito Buss"),
    RICARDO_KANITZ("Ponte Ricardo Kanitz", "Rio Itajaí do Sul", "Ponte Ricardo Kanitz"),
    BR470("Ponte BR-470", "Rio Itajaí do Oeste", "Ponte BR 470");

    public final String displayName;
    public final String river;
    public final String portalMarker;

    Bridge(String displayName, String river, String portalMarker) {
        this.displayName = displayName;
        this.river = river;
        this.portalMarker = portalMarker;
    }

    public String label() { return displayName + " — " + river; }
}
