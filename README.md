# Nível do Rio — Rio do Sul (Android)

App nativo Android sem bibliotecas de terceiros em runtime.

## Novidades da versão 1.1.0

- **Borda de criticidade no card "Nível nas últimas 24 horas"** (`RiverChartView.java`): filete fino de
  cantos arredondados na cor da situação atual do rio — verde (normal), amarelo (atenção), laranja (alerta),
  vermelho (emergência) — com a etiqueta da situação ao lado do título. Quando não há leitura, a borda fica cinza.
- **Água em movimento no mesmo card:** duas ondas na base, uma faixa de correnteza atravessando o card e um
  reflexo que percorre a linha do gráfico (`ChartRenderer.draw(..., pulse, flow)`).
- **Borda em três estados nos cards das barragens** (`DamSummaryView.java`): verde (abaixo de 80 % e sem
  comporta aberta), âmbar (80 % a 94,9 % ou com comportas abertas) e vermelho (95 % ou mais).
- **Previsões registradas no Supabase** (`SupabaseForecast.java`, `ForecastStore.java`): cada previsão para a
  próxima hora é gravada na tabela `river_forecasts` e conferida automaticamente com a leitura real da estação
  (janela de ± 20 min), classificando dentro da margem / perto / fora / sem medição.
- **Clique no card da previsão** agora mostra, além do memorial, a seção 7 com a tabela animada
  `data/hora | nível do rio | previsão | acertou?` e o resumo de aproveitamento
  (`ForecastScorecardView.java`).
- **Dois toques no card de uma barragem** abrem a tela das últimas 24 horas (`DamActivity.java`,
  `DamChartView.java`): nível do reservatório, comportas abertas e chuva por hora sobre a barragem — tudo
  com entrada animada. Os dados vêm de `/public/dams/{id}/gate-history?days=1` (série de 15 min) e do
  Open-Meteo nas coordenadas das barragens.
- O Open-Meteo passou a ser consultado também **sobre as duas barragens** (chuva das últimas 24 h) e o cache
  local guarda séries e chuva, então a tela nova funciona offline depois da primeira atualização.

## Novidades da versão 1.0.0

- **Leitura das três pontes corrigida.** A Defesa Civil trocou os identificadores das estações; o app agora
  procura cada ponte pelo id conhecido e, se ele mudar de novo, pelo nome da estação (`Bridge.java`).
  A estação do Rio Itajaí do Sul passou a se chamar "Ponte Hannelore Hartmann Eyng" no portal.
- **Cotas oficiais** de atenção (4,50 m), alerta (5,50 m) e emergência (6,50 m) lidas da própria API
  (`band_thresholds`), com esses valores como padrão quando a API não informa (`Cotas.java`).
- **Card principal com régua dinâmica:** vai até 8 m em dias normais e cresce sozinha acima disso.
- **Gráfico refeito:** série oficial de 24 h (`/public/station-history`), escala vertical automática,
  eixo de horas, cotas e ponto da previsão. Não depende mais do histórico coletado no aparelho.
- **Previsão para a próxima hora** (`ForecastEngine.java`) com memorial descritivo animado
  (`ForecastActivity.java`, `MemorialAnimView.java`).
- Horários corrigidos (microssegundos da API eram lidos como milissegundos) e compatibilidade com Android 6.

## Como a previsão é calculada

`previsto = nível atual + 0,9 × tendência da última hora + ajustes pequenos e limitados`

| Parcela | Regra | Limite |
|---|---|---|
| Tendência | reta de mínimos quadrados sobre 60 min de leituras da ponte × 0,9 | — |
| Rios a montante | 0,15 × (ritmo a montante − ritmo local) | ± 3 cm |
| Barragens | 0,25 cm por comporta aberta/fechada entre 2 e 12 h atrás | ± 1 cm |
| Chuva observada | 0,30 cm por mm (média de 1 h dos pluviômetros da bacia) | 4 cm |
| Chuva prevista | 0,15 cm por mm previsto para a próxima hora (Open-Meteo) | 3 cm |

