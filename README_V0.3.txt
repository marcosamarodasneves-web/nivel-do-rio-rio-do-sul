Nível do Rio — v0.3 patch

Mudanças:
- abandona scraping HTML do portal para os dados principais;
- usa API JSON public.asthon.com.br;
- usa station_id fixo para as três pontes;
- /public/panel como fonte principal;
- /public/stations/live como fallback para níveis;
- /public/dams para barragens;
- mantém cache e histórico local;
- exibe erro resumido da API durante este teste;
- preserva atualização do app a cada 5 min e widget em até 30 min;
- versionName 0.3.0-test.

Arquivos incluídos:
app/build.gradle
app/src/main/java/br/com/riodosul/niveldorio/Bridge.java
app/src/main/java/br/com/riodosul/niveldorio/Snapshot.java
app/src/main/java/br/com/riodosul/niveldorio/DataRepository.java
app/src/main/java/br/com/riodosul/niveldorio/MainActivity.java
app/src/main/java/br/com/riodosul/niveldorio/RiverWidgetProvider.java
