# Notas técnicas da versão 1.1.0 (task de 11/11/2026)

## Onde está o projeto
- Execução: computador do usuário `DESKTOP-3NGL8T9`, workspace `nivel-do-rio-rio-do-sul-fontes-1.0.0`
  (`D:\Google Drive\Pessoal\NivelRio\nivel-do-rio-rio-do-sul-fontes-1.0.0`).
- App **Android nativo em Java**, namespace `br.com.riodosul.niveldorio`, minSdk 23, compile/target 35,
  Java 11, **sem bibliotecas de terceiros** (View desenhada em Canvas + HttpURLConnection + org.json).
- **Não há JDK/Gradle/Android SDK nesta máquina** e a pasta não tem `.git` → não dá para compilar aqui.
  O build acontece no GitHub Actions (`.github/workflows/build-apk.yml`, `gradle assembleDebug`, artefato
  `nivel-do-rio-debug`).
- Validação local possível: parser de sintaxe Node em `.work/tools/javacheck` (java-parser).
  Rodar: `& "C:\Program Files\nodejs\node.exe" check.js "...\app\src\main\java"` (npm via
  `& "C:\Program Files\nodejs\npm.cmd"` — o `npm.ps1` está bloqueado pela execution policy).

## APIs externas (verificadas em 11/11/2026 com respostas reais)
- `https://public.asthon.com.br/public/panel?city_id=4214805&include_geometry=false` → estações com
  `station_id`, `name`, `level_m`, `last_reading_at`, `band_thresholds[] {cota_m, band_key}`,
  `rainfall_sensor`, `rainfall_1h`, `rainfall_24h`.
- `https://public.asthon.com.br/public/stations/live?city_id=4214805` → reserva (só id).
- `https://public.asthon.com.br/public/station-history?station_id=..&start=..&end=..&fields=level`
  → `{ "level": [ {timestamp, value}, ... ] }` (série de 24 h usada pelo gráfico e pela conferência).
- `https://public.asthon.com.br/public/dams?city_id=4214805` → array; exemplo real:
  `Barragem Oeste Taió` `station_id=d6e340c8-c1d6-441d-8fd0-4381ff17cb54`, lat -27.097438, lon -50.038791,
  `nivel_m` 19.91, `montante_m` 358.91, `gauge_zero_m` 339, `percent_use` 70.49,
  `comportas[]{key,nome,aberta}`, `comportas_abertas` 7, `comportas_total` 7, `measured_at`.
  `Barragem Sul Ituporanga` `station_id=60c6cf33-0148-4c64-842d-5a118f53b6f4`, lat -27.503854, lon -49.553596,
  `percent_use` 90.64, 5 comportas.
- `https://public.asthon.com.br/public/dams/{station_id}/gate-history?city_id=4214805&days=1` →
  `{ station_id, name, gauge_zero_m, window_start, window_end, gates[], events[], series[], coverage }`.
  `series[]` tem **96 pontos de 15 min**: `{ time (ISO Z), nivel_m, montante_m, percent_use, gates_open, gates_known }`
  → é a série de 24 h do reservatório usada no novo gráfico.
  `events[]` = manobras (`time`, `gate_key`, `aberta`, `nivel_m`, `gates_open_after`, `gates_total`).
- `https://api.open-meteo.com/v1/forecast?latitude=..&longitude=..&hourly=precipitation&past_days=1&forecast_days=1&timeformat=unixtime&timezone=GMT`
  → array (uma entrada por ponto, na ordem pedida) com `hourly.time` (unix s) e `hourly.precipitation` (mm).
  Com `past_days=1&forecast_days=0` veio exatamente as 24 h do dia anterior (00:00Z→23:00Z), por isso
  a consulta nova usa `past_days=1&forecast_days=1` e filtra `t <= now` e `t >= now-24h`.
