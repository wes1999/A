# Mapa de Temperaturas do Brasil — Android
Aplicativo Android independente, com malha municipal do IBGE 2025, mapa interativo e paleta térmica. As regiões administrativas do Distrito Federal são exibidas individualmente quando o zoom permite.

## Dados e limites
- O workflow baixa a malha oficial do IBGE 2025 e constrói os GeoJSON do APK (somente na compilação).
- O workflow dá preferência a `inputs/df_alinhado.zip`, se esse arquivo existir. Ele deve conter a malha SHP já alinhada pelo usuário ao contorno do DF (SHP, SHX, DBF e PRJ).
- Na ausência desse ZIP, usa provisoriamente o serviço público de Regiões Administrativas da IDE-DF, intersectando os polígonos com o contorno oficial do DF da malha IBGE 2025. **Esse substituto não é garantidamente idêntico ao arquivo corrigido pelo usuário.**
- Dados meteorológicos da Open-Meteo. Cor geral nacional baseada em amostragem regional interpolada; ao ampliar, a região visível recebe consultas pontuais, e ao tocar em um território recebe consulta específica. Não são medições de estações em cada território.
- Para respeitar os limites da API gratuita, as atualizações são periódicas e limitadas. O app precisa de Internet para novos dados; mantém as últimas estimativas em cache enquanto a tela estiver aberta.
- Requer Internet para carregar o Leaflet CDN no primeiro acesso. Publicação comercial requer plano apropriado da fonte de dados.
## Como compilar
Workflow em `.github/workflows/build-mapa-temperatura.yml` no branch `mapa-temperatura-brasil`. O artefato de saída é `MapaTemperaturaBrasil-APK`. `gradle assembleDebug` gera o APK de teste.
