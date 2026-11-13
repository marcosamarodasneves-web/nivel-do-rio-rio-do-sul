# Notas técnicas — migração Android 1.3.0

## Escopo

Esta alteração cobre somente o aplicativo Android em `app/src`. O app preserva leitura direta dos níveis, histórico visual, chuva oficial/contextual, barragens, widget e a UI geral. A previsão passou a ser complemento não bloqueante da consulta de níveis.

## Contrato e cliente

`HydrologyForecastClient.java` faz GET somente para o serviço centralizado configurado por `BuildConfig.HYDROLOGY_API_BASE` e aceita apenas respostas com `model_version` `deterministic-v2`. O parser converte `latest` para o `Forecast` existente, mantém `forecasts` históricos e `accuracy` em DTOs sem persistência SQL e apresenta `available:false` quando a resposta está ausente, antiga ou incompatível.

A resposta do servidor é buscada em paralelo para as pontes. O carregamento de níveis, chuva oficial/contextual e barragens não espera o forecast. O último JSON de resposta pode ser salvo em `SharedPreferences` com chave própria para cache offline; a UI identifica freshness/cache e não trata o cache antigo como previsão atual. Não existe fallback para cálculo local nem para payload v1.

Campos meteorológicos opcionais nulos permanecem ausentes (`NaN` no modelo de apresentação); nunca são convertidos em zero medido. A chuva oficial/contextual da UI continua sendo separada dos campos meteorológicos opcionais do forecast.

## UI e cotas

`ForecastCardView`, `ForecastActivity`, `MemorialAnimView` e `ForecastScorecardView` exibem +1/+2/+3 h, erro/indisponibilidade, freshness, fatores, histórico e estatísticas do servidor. O memorial não contém números estáticos de calibração ou backtest.

`Cotas.faixa` aplica exatamente: abaixo de 4,50 m normal; 4,50 m até antes de 5,50 m atenção; 5,50 m até 6,50 m inclusive alerta; acima de 6,50 m emergência.

## Remoções

Foram removidos `ForecastEngine.java`, `ForecastStore.java` e `SupabaseForecast.java`, junto com o cálculo local, histórico SQL local de previsões e fluxo de envio/sincronização de previsões. Credenciais e parâmetros de build relacionados não existem mais.

## Validação

Há teste unitário de parser usando um payload v2 representativo em `app/src/test`. A compilação final depende de Android SDK 35, JDK e Gradle disponíveis no ambiente de build.
