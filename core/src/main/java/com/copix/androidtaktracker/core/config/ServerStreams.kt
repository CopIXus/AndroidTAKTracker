package com.copix.androidtaktracker.core.config

/**
 * One CoT stream. Host is case-insensitive; blank protocol is `ssl`.
 * A second profile with the same key must not open another socket.
 */
object ServerStreams {
    fun key(host: String, port: Int, protocol: String?): String {
        val proto = protocol?.trim()?.lowercase()?.ifBlank { "ssl" } ?: "ssl"
        return "${host.trim().lowercase()}:$port:$proto"
    }

    fun key(profile: ServerProfile): String = key(profile.host, profile.port, profile.protocol)

    fun find(servers: Iterable<ServerProfile>, host: String, port: Int, protocol: String?): ServerProfile? {
        val wanted = key(host, port, protocol)
        return servers.firstOrNull { key(it) == wanted }
    }

    /** First enabled profile for each stream. Later duplicates are not connected. */
    fun uniqueEnabled(servers: Iterable<ServerProfile>): List<ServerProfile> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<ServerProfile>()
        for (profile in servers) {
            if (!profile.enabled || profile.host.isBlank()) continue
            if (seen.add(key(profile))) out.add(profile)
        }
        return out
    }
}
