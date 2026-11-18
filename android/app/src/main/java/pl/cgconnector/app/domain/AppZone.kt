package pl.cgconnector.app.domain

import java.time.ZoneId

/** Strefa kalendarza użytkowników i serwera: „dziś” i daty treningów liczymy zawsze w niej. */
object AppZone {
    val WARSAW: ZoneId = ZoneId.of("Europe/Warsaw")
}
