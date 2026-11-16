package com.fragmind.camnode.core.model

fun interface WallClock {
    fun nowMillis(): Long

    companion object {
        val System = WallClock { java.lang.System.currentTimeMillis() }
    }
}
