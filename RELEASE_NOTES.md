## 🎬 BestCine v1.1.16 — Windows, macOS, Linux, Android & iOS

Player e cliente de mídia open-source multiplataforma configurável pelo usuário, baseado no projeto **[NuvioMedia / NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop)** sob licença **GPL-3.0**.

### 🌐 Portal Oficial de Downloads
👉 **https://bestcine.github.io**

### ✨ Novidades, Melhorias e Correções desta versão (v1.1.16)
- **Addons externos liberados:**
  - Nova opção **Addons** disponível em *Configurações → Conteúdo e descoberta → Addons* para instalar, ativar, desativar, reordenar e remover addons externos (como catálogos, legendas e metadados — TMDB, Trakt, MDBList, Letterboxd, Cinemeta, OpenSubtitles, SubDL, entre outros), inclusive via links `stremio://`;
  - Novo interruptor **Catálogos padrão do BestCine na Home** em *Configurações → Tela Inicial* e *Catálogos*, permitindo escolher entre exibir as fileiras padrão ou apenas os catálogos dos seus addons externos;
  - Proteção automática do banner de destaque (*Hero*) para nunca ficar vazio ao ocultar os catálogos padrão.
- **Conta Nuvio opcional em Rastreamento (sincronização entre dispositivos):**
  - Disponível em *Configurações → Serviços → Rastreamento* para sincronizar **Continuar assistindo**, **Assistidos**, **Biblioteca** e **addons externos** entre PC, celular e BestCine TV;
  - Corrigida a persistência da sessão da Conta Nuvio ao fechar e reabrir o aplicativo no Windows e Android, evitando pedidos repetidos de login.
- **Compatibilidade com catálogos de Animes (ARM ID Resolver):**
  - Resolução automática de identificadores `kitsu:`, `mal:`, `anilist:` e `anidb:` para IMDb (`tt...`), permitindo abrir e reproduzir títulos vindos de catálogos externos de anime diretamente no player.
- **Melhorias no Player, Legendas e Cast:**
  - Ajustes no painel de legendas, seleção automática de faixas e sincronização durante a reprodução;
  - Melhorias no suporte MPEG-TS do Cast e refinamentos na aba TV ao Vivo.
- **Melhorias visuais no Celular (Android e iOS):**
  - Card **Catálogos** nas Configurações padronizado com o mesmo estilo discreto do Desktop e dos demais itens do menu;
  - Corrigido o contraste dos botões brancos na tela **Rastreamento** (Conta Nuvio, Trakt e Simkl), garantindo texto e ícones sempre visíveis.
- **Segurança e arquitetura:**
  - Configuração de endpoints movida para injeção exclusiva em tempo de build via variáveis de ambiente / GitHub Secrets.

### 📦 Instaladores desta versão
- **Windows PC (x64):** `BestCine-Windows-x64-1.1.16.msi`
- **macOS Apple Silicon (M1–M4):** `BestCine-macOS-arm64-1.1.16.dmg`
- **macOS Intel:** `BestCine-macOS-x86_64-1.1.16.dmg`
- **Linux (x64):** `BestCine-Linux-x86_64-1.1.16.AppImage`
- **Android Celular/Tablet:** `BestCine-Android-universal-1.1.16.apk`
- **iOS (iPhone / iPad — SideStore / AltStore / Sideloadly / TrollStore):** `BestCine-iOS-1.1.16.ipa`

> **Créditos (GPL-3.0):** Baseado no projeto open-source [NuvioMedia / NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) (Tapframe e contribuidores). O BestCine é um projeto independente e não é afiliado ao Nuvio.
