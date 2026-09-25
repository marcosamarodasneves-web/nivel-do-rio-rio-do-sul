# Nível do Rio — Rio do Sul (protótipo Android)

Protótipo nativo Android sem bibliotecas de terceiros em runtime.

## O que já existe

- App com 3 pontos de leitura:
  - Ponte Dom Tito Buss — Rio Itajaí-Açu
  - Ponte Ricardo Kanitz — Rio Itajaí do Sul
  - Ponte BR-470 — Rio Itajaí do Oeste
- Widget de tela inicial com nível, situação, tendência e gráfico.
- Botão **Trocar ponte** no widget (cicla pelas três pontes).
- Barragens de Taió e Ituporanga com capacidade, nível e estado das comportas.
- Painel gráfico combinado com histórico do rio, ocupação das barragens e comportas abertas/fechadas.
- Atualização manual por gesto de arrastar para baixo, com indicador giratório durante a consulta.
- Cache: se a fonte falhar, conserva a última leitura válida e marca como dado salvo.
- Histórico local de até 24 h por ponte, coletado pelo próprio aparelho.
- Contador diário de acessos para teste.

## Fontes do protótipo

- Dados atuais: https://defesacivil.riodosul.sc.gov.br/
- Contador de teste: https://countapi.mileshilliard.com/

### Importante sobre a fonte

A leitura está isolada em `DataRepository.java` e usa os endpoints JSON públicos atuais do portal:

- `https://public.asthon.com.br/public/panel?city_id=4214805&include_geometry=false`
- `https://public.asthon.com.br/public/dams?city_id=4214805`

O parser das barragens considera os campos atuais `nivel_m`, `percent_use`, `comportas_abertas`, `comportas_total` e `comportas[].aberta`. Os estados individuais também são preservados no cache para a tela continuar funcionando sem rede.

### Importante sobre o contador

O contador atual é adequado apenas para teste. Ele conta no máximo uma abertura por instalação/dia através de um serviço público. Para Play Store, substituir por um backend controlado (Firebase/Cloudflare/Supabase etc.) para métricas confiáveis e política de privacidade adequada.

## Build

Requisitos:
- Android Studio / Android SDK 35
- JDK 17 ou 21
- Gradle/Android Gradle Plugin compatível

Abra a pasta raiz no Android Studio, aguarde o sync e execute `assembleDebug`.
O APK sairá normalmente em:

`app/build/outputs/apk/debug/app-debug.apk`

## Observação sobre o gráfico

O gráfico do protótipo é histórico **coletado no aparelho** (até 24 h), para não associar indevidamente dados de outra estação à ponte selecionada. Assim que for confirmado um endpoint de histórico municipal por estação, o `HistoryStore` pode ser alimentado diretamente por ele.