O peso da tendência foi conferido com 21 dias de leituras reais (21/10 a 11/11/2026, com cheia perto de 9 m):
erro médio de +1 h de ~1,9 cm na Dom Tito Buss e ~1,1 cm na BR-470 (repetir o nível atual erra ~4 cm).
Na estação do Itajaí do Sul o sensor oscila e o erro típico fica perto de 8 cm. Os demais pesos são
estimativas, ainda não calibradas. A previsão é aproximada e não substitui avisos da Defesa Civil.

## Fontes

- Níveis, cotas, chuva, barragens e boletim: API pública do portal https://defesacivil.riodosul.sc.gov.br/
  (`https://public.asthon.com.br/public/...`).
- Chuva prevista hora a hora: https://open-meteo.com/ (cinco pontos da bacia).
- Chuva sobre as barragens (últimas 24 h): https://open-meteo.com/ nas coordenadas informadas pela própria
  API de barragens (`latitude`/`longitude` de cada estação).
- Contador de teste: https://countapi.mileshilliard.com/

A API intermediária antiga (`HYDROLOGY_API_BASE`) não é mais consultada: estava devolvendo leituras de dias atrás.

## Build

Requisitos: Android SDK 35 (`platforms;android-35` + `build-tools;35.0.0`), JDK 17 ou 21 e Gradle 8.9+
(o APK 1.1.0 foi gerado com Gradle 8.13 + AGP 8.7.3 + JDK 21).

`gradle assembleDebug` → `app/build/outputs/apk/debug/app-debug.apk`

O APK pronto desta versão está em `outputs/nivel-do-rio-v1.1.0-debug.apk` (1.1.0, versionCode 11,
`minSdk 23`, `targetSdk 35`, ~123 KB, já com o Supabase configurado).

### Registro de previsões no Supabase

Já está configurado e testado contra o projeto real:

- URL: `https://qqethajeaxjhirhetpju.supabase.co` (derivada da própria chave `anon`, que traz o `ref` do
  projeto no payload; a tabela `public.river_forecasts` foi criada pelo script `supabase/river_forecasts.sql`).
- A URL e a chave `anon` estão em `gradle.properties` (`SUPABASE_URL` / `SUPABASE_ANON_KEY`), então
  `gradle assembleDebug` já sai configurado. Também podem ser passadas por linha de comando
  (`-PSUPABASE_URL=... -PSUPABASE_ANON_KEY=...`) ou por variáveis de ambiente — é assim que o workflow do
  GitHub Actions funciona, lendo de *secrets*.
- São valores públicos por construção (chave `anon` + RLS liberado para `anon`). **Nunca** coloque a
  `service_role` no app nem no repositório: ela ignora as políticas de segurança. Se ela circula por onde
  não deveria, gere uma nova no painel do Supabase.
- Sem credenciais o app continua funcionando: as previsões ficam no espelho local (`forecast_history` no
  SQLite do aparelho), na fila com `synced_at = 0`, e sobem na primeira atualização depois de configurar.

### Instalar o APK no celular

É um APK de **debug**, assinado com a chave de debug da máquina que o compilou. Se já houver uma versão
instalada com outra chave, o Android recusa a atualização (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`): desinstale
a versão antiga e instale esta (o histórico volta a ser baixado das APIs; as previsões que ainda não
subiram para o Supabase se perdem junto). Instalar pelo gerenciador de arquivos exige permitir
"instalar apps desconhecidos" para o app usado.

Para acompanhar a consolidação dos dados:

```sql
select bridge,
       count(*) filter (where outcome <> 'pendente')                          as conferidas,
       round(100.0 * count(*) filter (where outcome = 'dentro')
             / nullif(count(*) filter (where outcome in ('dentro','quase','fora')), 0)) as pct_dentro,
       round(avg(abs(error_cm)) filter (where outcome in ('dentro','quase','fora'))::numeric, 1) as erro_cm
  from public.river_forecasts group by 1 order by 1;
```

### Importante sobre o contador

O contador atual é adequado apenas para teste. Ele conta no máximo uma abertura por instalação/dia através de um serviço público. Para Play Store, substituir por um backend controlado (Firebase/Cloudflare/Supabase etc.) para métricas confiáveis e política de privacidade adequada.
