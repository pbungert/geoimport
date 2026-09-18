package com.pbungert.geoimport.ui.map

/** "8.4 km", or metres below a kilometre - never "0.1 km" for a short walk. */
fun formatDistance(meters: Double): String =
    if (meters < 1000) "${meters.toInt()} m" else "%.1f km".format(meters / 1000)
