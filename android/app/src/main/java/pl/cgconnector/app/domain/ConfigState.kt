package pl.cgconnector.app.domain

/** Stan konfiguracji połączenia z serwerem. */
sealed interface ConfigState {
    data object NotConfigured : ConfigState

    /**
     * Adres jest zapisany, ale tokenu nie ma: nie dało się go odszyfrować (np. klucz Keystore unieważniony
     * po zmianie blokady ekranu) i został usunięty. Użytkownik musi wkleić token ponownie.
     */
    data class TokenMissing(val serverUrl: String) : ConfigState

    data class Configured(val config: ServerConfig) : ConfigState
}
