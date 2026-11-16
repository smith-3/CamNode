package com.fragmind.camnode.core.model

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a new phone needs to join: the household credential plus a few reachable
 * nodes to pull the directory from. Seeds only bootstrap; the directory is the truth.
 */
data class Invite(
    val household: Household,
    val seeds: List<NodeEndpoint>,
)

sealed interface InviteParseResult {
    data class Valid(val invite: Invite) : InviteParseResult
    data class Invalid(val problem: InviteProblem) : InviteParseResult
}

/** Why a link is not a usable invite; each app words it in its own language. */
enum class InviteProblem { NOT_A_LINK, NOT_CAMNODE, UNSUPPORTED_VERSION, INCOMPLETE }

/** `camnode://join?v=1&id=…&name=…&secret=…&seed=host:control:rtsp&seed=…` */
object InviteCodec {
    const val SCHEME = "camnode"
    private const val HOST = "join"
    private const val VERSION = "1"
    const val MAX_SEEDS = 8

    fun encode(invite: Invite): String {
        val params = buildList {
            add("v" to VERSION)
            add("id" to invite.household.id.value)
            add("name" to invite.household.name)
            add("secret" to invite.household.secret.value)
            invite.household.ownerKey?.let { add("owner" to it) }
            invite.seeds.take(MAX_SEEDS).forEach { add("seed" to encodeSeed(it)) }
        }
        return "$SCHEME://$HOST?" + params.joinToString("&") { (key, value) -> "$key=${value.urlEncoded()}" }
    }

    fun decode(text: String): InviteParseResult {
        val uri = runCatching { URI(text.trim()) }.getOrElse { return InviteParseResult.Invalid(InviteProblem.NOT_A_LINK) }
        if (uri.scheme != SCHEME || uri.host != HOST) return InviteParseResult.Invalid(InviteProblem.NOT_CAMNODE)
        val params = parseQuery(uri.rawQuery.orEmpty())
        if (params["v"]?.singleOrNull() != VERSION) return InviteParseResult.Invalid(InviteProblem.UNSUPPORTED_VERSION)
        return runCatching {
            val household = Household(
                id = HouseholdId(params.required("id")),
                name = params.required("name"),
                secret = HouseholdSecret(params.required("secret")),
                ownerKey = params["owner"]?.singleOrNull()?.takeIf(String::isNotBlank),
            )
            val seeds = params["seed"].orEmpty().take(MAX_SEEDS).map(::decodeSeed)
            InviteParseResult.Valid(Invite(household, seeds))
        }.getOrElse { InviteParseResult.Invalid(InviteProblem.INCOMPLETE) }
    }

    private fun encodeSeed(endpoint: NodeEndpoint): String =
        "${endpoint.urlHost}:${endpoint.controlPort}:${endpoint.rtspPort}"

    private fun decodeSeed(seed: String): NodeEndpoint {
        val ports = seed.substringAfterLast(']', seed).split(':').takeLast(2)
        require(ports.size == 2) { "Invalid seed: $seed" }
        val host = seed.removeSuffix(":${ports[0]}:${ports[1]}").removePrefix("[").removeSuffix("]")
        return NodeEndpoint(
            host = host,
            controlPort = ports[0].toIntOrNull() ?: error("Invalid port: $seed"),
            rtspPort = ports[1].toIntOrNull() ?: error("Invalid port: $seed"),
        )
    }

    private fun parseQuery(rawQuery: String): Map<String, List<String>> =
        rawQuery.split('&')
            .filter { it.isNotEmpty() }
            .map { it.substringBefore('=').urlDecoded() to it.substringAfter('=', "").urlDecoded() }
            .groupBy({ it.first }, { it.second })

    private fun Map<String, List<String>>.required(key: String): String =
        this[key]?.singleOrNull()?.takeIf(String::isNotBlank) ?: error("Missing field '$key'")

    // The Charset overloads only exist from Android 13; the charset-name ones work on every phone.
    private fun String.urlEncoded(): String = URLEncoder.encode(this, UTF_8)
    private fun String.urlDecoded(): String = URLDecoder.decode(this, UTF_8)

    private const val UTF_8 = "UTF-8"
}
