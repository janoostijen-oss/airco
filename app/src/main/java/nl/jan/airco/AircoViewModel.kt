package nl.jan.airco

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import nl.jan.airco.data.AircoUnit
import nl.jan.airco.data.Auth
import nl.jan.airco.data.BadCredentialsException
import nl.jan.airco.data.CredentialStore
import nl.jan.airco.data.LoginFlowException
import nl.jan.airco.data.FanSpeed
import nl.jan.airco.data.MelcloudApi
import nl.jan.airco.data.Mode
import nl.jan.airco.data.NotLoggedInException
import nl.jan.airco.data.TokenStore
import kotlin.math.round

sealed interface Screen {
    data object Login : Screen
    data object Units : Screen
}

data class UiState(
    val screen: Screen,
    val units: List<AircoUnit> = emptyList(),
    val loading: Boolean = false,
    /** Alleen true bij zelf omlaag trekken; achtergrond-refreshes zijn onzichtbaar. */
    val pullRefreshing: Boolean = false,
    val message: String? = null,
    /** Laatste fout bij ophalen; blijft zichtbaar zolang er geen units zijn. */
    val loadError: String? = null,
    /** Reserve-route: alleen aangeboden als het formulier-inloggen technisch faalt. */
    val webLoginOffered: Boolean = false,
    val showWebLogin: Boolean = false,
    val loginUrl: String? = null,
)

class AircoViewModel(app: Application) : AndroidViewModel(app) {
    private val api = MelcloudApi(TokenStore(app), CredentialStore(app))
    private val _state = MutableStateFlow(UiState(screen = if (api.isLoggedIn) Screen.Units else Screen.Login))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var verifier: String? = null
    private val pendingTemp = mutableMapOf<String, Job>()
    private var refreshJob: Job? = null

    // ---------- Login ----------

