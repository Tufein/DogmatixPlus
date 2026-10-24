package com.tufein.valkyr.util

import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * Pairing with a RomM server by a short code instead of pasting a token (5.6.0). Pure JVM for the tests;
 * [com.tufein.valkyr.data.service.RommClient] does the asking.
 *
 * VERIFIED against the RomM source code (github.com/rommapp/romm, `backend/endpoints/client_tokens.py` and
 * `backend/utils/client_tokens.py`, read in October 2026 from the main branch), NOT against a running server or a
 * handheld:
 * - In RomM's web interface a client token has a "pair" action. It shows a code of 8 characters from the alphabet
 *   [ALPHABET] that lives for 60 seconds (`PAIR_CODE_TTL_SECONDS`).
 * - `POST /api/client-tokens/exchange` with `{"code": "..."}` needs no login (CSRF-exempt). The server removes the
 *   dashes, upper-cases the code, deletes it (one use) and answers with the same client token under a NEW secret
 *   (`raw_token`, prefix `rmm_`): pairing again replaces the secret of that token, and the old one stops working.
 * - Errors: 404 = unknown or expired code, 429 = more than 5 attempts a minute from this address, 403 = the token's
 *   owner is disabled.
 * Servers older than this feature have no such route and answer 404/405 to it as well, which cannot be told apart
 * from a wrong code; the screen's message covers both.
 */
object RommPairing {

    /** What a code is made of (RomM's `PAIR_ALPHABET`: no 0, 1, I, L or O, so a code reads without doubt). */
    const val ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"
    const val CODE_LENGTH = 8

    /** Client tokens start with this (the prefix RomM gives them). */
    const val TOKEN_PREFIX = "rmm_"

    /** [input] as RomM reads it: separators and blanks dropped, upper case. */
    fun normalize(input: String): String = input.filter { it.isLetterOrDigit() }.uppercase().take(CODE_LENGTH)

    /** Eight characters, all from [ALPHABET]. */
    fun isComplete(input: String): Boolean = normalize(input).let { it.length == CODE_LENGTH && it.all { c -> c in ALPHABET } }

    /** Characters of [input] RomM never uses in a code (shown as a hint while typing). */
    fun invalidChars(input: String): String = normalize(input).filter { it !in ALPHABET }.toSet().joinToString("")

    /** `ABCD-EFGH`, the way the web interface shows it (shorter while it is being typed). */
    fun display(input: String): String = normalize(input).let { if (it.length > 4) it.substring(0, 4) + "-" + it.substring(4) else it }

    fun exchangeBody(input: String): String = JsonObject().apply { addProperty("code", normalize(input)) }.toString()

    sealed interface Exchange {
        /** [token] is the new secret; [name] the name the token has on the server. */
        data class Paired(val token: String, val name: String) : Exchange
        /** 404: wrong, used or expired (codes live for a minute). */
        data object UnknownCode : Exchange
        /** 429. */
        data object TooManyTries : Exchange
        /** 403. */
        data object OwnerDisabled : Exchange
        /** Anything else, with the status (0 = no usable answer). */
        data class Failed(val status: Int) : Exchange
    }

    fun parseExchange(status: Int, json: JsonElement?): Exchange = when {
        status in 200..299 -> {
            val o = json?.takeIf { it.isJsonObject }?.asJsonObject
            val token = o?.text("raw_token").orEmpty()
            if (token.startsWith(TOKEN_PREFIX) && token.length > TOKEN_PREFIX.length) Exchange.Paired(token, o?.text("name").orEmpty())
            else Exchange.Failed(0)
        }
        status == 404 || status == 405 -> Exchange.UnknownCode
        status == 429 -> Exchange.TooManyTries
        status == 403 -> Exchange.OwnerDisabled
        else -> Exchange.Failed(status)
    }

    private fun JsonObject.text(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }
}
