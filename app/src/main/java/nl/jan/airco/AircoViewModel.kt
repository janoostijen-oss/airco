package nl.jan.airco

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import nl.jan.airco.data.RateLimitedException
import nl.jan.airco.data.TokenStore
import nl.jan.airco.data.outdoorTrend
import nl.jan.airco.data.outdoorExtremes
import nl.jan.airco.data.AtTargetTracker
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
    /** Gemiddelde buitentemperatuur over alle buitenunits (meest recente meting elk). */
    val outdoorTemperature: Double? = null,
    /** Buitentemperatuur t.o.v. een half uur geleden: 1 warmer, -1 kouder, 0 gelijk, `null` onbekend. */
    val outdoorTrend: Int? = null,
    /** Hoogste / laagste buitentemperatuur vandaag (gemiddeld over buitenunits). */
    val outdoorMaxToday: Double? = null,
    val outdoorMinToday: Double? = null,
    /** Units die "op temperatuur" zijn (huidig = doel), zie [nl.jan.airco.data.AtTargetTracker]. */
    val atTarget: Set<String> = emptySet(),
    /** Totaal stroomverbruik vandaag (kWh) van alle units die meten; `null` = onbekend. */
    val energyTodayKwh: Double? = null,
    /** Totaal verbruik gisteren (hele dag, kWh); `null` = (nog) onbekend. */
    val energyYesterdayKwh: Double? = null,
    val energyYesterdayLoading: Boolean = false,
    /** Reserve-route: alleen aangeboden als het formulier-inloggen technisch faalt. */
    val webLoginOffered: Boolean = false,
    val showWebLogin: Boolean = false,
    val loginUrl: String? = null,
)

/**
 * Versheid van de "bijzaken" (buitentemperatuur-rapport, verbruik): hoe oud
 * mogen ze zijn voordat de app ze opnieuw vraagt? Elk ophalen kost één verzoek
 * per buiten-/binnenunit, dus bewust zuinig (advies council):
 *  - vanzelf (elke minuut-ronde): pas na 10 min;
 *  - bij terugkomen in de app of tikken op Buiten/Vandaag: na 3 min;
 *  - nooit vaker dan eens per minuut, ook niet bij zelf vernieuwen.
 */
internal object Freshness {
    const val AUTO_MS = 10 * 60_000L
    const val RESUME_OR_TAP_MS = 3 * 60_000L
    const val MIN_INTERVAL_MS = 60_000L
    const val TAP_DEBOUNCE_MS = 800L
    /** Pauze tussen verzoeken per unit (achter elkaar, niet tegelijk). */
    const val PER_UNIT_GAP_MS = 300L

    /** Ophalen nodig? [fetchedAt] = laatste gelukte, [attemptAt] = laatste poging. */
    fun due(now: Long, fetchedAt: Long, attemptAt: Long, maxAgeMs: Long): Boolean =
        now - fetchedAt >= maxAgeMs && now - attemptAt >= MIN_INTERVAL_MS
}

/** Na zoveel minuten zonder bediening ververst de lijst rustiger. */
private const val IDLE_AFTER_MS = 5 * 60_000L
private const val POLL_MS = 60_000L
private const val POLL_IDLE_MS = 180_000L
private const val SETTLE_MS = 4000L

/**
 * Binnenunits gegroepeerd per buitenunit (multi-split deelt een systemId;
 * zonder systemId heeft een unit zijn eigen buitenunit). Volgorde blijft behouden.
 */
/**
 * Telt het verbruik van vandaag op. Per unit: of hij een meter heeft
 * (`null` = onbekend) en het gemeten verbruik (`null` = geen meetpunten).
 *  - Meter, maar geen meetpunten: hij stond uit, telt als 0.
 *  - Onbekend en geen meetpunten: telt niet mee (misschien meet hij niet).
 * `null` als geen enkele unit iets bijdraagt — dan toont de app "–".
 */
internal fun combineEnergy(perUnit: List<Pair<Boolean?, Double?>>): Double? {
    val known = perUnit.mapNotNull { (meter, kwh) -> kwh ?: if (meter == true) 0.0 else null }
    return if (known.isEmpty()) null else known.sum()
}

