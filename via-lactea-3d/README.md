# Via Láctea 3D — APK Android
Primeira versão experimental do explorador astronômico para Android, feito para rodar sem conexão à internet no Galaxy S24 e outros celulares compatíveis com WebGL.

## Funções incluídas
- Visualização 3D de um modelo aproximado da Via Láctea, com braços espirais, disco, bojo e halo.
- Renderização econômica por WebGL de 35 mil, 70 mil ou 132 mil pontos estelares (não são todas as estrelas reais).
- Zoom astronômico logarítmico entre a galáxia, o Sol, os planetas e a Terra.
- Distâncias físicas em anos-luz, UA e km e tamanhos planetários aproximados em escala.
- 8 estrelas próximas com posições 3D estimadas de ascensão reta, declinação e distância; dados são aproximados.
- Gestos de rotação, pinça, slider e atalhos.
- 30/60 FPS **como limite durante a navegação**; em repouso a renderização para para reduzir consumo.
- Uso offline, sem permissão de internet e sem anúncios.

## Limitações científicas
Não é possível representar cada estrela real da Via Láctea: existem centenas de bilhões e apenas parte tem posições e distâncias medidas. O grande conjunto de pontos é gerado proceduralmente, e sua localização NÃO é um catálogo Gaia. Estrelas próximas são um pequeno exemplo, em coordenadas equatoriais locais; o disco galáctico é um modelo didático e não uma representação astrométrica de alta precisão. Órbitas planetárias são círculos ilustrativos, sem cálculo de efemérides, e a Terra é representada por material/procedural simples, não uma fotografia. Pontos de tamanho mínimo são marcadores visuais: não representam o diâmetro físico de estrelas distantes.

## Compilação
Executar o workflow "Via Láctea 3D - APK" na aba Actions no branch via-lactea-3d. O artefato ViaLactea3D-APK contém app-debug.apk assinado com chave de desenvolvimento, próprio para testes em Android. Para publicar oficialmente, gere um APK/AAB de release assinado com chave privada.

## Fonte científica
ESA Gaia DR3: https://www.cosmos.esa.int/web/gaia/dr3
Distância galactocêntrica do Sol aproximada: 8,1 kpc (cerca de 26.300 anos-luz). Raio de órbitas e raios dos planetas aproximados de parâmetros usuais do Sistema Solar.
