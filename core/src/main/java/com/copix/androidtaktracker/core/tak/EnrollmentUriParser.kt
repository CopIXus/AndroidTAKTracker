package com.copix.androidtaktracker.core.tak

import java.net.URI
import java.net.URLDecoder

enum class EnrollmentKind {
    UNKNOWN,
    OPEN_TAK_TRACKER_ENROLL,
    TAK_ENROLL,
    TAK_PREFERENCE,
    TAK_IMPORT_URL,
    ITAK_CSV,
}

data class EnrollmentParseResult(
    val kind: EnrollmentKind = EnrollmentKind.UNKNOWN,
    val host: String? = null,
    val port: Int? = null,
    /** Marti certificate enrollment HTTPS port (default 8446). */
    val enrollmentPort: Int = 8446,
    val protocol: String = "ssl",
    val username: String? = null,
    val token: String? = null,
    val callsign: String? = null,
    val team: String? = null,
    val role: String? = null,
    val displayName: String? = null,
    val importUrl: String? = null,
    val preferences: Map<String, String> = emptyMap(),
    val error: String? = null,
) {
    val success: Boolean get() = error == null && kind != EnrollmentKind.UNKNOWN
}

/**
 * Parses opentaktracker://, tak:// enroll/preference/import, and iTAK CSV enrollment strings.
 * Never logs raw input — callers should redact before logging (see RedactedLogger).
 */
object EnrollmentUriParser {

    fun parse(input: String?): EnrollmentParseResult {
        if (input.isNullOrBlank()) return fail("Empty input.")

        val text = input.trim()

        if (text.contains(',') && !text.contains("://"))
            return parseItakCsv(text)

        val uri = try {
            URI(text)
        } catch (_: Exception) {
            null
        }
        if (uri == null || !uri.isAbsolute) return fail("Not a valid URI or iTAK CSV.")

        return when (uri.scheme?.lowercase()) {
            "opentaktracker" -> parseOpenTak(uri)
            "tak" -> parseTak(uri)
            else -> fail("Unsupported scheme: ${uri.scheme}")
        }
    }

    private fun parseOpenTak(uri: URI): EnrollmentParseResult {
        val q = parseQuery(uri)
        val hostField = get(q, "host")
        val split = splitHostField(
            hostField,
            parseInt(get(q, "port")),
            get(q, "protocol"),
            parseInt(get(q, "enrollmentPort") ?: get(q, "enrollPort")),
        )

        return EnrollmentParseResult(
            kind = EnrollmentKind.OPEN_TAK_TRACKER_ENROLL,
            host = split.host,
            username = get(q, "username"),
            token = get(q, "token") ?: get(q, "password"),
            callsign = get(q, "callsign"),
            team = get(q, "team"),
            role = get(q, "role"),
            port = split.streamPort ?: 8089,
            enrollmentPort = split.enrollmentPort ?: 8446,
            protocol = normalizeProtocol(split.protocol ?: "ssl"),
        )
    }

    private fun parseTak(uri: URI): EnrollmentParseResult {
        val path = uri.rawPath.trim('/')
        val q = parseQuery(uri)

        if (path.endsWith("enroll", ignoreCase = true) || path.contains("enroll", ignoreCase = true)) {
            val hostField = get(q, "host")
            val split = splitHostField(
                hostField,
                parseInt(get(q, "port")),
                get(q, "protocol"),
                parseInt(get(q, "enrollmentPort") ?: get(q, "enrollPort")),
            )

            return EnrollmentParseResult(
                kind = EnrollmentKind.TAK_ENROLL,
                host = split.host,
                username = get(q, "username"),
                token = get(q, "token") ?: get(q, "password"),
                callsign = get(q, "callsign"),
                team = get(q, "team"),
                role = get(q, "role"),
                port = split.streamPort ?: 8089,
                enrollmentPort = split.enrollmentPort ?: 8446,
                protocol = normalizeProtocol(split.protocol ?: "ssl"),
            )
        }

        if (path.endsWith("preference", ignoreCase = true) || path.contains("preference", ignoreCase = true)) {
            return EnrollmentParseResult(
                kind = EnrollmentKind.TAK_PREFERENCE,
                callsign = get(q, "locationCallsign") ?: get(q, "callsign"),
                team = get(q, "locationTeam") ?: get(q, "team"),
                role = get(q, "locationRole") ?: get(q, "role"),
                preferences = q,
            )
        }

        if (path.endsWith("import", ignoreCase = true) || path.contains("import", ignoreCase = true)) {
            return EnrollmentParseResult(
                kind = EnrollmentKind.TAK_IMPORT_URL,
                importUrl = get(q, "url"),
            )
        }

        return fail("Unrecognized tak:// path.")
    }

