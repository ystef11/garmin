package com.example.runstef.network.garmin

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

/**
 * Реализация логина Garmin Connect (SSO -> service ticket -> OAuth1 -> OAuth2),
 * повторяющая то, что делает Python-библиотека `garth` (garmin_plan_import.py).
 *
 * ВНИМАНИЕ: Garmin не документирует этот поток официально, страницы SSO и разметка
 * CSRF/MFA могут поменяться без предупреждения — это самая хрупкая часть приложения.
 * Перед использованием стоит проверить вход на реальном аккаунте; если Garmin изменит
 * разметку страницы входа, потребуется поправить регэкспы ниже.
 */
class GarminAuth(
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val SSO_BASE = "https://sso.garmin.com/sso"
        private const val CONNECT_API = "https://connectapi.garmin.com"
        private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        // Публикуется сообществом garth, т.к. Garmin периодически меняет OAuth1-консьюмера.
        private const val CONSUMER_JSON_URL = "https://thegarth.s3.amazonaws.com/oauth_consumer.json"

        private val CSRF_RE = Regex("name=\"_csrf\"\\s+value=\"(.*?)\"")
        private val TICKET_RE = Regex("ticket=([^\"'&]+)")
        private val MFA_MARKER = "sso.garmin.com/sso/verifyMFA/loginEnterMfaCode"
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    data class ConsumerCreds(val key: String, val secret: String)

    private fun fetchConsumerCreds(): ConsumerCreds {
        val req = Request.Builder().url(CONSUMER_JSON_URL).header("User-Agent", UA).build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: throw RuntimeException("Не удалось получить OAuth1-ключи Garmin")
            val key = Regex("\"consumer_key\"\\s*:\\s*\"(.*?)\"").find(text)?.groupValues?.get(1)
                ?: throw RuntimeException("consumer_key не найден в ответе")
            val secret = Regex("\"consumer_secret\"\\s*:\\s*\"(.*?)\"").find(text)?.groupValues?.get(1)
                ?: throw RuntimeException("consumer_secret не найден в ответе")
            return ConsumerCreds(key, secret)
        }
    }

    private fun signinQuery(): String {
        val service = "$SSO_BASE/embed"
        return listOf(
            "service" to service,
            "webhost" to "https://connect.garmin.com/modern",
            "source" to "$SSO_BASE/embed",
            "redirectAfterAccountLoginUrl" to service,
            "redirectAfterAccountCreationUrl" to service,
            "gauthHost" to SSO_BASE,
            "locale" to "ru_RU",
            "id" to "gauth-widget",
            "cssUrl" to "https://connect.garmin.com/gauth-custom-v1.2-min.css",
            "clientId" to "GarminConnect",
            "rememberMeShown" to "true",
            "rememberMeChecked" to "false",
            "createAccountShown" to "true",
            "openCreateAccount" to "false",
            "displayNameShown" to "false",
            "consumeServiceTicket" to "false",
            "initialFocus" to "true",
            "embedWidget" to "false",
            "generateExtraServiceTicket" to "true",
            "generateTwoExtraServiceTickets" to "false",
            "generateNoServiceTicket" to "false",
            "globalOptInShown" to "true",
            "globalOptInChecked" to "false",
            "mobile" to "false",
            "connectLegalTerms" to "true",
            "locationPromptShown" to "true",
            "showPassword" to "true"
        ).joinToString("&") { (k, v) -> "$k=${OAuth1Signer.rfc3986Encode(v)}" }
    }

    /** Интерфейс обратного вызова для запроса кода 2FA у пользователя (показать диалог и вернуть код). */
    fun interface MfaPrompt {
        suspend fun ask(): String
    }

    /**
     * Полный вход по email/паролю. При необходимости вызывает [mfaPrompt] для получения кода 2FA.
     * Возвращает готовые токены (OAuth1 + OAuth2) для сохранения в GarminTokenStore.
     */
    suspend fun login(email: String, password: String, mfaPrompt: MfaPrompt?): GarminTokens {
        val query = signinQuery()
        val signinUrl = "$SSO_BASE/signin?$query"

        // 1) embed — установить куки сессии
        client.newCall(Request.Builder().url("$SSO_BASE/embed?id=gauth-widget&embedWidget=true&gauthHost=$SSO_BASE")
            .header("User-Agent", UA).build()).execute().close()

        // 2) страница входа — вытащить csrf
        val getResp = client.newCall(Request.Builder().url(signinUrl).header("User-Agent", UA).build()).execute()
        val getHtml = getResp.body?.string() ?: ""
        getResp.close()
        var csrf = CSRF_RE.find(getHtml)?.groupValues?.get(1)
            ?: throw RuntimeException("Не удалось получить CSRF-токен со страницы входа Garmin (разметка могла поменяться).")

        // 3) отправка логина/пароля
        val loginBody = FormBody.Builder()
            .add("username", email)
            .add("password", password)
            .add("embed", "true")
            .add("_csrf", csrf)
            .build()
        val loginResp = client.newCall(
            Request.Builder().url(signinUrl)
                .header("User-Agent", UA)
                .header("Referer", signinUrl)
                .post(loginBody)
                .build()
        ).execute()
        var html = loginResp.body?.string() ?: ""
        loginResp.close()

        // 4) MFA?
        if (html.contains(MFA_MARKER) || html.contains("verifyMFA")) {
            log("Требуется код двухфакторной аутентификации Garmin")
            val mfaCsrf = CSRF_RE.find(html)?.groupValues?.get(1) ?: csrf
            val code = mfaPrompt?.ask() ?: throw RuntimeException("Нужен код 2FA, но экран его не запросил.")
            val mfaUrl = "$SSO_BASE/verifyMFA/loginEnterMfaCode?$query"
            val mfaBody = FormBody.Builder()
                .add("mfa-code", code)
                .add("embed", "true")
                .add("_csrf", mfaCsrf)
                .add("fromPage", "setupEnterMfaCode")
                .build()
            val mfaResp = client.newCall(
                Request.Builder().url(mfaUrl)
                    .header("User-Agent", UA)
                    .header("Referer", signinUrl)
                    .post(mfaBody)
                    .build()
            ).execute()
            html = mfaResp.body?.string() ?: ""
            mfaResp.close()
        }

        val ticket = TICKET_RE.find(html)?.groupValues?.get(1)
            ?: throw RuntimeException(
                "Не удалось войти в Garmin Connect: не найден service ticket в ответе. " +
                    "Проверьте логин/пароль или код 2FA."
            )

        val creds = fetchConsumerCreds()

        // 5) обмен тикета на OAuth1 токен
        val preauthUrl = "$CONNECT_API/oauth-service/oauth/preauthorized" +
            "?ticket=${OAuth1Signer.rfc3986Encode(ticket)}" +
            "&login-url=${OAuth1Signer.rfc3986Encode("$SSO_BASE/embed")}" +
            "&accepts-mfa-tokens=true"
        val authHeader1 = OAuth1Signer.authHeader(
            method = "GET",
            url = preauthUrl,
            consumerKey = creds.key,
            consumerSecret = creds.secret,
            queryParams = mapOf(
                "ticket" to ticket,
                "login-url" to "$SSO_BASE/embed",
                "accepts-mfa-tokens" to "true"
            )
        )
        val oauth1Resp = client.newCall(
            Request.Builder().url(preauthUrl).header("Authorization", authHeader1).header("User-Agent", UA).build()
        ).execute()
        val oauth1Text = oauth1Resp.body?.string() ?: ""
        oauth1Resp.close()
        if (!oauth1Resp.isSuccessful) {
            throw RuntimeException("Обмен service ticket на OAuth1 не удался: ${oauth1Resp.code} $oauth1Text")
        }
        val oauth1Params = oauth1Text.split("&").mapNotNull {
            val idx = it.indexOf('=')
            if (idx < 0) null else it.substring(0, idx) to it.substring(idx + 1)
        }.toMap()
        val oauth1Token = oauth1Params["oauth_token"]
            ?: throw RuntimeException("В ответе нет oauth_token: $oauth1Text")
        val oauth1TokenSecret = oauth1Params["oauth_token_secret"]
            ?: throw RuntimeException("В ответе нет oauth_token_secret: $oauth1Text")

        return exchangeOAuth1ForOAuth2(creds, oauth1Token, oauth1TokenSecret)
    }

    private fun exchangeOAuth1ForOAuth2(creds: ConsumerCreds, oauth1Token: String, oauth1TokenSecret: String): GarminTokens {
        val exchangeUrl = "$CONNECT_API/oauth-service/oauth/exchange/user/2.0"
        val authHeader = OAuth1Signer.authHeader(
            method = "POST",
            url = exchangeUrl,
            consumerKey = creds.key,
            consumerSecret = creds.secret,
            token = oauth1Token,
            tokenSecret = oauth1TokenSecret
        )
        val resp = client.newCall(
            Request.Builder().url(exchangeUrl)
                .header("Authorization", authHeader)
                .header("User-Agent", UA)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .post(FormBody.Builder().build())
                .build()
        ).execute()
        val text = resp.body?.string() ?: ""
        resp.close()
        if (!resp.isSuccessful) {
            throw RuntimeException("Обмен OAuth1 -> OAuth2 не удался: ${resp.code} $text")
        }
        val json = Json.parseToJsonElement(text).jsonObject
        val access = json["access_token"]?.jsonPrimitive?.content
            ?: throw RuntimeException("В ответе нет access_token: $text")
        val refresh = json["refresh_token"]?.jsonPrimitive?.content ?: ""
        val expiresIn = json["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L
        return GarminTokens(
            oauth1Token = oauth1Token,
            oauth1TokenSecret = oauth1TokenSecret,
            oauth2AccessToken = access,
            oauth2RefreshToken = refresh,
            oauth2ExpiresAtEpochSec = System.currentTimeMillis() / 1000 + expiresIn
        )
    }

    /** Обновляет OAuth2 access_token, переобменивая сохранённый OAuth1-токен (тот не истекает так быстро). */
    fun refresh(tokens: GarminTokens): GarminTokens {
        val creds = fetchConsumerCreds()
        return exchangeOAuth1ForOAuth2(creds, tokens.oauth1Token, tokens.oauth1TokenSecret)
    }

    fun isExpired(tokens: GarminTokens): Boolean =
        System.currentTimeMillis() / 1000 >= tokens.oauth2ExpiresAtEpochSec - 60

    /** Универсальный вызов connectapi.garmin.com с Bearer-токеном (аналог garth.connectapi). */
    fun connectApi(tokens: GarminTokens, path: String, method: String = "GET", jsonBody: String? = null): Response {
        val url = "$CONNECT_API$path"
        val builder = Request.Builder().url(url)
            .header("Authorization", "Bearer ${tokens.oauth2AccessToken}")
            .header("User-Agent", UA)
        val req = when (method) {
            "GET" -> builder.get()
            "DELETE" -> builder.delete()
            "POST" -> builder.post(
                (jsonBody ?: "{}").toRequestBody("application/json".toMediaType())
            )
            else -> throw IllegalArgumentException(method)
        }.build()
        return client.newCall(req).execute()
    }
}
