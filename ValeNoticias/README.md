# Vale Noticias (Android)
App nativo com WebView local para acompanhar noticias de Ipatinga, Coronel Fabriciano, Timoteo, Santana do Paraiso e municipios adicionais.

Fontes iniciais: Diario do Aco, Plox, Vale 24 Horas. Para sites comuns, pesquisa Google Noticias RSS por municipio e dominio; para feeds RSS informados diretamente, baixa o feed. A cobertura depende da indexacao e da disponibilidade do RSS.

A chamada apresentada e uma previa do feed e nao um resumo por IA generativa. Esta versao nao inclui IA com chave API para evitar expor credenciais.
Fontes e municipios adicionais sao salvos no armazenamento local do aplicativo.

Build: `gradle :app:assembleDebug` dentro de `ValeNoticias`. APK debug assinado automaticamente. Apenas fontes HTTPS.
