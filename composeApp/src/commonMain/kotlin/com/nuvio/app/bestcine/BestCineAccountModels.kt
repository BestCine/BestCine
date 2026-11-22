package com.nuvio.app.bestcine

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class BestCineUser(
    val id: Long = 0L,
    val nome: String = "",
    val email: String = "",
    val avatar: String? = null,
    val plano: String = "gratuito",
    @SerialName("token_instalacao") val tokenInstalacao: String = "",
    @SerialName("vencimento_apoio") val vencimentoApoio: String? = null,
    val banido: Int = 0,
    @SerialName("motivo_ban") val motivoBan: String? = null,
) {
    val isBanned: Boolean
        get() = banido != 0

    val formattedVencimento: String?
        get() {
            val raw = vencimentoApoio?.trim()
                ?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
                ?: return null
            val datePart = raw.take(10)
            val parts = datePart.split("-")
            return if (parts.size == 3 && parts[0].length == 4) {
                "${parts[2]}/${parts[1]}/${parts[0]}"
            } else {
                datePart
            }
        }

    val isVipPlan: Boolean
        get() {
            val p = plano.trim().lowercase()
            return p == "vip" || p.contains("vip")
        }

    val isApoiadorPlan: Boolean
        get() {
            val p = plano.trim().lowercase()
            return !isVipPlan && (p == "apoiador" || p.contains("apoiador"))
        }

    val isApoiadorOrVip: Boolean
        get() = isVipPlan || isApoiadorPlan

    val isVitalicio: Boolean
        get() = isApoiadorOrVip && formattedVencimento == null

    val planShortBadge: String
        get() = when {
            isVipPlan -> "⭐ VIP"
            isApoiadorPlan -> "👑 Apoiador"
            else -> "Gratuito"
        }

    val planDetailedLabel: String
        get() = when {
            !isApoiadorOrVip -> "Plano Gratuito (Free)"
            isVitalicio -> "$planShortBadge (Vitalício)"
            else -> "$planShortBadge • Válido até $formattedVencimento"
        }

    val planSubtitleLabel: String
        get() = when {
            !isApoiadorOrVip -> "Acesso padrão BestCine"
            isVitalicio -> "⭐ Acesso vitalício liberado • Nunca expira"
            isVipPlan -> "⭐ Acesso VIP ativo • Vencimento em $formattedVencimento"
            else -> "⭐ Benefícios de Apoiador ativos até $formattedVencimento"
        }
}

@Serializable
data class BestCineMeResponse(
    val logado: Boolean = false,
    val usuario: BestCineUser? = null,
    @SerialName("token_invalido") val tokenInvalido: Boolean = false,
)

@Serializable
data class BestCineSession(
    val baseUrl: String = BestCineBranding.PRODUCTION_BASE_URL,
    val user: BestCineUser,
    val lastManifestSyncMs: Long = 0L,
) {
    val normalizedBaseUrl: String
        get() = baseUrl.trim().trimEnd('/')

    val manifestUrl: String
        get() = "$normalizedBaseUrl/${user.tokenInstalacao.trim()}/manifest.json"
}

sealed interface BestCineAuthResult {
    data class Success(val user: BestCineUser) : BestCineAuthResult
    data object Cancelled : BestCineAuthResult
    data class Error(val message: String) : BestCineAuthResult
}
