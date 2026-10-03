package com.cortinadev.dogmatix.util

/**
 * Removes what must not end up in a log that is shared for a bug report: tokens, passwords, server
 * addresses, magnet links and IP addresses. Errs on the side of removing too much.
 */
object DiagnosticsRedactor {
    private val bearer = Regex("""(?i)(bearer|basic)\s+[A-Za-z0-9._~+/=-]{6,}""")
    private val rommToken = Regex("""rmm_[A-Za-z0-9_-]+""")
    private val keyValue = Regex("""(?i)\b(api[_-]?key|apikey|token|password|passwd|secret|authorization|auth)\b(["']?\s*[:=]\s*["']?)[^\s&"',;]+""")
    private val url = Regex("""(?i)\b(?:https?|ftp|romm)://[^\s"')<>]+""")
    private val magnet = Regex("""(?i)magnet:\?[^\s"')<>]+""")
    private val contentUri = Regex("""content://[^\s"')<>]+""")
    private val ipv4 = Regex("""\b(?:\d{1,3}\.){3}\d{1,3}(?::\d{2,5})?\b""")
    private val email = Regex("""[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}""")

    /** [secrets] are removed wherever they appear (the saved token, the server host). */
    fun redact(text: String, secrets: Collection<String> = emptyList()): String {
        var out = text
        secrets.filter { it.length >= 4 }.sortedByDescending { it.length }.forEach { out = out.replace(it, "<redacted>") }
        out = bearer.replace(out) { "${it.groupValues[1]} <redacted>" }
        out = rommToken.replace(out, "<token>")
        out = keyValue.replace(out) { "${it.groupValues[1]}${it.groupValues[2]}<redacted>" }
        out = magnet.replace(out, "magnet:<redacted>")
        out = url.replace(out) { m -> schemeOf(m.value) + "<host>" }
        out = contentUri.replace(out, "content://<uri>")
        out = email.replace(out, "<email>")
        out = ipv4.replace(out, "<ip>")
        return out
    }

    private fun schemeOf(url: String): String = url.substringBefore("://", "").let { if (it.isEmpty()) "" else "$it://" }
}
