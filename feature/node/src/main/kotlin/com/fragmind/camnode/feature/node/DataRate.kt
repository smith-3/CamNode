package com.fragmind.camnode.feature.node

import java.util.Locale

/** "850 kbps" or "1.2 Mbps": what one viewer costs, short enough for a chip over video. */
internal fun formatDataRate(bitsPerSecond: Long): String =
    if (bitsPerSecond < 1_000_000) "${(bitsPerSecond + 500) / 1_000} kbps"
    else String.format(Locale.ROOT, "%.1f Mbps", bitsPerSecond / 1_000_000.0)