- `https://public.asthon.com.br/public/cities/4214805/forecast/bulletin` → boletim (`days[]`).
- Contador de acessos (teste): `https://countapi.mileshilliard.com/` (`DailyCounter`).
- Cotas oficiais: atenção 4,50 m, alerta 5,50 m, emergência 6,50 m (`Cotas`, cores
  verde #1F915B / amarelo #D6A408 / laranja #DD6B12 / vermelho #C42828).

## Decisão: previsão no Supabase (pedido do usuário)
- Usuário pediu para **gravar a previsão no banco** e depois informou: **usar Supabase**,
  tabela padrão `river_forecasts`, **RLS liberado para a chave `anon`**.
- Ainda não recebi a **URL do projeto** nem a **anon key** → os campos ficam vazios em
  `app/build.gradle` e o app grava só no espelho local (SQLite `forecast_history`), na fila
  (`synced_at = 0`) para subir quando as credenciais existirem.
- Arquivo para colar no SQL Editor: `supabase/river_forecasts.sql` (tabela + trigger de `updated_at`
  + policies anon select/insert/update + view `river_forecasts_accuracy`).
- Colunas: `bridge`, `target_time`, `base_time`, `station_id`, `device_bucket`, `current_level`,
  `predicted_level`, `band_cm`, `tolerance_cm`, `confidence`, `outcome`
  ('pendente','dentro','quase','fora','sem_medicao'), `actual_level`, `actual_time`, `error_cm`,
  `created_at`, `updated_at`, `unique (bridge, target_time)`.
- Envio: POST `/rest/v1/river_forecasts?on_conflict=bridge,target_time` com
  `Prefer: resolution=merge-duplicates,return=minimal`, `apikey` e `Authorization: Bearer <anon>`.
- CI: `build-apk.yml` passa `SUPABASE_URL` / `SUPABASE_ANON_KEY` de secrets para o build (via env;
  `app/build.gradle` lê `findProperty` ou `System.getenv`).

## O que já existscia e foi alterado
- `MainActivity` (spinner de pontes, refresh 5 min, pull refresh) → cards: `RiverStatusCardView`,
  `ForecastCardView` (clique abre `ForecastActivity`), `RiverChartView`, `DamSummaryView`.
- `ForecastEngine` calcula `Forecast` (tendência 0,9×, montante ±3 cm, barragens ±1 cm, chuva observada
  0,30 cm/mm até 4 cm, prevista 0,15 cm/mm até 3 cm).
- `AppCache` serializa o Snapshot inteiro em SharedPreferences (`snapshot_v2`) + memória.

## Feito nesta versão (1.1.0 / versionCode 11)
1. Card "Nível nas últimas 24 horas": borda fina arredondada na cor da criticidade atual + etiqueta da
   situação; água animada na base (duas ondas), faixa de correnteza cruzando o card e reflexo que corre
   sobre a linha (`ChartRenderer.draw(..., pulse, flow)`).
2. Cards das barragens: borda fina em 3 estados — verde (<80% e sem comporta aberta), âmbar (≥80% ou
   comportas abertas), vermelho (≥95%) — etiqueta do estado, 🌧 total 24 h e aviso "2× toque: 24 h".
3. Previsão registrada: `ForecastStore` (SQLite espelho + fila de sync) e `SupabaseForecast` (REST).
4. Clique no card de previsão → `ForecastActivity` ganhou a seção 7 com `ForecastScorecardView`
   (tabela animada: data/hora | nível do rio | previsão | acertou?) + status do Supabase.
5. Duplo toque no card da barragem → nova `DamActivity` com `DamChartView` (24 h: nível, comportas e
   chuva por hora), KPIs, resumo de chuva e de manobras e legenda das cores.

## Verificação feita (11/11/2026, sem Android SDK nesta máquina)
Como aqui não há JDK/Gradle/SDK, a validação foi feita numa máquina Manus (sandbox) com o código copiado
por handoff — nada foi instalado no PC do usuário:

1. **Compilação**: `javac --release 11 -classpath android.jar (API 35)` sobre os 29 fontes, com stubs
   gerados de `R` e `BuildConfig` (`.work/tools/javacheck` + script python no sandbox).
   Resultado: **0 erros**. Único problema encontrado e corrigido: `DamChartView.HOUR` era `int`
   recebendo `3600000L` (conversão com perda) → passou a `long`.
2. **Datas**: `DataRepository.parseTimestamp` confere com os formatos que o Supabase devolve
   (`2026-11-12T15:00:00.000Z`, `...+00:00`, microsegundos) → milissegundos corretos.
3. **Conferência**: `ForecastStore.classify` nos limites — 2 cm/tol 5 = dentro; 6 cm = perto;
   10 cm = perto; 10,1 cm = fora; tolerância abaixo de 5 cm é elevada a 5 cm.
4. **SQL**: `supabase/river_forecasts.sql` executado de ponta a ponta em PostgreSQL 16 (`ON_ERROR_STOP`)
   com as roles `anon`/`authenticated`: tabela, índices, trigger de `updated_at`, policies de RLS e a
   visão `river_forecasts_accuracy` (testado com 1 dentro + 1 fora → `pct_dentro=50`, `erro_medio_cm=6.5`).
   Ajustes feitos por causa do teste: `base_time` passou a **nullable** (um upsert parcial não pode
   falhar) e entraram **grants explícitos** de `select/insert/update` na tabela e `usage/select` na
   sequência do `identity` (sem isso a role `anon` recebe *permission denied* fora dos padrões do Supabase).
5. **REST**: PostgREST 16.5 rodando contra esse banco, com JWT `anon` assinado, recebendo os **payloads
   gerados pelo próprio app** (métodos privados chamados por reflexão):
   - `POST /rest/v1/river_forecasts?on_conflict=bridge,target_time` + `Prefer: resolution=merge-duplicates,return=minimal`
     → **201** na primeira vez e **200** no reenvio, sem duplicar linha;
   - `GET ...?select=target_time&limit=500` (contagem exibida na tela) → OK;
   - `GET ...?select=<colunas>&bridge=eq.DOM_TITO&order=target_time.desc&limit=60` (tela de conferência)
     → OK, e o `fromJson` do app leu a resposta devolvendo nível real, erro em cm e o texto do estado
     (`null` do banco vira `NaN` → "—" / "Aguardando").
   - **Descoberta importante**: o PostgREST rejeita um lote cujos objetos têm chaves diferentes
     (`PGRST102: All object keys must match`). O envio foi então dividido em dois lotes (previsões
     pendentes e previsões conferidas) e `base_time` passou a ser sempre enviado.

## Pendência
Falta apenas o usuário informar **URL do projeto** e a **chave anon** do Supabase (e colar o SQL no
SQL Editor). Sem isso o app roda normalmente, só mantém as previsões no espelho local.

## Supabase real (configurado em 11/11, 19h)
- Chave `anon` informada pelo usuário → payload JWT traz `ref`: **qqethajeaxjhirhetpju** ⇒
  URL do projeto **https://qqethajeaxjhirhetpju.supabase.co** (a URL não estava anotada em lugar nenhum;
  foi deduzida da própria chave e confirmada por teste). Cópia em `.work/supabase-url.txt` e
  `.work/supabase-anon.txt`. Também gravadas em `gradle.properties`.
