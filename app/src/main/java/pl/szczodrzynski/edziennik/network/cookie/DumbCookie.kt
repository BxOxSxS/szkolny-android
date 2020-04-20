/*
 * Copyright (c) Kuba Szczodrzyński 2020-3-24.
 */

package pl.szczodrzynski.edziennik.network.cookie

import okhttp3.Cookie

class DumbCookie(var cookie: Cookie) {

    constructor(domain: String, name: String, value: String, expiresAt: Long? = null) : this(
            Cookie.Builder()
                    .name(name)
                    .value(value)
                    .also { if (expiresAt != null) it.expiresAt(expiresAt) }
                    .domain(domain)
                    .build()
    )

    init {
        cookie = Cookie.Builder()
                .name(cookie.name())
                .value(cookie.value())
                .expiresAt(cookie.expiresAt())
                .domain(cookie.domain())
                .build()
    }

    fun domainMatches(host: String): Boolean {
        val domain = cookie.domain()
        return host == domain || host.endsWith(".$domain")
    }

    override fun equals(other: Any?): Boolean {
        if (other !is DumbCookie) return false
        if (this.cookie === other.cookie) return true

        return cookie.name() == other.cookie.name()
                && cookie.domain() == other.cookie.domain()
    }

    override fun hashCode(): Int {
        var hash = 17
        hash = 31 * hash + cookie.name().hashCode()
        hash = 31 * hash + cookie.domain().hashCode()
        return hash
    }
}