    private fun parseItakCsv(text: String): EnrollmentParseResult {
        val parts = text.split(',')
        if (parts.size < 4) return fail("iTAK CSV requires Name,host,port,protocol.")

        return EnrollmentParseResult(
            kind = EnrollmentKind.ITAK_CSV,
            displayName = parts[0].trim(),
            host = parts[1].trim(),
            port = parseInt(parts[2].trim()) ?: 8089,
            protocol = normalizeProtocol(parts[3].trim()),
        )
    }

    internal data class HostFieldResult(
        val host: String?,
        val streamPort: Int?,
        val protocol: String?,
        val enrollmentPort: Int?,
    )

    /**
     * Accepts host, host:port, or host:port:ssl|tcp (ATAK connect-string style).
     * Port 8446 in the host field is treated as the enrollment port, not CoT streaming.
     */
    internal fun splitHostField(
        hostField: String?,
        explicitPort: Int?,
        explicitProtocol: String?,
        explicitEnrollmentPort: Int?,
    ): HostFieldResult {
        if (hostField.isNullOrBlank())
            return HostFieldResult(null, explicitPort, explicitProtocol, explicitEnrollmentPort)

        var raw = hostField.trim()
        var streamPort: Int? = explicitPort
        var enrollPort: Int? = explicitEnrollmentPort
        var protocol: String? = explicitProtocol

        fun applyPort(port: Int) {
            if (port == 8446) {
                if (enrollPort == null) enrollPort = port
            } else if (streamPort == null) {
                streamPort = port
            }
        }

        // host:port:protocol
        var lastColon = raw.lastIndexOf(':')
        if (lastColon > 0) {
            val maybeProto = raw.substring(lastColon + 1)
            if (maybeProto.equals("ssl", true) || maybeProto.equals("tcp", true) ||
                maybeProto.equals("tls", true) || maybeProto.equals("https", true) || maybeProto.equals("http", true)
            ) {
                if (protocol == null) protocol = maybeProto
                raw = raw.substring(0, lastColon)
                lastColon = raw.lastIndexOf(':')
                if (lastColon > 0) {
                    val connectPort = raw.substring(lastColon + 1).toIntOrNull()
                    if (connectPort != null) {
                        applyPort(connectPort)
                        raw = raw.substring(0, lastColon)
                    }
                }

                return HostFieldResult(raw, streamPort ?: 8089, protocol, enrollPort)
            }
        }

        // host:port
        val colon = raw.lastIndexOf(':')
        if (colon > 0) {
            val portOnly = raw.substring(colon + 1).toIntOrNull()
            if (portOnly != null) {
                applyPort(portOnly)
                raw = raw.substring(0, colon)
            }
        }

        return HostFieldResult(raw, streamPort, protocol, enrollPort)
    }

    private fun parseQuery(uri: URI): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        var q = uri.rawQuery ?: return result
        if (q.startsWith("?")) q = q.substring(1)
        if (q.isEmpty()) return result

        for (pair in q.split('&')) {
            if (pair.isEmpty()) continue
            val idx = pair.indexOf('=')
            if (idx <= 0) {
                result[percentDecode(pair)] = ""
                continue
            }

            val key = percentDecode(pair.substring(0, idx))
            val value = percentDecode(pair.substring(idx + 1)).replace('+', ' ')
            result[key] = value
        }

        return result
    }

    private fun percentDecode(s: String): String = try {
        // Preserve literal '+' (do not treat as space) to match Uri.UnescapeDataString semantics;
        // callers explicitly convert '+' -> ' ' for values only, matching the source parser.
        URLDecoder.decode(s.replace("+", "%2B"), "UTF-8")
    } catch (_: Exception) {
        s
    }

    private fun normalizeProtocol(proto: String): String =
        when (proto.trim().lowercase()) {
            "https", "ssl", "tls" -> "ssl"
            "http", "tcp" -> "tcp"
            else -> "ssl"
        }

    private fun get(q: Map<String, String>, key: String): String? =
        q.entries.firstOrNull { it.key.equals(key, ignoreCase = true) }?.value?.takeIf { it.isNotBlank() }

    private fun parseInt(s: String?): Int? = s?.toIntOrNull()

    private fun fail(error: String): EnrollmentParseResult =
        EnrollmentParseResult(kind = EnrollmentKind.UNKNOWN, error = error)
}