- O usuário já executou `supabase/river_forecasts.sql` no SQL Editor.
- Sondas feitas **contra o projeto real**, sem deixar lixo na tabela:
  | teste | resultado |
  |---|---|
  | `GET /rest/v1/river_forecasts?select=target_time&limit=3` | **200** `[]` (tabela criada, RLS de leitura OK) |
  | `POST` propositadamente com `outcome` inválido | **400 / 23514** violação de CHECK — ou seja, a política de INSERT para `anon` passou ✓ e nada foi gravado |
  | `PATCH` com filtro que não casa | **204** (política de UPDATE OK) |
  | `GET /rest/v1/river_forecasts_accuracy?select=*` | **200** `[]` (visão acessível pela API) |
  | `GET` com a lista de colunas usada por `SupabaseForecast.pull` | **200** `[]` |
- A `service_role` foi colada na conversa por engano: **não foi usada** em lugar nenhum (nem no app, nem
  nestes arquivos) e vale regenerá-la no painel.

## Como o APK foi gerado (11/11, 19h43)
A máquina do usuário não tem JDK/Gradle/Android SDK, e baixar isso lá é pesado. O build foi feito na
máquina Manus (Linux **arm64**), que não tem binários Android nativos para essa arquitetura:

1. `apt` multiarch: `dpkg --add-architecture amd64` + `libc6:amd64 zlib1g:amd64 libstdc++6:amd64 libgcc-s1:amd64`
   (índices amd64 vieram de `archive.ubuntu.com`, com `ubuntu.sources` restrito a `Architectures: arm64`).
2. `qemu-user-static` + **binfmt_misc** de x86_64 (já vinha registrado pelo pacote) ⇒ os binários
   x86_64 do SDK (`aapt2`, `zipalign`) rodam direto, sem wrapper.
3. SDK montado à mão em `/home/ubuntu/android-sdk`: `platforms/android-35` (de `platform-35_r02.zip`),
   `build-tools/35.0.0` (de `build-tools_r35_linux.zip`) e `licenses/android-sdk-license` com os hashes.
   O AGP ainda auto-instalou `build-tools;34.0.0` e `platform-tools` (licenças aceitas pelos arquivos).
4. Gradle 8.13 (`services.gradle.org`) + JDK 21 do sistema; AGP 8.7.3 baixou `aapt2` x86_64 do Maven.
5. `assembleDebug` com `SUPABASE_URL`/`SUPABASE_ANON_KEY` no ambiente ⇒ **BUILD SUCCESSFUL em 39 s**.

Verificação do artefato (tudo na mesma máquina):
- `aapt2 dump badging`: package `br.com.riodosul.niveldorio`, versionCode **11**, versionName **1.1.0**,
  `minSdkVersion 23`, `targetSdkVersion 35`, label "Nível do Rio", permissões INTERNET +
  ACCESS_NETWORK_STATE, launchable `MainActivity`;
