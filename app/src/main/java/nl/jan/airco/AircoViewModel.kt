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
import nl.jan.airco.data.EnergyCache
import nl.jan.airco.data.fetchAllWithRetry
import nl.jan.airco.data.fetchEachWithRetry
import nl.jan.airco.data.outdoorTrend
import nl.jan.airco.data.outdoorExtremes
import nl.jan.airco.data.AtTargetTracker
import nl.jan.airco.data.Reading
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
    /** Bezig met ophalen (incl. herhaalpogingen): toont een laad-animatie zolang er nog geen waarde is. */
    val energyTodayLoading: Boolean = false,
    val outdoorLoading: Boolean = false,
    /** Grafiek "vandaag" per unit-id (alleen units waarvan de detailpagina open was). */
    val dayCharts: Map<String, DayChart> = emptyMap(),
    /** Wacht op "Ja/Nee": temperatuur boven 25° bij Warm of Auto (nog niet verstuurd). */
    val hotConfirm: HotConfirm? = null,
    /** Reserve-route: alleen aangeboden als het formulier-inloggen technisch faalt. */
    val webLoginOffered: Boolean = false,
    val showWebLogin: Boolean = false,
    val loginUrl: String? = null,
)

/**
 * Temperatuurverloop van één unit sinds [from] (lokale middernacht): huidig en
 * doel (doel [Double.NaN] = uit). [loading] = bezig met ophalen; [loaded] = er is
 * minstens één keer een antwoord geweest (ook als dat leeg was).
 */
data class DayChart(
    val from: Instant,
    val room: List<Reading> = emptyList(),
    val target: List<Reading> = emptyList(),
    val loading: Boolean = false,
    val loaded: Boolean = false,
)

/**
 * Nieuwe stand uit de lijst (elke minuut) achter de grafiek plakken, zodat hij
 * bijblijft zonder extra verzoeken. Alleen bij verandering; doel NaN als de unit uit staat.
 */
internal fun DayChart.withLive(unit: AircoUnit, now: Instant): DayChart {
    if (now.isBefore(from)) return this
    val room = unit.roomTemperature
    val newRoom = if (room != null && room != this.room.lastOrNull()?.value) this.room + Reading(now, room) else this.room
    val tgt = if (unit.power) unit.setTemperature else Double.NaN
    val last = target.lastOrNull()?.value
    val same = last != null && (last == tgt || (last.isNaN() && tgt.isNaN()))
    val newTarget = if (!same && !(last == null && tgt.isNaN())) target + Reading(now, tgt) else target
    return if (newRoom === this.room && newTarget === target) this else copy(room = newRoom, target = newTarget)
}

/**
 * Versheid van de "bijzaken" (buitentemperatuur-rapport, verbruik): hoe oud
 * mogen ze zijn voordat de app ze opnieuw vraagt? Elk ophalen kost één verzoek
 * per buiten-/binnenunit, dus bewust zuinig (advies council):
 *  - terugkomen in de app (uit de achtergrond): áltijd, zodra de lijst geladen is
 *    (alleen niet als het minder dan 30 s geleden al gebeurde — snel wisselen);
 *  - tikken op Buiten/Vandaag: na 3 min;
 *  - vanzelf (elke minuut-ronde): pas na 10 min;
 *  - verder nooit vaker dan eens per minuut, ook niet bij zelf vernieuwen.
 */
internal object Freshness {
    const val AUTO_MS = 10 * 60_000L
    const val RESUME_OR_TAP_MS = 3 * 60_000L
    const val MIN_INTERVAL_MS = 60_000L
    /** Bij terugkomen in de app: alleen overslaan als het net (< 30 s) gebeurd is. */
    const val RESUME_MIN_INTERVAL_MS = 30_000L
    const val TAP_DEBOUNCE_MS = 800L
    /** Pauze tussen verzoeken per unit (achter elkaar, niet tegelijk). */
    const val PER_UNIT_GAP_MS = 300L

    /** Ophalen nodig? [fetchedAt] = laatste gelukte, [attemptAt] = laatste poging. */
    fun due(now: Long, fetchedAt: Long, attemptAt: Long, maxAgeMs: Long, minIntervalMs: Long = MIN_INTERVAL_MS): Boolean =
        now - fetchedAt >= maxAgeMs && now - attemptAt >= minIntervalMs
}

