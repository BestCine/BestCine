package com.nuvio.app.bestcine

import androidx.compose.ui.graphics.Color

/**
 * Identidade visual e configurações centrais do fork BestCine.
 * Mantido em pacote isolado para facilitar futuros merges com o upstream do Nuvio.
 */
object BestCineBranding {
    const val APP_NAME = "BestCine"
    const val PRODUCTION_BASE_URL = "https://example.invalid"

    /**
     * Repositório GitHub padrão usado pelo sistema de atualização automática (GitHub Releases).
     * Basta alterar o owner/repo para a sua conta GitHub quando subir o repositório,
     * ou redirecionar via `${PRODUCTION_BASE_URL}/api/app/releases`.
     */
    const val GITHUB_UPDATE_OWNER = "BestCine"
    const val GITHUB_UPDATE_REPO = "BestCine"

    val PrimaryBlue = Color(0xFF1E6BFF)
    val PrimaryBlueBright = Color(0xFF4299FF)
    val DeepNightBackground = Color(0xFF070B14)

    val AuthGradientLarge: Array<Pair<Float, Color>> = arrayOf(
        0f to Color(0xFF0B245B),
        0.14f to Color(0xFF0A2050),
        0.26f to Color(0xFF08173A),
        0.38f to Color(0xFF061028),
        0.52f to Color(0xFF040A18),
        0.66f to Color(0xFF03050C),
        0.78f to Color.Black,
        1f to Color.Black,
    )

    val AuthGradientMobile: Array<Pair<Float, Color>> = arrayOf(
        0f to Color(0xFF0B245B),
        0.14f to Color(0xFF0A2050),
        0.26f to Color(0xFF08173A),
        0.38f to Color(0xFF061028),
        0.52f to Color(0xFF040A18),
        0.64f to Color.Black,
        1f to Color.Black,
    )
}
