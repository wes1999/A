# Mapa de Temperaturas Brasil — V6 (observações + estimativas)

## Fonte das temperaturas
- Todos os 5.569 municípios do IBGE 2025, as 37 regiões administrativas do Distrito Federal e Fernando de Noronha constam do cadastro.
- A base nacional NOAA GFS fornece temperatura estimada para todos os territórios, de modo independente do zoom.
- Onde existe estação aeroportuária METAR física com uma observação recente, dentro do polígono da localidade, a leitura REAL dessa estação substitui a estimativa. Isso não significa que a mesma temperatura vale em todo o território.
- A tela diferencia **OBSERVAÇÃO REAL** de **ESTIMATIVA METEOROLÓGICA** e exibe horário e código da estação quando aplicável.
- O botão Atualizar tudo baixa uma nova fotografia nacional. A fonte é preparada pelo GitHub Actions de hora em hora e o Android verifica a publicação a cada 15 minutos enquanto o aplicativo está aberto.
- A Weather.com NÃO foi integrada para coleta automática: páginas de clima são para navegação humana e a API oficial da Weather.com exige chave/licença específica; não se deve extrair dados de milhares de páginas do site.
- Não há termômetro em todos os municípios. Portanto, "tempo real medido em cada cidade" não é tecnicamente disponível sem uma rede física de sensores para todas essas localidades.
- Transparência de origem: NOAA Aviation Weather Center METAR e NOAA GFS.

## Mapa
- Mesmos dados térmicos e cores do nível Brasil inteiro até zoom máximo.
- Polígonos IBGE 2025 de precisão aumentada.
- Regiões administrativas do DF são disponibilizadas com dados públicos de limites; para reproduzir os ajustes originais do usuário, o arquivo de polígonos personalizado ainda precisa ser incluído em inputs/df_alinhado.zip.

## Compilação
Workflow .github/workflows/build-mapa-temperatura.yml no branch mapa-temperatura-brasil.
O backend em temperature_backend/make_snapshot.py publica dados em data/temperaturas.json
no branch dados-temperaturas, ao lado do catálogo data/pontos.json.
A integração METAR em temperature_backend/metar_observations.py tolera falha de serviço sem perder os municípios estimados.