/** Boven deze temperatuur vraagt de app eerst om bevestiging (alleen bij Warm en Auto). */
internal const val HOT_LIMIT = 25.0

/** Openstaande vraag "hoger dan 25°?": voor welke unit, van → naar. */
data class HotConfirm(val unitId: String, val unitName: String, val from: Double, val to: Double)

/**
 * Moet deze wijziging eerst bevestigd worden? Alleen bij verwarmen (Warm, Auto) en alleen
 * bij het passeren van 25°: van 25° of lager naar erboven. Wie al boven 25° zit en verder
 * omhoog gaat, heeft dat eerder al bevestigd (of via de officiële app ingesteld).
 */
internal fun needsHotConfirm(mode: Mode?, from: Double, to: Double, fromMode: Mode? = mode): Boolean {
    val heating = mode == Mode.Heat || mode == Mode.Automatic
    if (!heating || to <= HOT_LIMIT) return false
    val wasHeating = fromMode == Mode.Heat || fromMode == Mode.Automatic
    // Van koelen/drogen/ventileren naar Warm/Auto met een doel boven 25°: ook vragen.
    return from <= HOT_LIMIT || !wasHeating
}

/** Na zoveel minuten zonder bediening ververst de lijst rustiger. */
private const val IDLE_AFTER_MS = 5 * 60_000L
private const val POLL_MS = 60_000L
/** Grafiek: hooguit eens per 10 min per unit opnieuw (daartussen bijgehouden uit de lijst). */
private const val CHART_MAX_AGE_MS = 10 * 60_000L
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
        energyCache.clear() // niet bij een ander account laten zien
        yesterdayValidUntil = null
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
    private fun refreshOutdoor(maxAgeMs: Long, minIntervalMs: Long = Freshness.MIN_INTERVAL_MS) {
        val units = _state.value.units
        // Eén tegelijk: een tik tijdens het ophalen sluit gewoon aan bij dat ophalen.
        if (units.isEmpty() || outdoorJob?.isActive == true) return
        val nowMs = System.currentTimeMillis()
        if (!Freshness.due(nowMs, outdoorFetchedAt, outdoorAttemptAt, maxAgeMs, minIntervalMs)) return
        outdoorAttemptAt = nowMs
        outdoorJob = viewModelScope.launch {
            // Per buitenunit één rapport, achter elkaar: binnenunits van dezelfde buitenunit
            // meten dezelfde sensor. Lukt het bij de eerste niet, dan de volgende proberen.
            _state.update { it.copy(outdoorLoading = true) }
            // Per buitenunit; lukt het bij geen enkele binnenunit van die buitenunit (fout),
            // dan wordt alleen die buitenunit na 2, 5 en 12 s opnieuw geprobeerd.
            val groups = outdoorGroups(units)
            val fetched: List<List<nl.jan.airco.data.Reading>?> = try {
                // Per buitenunit: wat lukt telt mee (zoals voorheen), alleen mislukte opnieuw.
                fetchEachWithRetry<List<nl.jan.airco.data.Reading>>(groups.size, gapMs = Freshness.PER_UNIT_GAP_MS) { g ->
                    var lastError: Exception? = null
                    for (id in groups[g]) {
                        try {
                            val s = api.outdoorSeries(id)
                            if (s.isNotEmpty()) return@fetchEachWithRetry s
                        } catch (e: kotlinx.coroutines.CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            lastError = e
                        }
                    }
                    lastError?.let { throw it }
                    emptyList() // geen fout, alleen geen metingen
                }
            } finally {
                _state.update { it.copy(outdoorLoading = false) }
            }
            val series = fetched.filterNotNull().filter { it.isNotEmpty() }
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
    private fun refreshEnergy(maxAgeMs: Long, minIntervalMs: Long = Freshness.MIN_INTERVAL_MS) {
        val units = _state.value.units.filter { it.capabilities.hasEnergyMeter != false }
        if (energyJob?.isActive == true) return
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val today = now.atZone(zone).toLocalDate()
        val newDay = today != energyDay
        val nowMs = System.currentTimeMillis()
        // Nieuwe dag: meteen (nog steeds hooguit eens per minuut), anders volgens de versheidsregel.
        if (!Freshness.due(nowMs, if (newDay) 0L else energyFetchedAt, energyAttemptAt, maxAgeMs, minIntervalMs)) return
        energyAttemptAt = nowMs
        if (units.isEmpty()) {
            _state.update { it.copy(energyTodayKwh = null) }
            return
        }
        energyJob = viewModelScope.launch {
            _state.update { it.copy(energyTodayLoading = true) }
            val total = try {
                energyTotal(units, startOfToday(now, zone), now.plusSeconds(60))
            } finally {
                _state.update { it.copy(energyTodayLoading = false) }
            }
            // Alleen bijwerken als het ophalen voor elke unit lukte; anders de vorige waarde houden.
            if (total != null) {
                energyFetchedAt = System.currentTimeMillis()
                energyDay = today
                _state.update { it.copy(energyTodayKwh = total.value) }
            } else if (newDay) {
                _state.update { it.copy(energyTodayKwh = null) } // gisteren niet als vandaag tonen
            }
            // Nieuwe dag: het bewaarde "gisteren" hoort nu bij eergisteren.
            if (newDay && yesterdayValidUntil != null && Instant.now() >= yesterdayValidUntil) {
                yesterdayValidUntil = null
                _state.update { it.copy(energyYesterdayKwh = null) }
            }
        }
    }

    /** Uitkomst van één optelling; `null` als het ophalen bij een unit mislukte. */
    private class EnergyTotal(val value: Double?)

    private suspend fun energyTotal(
        units: List<AircoUnit>,
        from: Instant,
        to: Instant,
        onRetry: (Int) -> Unit = {},
    ): EnergyTotal? {
        // Per unit, achter elkaar met een korte pauze. Mislukt er één (time-out, serverfout),
        // dan alleen die opnieuw na 2, 5 en 12 s — eerder viel dan het hele totaal weg tot de
        // volgende ronde (soms ruim een minuut "–" na het openen).
        val results = fetchAllWithRetry(units.size, gapMs = Freshness.PER_UNIT_GAP_MS, onRetry = onRetry) { i ->
            val u = units[i]
            try {
                u.capabilities.hasEnergyMeter to api.energyKwh(u.id, from, to)
            } catch (e: nl.jan.airco.data.HttpStatusException) {
                // Unit waarvan niet zeker is dát hij meet en die de vraag afwijst (4xx):
                // telt niet mee, maar laat het totaal van de rest niet wegvallen.
                if (u.capabilities.hasEnergyMeter != true && e.code in 400..499 && e.code != 408) u.capabilities.hasEnergyMeter to null
                else throw e
            }
        } ?: return null
        return EnergyTotal(combineEnergy(results))
    }

    /** Dag waarvoor [UiState.energyYesterdayKwh] geldt als "gisteren" (= de dag van vandaag). */
    private var yesterdayValidUntil: Instant? = null
    private var yesterdayJob: Job? = null
    private val energyCache = EnergyCache(app)

    /**
     * Gisteren is een afgesloten dag: één keer per dag ophalen en bewaren.
     * Pas bij de eerste tik, zodat wie nooit tikt ook geen extra verzoeken kost.
     */
    override fun loadEnergyYesterday() {
        val zone = ZoneId.systemDefault()
        val now = Instant.now()
        val yesterday = now.atZone(zone).toLocalDate().minusDays(1)
        yesterdayValidUntil?.let { if (now < it) return } // al opgehaald en nog geldig
        if (yesterdayJob?.isActive == true) return
        // Op het toestel bewaard (ook na herstart): geen verzoek nodig.
        energyCache.load(yesterday)?.let { cached ->
            yesterdayValidUntil = yesterday.plusDays(2).atStartOfDay(zone).toInstant()
            _state.update { it.copy(energyYesterdayKwh = cached, energyYesterdayLoading = false) }
            return
        }
        val units = _state.value.units.filter { it.capabilities.hasEnergyMeter != false }
        if (units.isEmpty()) return
        yesterdayJob = viewModelScope.launch {
            _state.update { it.copy(energyYesterdayLoading = true) }
            val total = try {
                energyTotal(units, startOfYesterday(now, zone), startOfToday(now, zone))
            } catch (e: Exception) {
                _state.update { it.copy(energyYesterdayLoading = false) }
                throw e
            }
            if (total != null) {
                val fetchedAt = now.atZone(zone)
                yesterdayValidUntil = EnergyCache.validUntil(fetchedAt, zone).toInstant()
                // Pas na 01:00 en alleen een geldige waarde > 0 vastleggen: daarvoor kan
                // MELCloud nog verbruik van gisteren nasturen.
                if (EnergyCache.shouldPersist(total.value, fetchedAt)) energyCache.save(yesterday, total.value!!)
            }
            _state.update {
                it.copy(
                    energyYesterdayLoading = false,
                    energyYesterdayKwh = if (total != null) total.value else it.energyYesterdayKwh,
                )
            }
        }
    }

    // ---------- Grafiek vandaag ----------

    private val chartFetchedAt = mutableMapOf<String, Long>()
    private val chartAttemptAt = mutableMapOf<String, Long>()
    private var chartJob: Job? = null
    private var chartJobUnit: String? = null

    /**
     * Grafiek voor de geopende unit: één rapport-verzoek, hooguit eens per 10 minuten
     * per unit (daartussen plakt elke lijst-ronde de nieuwe stand erachter, zie
     * [withLive]). Een nieuwe dag begint met een lege grafiek. Wissel je van kamer,
     * dan stopt het ophalen van de vorige.
     */
    override fun loadDayChart(unitId: String) {
        if (_state.value.screen != Screen.Units) return
        if (chartJob?.isActive == true && chartJobUnit == unitId) return
        val now = Instant.now()
        val from = startOfToday(now, ZoneId.systemDefault())
        val existing = _state.value.dayCharts[unitId]?.takeIf { it.from == from }
        if (existing == null) {
            chartFetchedAt.remove(unitId)
            chartAttemptAt.remove(unitId)
        }
        val nowMs = System.currentTimeMillis()
        val due = Freshness.due(nowMs, chartFetchedAt[unitId] ?: 0L, chartAttemptAt[unitId] ?: 0L, CHART_MAX_AGE_MS)
        if (!due) return
        chartAttemptAt[unitId] = nowMs
        chartJob?.cancel()
        chartJobUnit = unitId
        _state.update { s -> s.copy(dayCharts = s.dayCharts + (unitId to (existing ?: DayChart(from)).copy(loading = true))) }
        chartJob = viewModelScope.launch {
            val series = try {
                fetchEachWithRetry(1) { api.daySeries(unitId, from) }.first()
            } catch (e: kotlinx.coroutines.CancellationException) {
                chartAttemptAt.remove(unitId) // afgebroken (andere kamer): straks gewoon opnieuw
                _state.update { s -> s.copy(dayCharts = s.dayCharts.update(unitId) { it.copy(loading = false) }) }
                throw e
            }
            if (series != null) chartFetchedAt[unitId] = System.currentTimeMillis()
            _state.update { s ->
                val unit = s.units.firstOrNull { it.id == unitId }
                s.copy(
                    dayCharts = s.dayCharts.update(unitId) { c ->
                        if (series == null) c.copy(loading = false)
                        else {
                            val fresh = DayChart(from, series.room, series.target, loading = false, loaded = true)
                            unit?.let { fresh.withLive(it, Instant.now()) } ?: fresh
                        }
                    },
                )
            }
        }
    }

    private inline fun Map<String, DayChart>.update(id: String, f: (DayChart) -> DayChart): Map<String, DayChart> =
        this[id]?.let { this + (id to f(it)) } ?: this

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
            // Uit de achtergrond: Buiten en verbruik vandaag áltijd vernieuwen. Als vlag, zodat
            // het ook gebeurt als dit eerste verzoek mislukt (netwerk komt net terug): dan
            // doet de eerstvolgende gelukte ronde het alsnog.
            extrasOnNextLoad = true
            loadUnits(userInitiated = false, background = false, extrasMaxAgeMs = Freshness.AUTO_MS)
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
    /** Gezet bij terugkomen in de app; de eerstvolgende gelukte lijst-ronde vernieuwt Buiten en verbruik. */
    private var extrasOnNextLoad = false

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
                        val awaitingAnswer = s.hotConfirm?.unitId == u.id
                        val candidate = if (local != null && awaitingAnswer) {
                            // Vraag staat open: de getoonde (nog niet verstuurde) stand vasthouden.
                            u.copy(setTemperature = local.setTemperature, mode = local.mode, power = local.power)
                        } else if (local != null && pendingTemp[u.id]?.isActive == true) {
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
                    val now = Instant.now()
                    val charts = if (s.dayCharts.isEmpty()) s.dayCharts else s.dayCharts.mapValues { (id, c) ->
                        units.firstOrNull { it.id == id }?.let { c.withLive(it, now) } ?: c
                    }
                    s.copy(
                        units = units,
                        dayCharts = charts,
                        atTarget = if (reached == s.atTarget) s.atTarget else reached,
                        loading = false,
                        pullRefreshing = false,
                        loadError = null,
                    )
                }
                if (extrasOnNextLoad) {
                    extrasOnNextLoad = false
                    refreshOutdoor(maxAgeMs = 0L, minIntervalMs = Freshness.RESUME_MIN_INTERVAL_MS)
                    refreshEnergy(maxAgeMs = 0L, minIntervalMs = Freshness.RESUME_MIN_INTERVAL_MS)
                } else {
                    refreshOutdoor(extrasMaxAgeMs)
                    refreshEnergy(extrasMaxAgeMs)
                }
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
        if (_state.value.hotConfirm != null) return
        if (needsHotConfirm(mode, unit.setTemperature, newTemp, fromMode = unit.mode)) {
            // Bijv. van Koelen 27° naar Warm: eerst bevestigen, dan pas versturen.
            val before = unit
            patchLocal(unit.id) { it.copy(mode = mode, setTemperature = newTemp, power = true) }
            hotAction = HotAction(
                confirm = { send(before, values) { it.copy(mode = mode, setTemperature = newTemp, power = true) } },
                cancel = { patchLocal(before.id) { it.copy(mode = before.mode, setTemperature = before.setTemperature, power = before.power) } },
            )
            _state.update { it.copy(hotConfirm = HotConfirm(unit.id, unit.name, unit.setTemperature, newTemp)) }
            return
        }
        send(unit, values) { it.copy(mode = mode, setTemperature = newTemp, power = true) }
    }

    private class HotAction(val confirm: () -> Unit, val cancel: () -> Unit)
    /** Bij een moduswissel: wat Ja/Nee doen. `null` = gewone temperatuurwijziging. */
    private var hotAction: HotAction? = null

    /** "Ja": de wijziging boven 25° alsnog versturen. */
    override fun confirmHot() {
        val q = _state.value.hotConfirm ?: return
        _state.update { it.copy(hotConfirm = null) }
        val action = hotAction
        hotAction = null
        if (action != null) action.confirm() else schedulePush(q.unitId)
    }

    /** "Nee": terug naar de vorige waarde; er is niets naar de airco gegaan. */
    override fun cancelHot() {
        val q = _state.value.hotConfirm ?: return
        _state.update { it.copy(hotConfirm = null) }
        val action = hotAction
        hotAction = null
        if (action != null) {
            action.cancel()
        } else {
            patchLocal(q.unitId) { it.copy(setTemperature = q.from) }
            // Een eerdere, nog niet verstuurde stap (bv. 24,5 → 25) alsnog versturen.
            schedulePush(q.unitId)
        }
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
        if (_state.value.hotConfirm != null) return // eerst de openstaande vraag beantwoorden
        val from = current.setTemperature
        patchLocal(unit.id) { it.copy(setTemperature = target) }
        if (needsHotConfirm(current.mode, from, target)) {
            // Nog niet versturen: eerst Ja/Nee. De knop toont de nieuwe waarde alvast.
            pendingTemp[unit.id]?.cancel()
            hotAction = null
            _state.update { it.copy(hotConfirm = HotConfirm(unit.id, current.name, from, target)) }
            return
        }
        schedulePush(unit.id)
    }

    /** Temperatuur na een korte pauze naar MELCloud (bundelt snel tikken / slepen). */
    private fun schedulePush(unitId: String) {
        val unit = _state.value.units.firstOrNull { it.id == unitId } ?: return
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
