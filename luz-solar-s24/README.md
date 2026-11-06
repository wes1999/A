# Luz Solar S24 — simulador de iluminação astronômica

Aplicativo experimental Android, criado para o Galaxy S24 (Android 10+).

**Funções**
- Luminária em tela cheia usando a tela AMOLED, sem USB e sem conexão com a lâmpada externa.
- Modo manual: brilho 0–100% e temperatura de cor RGB aproximada 1800–10000 K.
- Modo Sol real: altitude do Sol por data, latitude, longitude e fuso do aparelho.
- Crepúsculos astronômico (-18°), náutico (-12°), civil (-6°) e nascer/pôr do Sol (-0,833°).
- Modo Ciclo 24h: escolha da aurora (ex.: 06:00) e do pôr do Sol (ex.: 18:00), mantendo a duração astronômica das fases.
- Dia acelerado em 1h/6h/12h/24h; temporizador, limite de brilho, tela cheia.
- GPS opcional e coordenadas manuais; padrão: Santana do Paraíso/MG (-19.36, -42.52).
- Sem internet, contas ou rastreadores; localização usada localmente.

**Limitações**
- Kelvin é aproximação RGB, sem calibração com colorímetro.
- Brilho relativo ao painel, não iluminação medida em lux.
- Não substitui luz especializada para plantas.
- Uso prolongado com brilho alto pode aquecer e desgastar o AMOLED.
- O cálculo NOAA é aproximado, para horizonte ideal.
- A simulação acompanha o horário quando o aplicativo volta do repouso.

**APK**
A workflow Build Luz Solar S24 publica o artefato LuzSolarS24-APK no GitHub Actions, como arquivo ZIP contendo app-debug.apk.
