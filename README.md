<p align="center">
  <img src="composeApp/src/desktopMain/resources/icons/bestcine-app-icon.png" width="128" height="128" alt="BestCine Logo" />
</p>

<h1 align="center">🎬 BestCine (v1.1.5) — Windows PC &amp; Celular / Tablet Android</h1>

<p align="center">
  <strong>Aplicativo oficial da plataforma BestCine para Computador (Windows) e Celular / Tablet (Android).</strong><br/>
  Filmes, Séries, TV ao Vivo com Favoritos e EPG, DNS Anti-Bloqueio e Atualização Automática.
</p>

<p align="center">
  <a href="https://bestcine.github.io">
    <img src="https://img.shields.io/badge/⬇️_CENTRAL_OFICIAL_DE_DOWNLOADS-bestcine.github.io-2563EB?style=for-the-badge" alt="Baixar BestCine" />
  </a>
  <a href="https://github.com/BestCine/BestCine/releases/latest">
    <img src="https://img.shields.io/badge/Vers%C3%A3o_Atual-v1.1.5-1E6BFF?style=for-the-badge" alt="Versão Atual" />
  </a>
  <a href="https://github.com/BestCine/BestCine-TV">
    <img src="https://img.shields.io/badge/Smart_TVs_%2F_Mi_Box_%2F_Fire_TV-BestCine--TV-8B5CF6?style=for-the-badge&logo=youtube-tv&logoColor=white" alt="BestCine TV" />
  </a>
  <a href="https://dsc.gg/bestcine">
    <img src="https://img.shields.io/badge/Discord-Comunidade_Oficial-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord BestCine" />
  </a>
</p>

> 🌐 **JEITO MAIS FÁCIL DE BAIXAR (TODOS OS APARELHOS EM 1 PÁGINA):** acesse **[https://bestcine.github.io](https://bestcine.github.io)** e clique no botão do seu aparelho!

---

## 🎯 QUAL É O SEU APARELHO? (CLIQUE E BAIXE DIRETO — NÃO PRECISA ABRIR "ASSETS")

> ⚠️ **ATENÇÃO ANTES DE BAIXAR!**
> - **Celular / Tablet Android** usa toque na tela → baixe **AQUI** (item 2 abaixo).
> - **Mi Box / TV Box / Fire TV / Google TV / Samsung / LG** usa controle remoto → baixe no **BestCine TV** (item 3 abaixo)! **NÃO instale o app de celular na TV Box ou o controle remoto não vai navegar!**

| Qual aparelho você vai usar? | Clique para baixar o arquivo certo |
| :--- | :--- |
| 💻 **Computador / Notebook Windows (10 ou 11)** | 👉 **[⬇️ BAIXAR PARA WINDOWS (.msi)](https://github.com/BestCine/BestCine/releases/download/v1.1.5/BestCine-Windows-x64-1.1.5.msi)** |
| 📱 **Celular ou Tablet Android (Maioria — 64-bit)** | 👉 **[⬇️ BAIXAR PARA CELULAR ANDROID (.apk)](https://github.com/BestCine/BestCine/releases/download/v1.1.5/BestCine-Android-arm64-v8a-1.1.5.apk)** |
| 📱 **Celular Android mais antigo (se o de cima não instalar)** | 👉 **[⬇️ BAIXAR APK UNIVERSAL (.apk)](https://github.com/BestCine/BestCine/releases/download/v1.1.5/BestCine-Android-universal-1.1.5.apk)** |
| 📺 **Mi Box / TV Box / Fire TV Stick / Google TV (TCL, Philips, Sony…)** | 👉 **[⬇️ BAIXAR BESTCINE TV PARA CONTROLE REMOTO (.apk)](https://github.com/BestCine/BestCine-TV/releases/download/v1.1.5/BestCineTV-1.1.5-android-lite-armv7.apk)** |
| 📺 **Smart TV Samsung (Tizen) ou Smart TV LG (webOS)** | 👉 **[📺 ABRIR PÁGINA DO BESTCINE TV (LG &amp; SAMSUNG)](https://github.com/BestCine/BestCine-TV)** |

> 🔄 **Atualização Automática:** se você já tem o BestCine instalado no Windows ou no Celular Android, basta abrir o app para receber o aviso e atualizar automaticamente!

---

## ✨ Recursos Principais

- 🔐 **Login Google &amp; QR Code BestCine:** conecte sua conta em 1 clique e sincronize automaticamente seu plano (**Gratuito**, **👑 Apoiador** ou **⭐ VIP**).
- 📺 **TV ao Vivo com ★ Favoritos, EPG &amp; Picture-in-Picture (PiP):** grade completa por categorias, busca rápida, programação ao vivo e janela flutuante.
- 📚 **Biblioteca Completa:** *Continuar assistindo*, *Favoritos* e *Histórico* sincronizados.
- 🛡️ **DNS Anti-Bloqueio (Cloudflare DoH IPv4/IPv6):** contorna bloqueios de operadoras sem alterar nada no Windows ou no celular.
- 🇧🇷 **100% Português (BR):** Home, banner de destaque (*Hero*), *Filmes Populares*, *Séries Populares*, temporadas e episódios em português via TMDB.
- 🔗 **Integrações Ativas:** **Trakt**, **Simkl**, **MDBList**, **TMDB** e **Discord Rich Presence**.

---

## 🛠️ Compilação a partir do Código-Fonte (Desenvolvedores)

Este repositório disponibiliza o código-fonte completo do aplicativo cliente sob a licença **GPL-3.0**. Chaves de API externas (`TMDB_API_KEY`, `TRAKT_CLIENT_ID`, `SIMKL_CLIENT_ID`, `MDBLIST_CLIENT_ID`, `NUVIO_DISCORD_CLIENT_ID`) e credenciais de assinatura Android não ficam no repositório — configure-as em `local.properties` antes de compilar:

```bash
# Windows MSI
./gradlew :composeApp:packageMsi

# Android Release APK (com otimização R8)
./gradlew :androidApp:assembleFullRelease -Pnuvio.android.distribution=full -PreleaseMinifyEnabled=true
```

---

## 🙏 Créditos e Licença (GPL-3.0)

O **BestCine** é um projeto independente **não afiliado ao Nuvio**, construído como um fork modificado sobre a base open-source do projeto **[Nuvio (NuvioMedia)](https://github.com/NuvioMedia)** ([NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) / [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)).

Agradecemos ao **[Tapframe](https://github.com/Tapframe)** e a todos os contribuidores da comunidade **NuvioMedia** pelo excelente trabalho na arquitetura de interface, player multimídia (`libmpv` / `Media3 ExoPlayer`) e ecossistema Kotlin Multiplatform.

Distribuído sob a licença **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)**.
