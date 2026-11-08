# TermoMapa Brasil — Android

APK Android de mapa térmico municipal do Brasil. Mostra todos os polígonos oficiais IBGE 2025 (5.569 municípios) e o Distrito Federal dividido em regiões administrativas. Não mostra nomes sobre o mapa: toque em um polígono para consultar o valor.

**Temperaturas:** Open-Meteo, dados de modelo meteorológico (não termômetro físico de cada município). Na visão do Brasil, cores são estimadas por pontos regionais de referência; ao ampliar o mapa, o app atualiza os municípios visíveis individualmente, e ao tocar consulta exatamente as coordenadas do território. Atualiza durante o uso, com cache para respeitar limites gratuitos.

**Arquivo do DF:** a malha inicial é de fonte pública IDE-DF. Para usar a mesma malha ajustada já criada em seus mapas, abra o menu **Importar SHP do DF** e escolha `regioes_administrativas_DF_alinhadas_SHP.zip` ou outro ZIP Shapefile. O app guarda a importação localmente e substitui a malha inicial. Não é necessário importar novamente a cada abertura.

**Criação do APK:** o workflow GitHub Actions da branch `termomapa-s24-android` baixa e prepara os shapefiles IBGE 2025 e compila Android. O arquivo `TermoMapaBrasil.apk` é publicado como artifact; em caso de sucesso o fluxo também tenta publicar o release `termomapa-preview`.

**Dados e licenças:** IBGE (malha digital 2025); IDE/DF (RA); Open-Meteo CC BY 4.0, com atribuição dentro do app; tiles © OpenStreetMap. App sem anúncios, uso pessoal/não comercial.
