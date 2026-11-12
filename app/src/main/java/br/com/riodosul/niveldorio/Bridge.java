package br.com.riodosul.niveldorio;

/**
 * Pontos de leitura acompanhados pelo app.
 *
 * A Defesa Civil já trocou os identificadores das estações; por isso cada ponte guarda todos os
 * ids conhecidos e também palavras-chave do nome, usadas quando o id mudar de novo.
 */
public enum Bridge {
    DOM_TITO(
            "Ponte Dom Tito Buss",
            "Rio Itajaí-Açu",
            "Atrás do Catarinão Super",
            new String[] {
                    "d2f60af1-53e3-497f-81d6-c6498d28f5a2",
                    "f6360951-219f-4859-935f-b2e2d13962f1"
            },
            new String[] { "dom tito" },
            null
    ),
    RICARDO_KANITZ(
            "Ponte Hannelore H. Eyng",
            "Rio Itajaí do Sul",
            "Estação do Rio Itajaí do Sul",
            new String[] {
                    "039a5d4f-f58d-4fca-bef6-5d37e132a6f0",
                    "30475400-b7ba-4551-9646-19df0c3bfa38"
            },
            new String[] { "hannelore", "ricardo kanitz" },
            "itajai do sul"
    ),
    BR470(
            "Ponte BR-470",
            "Rio Itajaí do Oeste",
            "Perto do Pamplona",
            new String[] { "3167e629-3bbe-48f2-9244-c65dfe6882d8" },
            new String[] { "br 470", "br470" },
            null
    );

    public final String displayName;
    public final String river;
    public final String reference;
    /** Ids conhecidos da estação, do mais novo para o mais antigo. */
    public final String[] stationIds;
    /** Trechos do nome da estação (sem acento, minúsculo) aceitos quando nenhum id confere. */
    public final String[] nameKeys;
    /** Último recurso: estação com "ponte" no nome e este rio. */
    public final String riverKey;

    Bridge(String displayName, String river, String reference,
           String[] stationIds, String[] nameKeys, String riverKey) {
        this.displayName = displayName;
        this.river = river;
        this.reference = reference;
        this.stationIds = stationIds;
        this.nameKeys = nameKeys;
        this.riverKey = riverKey;
    }

    public String label() {
        return displayName + " — " + river;
    }
}
