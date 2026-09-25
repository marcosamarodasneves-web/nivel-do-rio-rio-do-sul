package br.com.riodosul.niveldorio;

public enum Bridge {
    DOM_TITO(
            "Ponte Dom Tito Buss",
            "Rio Itajaí-Açu",
            "Ponte Dom Tito Buss",
            "f6360951-219f-4859-935f-b2e2d13962f1"
    ),
    RICARDO_KANITZ(
            "Ponte Ricardo Kanitz",
            "Rio Itajaí do Sul",
            "Ponte Ricardo Kanitz",
            "30475400-b7ba-4551-9646-19df0c3bfa38"
    ),
    BR470(
            "Ponte BR-470",
            "Rio Itajaí do Oeste",
            "Ponte BR 470",
            "3167e629-3bbe-48f2-9244-c65dfe6882d8"
    );

    public final String displayName;
    public final String river;
    public final String portalMarker;
    public final String stationId;

    Bridge(String displayName, String river, String portalMarker, String stationId) {
        this.displayName = displayName;
        this.river = river;
        this.portalMarker = portalMarker;
        this.stationId = stationId;
    }

    public String label() {
        return displayName + " — " + river;
    }
}
