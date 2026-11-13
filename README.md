# Nível do Rio — Rio do Sul (Android)

App nativo Android sem bibliotecas de terceiros em runtime. A versão 1.3.0 mantém os níveis atuais, histórico visual de níveis, chuva oficial, barragens e widget; as previsões são somente leitura de um serviço centralizado.

## Versão 1.3.0

- Previsões centralizadas para **+1 h, +2 h e +3 h**, apresentadas no card principal e no memorial.
- O app faz somente consultas GET para previsões; não calcula, registra, confere nem envia previsões localmente.
- Histórico e estatísticas de acurácia são apresentados somente quando retornados pelo servidor.
- O último JSON de previsão pode ser mantido como cache offline, sempre identificado como salvo/antigo; não há banco SQL local de previsões.
- Respostas sem dados atuais, sem horizonte necessário ou de modelo incompatível aparecem como indisponíveis e não são substituídas por uma previsão local.
- Cotas oficiais: abaixo de 4,50 m normal; 4,50 m a menos de 5,50 m atenção; 5,50 m até 6,50 m inclusive alerta; acima de 6,50 m emergência.
- Chuva meteorológica opcional permanece ausente quando o servidor retorna `null`; `null` não é convertido em zero medido.
- Os níveis atuais continuam vindo diretamente do portal público. Chuva oficial, barragens e histórico visual permanecem complementos independentes da previsão.

## Limites importantes

A previsão é experimental e não substitui avisos oficiais. O memorial exibe fatores, margens, versão do modelo, freshness e estatísticas que vierem do servidor; não contém números históricos de calibração ou promessas de acurácia incorporadas no APK. Siga sempre os avisos da Defesa Civil.

## Build

Requisitos: Android SDK 35 (`platforms;android-35` e `build-tools;35.0.0`), JDK 17 ou 21 e Gradle 8.10.2 ou compatível.

```bash
gradle assembleDebug
```

O APK de debug é gerado em `app/build/outputs/apk/debug/app-debug.apk`. A aplicação usa `minSdk 23`, `targetSdk 35`, `versionCode 13` e `versionName 1.3.0`.

## Instalação

APKs de debug são assinados pela chave de debug da máquina que os compilou. Se o Android rejeitar a atualização por assinatura incompatível, desinstale a versão anterior antes de instalar o novo APK. Instalar pelo gerenciador de arquivos pode exigir a permissão de instalar apps desconhecidos.

## Estrutura relevante

- `DataRepository.java`: níveis diretos, histórico visual, chuva oficial/contextual e barragens.
- `HydrologyForecastClient.java`: cliente GET, parser do contrato v2, cache JSON e regra de freshness.
- `ForecastCardView.java`, `ForecastActivity.java`, `ForecastScorecardView.java`: apresentação de +1/+2/+3 h, indisponibilidade e estatísticas do servidor.
- `HistoryStore.java`: reserva local para o gráfico de níveis quando a série pública não responde; não é histórico de previsões.
