<p align="center">
  <img src="composeApp/src/desktopMain/resources/icons/bestcine-app-icon.png" width="128" height="128" alt="BestCine Logo" />
</p>

<h1 align="center">🎬 BestCine — Aplicativo Oficial (Windows PC & Celular Android)</h1>

<p align="center">
  <strong>Cliente oficial da plataforma BestCine para Windows PC, Celulares e Tablets Android.</strong><br/>
  Filmes, Séries, Canais de TV ao Vivo com Favoritos e EPG, DNS Anti-Bloqueio integrado e Atualizações Automáticas.
</p>

<p align="center">
  <a href="https://github.com/BestCine/BestCine/releases/latest">
    <img src="https://img.shields.io/badge/Vers%C3%A3o_Atual-v1.1.3-1E6BFF?style=for-the-badge" alt="Versão Atual" />
  </a>
  <a href="https://github.com/BestCine/BestCine/releases/download/v1.1.3/BestCine-Windows-x64-1.1.3.msi">
    <img src="https://img.shields.io/badge/Windows-x64_(.msi)-0078D6?style=for-the-badge&logo=windows&logoColor=white" alt="Windows Download" />
  </a>
  <a href="https://github.com/BestCine/BestCine/releases/download/v1.1.3/BestCine-Android-arm64-v8a-1.1.3.apk">
    <img src="https://img.shields.io/badge/Android-Celular_(.apk)-3DDC84?style=for-the-badge&logo=android&logoColor=white" alt="Android Download" />
  </a>
  <a href="https://github.com/BestCine/BestCine-TV">
    <img src="https://img.shields.io/badge/Smart_TVs-BestCine--TV-8B5CF6?style=for-the-badge&logo=youtube-tv&logoColor=white" alt="BestCine TV" />
  </a>
  <a href="https://dsc.gg/bestcine">
    <img src="https://img.shields.io/badge/Discord-Comunidade_Oficial-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord BestCine" />
  </a>
</p>

> 📺 **Usa Smart TV (LG webOS, Samsung Tizen) ou Android TV / Google TV / TV Box?**
> Baixe a versão dedicada para TVs (mais leve, 60 FPS e feita para controle remoto) em **[BestCine/BestCine-TV](https://github.com/BestCine/BestCine-TV)**.

---

## 📥 Download Oficial (v1.1.3)

| Plataforma | Arquivo | Link Direto |
| :--- | :--- | :--- |
| 💻 **Windows 10 / 11 (64-bit)** | `BestCine-Windows-x64-1.1.3.msi` | **[⬇️ Baixar para Windows (.msi)](https://github.com/BestCine/BestCine/releases/download/v1.1.3/BestCine-Windows-x64-1.1.3.msi)** |
| 📱 **Android Celular / Tablet (64-bit — Recomendado)** | `BestCine-Android-arm64-v8a-1.1.3.apk` | **[⬇️ Baixar para Android (.apk)](https://github.com/BestCine/BestCine/releases/download/v1.1.3/BestCine-Android-arm64-v8a-1.1.3.apk)** |
| 📱 **Android Celular Antigo (32-bit)** | `BestCine-Android-armeabi-v7a-1.1.3.apk` | **[⬇️ Baixar APK 32-bit](https://github.com/BestCine/BestCine/releases/download/v1.1.3/BestCine-Android-armeabi-v7a-1.1.3.apk)** |
| 📱 **Android Universal (Todos os Celulares e Tablets)** | `BestCine-Android-universal-1.1.3.apk` | **[⬇️ Baixar APK Universal](https://github.com/BestCine/BestCine/releases/download/v1.1.3/BestCine-Android-universal-1.1.3.apk)** |

> 🔄 **Atualização Automática:** Se você já possui o BestCine instalado no Windows ou no Android, basta abrir o aplicativo para receber o aviso e atualizar automaticamente!

---

## ✨ Recursos Principais

- 🔐 **Login Google & QR Code BestCine:** Conecte sua conta em 1 clique pelo navegador ou escaneando o QR Code no celular, com sincronização em tempo real do seu plano (**Gratuito**, **👑 Apoiador** ou **⭐ VIP**).
- 📺 **TV ao Vivo com ★ Favoritos, EPG & Picture-in-Picture (PiP):** Grade completa de canais organizada por categorias, aba de Favoritos, busca rápida, programação ao vivo e janela flutuante (PiP) no Windows e Android.
- 📚 **Biblioteca Completa:** Abas dedicadas para **Continuar assistindo**, **Favoritos** e **Histórico**.
- 🛡️ **DNS Anti-Bloqueio (Cloudflare DoH IPv4/IPv6):** Contorna automaticamente bloqueios de operadoras sem precisar alterar configurações do Windows ou do celular.
- 🇧🇷 **Busca Inteligente & Metadados em Português:** Temporadas/episódios completos com títulos e sinopses em PT-BR via TMDB + Cinemeta integrado.
- 🔗 **Integrações Ativas:** Suporte completo a **Trakt**, **Simkl**, **MDBList**, **TMDB** e **Discord Rich Presence** (ativado por padrão no Windows).

---

## 🛠️ Compilação a partir do Código-Fonte

Este repositório disponibiliza o código-fonte completo do aplicativo cliente em conformidade com a licença **GPL-3.0**.  
Por segurança, chaves de API externas (como `TMDB_API_KEY`, `TRAKT_CLIENT_ID`, `SIMKL_CLIENT_ID`, `MDBLIST_CLIENT_ID`) e credenciais de assinatura Android não são incluídas no repositório — configure-as localmente no arquivo `local.properties` antes de compilar:

```bash
# Windows MSI
./gradlew :composeApp:packageMsi

# Android Release APK (com otimização R8)
./gradlew :androidApp:assembleFullRelease -Pnuvio.android.distribution=full -PreleaseMinifyEnabled=true
```

---

## 🙏 Créditos, Agradecimentos & Licença (GPL-3.0)

O aplicativo **BestCine** é um projeto independente **não afiliado ao Nuvio**, construído como um fork modificado sobre a base open-source do projeto **[Nuvio (NuvioMedia)](https://github.com/NuvioMedia)** ([NuvioDesktop](https://github.com/NuvioMedia/NuvioDesktop) / [NuvioMobile](https://github.com/NuvioMedia/NuvioMobile)).

Agradecemos imensamente ao **[Tapframe](https://github.com/Tapframe)** e a todos os contribuidores da comunidade **NuvioMedia** pelo excelente trabalho na arquitetura de interface, player multimídia (`libmpv` / `Media3 ExoPlayer`) e ecossistema Kotlin Multiplatform.

Distribuído sob a licença **[GNU General Public License v3.0 (GPL-3.0)](LICENSE)**.