- manifest com `MainActivity`, `ForecastActivity`, **`DamActivity`** e `RiverWidgetProvider` ✓;
- `apksigner verify`: assinado **v1 + v2** com `CN=Android Debug`; `zipalign -c 4` OK;
- `dexdump`: as 10 classes novas/alteradas presentes (`DamActivity`, `DamChartView`, `DamSummaryView`,
  `DamPoint`, `ForecastStore`, `ForecastScorecardView`, `SupabaseForecast`, `RiverChartView`,
  `ChartRenderer`, `RiverStatusCardView`); a URL e a chave `anon` completas estão no dex;
- SHA-256 `4aff4c07d13c97d63bcaac150d85487ac6062bdfa170de714d6b54c1acd81cdb`, 125.477 bytes,
  entregue em `outputs/nivel-do-rio-v1.1.0-debug.apk` (handoff com hash conferido).

**Consequência prática:** como é APK de debug assinado pela chave de debug *desta* máquina, quem tiver uma
versão anterior instalada com outra chave precisa desinstalar antes (`INSTALL_FAILED_UPDATE_INCOMPATIBLE`).

---

# Versão 1.2.0 (12/11/2026)

## Previsão de 1, 2 e 3 horas
- `ForecastEngine` calcula `Forecast.horizons` (1, 2, 3 h). O +1 h é exatamente a conta anterior.
  Para h = 2 e 3: tendência = 0,85 × ritmo × h; montante, barragens e chuva observada escalam com h
  (limites × h); chuva prevista usa o acumulado previsto de h horas (Open-Meteo, `rainNext2hMm`/`rainNext3hMm`).
- Margem: `max(3·√h, 2·ruído·√h + 0,45·|Δ|) + 0,5·(chuva)`; confiança alta ≤ 4·√h, média ≤ 8·√h.
- Calibração (21/10–11/11/2026, mesmas séries da 1.0.0): erro médio +2 h / +3 h = 3,75 / 5,92 cm (Dom Tito),
  2,33 / 3,97 cm (BR-470), contra 8,6 / 12,6 e 7,6 / 11,6 cm repetindo o nível atual; a margem cobriu
  85–91% (+2 h) e 81–88% (+3 h). Itajaí do Sul (sensor ruidoso): 11 / 15,5 cm, pouco melhor que repetir.

## Registro
- SQLite local versão 2: coluna `horizon`, chave única `(bridge, target_time, horizon)`; migração da
  versão 1 renomeia a tabela, copia com `horizon = 1` e apaga a antiga. Testado no sqlite3 do Python com
  as instruções lidas do próprio `ForecastStore.java` (migração, `INSERT OR IGNORE`, consulta da grade).
- Supabase: coluna `horizon_h`, `on_conflict=bridge,target_time,horizon_h`. Se a API responder que a coluna
  não existe (42703 / PGRST204 / 42P10), o app passa ao modo antigo: envia só o +1 h sem `horizon_h`
  e deixa +2 h/+3 h na fila. Migração: `supabase/migracao_1.2.0_horizontes.sql` (não executada aqui —
  não há Postgres nesta máquina e a `service_role` não deve ser usada).
- Estado do projeto real em 12/11 08h (leitura com a chave anon): 42 previsões, sem `horizon_h`.

## Chuva por município
- `/public/stations/list?city_id=4214805` traz `spatial_city_name` de cada estação; cruzado com
  `rainfall_24h`/`last_reading_at` do painel. Pluviômetro sem leitura há > 3 h não entra; o município fica
  com estimativa Open-Meteo (últimas 24 h no centro das suas estações), marcada como "estimativa".
- Em 12/11 08h: 6 municípios medidos (Rio do Sul com 5 pluviômetros), 8 só com estimativa.

## Barragens
- Chuva do gráfico: `/public/station-history?station_id=<barragem>&fields=rain` (10 em 10 min, mm),
  somada em 24 barras de 1 h (horário = fim da hora). Só substitui o Open-Meteo com ≥ 12 h cobertas.
  Motivo: em 12/11 o modelo indicava 4,0 mm (Taió) e 7,3 mm (Ituporanga) e os pluviômetros, 0,0 e 0,2 mm.
- `DamChartView` refeito sem a faixa de comportas; `WeatherIcon` (sol girando / nuvem com gotas) usado no
  gráfico, na tela da barragem e nos cards da tela inicial.

## INMET
- `apitempo.inmet.gov.br/estacoes/T`: público (23 automáticas em SC). Dados por estação
  (`/estacao/{ini}/{fim}/{cod}`) → 204 sem conteúdo; `/token/estacao/...` → "CHAVE INVÁLIDA!".
  Precisa de token solicitado ao INMET. Estação mais próxima: A863 Ituporanga (23 km).

## Verificação
- `gradle assembleDebug` (Gradle 8.14, JBR 21, SDK 35) sem erros.
- Código de dados + previsão + telas novas rodado no PC com dados reais (substitutos das classes de
  desenho do Android em Java2D) e telas renderizadas em PNG. Não rodou em aparelho/emulador.
