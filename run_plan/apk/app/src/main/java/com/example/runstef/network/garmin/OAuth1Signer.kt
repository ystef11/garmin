package com.example.runstef.network.garmin

import java.net.URLEncoder
import java.util.SortedMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Минимальная реализация подписи OAuth 1.0a (HMAC-SHA1), нужная для обмена
 * SSO-тикета Garmin на OAuth1-токен и для обмена OAuth1 -> OAuth2 (см. GarminAuth).
 */
object OAuth1Signer {

    fun rfc3986Encode(value: String): String =
        URLEncoder.encode(value, "UTF-8")
            .replace("+", "%20")
            .replace("*", "%2A")
            .replace("%7E", "~")

    fun nonce(): String = (1..24).map { "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")

    /**
     * Строит заголовок Authorization: OAuth ... для запроса method+url с доп. oauth-параметрами
     * (например oauth_token/oauth_verifier при обмене тикета).
     */
    fun authHeader(
        method: String,
        url: String,
        consumerKey: String,
        consumerSecret: String,
        token: String? = null,
        tokenSecret: String? = null,
        extraOauthParams: Map<String, String> = emptyMap(),
        queryParams: Map<String, String> = emptyMap()
    ): String {
        val timestamp = (System.currentTimeMillis() / 1000).toString()
        val params = sortedMapOf<String, String>()
        params["oauth_consumer_key"] = consumerKey
        params["oauth_nonce"] = nonce()
        params["oauth_signature_method"] = "HMAC-SHA1"
        params["oauth_timestamp"] = timestamp
        params["oauth_version"] = "1.0"
        if (token != null) params["oauth_token"] = token
        params.putAll(extraOauthParams)
        params.putAll(queryParams)

        val baseUrl = url.substringBefore("?")
        val paramString = params.toSortedMap().entries.joinToString("&") {
            "${rfc3986Encode(it.key)}=${rfc3986Encode(it.value)}"
        }
        val baseString = listOf(
            method.uppercase(),
            rfc3986Encode(baseUrl),
            rfc3986Encode(paramString)
        ).joinToString("&")

        val signingKey = "${rfc3986Encode(consumerSecret)}&${rfc3986Encode(tokenSecret ?: "")}"
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(signingKey.toByteArray(), "HmacSHA1"))
        val signature = android.util.Base64.encodeToString(mac.doFinal(baseString.toByteArray()), android.util.Base64.NO_WRAP)

        val headerParams = sortedMapOf<String, String>()
        headerParams["oauth_consumer_key"] = consumerKey
        headerParams["oauth_nonce"] = params["oauth_nonce"]!!
        headerParams["oauth_signature_method"] = "HMAC-SHA1"
        headerParams["oauth_timestamp"] = timestamp
        headerParams["oauth_version"] = "1.0"
        if (token != null) headerParams["oauth_token"] = token
        headerParams.putAll(extraOauthParams)
        headerParams["oauth_signature"] = signature

        return "OAuth " + headerParams.entries.joinToString(", ") {
            "${rfc3986Encode(it.key)}=\"${rfc3986Encode(it.value)}\""
        }
    }
}
