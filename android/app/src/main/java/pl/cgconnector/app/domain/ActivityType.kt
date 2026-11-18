package pl.cgconnector.app.domain

/** Typ aktywności. [apiValue] to wartość pola `type` w API serwera. */
enum class ActivityType(val apiValue: String) {
    RUNNING("running"),
    STRENGTH("strength"),
    CLIMBING("climbing"),
    OTHER("other"),
    ;

    companion object {
        /** Nieznane lub brakujące typy trafiają do [OTHER], żeby nowy typ na serwerze nie psuł aplikacji. */
        fun fromApi(value: String?): ActivityType = entries.firstOrNull { it.apiValue == value } ?: OTHER
    }
}