/** Begin van vandaag (lokale middernacht) als tijdstip. */
internal fun startOfToday(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

/** Begin van gisteren (lokale middernacht); gisteren loopt tot [startOfToday]. Klopt ook rond zomer-/wintertijd. */
internal fun startOfYesterday(now: Instant, zone: ZoneId): Instant =
    now.atZone(zone).toLocalDate().minusDays(1).atStartOfDay(zone).toInstant()

internal fun outdoorGroups(units: List<AircoUnit>): List<List<String>> =
    units.groupBy({ it.systemId ?: it.id }, { it.id }).values.toList()

class AircoViewModel(app: Application) : AndroidViewModel(app), AircoActions {
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
                // Het units-scherm haalt bij verschijnen zelf de data op (onForeground).
                _state.value = UiState(screen = Screen.Units)
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
                }
                .onFailure { e ->
                    verifier = null
                    _state.update { it.copy(loading = false, loginUrl = null, message = "Inloggen mislukt: ${e.message}") }
                    prepareLogin()
                }
        }
    }

    override fun logout() {
        api.logout()
        _state.value = UiState(screen = Screen.Login)
    }

    // ---------- Data ----------

    private var outdoorFetchedAt = 0L
    private var outdoorAttemptAt = 0L
    private var outdoorJob: Job? = null

    /**
     * Buitentemperatuur: per buitenunit de meest recente meting, daarna het
     * gemiddelde. Binnenunits met hetzelfde systemId delen één buitenunit
     * (multi-split) en tellen dus één keer. Hooguit eens per 10 minuten,
     * tenzij je zelf vernieuwt: het rapport kost een verzoek per unit.
     */
    private fun refreshOutdoor(maxAgeMs: Long) {
        val units = _state.value.units
        // Eén tegelijk: een tik tijdens het ophalen sluit gewoon aan bij dat ophalen.
        if (units.isEmpty() || outdoorJob?.isActive == true) return
        val nowMs = System.currentTimeMillis()
        if (!Freshness.due(nowMs, outdoorFetchedAt, outdoorAttemptAt, maxAgeMs)) return
        outdoorAttemptAt = nowMs
        outdoorJob = viewModelScope.launch {
            // Per buitenunit één rapport, achter elkaar: binnenunits van dezelfde buitenunit
            // meten dezelfde sensor. Lukt het bij de eerste niet, dan de volgende proberen.
            val series = mutableListOf<List<nl.jan.airco.data.Reading>>()
            for ((i, ids) in outdoorGroups(units).withIndex()) {
                if (i > 0) delay(Freshness.PER_UNIT_GAP_MS)
                ids.firstNotNullOfOrNull { id -> outdoorSeriesOrNull(id)?.takeIf { it.isNotEmpty() } }?.let { series += it }
            }
            if (series.isNotEmpty()) {
                outdoorFetchedAt = System.currentTimeMillis()
                val now = Instant.now()
                val extremes = outdoorExtremes(series, startOfToday(now, ZoneId.systemDefault()))
                _state.update {
                    it.copy(
                        outdoorTemperature = series.map { s -> s.last().value }.average(),
                        outdoorTrend = outdoorTrend(series, now),
                        outdoorMaxToday = extremes?.first,
                        outdoorMinToday = extremes?.second,
                    )
                }
            }
        }
    }

    private suspend fun outdoorSeriesOrNull(unitId: String) = try {
        api.outdoorSeries(unitId)
    } catch (e: Exception) {
        if (e is kotlinx.coroutines.CancellationException) throw e
        null // Buitentemperatuur is bijzaak: stil overslaan.
    }

    private var energyFetchedAt = 0L
    private var energyAttemptAt = 0L
    private var energyDay: LocalDate? = null
    private var energyJob: Job? = null

    /**
     * Verbruik vandaag: per unit het verbruik sinds lokale middernacht,
     * opgeteld. Zelfde tempo als de buitentemperatuur (hooguit eens per
     * 10 minuten, tenzij je zelf vernieuwt of het een nieuwe dag is).
     * Units die zeker geen meter hebben worden niet gevraagd.
     */
    private fun refreshEnergy(maxAgeMs: Long) {
        val units = _state.value.units.filter { it.capabilities.hasEnergyMeter != false }
        if (energyJob?.isActive == true) return
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        val newDay = today != energyDay
        val nowMs = System.currentTimeMillis()
        // Nieuwe dag: meteen (nog steeds hooguit eens per minuut), anders volgens de versheidsregel.
        if (!Freshness.due(nowMs, if (newDay) 0L else energyFetchedAt, energyAttemptAt, maxAgeMs)) return
        energyAttemptAt = nowMs
        if (units.isEmpty()) {
            _state.update { it.copy(energyTodayKwh = null) }
            return
        }
        energyJob = viewModelScope.launch {
            val total = energyTotal(units, startOfToday(now, zone), now.plusSeconds(60))
            // Alleen bijwerken als het ophalen voor elke unit lukte; anders de vorige waarde houden.
            if (total != null) {
                energyFetchedAt = System.currentTimeMillis()
                energyDay = today
                _state.update { it.copy(energyTodayKwh = total.value) }
            } else if (newDay) {
                _state.update { it.copy(energyTodayKwh = null) } // gisteren niet als vandaag tonen
            }
            // Nieuwe dag: het bewaarde "gisteren" hoort nu bij eergisteren.
            if (newDay && yesterdayDay != null && yesterdayDay != today) {
                yesterdayDay = null
                _state.update { it.copy(energyYesterdayKwh = null) }
            }
        }
    }

    /** Uitkomst van één optelling; `null` als het ophalen bij een unit mislukte. */
    private class EnergyTotal(val value: Double?)

    private suspend fun energyTotal(units: List<AircoUnit>, from: Instant, to: Instant): EnergyTotal? {
        // Achter elkaar met een korte pauze, niet alle units tegelijk op de API.
        val results = mutableListOf<Pair<Boolean?, Double?>>()
        for ((i, u) in units.withIndex()) {
            if (i > 0) delay(Freshness.PER_UNIT_GAP_MS)
            val kwh = try {
                api.energyKwh(u.id, from, to)
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                return null // mislukt (of even geen budget): deze ronde niet meetellen
            }
            results += u.capabilities.hasEnergyMeter to kwh
        }
        return EnergyTotal(combineEnergy(results))
    }

    /** Dag waarvoor [UiState.energyYesterdayKwh] geldt als "gisteren" (= de dag van vandaag). */
    private var yesterdayDay: LocalDate? = null
    private var yesterdayJob: Job? = null

    /**
     * Gisteren is een afgesloten dag: één keer per dag ophalen en bewaren.
     * Pas bij de eerste tik, zodat wie nooit tikt ook geen extra verzoeken kost.
     */
    override fun loadEnergyYesterday() {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        if (yesterdayDay == today) return // al opgehaald vandaag
        if (yesterdayJob?.isActive == true) return
        val units = _state.value.units.filter { it.capabilities.hasEnergyMeter != false }
        if (units.isEmpty()) return
        yesterdayJob = viewModelScope.launch {
            _state.update { it.copy(energyYesterdayLoading = true) }
            val total = energyTotal(units, startOfYesterday(now, zone), startOfToday(now, zone))
            if (total != null) yesterdayDay = today
            _state.update {
                it.copy(
                    energyYesterdayLoading = false,
                    energyYesterdayKwh = if (total != null) total.value else it.energyYesterdayKwh,
                )
            }
        }
    }

    // ---------- Automatisch verversen ----------

    private var pollJob: Job? = null
    private var lastLocalChangeAt = 0L

    /**
     * Zolang de app op de voorgrond is: meteen verversen, na 15 s nog eens
     * (vangt een net gegeven IR-commando), daarna elke minuut — hetzelfde
     * tempo als melcloud-api's standaard sync. Een wijziging met de
     * IR-afstandsbediening verschijnt zo vanzelf zodra de wifi-module van de
     * unit hem aan MELCloud heeft gemeld.
     */
    override fun onForeground() {
        lastInteractionAt = System.currentTimeMillis()
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            // Terug in de app: bijzaken die ouder zijn dan 3 min meteen vernieuwen
            // (anders zag je na lang openstaan nog de stand van eerder).
            loadUnits(userInitiated = false, background = false, extrasMaxAgeMs = Freshness.RESUME_OR_TAP_MS)
            delay(15_000)
            pollRefresh()
            while (true) {
                // Even niets bediend? Dan rustiger: elke 3 min i.p.v. elke minuut.
                val idle = System.currentTimeMillis() - lastInteractionAt > IDLE_AFTER_MS
                delay(if (idle) POLL_IDLE_MS else POLL_MS)
                pollRefresh()
            }
        }
    }

    private var lastInteractionAt = 0L
    private var tapJob: Job? = null

    /**
     * Tik op Buiten of Vandaag: de weergave wisselt meteen (uit wat er al is);
     * 0,8 s na de láátste tik kijkt de app of de cijfers ouder zijn dan 3 min en
     * haalt ze dan één keer op. Snel achter elkaar tikken kost dus hooguit één ronde.
     */
    override fun onSummaryTap() {
        lastInteractionAt = System.currentTimeMillis()
        tapJob?.cancel()
        tapJob = viewModelScope.launch {
            delay(Freshness.TAP_DEBOUNCE_MS)
            refreshOutdoor(Freshness.RESUME_OR_TAP_MS)
            refreshEnergy(Freshness.RESUME_OR_TAP_MS)
        }
    }

    override fun onBackground() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun pollRefresh() {
        // Net zelf iets aangepast? Dan deze ronde overslaan: de server kan die
        // wijziging nog niet hebben en zou hem op je scherm terugdraaien.
        if (System.currentTimeMillis() - lastLocalChangeAt < 10_000) return
        if (pendingTemp.values.any { it.isActive }) return
        refresh(background = true)
    }

    override fun refresh(userInitiated: Boolean, background: Boolean) {
        if (userInitiated) lastInteractionAt = System.currentTimeMillis()
        // Zelf vernieuwen: bijzaken ook, maar nog steeds hooguit eens per minuut.
        loadUnits(userInitiated, background, extrasMaxAgeMs = if (userInitiated) 0L else Freshness.AUTO_MS)
    }

    private fun loadUnits(userInitiated: Boolean, background: Boolean, extrasMaxAgeMs: Long) {
        if (_state.value.screen != Screen.Units) return
        // Een achtergrondronde onderbreekt geen lopende (handmatige) refresh.
        if (background && refreshJob?.isActive == true) return
        refreshJob?.cancel()
        refreshJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
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
                    // Eigen wijzigingen die al even onderweg waren, zijn nu door MELCloud verwerkt.
                    changedAt.entries.removeAll { (id, at) ->
                        at < startedAt - 3_000 && pendingTemp[id]?.isActive != true
                    }
                    val units = if (merged == s.units) s.units else merged
                    val reached = atTarget.update(units, suppressed = changedAt.keys)
                    s.copy(
                        units = units,
                        atTarget = if (reached == s.atTarget) s.atTarget else reached,
                        loading = false,
                        pullRefreshing = false,
                        loadError = null,
                    )
                }
                refreshOutdoor(extrasMaxAgeMs)
                refreshEnergy(extrasMaxAgeMs)
            } catch (e: Exception) {
                // Bij 429 blijft het verversen gewoon lopen: de poortwachter in de API houdt
                // tijdens het afkoelen álle verzoeken tegen en laat daarna weer één door.
                if (background && e !is NotLoggedInException && e !is kotlinx.coroutines.CancellationException) {
                    // Stil mislukken: geen snackbar elke minuut. Alleen zonder data een fout tonen.
                    _state.update {
                        it.copy(
                            loading = false,
                            loadError = if (it.units.isEmpty()) e.message ?: "Er ging iets mis" else it.loadError,
                        )
                    }
                } else {
                    handleError(e)
                }
            }
        }
    }

    override fun clearMessage() = _state.update { it.copy(message = null) }

    /** "Alles uit/aan" in het overzicht: elke bereikbare unit die nog niet in die stand staat. */
    override fun setAllPower(on: Boolean) {
        _state.value.units.filter { it.isConnected && it.power != on }.forEach { setPower(it, on) }
    }

    override fun setPower(unit: AircoUnit, on: Boolean) =
        send(unit, mapOf("power" to on)) { it.copy(power = on) }

    override fun setMode(unit: AircoUnit, mode: Mode) {
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

    override fun setFanSpeed(unit: AircoUnit, speed: FanSpeed) =
        send(unit, mapOf("setFanSpeed" to speed.wire)) { it.copy(fanSpeed = speed) }

    /** +/- knoppen: direct zichtbaar, pas na een korte pauze naar MELCloud (scheelt verzoeken). */
    override fun changeTemperature(unit: AircoUnit, direction: Int) {
        val current = _state.value.units.firstOrNull { it.id == unit.id } ?: return
        setTemperature(unit, current.setTemperature + direction * current.capabilities.step)
    }

    /** Draaiknop/slepen: exacte waarde, afgerond op de stap van de unit; debounced verstuurd. */
    override fun setTemperature(unit: AircoUnit, value: Double) {
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
            // Tot de controle-refresh "in behandeling" blijven (zoals voorheen): zo lang
            // overschrijft een tussentijdse refresh je nieuwe temperatuur niet.
            delay(SETTLE_MS)
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
            refreshAfterChanges()
        } catch (e: Exception) {
            handleError(e)
            refresh()
        }
    }

    private var settleJob: Job? = null
    private val atTarget = AtTargetTracker()
    /** Laatste eigen wijziging per unit (ms), tot een refresh die daarna begon hem bevestigt. */
    private val changedAt = mutableMapOf<String, Long>()

    /**
     * Eén refresh, 4 s na de láátste wijziging. Snel achter elkaar tikken of
     * "Alles uit" over vijf units kost zo één verzoek in plaats van één per wijziging.
     */
    private fun refreshAfterChanges() {
        settleJob?.cancel()
        settleJob = viewModelScope.launch {
            delay(SETTLE_MS)
            refresh()
        }
    }

    private fun patchLocal(unitId: String, change: (AircoUnit) -> AircoUnit) {
        lastLocalChangeAt = System.currentTimeMillis()
        lastInteractionAt = lastLocalChangeAt
        // Net zelf iets veranderd: niet "op temperatuur" tonen tot MELCloud het bevestigt.
        changedAt[unitId] = lastLocalChangeAt
        _state.update { s ->
            val units = s.units.map { if (it.id == unitId) change(it) else it }
            s.copy(units = units, atTarget = atTarget.update(units, suppressed = changedAt.keys))
        }
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
