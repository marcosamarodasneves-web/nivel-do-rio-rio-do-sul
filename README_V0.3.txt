Nível do Rio — v0.3 patch

Mudanças:
- abandona scraping HTML do portal para os dados principais;
- usa dados públicos da Defesa Civil;
- usa station_id fixo para as três pontes;
- mantém níveis e barragens na camada isolada de dados;
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