    /** Het normale loginformulier. Bij succes worden de gegevens versleuteld bewaard. */
    fun login(username: String, password: String) {
        if (_state.value.loading) return
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            try {
                api.login(username.trim(), password)
                _state.value = UiState(screen = Screen.Units)
                refresh()
            } catch (e: BadCredentialsException) {
                _state.update { it.copy(loading = false, message = e.message) }
            } catch (e: LoginFlowException) {
                _state.update {
                    it.copy(
                        loading = false,
                        webLoginOffered = true,
                        message = "Inloggen via de app lukt nu niet (${e.message}). Gebruik de MELCloud-pagina hieronder.",
                    )
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(loading = false, message = "Geen verbinding met MELCloud: ${e.message}") }
            }
        }
    }

    fun showWebLogin(show: Boolean) {
        _state.update { it.copy(showWebLogin = show, loginUrl = null, message = null) }
        verifier = null
        if (show) prepareLogin()
    }

    fun prepareLogin() {
        if (_state.value.loginUrl != null) return
        viewModelScope.launch {
            runCatching { Auth.startLogin() }
                .onSuccess { req ->
                    verifier = req.verifier
                    _state.update { it.copy(loginUrl = req.authorizeUrl) }
                }
                .onFailure { e -> _state.update { it.copy(message = "Kan loginpagina niet laden: ${e.message}") } }
        }
    }

    /** Aangeroepen door de WebView zodra hij naar `melcloudhome://…` wil. */
    fun onLoginRedirect(code: String?, error: String?) {
        val v = verifier
        if (code == null || v == null) {
            _state.update { it.copy(loginUrl = null, message = "Inloggen mislukt${error?.let { e -> " ($e)" } ?: ""}") }
            verifier = null
            prepareLogin()
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true) }
            runCatching { Auth.exchangeCode(code, v) }
                .onSuccess { tokens ->
                    api.saveTokens(tokens)
                    verifier = null
                    _state.value = UiState(screen = Screen.Units)
                    refresh()
                }
                .onFailure { e ->
                    verifier = null
                    _state.update { it.copy(loading = false, loginUrl = null, message = "Inloggen mislukt: ${e.message}") }
                    prepareLogin()
                }
        }
    }

    fun logout() {
        api.logout()
        _state.value = UiState(screen = Screen.Login)
    }

    // ---------- Data ----------

    fun refresh(userInitiated: Boolean = false) {
        if (_state.value.screen != Screen.Units) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            // Eerste keer laden (lege lijst) of zelf getrokken: laat zien dat er geladen wordt.
            // Anders stil op de achtergrond, zonder spinner die de lijst laat verspringen.
            _state.update {
                it.copy(loading = it.units.isEmpty(), pullRefreshing = userInitiated || it.pullRefreshing)
            }
            try {
                val fresh = api.units()
                _state.update { s ->
                    val merged = fresh.map { u ->
                        val local = s.units.firstOrNull { it.id == u.id }
                        val candidate = if (local != null && pendingTemp[u.id]?.isActive == true) {
                            // Temperatuur die je net aan het bijstellen bent niet overschrijven.
                            u.copy(setTemperature = local.setTemperature)
                        } else u
                        // Ongewijzigd? Houd hetzelfde object, dan slaat Compose die kaart over.
                        if (local != null && candidate == local) local else candidate
                    }
                    s.copy(
                        units = if (merged == s.units) s.units else merged,
                        loading = false,
                        pullRefreshing = false,
                        loadError = null,
                    )
                }
            } catch (e: Exception) {
                handleError(e)
            }
        }
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** "Alles uit/aan" in het overzicht: elke bereikbare unit die nog niet in die stand staat. */
    fun setAllPower(on: Boolean) {
        _state.value.units.filter { it.isConnected && it.power != on }.forEach { setPower(it, on) }
    }

    fun setPower(unit: AircoUnit, on: Boolean) =
        send(unit, mapOf("power" to on)) { it.copy(power = on) }

    fun setMode(unit: AircoUnit, mode: Mode) {
        val values = mutableMapOf<String, Any>("operationMode" to mode.wire)
        var newTemp = unit.setTemperature
        unit.capabilities.rangeFor(mode)?.let { range ->
            val clamped = unit.setTemperature.coerceIn(range)
            if (clamped != unit.setTemperature) {
                values["setTemperature"] = clamped
                newTemp = clamped
            }
        }
        // Modus kiezen op een uitgeschakelde unit = ook aanzetten, voelt natuurlijker.
        if (!unit.power) values["power"] = true
        send(unit, values) { it.copy(mode = mode, setTemperature = newTemp, power = true) }
    }

    fun setFanSpeed(unit: AircoUnit, speed: FanSpeed) =
        send(unit, mapOf("setFanSpeed" to speed.wire)) { it.copy(fanSpeed = speed) }

    /** +/- knoppen: direct zichtbaar, pas na een korte pauze naar MELCloud (scheelt verzoeken). */
    fun changeTemperature(unit: AircoUnit, direction: Int) {
        val current = _state.value.units.firstOrNull { it.id == unit.id } ?: return
        setTemperature(unit, current.setTemperature + direction * current.capabilities.step)
    }

    /** Draaiknop/slepen: exacte waarde, afgerond op de stap van de unit; debounced verstuurd. */
    fun setTemperature(unit: AircoUnit, value: Double) {
        val current = _state.value.units.firstOrNull { it.id == unit.id } ?: return
        val range = current.capabilities.rangeFor(current.mode) ?: return
        val step = current.capabilities.step
        val target = (round(value / step) * step).coerceIn(range)
        if (target == current.setTemperature) return
        patchLocal(unit.id) { it.copy(setTemperature = target) }

        pendingTemp[unit.id]?.cancel()
        pendingTemp[unit.id] = viewModelScope.launch {
            delay(900)
            val latest = _state.value.units.firstOrNull { it.id == unit.id } ?: return@launch
            pushUpdate(unit.id, mapOf("setTemperature" to latest.setTemperature))
        }
    }

    // ---------- intern ----------

    private fun send(unit: AircoUnit, values: Map<String, Any>, optimistic: (AircoUnit) -> AircoUnit) {
        patchLocal(unit.id, optimistic)
        viewModelScope.launch { pushUpdate(unit.id, values) }
    }

    private suspend fun pushUpdate(unitId: String, values: Map<String, Any>) {
        try {
            api.update(unitId, values)
            // MELCloud verwerkt de wijziging met wat vertraging; daarna de echte stand ophalen.
            delay(4000)
            refresh()
        } catch (e: Exception) {
            handleError(e)
            refresh()
        }
    }

    private fun patchLocal(unitId: String, change: (AircoUnit) -> AircoUnit) {
        _state.update { s -> s.copy(units = s.units.map { if (it.id == unitId) change(it) else it }) }
    }

    private fun handleError(e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        if (e is NotLoggedInException) {
            // De API heeft al opgeruimd wat ongeldig is (en stil opnieuw inloggen geprobeerd).
            _state.value = UiState(
                screen = Screen.Login,
                message = e.message,
                webLoginOffered = e.loginFlowBroken,
            )
            return
        }
        val text = e.message?.takeIf { it.isNotBlank() } ?: "Er ging iets mis (${e.javaClass.simpleName})"
        _state.update {
            it.copy(
                loading = false,
                pullRefreshing = false,
                loadError = text,
                // Met een lege lijst staat de fout al groot in beeld; dan geen snackbar erbij.
                message = if (it.units.isEmpty()) null else text,
            )
        }
    }
}
