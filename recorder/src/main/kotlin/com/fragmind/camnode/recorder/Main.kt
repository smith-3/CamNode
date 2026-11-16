package com.fragmind.camnode.recorder

import com.fragmind.camnode.core.model.InviteCodec
import com.fragmind.camnode.core.model.InviteParseResult
import com.fragmind.camnode.core.model.LocalNode
import com.fragmind.camnode.core.model.NodeId
import java.net.InetAddress
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """Uso:
  camnode-recorder join "<enlace camnode://join?...>" [--name "PC sala"] [--dir ~/CamNode] [--days 7]
  camnode-recorder run

El enlace se obtiene en la app: Hogar → Invitar dispositivo → Compartir enlace."""

fun main(args: Array<String>) {
    val store = RecorderStore(configFile())
    when (args.firstOrNull()) {
        "join" -> join(store, args.drop(1))
        "run" -> {
            if (!store.exists()) fail("Primero une este equipo al hogar con: camnode-recorder join \"<enlace>\"")
            if (runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor() }.getOrNull() != 0) fail("Instala ffmpeg para grabar")
            RecorderNode(store).run()
        }
        else -> fail(USAGE)
    }
}

private fun join(store: RecorderStore, args: List<String>) {
    val link = args.firstOrNull() ?: fail(USAGE)
    val options = args.drop(1).chunked(2).associate { it.first() to it.getOrElse(1) { fail(USAGE) } }
    val invite = when (val parsed = InviteCodec.decode(link)) {
        is InviteParseResult.Valid -> parsed.invite
        is InviteParseResult.Invalid -> fail("Invitación inválida: ${parsed.problem}")
    }
    val home = System.getProperty("user.home")
    val dir = options["--dir"]?.replaceFirst("~", home) ?: Path.of(home, "CamNode").toString()
    val name = options["--name"] ?: runCatching { InetAddress.getLocalHost().hostName }.getOrDefault("Grabador")
    store.create(
        RecorderState(
            household = invite.household,
            self = LocalNode(NodeId.random(), name),
            storageDir = dir,
            retentionDays = options["--days"]?.toIntOrNull() ?: 7,
            bootstrapSeeds = invite.seeds,
        ),
    )
    println("Unido a \"${invite.household.name}\" como \"$name\". Grabaciones en $dir. Inicia con: camnode-recorder run")
}

private fun configFile(): Path {
    val base = System.getenv("XDG_CONFIG_HOME")?.takeIf(String::isNotBlank) ?: Path.of(System.getProperty("user.home"), ".config").toString()
    return Path.of(base, "camnode-recorder", "state.json")
}

private fun fail(message: String): Nothing {
    System.err.println(message)
    exitProcess(1)
}
