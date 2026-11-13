package br.com.riodosul.niveldorio;

/** Cotas de atenção, alerta e emergência da Defesa Civil de Rio do Sul (em metros). */
public class Cotas {
    public static final double PADRAO_ATENCAO = 4.50;
    public static final double PADRAO_ALERTA = 5.50;
    public static final double PADRAO_EMERGENCIA = 6.50;

    public static final int COR_NORMAL = 0xFF1F915B;
    public static final int COR_ATENCAO = 0xFFD6A408;
    public static final int COR_ALERTA = 0xFFDD6B12;
    public static final int COR_EMERGENCIA = 0xFFC42828;

    public double atencao = PADRAO_ATENCAO;
    public double alerta = PADRAO_ALERTA;
    public double emergencia = PADRAO_EMERGENCIA;
    /** true quando os valores vieram da API (e não do padrão embutido no app). */
    public boolean oficial;

    public Cotas() {}

    public Cotas(double atencao, double alerta, double emergencia, boolean oficial) {
        this.atencao = atencao;
        this.alerta = alerta;
        this.emergencia = emergencia;
        this.oficial = oficial;
    }

    public boolean valida() {
        return atencao > 0 && alerta > atencao && emergencia > alerta;
    }

    /** 0 = normal, 1 = atenção, 2 = alerta, 3 = emergência. */
    public int faixa(double nivel) {
        // Regra oficial: abaixo de 4,50 normal; 4,50 a <5,50 atenção;
        // 5,50 a 6,50 (inclusive) alerta; acima de 6,50 emergência.
        if (nivel > emergencia) return 3;
        if (nivel >= alerta) return 2;
        if (nivel >= atencao) return 1;
        return 0;
    }

    public String situacao(double nivel) {
        if (Double.isNaN(nivel) || nivel < 0) return "Sem dados";
        return nomeDaFaixa(faixa(nivel));
    }

    public int cor(double nivel) {
        if (Double.isNaN(nivel) || nivel < 0) return COR_NORMAL;
        return corDaFaixa(faixa(nivel));
    }

    public static String nomeDaFaixa(int faixa) {
        switch (faixa) {
            case 3: return "Emergência";
            case 2: return "Alerta";
            case 1: return "Atenção";
            default: return "Normal";
        }
    }

    public static int corDaFaixa(int faixa) {
        switch (faixa) {
            case 3: return COR_EMERGENCIA;
            case 2: return COR_ALERTA;
            case 1: return COR_ATENCAO;
            default: return COR_NORMAL;
        }
    }

    /** Topo da escala de exibição: nunca menor que 8 m e sempre com folga acima dos níveis. */
    public double topoDaEscala(double... niveis) {
        double maior = emergencia + 1.0;
        for (double n : niveis) {
            if (!Double.isNaN(n) && n + 0.5 > maior) maior = n + 0.5;
        }
        return Math.max(8.0, Math.ceil(maior));
    }
}
