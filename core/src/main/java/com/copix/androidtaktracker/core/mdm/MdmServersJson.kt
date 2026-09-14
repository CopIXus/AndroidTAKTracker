package com.copix.androidtaktracker.core.mdm

import com.copix.androidtaktracker.core.config.ServerStreams
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** One TAK stream pushed from Headwind `serversJson`. */
data class MdmServerSpec(
    val host: String,
    val port: Int = 8089,
    val protocol: String = "ssl",
    val enrollPort: Int = 8446,
    val username: String? = null,
    val password: String? = null,
    val token: String? = null,
    val name: String? = null,
    val allowInsecureTlsSoftAccept: Boolean? = null,
) {
    fun secret(): String? {
        val token = token?.trim()?.takeIf { it.isNotEmpty() }
        val password = password?.trim()?.takeIf { it.isNotEmpty() }
        return token ?: password
    }
}

/**
 * Parsed `serversJson` plus overlay fields. When [serversAuthoritative] is true the array
 * is the managed server set (flat `serverHost` is ignored). A parse failure leaves
 * [serversAuthoritative] false so the existing flat keys still enroll.
 */
data class MdmServersDocument(
    val servers: List<MdmServerSpec> = emptyList(),
    val serversAuthoritative: Boolean = false,
    val parseError: String? = null,
    val settingsLock: String? = null,
    val settingsLockClear: Boolean = false,
    val callsign: String? = null,
    val team: String? = null,
    val role: String? = null,
    val allowInsecureTlsSoftAccept: Boolean? = null,
    val requestBatteryExemption: Boolean? = null,
    val preventSleepWhileTracking: Boolean? = null,
)

object MdmServersJson {
    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    fun parse(raw: String?): MdmServersDocument {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) return MdmServersDocument()
        val element = try {
            json.parseToJsonElement(trimmed)
        } catch (ex: Exception) {
            return MdmServersDocument(parseError = "serversJson is not valid JSON (${ex.javaClass.simpleName}).")
        }
        val parsed = when (element) {
            is JsonArray -> MdmServersDocument(
                servers = element.mapNotNull { serverFrom(it) },
                serversAuthoritative = true,
            )
            is JsonObject -> fromObject(element)
            else -> return MdmServersDocument(parseError = "serversJson must be an array or object.")
        }
        return parsed.copy(servers = distinctStreams(parsed.servers))
    }

    private fun fromObject(obj: JsonObject): MdmServersDocument {
        val servers = when (val nested = obj["servers"]) {
            is JsonArray -> nested.mapNotNull { serverFrom(it) }
            null -> emptyList()
            else -> return MdmServersDocument(parseError = "serversJson.servers must be an array.")
        }
        return MdmServersDocument(
            servers = servers,
            serversAuthoritative = true,
            settingsLock = stringField(obj, "settingsLock"),
            settingsLockClear = boolField(obj, "settingsLockClear") == true,
            callsign = stringField(obj, "callsign"),
            team = stringField(obj, "team"),
            role = stringField(obj, "role"),
            allowInsecureTlsSoftAccept = boolField(obj, "allowInsecureTlsSoftAccept"),
            requestBatteryExemption = boolField(obj, "requestBatteryExemption"),
            preventSleepWhileTracking = boolField(obj, "preventSleepWhileTracking"),
        )
    }

    private fun serverFrom(element: JsonElement): MdmServerSpec? {
        val obj = element as? JsonObject ?: return null
        val host = stringField(obj, "host") ?: return null
        return MdmServerSpec(
            host = host,
            port = intField(obj, "port") ?: 8089,
            protocol = stringField(obj, "protocol") ?: "ssl",
            enrollPort = intField(obj, "enrollPort") ?: 8446,
            username = stringField(obj, "username"),
            password = stringField(obj, "password"),
            token = stringField(obj, "token"),
            name = stringField(obj, "name") ?: stringField(obj, "displayName"),
            allowInsecureTlsSoftAccept = boolField(obj, "allowInsecureTlsSoftAccept"),
        )
    }

    private fun stringField(obj: JsonObject, key: String): String? {
        val prim = obj[key] as? JsonPrimitive ?: return null
        return prim.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun intField(obj: JsonObject, key: String): Int? {
        val prim = obj[key] as? JsonPrimitive ?: return null
        prim.doubleOrNull?.toInt()?.takeIf { it > 0 }?.let { return it }
        return prim.contentOrNull?.toIntOrNull()?.takeIf { it > 0 }
    }

    private fun boolField(obj: JsonObject, key: String): Boolean? {
        val prim = obj[key] as? JsonPrimitive ?: return null
        prim.booleanOrNull?.let { return it }
        return parseBool(prim.contentOrNull)
    }

    private fun distinctStreams(servers: List<MdmServerSpec>): List<MdmServerSpec> {
        val seen = LinkedHashSet<String>()
        return servers.filter { seen.add(ServerStreams.key(it.host, it.port, it.protocol)) }
    }

    fun parseBool(raw: String?): Boolean? {
        val v = raw?.trim()?.lowercase() ?: return null
        return when (v) {
            "true", "1", "yes" -> true
            "false", "0", "no" -> false
            else -> null
        }
    }
}
