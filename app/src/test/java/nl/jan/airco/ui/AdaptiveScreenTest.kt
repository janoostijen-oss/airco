package nl.jan.airco.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import nl.jan.airco.AircoActions
import nl.jan.airco.Screen
import nl.jan.airco.UiState
import nl.jan.airco.data.AircoUnit
import nl.jan.airco.data.Capabilities
import nl.jan.airco.data.FanSpeed
import nl.jan.airco.data.Mode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Het echte units-scherm op verschillende schermformaten (Robolectric zet de
 * vensterbreedte via qualifiers), met nepdata en een nep-ViewModel.
 */
@RunWith(AndroidJUnit4::class)
class AdaptiveScreenTest {

    @get:Rule
    val rule = createComposeRule()

    private val actions = FakeActions()

    private fun show(state: UiState = UiState(
        screen = Screen.Units, units = sampleUnits(), outdoorTemperature = 8.5, outdoorTrend = -1,
        energyTodayKwh = 5.63, energyYesterdayKwh = 11.04,
    )) {
        rule.setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                UnitsScreen(
                    state = state,
                    vm = actions,
                )
            }
        }
        rule.waitForIdle()
    }

    // --------------------------------------------------------------- telefoon

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun telefoon_toontOverzichtZonderLijstPaneel() {
        show()
        rule.onNodeWithTag(TestTags.OVERVIEW).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.LIST_PANE).assertDoesNotExist()
        rule.onNodeWithTag(TestTags.CURRENT_TEMP).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun telefoon_tikOpRuimteOpentBediening() {
        show()
        rule.onNode(hasText("Keuken") and hasAnyAncestor(hasTestTag(TestTags.OVERVIEW))).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.DETAIL_PANE).assertIsDisplayed()
        rule.onNode(hasTestTag(TestTags.DETAIL_NAME) and hasAnyAncestor(hasTestTag(TestTags.DETAIL_PANE)))
            .assertTextEquals("Keuken")
    }

    // --------------------------------------------------------------- foldable

    @Test
    @Config(qualifiers = "w700dp-h840dp")
    fun foldable_toontLijstEnBedieningNaastElkaar() {
        show()
        rule.onNodeWithTag(TestTags.LIST_PANE).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.DETAIL_PANE).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.DIAL).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.OVERVIEW).assertDoesNotExist()
        // Eerste ruimte is standaard gekozen; de tablet-tegel hoort hier niet.
        rule.onNodeWithTag(TestTags.DETAIL_NAME).assertTextEquals("Kantoor")
        rule.onNodeWithTag(TestTags.CURRENT_TEMP).assertDoesNotExist()
    }

    @Test
    @Config(qualifiers = "w700dp-h840dp")
    fun foldable_ruimteKiezenWisseltBediening() {
        show()
        rule.onNodeWithTag(TestTags.roomRow("slaapkamer")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.DETAIL_NAME).assertTextEquals("Slaapkamer")
    }

    @Test
    @Config(qualifiers = "w600dp-h800dp")
    fun smalleFoldable_draaiknopPastNogInBeeld() {
        show()
        rule.onNodeWithTag(TestTags.LIST_PANE).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.DIAL).assertIsDisplayed()
        // De knop wordt hier geschaald: hij moet binnen het bedieningspaneel blijven.
        val pane = rule.onNodeWithTag(TestTags.DETAIL_PANE).fetchSemanticsNode().boundsInRoot
        val dial = rule.onNodeWithTag(TestTags.DIAL).fetchSemanticsNode().boundsInRoot
        assertTrue("draaiknop ($dial) valt buiten paneel ($pane)", dial.left >= pane.left - 1f && dial.right <= pane.right + 1f)
    }

    @Test
    @Config(qualifiers = "w800dp-h1280dp")
    fun staandeTablet_gebruiktFoldableIndeling() {
        show()
        rule.onNodeWithTag(TestTags.LIST_PANE).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.DETAIL_PANE).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.CURRENT_TEMP).assertDoesNotExist()
    }

    // --------------------------------------------------------------- tablet

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun tablet_toontLijstEnGecentreerdeBediening() {
        show()
        rule.onNodeWithTag(TestTags.LIST_PANE).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.DETAIL_PANE).assertIsDisplayed()
        rule.onNodeWithTag(TestTags.CURRENT_TEMP).assertIsDisplayed()
        rule.onNodeWithText("HUIDIGE TEMPERATUUR").assertIsDisplayed()
        rule.onNodeWithTag(TestTags.OVERVIEW).assertDoesNotExist()

        // Draaiknop horizontaal gecentreerd in het bedieningspaneel.
        val pane = rule.onNodeWithTag(TestTags.DETAIL_PANE).fetchSemanticsNode().boundsInRoot
        val dial = rule.onNodeWithTag(TestTags.DIAL).fetchSemanticsNode().boundsInRoot
        val paneCenter = pane.left + 44f * density() + (pane.width - (44f + 40f) * density()) / 2f
        assertEquals(paneCenter, dial.center.x, 2f * density())
        // Volgorde van boven naar onder: huidige temperatuur, draaiknop.
        val tile = rule.onNodeWithTag(TestTags.CURRENT_TEMP).fetchSemanticsNode().boundsInRoot
        assertTrue("tegel staat niet boven de draaiknop", tile.bottom <= dial.top)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun tablet_ruimteKiezenEnAanzettenVanuitLijst() {
        show()
        rule.onNodeWithTag(TestTags.roomRow("keuken")).performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(TestTags.DETAIL_NAME).assertTextEquals("Keuken")

        rule.onNode(
            hasClickAction() and hasAnyAncestor(hasTestTag(TestTags.roomRow("slaapkamer"))) and
                androidx.compose.ui.test.hasContentDescription("Slaapkamer uit, tik om aan te zetten"),
        ).performClick()
        assertEquals(listOf("slaapkamer" to true), actions.powerCalls)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun tablet_minPlusWerktOpGekozenRuimte() {
        show()
        rule.onNodeWithContentDescription("Temperatuur omhoog").performClick()
        assertEquals(listOf("kantoor" to 1), actions.tempSteps)
    }

    // --------------------------------------------------------------- "Alles uit/aan"

    @Test
    @Config(qualifiers = "w411dp-h891dp")
    fun telefoon_allesUitAlsErEenAanStaat() {
        show()
        rule.onNode(hasText("Alles uit") and hasAnyAncestor(hasTestTag(TestTags.OVERVIEW))).performClick()
        assertEquals(listOf(false), actions.allPowerCalls)
    }

    @Test
    @Config(qualifiers = "w700dp-h840dp")
    fun foldable_allesUitInCompacteSamenvatting() {
        show()
        rule.onNode(hasText("Alles uit") and hasAnyAncestor(hasTestTag(TestTags.LIST_PANE))).performClick()
        assertEquals(listOf(false), actions.allPowerCalls)
    }

    @Test
    @Config(qualifiers = "w1280dp-h800dp-land")
    fun tablet_allesUitInSamenvatting() {
        show()
        rule.onNode(hasText("Alles uit") and hasAnyAncestor(hasTestTag(TestTags.LIST_PANE))).performClick()
        assertEquals(listOf(false), actions.allPowerCalls)
    }

    // --------------------------------------------------------------- "Vandaag" (kWh)

    private fun assertVandaag() {
        rule.onNodeWithText("VANDAAG").assertIsDisplayed()
        rule.onNode(hasText("5,6") and hasAnyAncestor(hasTestTag(TestTags.ENERGY_TODAY)), useUnmergedTree = true).assertIsDisplayed()
        rule.onNode(hasText(" kWh") and hasAnyAncestor(hasTestTag(TestTags.ENERGY_TODAY)), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun telefoon_toontVerbruikVandaag() { show(); assertVandaag() }

    @Test @Config(qualifiers = "w600dp-h800dp")
    fun smalleFoldable_toontVerbruikVandaag() { show(); assertVandaag() }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun tablet_toontVerbruikVandaag() { show(); assertVandaag() }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun tikOpVandaagToontGisterenEnWeerTerug() {
        show()
        rule.onNodeWithTag(TestTags.ENERGY_TODAY).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("GISTEREN").assertIsDisplayed()
        rule.onNode(hasText("11,0") and hasAnyAncestor(hasTestTag(TestTags.ENERGY_TODAY)), useUnmergedTree = true).assertIsDisplayed()
        assertEquals(1, actions.yesterdayLoads)

        rule.onNodeWithTag(TestTags.ENERGY_TODAY).performClick()
        rule.waitForIdle()
        rule.onNodeWithText("VANDAAG").assertIsDisplayed()
        rule.onNode(hasText("5,6") and hasAnyAncestor(hasTestTag(TestTags.ENERGY_TODAY)), useUnmergedTree = true).assertIsDisplayed()
        assertEquals("terugtikken haalt niets op", 1, actions.yesterdayLoads)
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun gisterenLadenToontPuntjes() {
        show(UiState(screen = Screen.Units, units = sampleUnits(), energyTodayKwh = 5.63, energyYesterdayLoading = true))
        rule.onNodeWithTag(TestTags.ENERGY_TODAY).performClick()
        rule.waitForIdle()
        rule.onNode(hasText("…") and hasAnyAncestor(hasTestTag(TestTags.ENERGY_TODAY)), useUnmergedTree = true).assertIsDisplayed()
    }

    // --------------------------------------------------------------- trend buiten

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun telefoon_trendpijltjeNaBuitentemperatuur() {
        show()
        rule.onNodeWithContentDescription("Buiten kouder dan een half uur geleden").assertIsDisplayed()
        val temp = rule.onNodeWithText("8,5°", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val arrows = rule.onNodeWithTag(TestTags.OUTDOOR_TREND, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("pijltjes staan rechts van de temperatuur", arrows.left >= temp.right)
    }

    @Test @Config(qualifiers = "w600dp-h800dp")
    fun foldable_trendWarmer() {
        show(UiState(screen = Screen.Units, units = sampleUnits(), outdoorTemperature = 8.5, outdoorTrend = 1))
        rule.onNodeWithContentDescription("Buiten warmer dan een half uur geleden").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun tablet_trendGelijk() {
        show(UiState(screen = Screen.Units, units = sampleUnits(), outdoorTemperature = 8.5, outdoorTrend = 0))
        rule.onNodeWithContentDescription("Buitentemperatuur gelijk gebleven").assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun geenPijltjesZonderBuitentemperatuur() {
        show(UiState(screen = Screen.Units, units = sampleUnits()))
        rule.onNodeWithTag(TestTags.OUTDOOR_TREND, useUnmergedTree = true).assertDoesNotExist()
    }

    // --------------------------------------------------------------- buiten nu / max / min

    private fun extremesState() = UiState(
        screen = Screen.Units, units = sampleUnits(), outdoorTemperature = 8.5, outdoorTrend = -1,
        outdoorMaxToday = 12.5, outdoorMinToday = -4.5,
    )

    private fun tapBuitenEnCheck(label: String, second: String?, value: String, arrows: Boolean) {
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(TestTags.OUTDOOR)), useUnmergedTree = true).assertIsDisplayed()
        if (second != null) {
            rule.onNode(hasText(second) and hasAnyAncestor(hasTestTag(TestTags.OUTDOOR)), useUnmergedTree = true).assertIsDisplayed()
        }
        rule.onNode(hasText(value) and hasAnyAncestor(hasTestTag(TestTags.OUTDOOR)), useUnmergedTree = true).assertIsDisplayed()
        val n = rule.onAllNodes(hasTestTag(TestTags.OUTDOOR_TREND), useUnmergedTree = true).fetchSemanticsNodes().size
        assertEquals("pijltjes bij $label", if (arrows) 1 else 0, n)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun tikkenOpBuitenEnVandaagVraagtOmVerseCijfers() {
        show(extremesState())
        rule.onNodeWithTag(TestTags.OUTDOOR).performClick()
        rule.onNodeWithTag(TestTags.OUTDOOR).performClick()
        rule.onNodeWithTag(TestTags.ENERGY_TODAY).performClick()
        // Elke tik meldt zich; het ViewModel bundelt ze (0,8 s) en haalt hooguit één keer op.
        assertEquals(3, actions.summaryTaps)
    }

    // --------------------------------------------------------------- vloeiend scrollen

    /**
     * De ventilatorstrook is uitgezonderd van het terug-veeggebaar. Die uitzondering
     * mag tijdens scrollen niet elk frame naar Android (dat gaf de hapering onderaan):
     * weg tijdens scrollen, één keer terug als de pagina stilstaat.
     */
    @Test @Config(qualifiers = "w411dp-h600dp")
    fun gebaaruitzonderingNietTijdensScrollen() {
        var view: android.view.View? = null
        rule.setContent {
            view = androidx.compose.ui.platform.LocalView.current
            MaterialTheme(colorScheme = darkColorScheme()) {
                UnitsScreen(state = UiState(screen = Screen.Units, units = sampleUnits()), vm = actions)
            }
        }
        rule.onNode(hasText("Kantoor") and hasAnyAncestor(hasTestTag(TestTags.OVERVIEW))).performClick()
        rule.waitForIdle()
        rule.mainClock.advanceTimeBy(1_000)
        // Naar beneden, zodat de ventilatorstrook in beeld is; daarna laten uitlopen.
        rule.onNodeWithTag(TestTags.DETAIL_PANE).performTouchInput { swipeUp() }
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        assertTrue("stilstaand: strook uitgezonderd", view!!.systemGestureExclusionRects.isNotEmpty())

        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(TestTags.DETAIL_PANE).performTouchInput {
            down(center)
            moveBy(androidx.compose.ui.geometry.Offset(0f, 40f))
            moveBy(androidx.compose.ui.geometry.Offset(0f, 40f))
            moveBy(androidx.compose.ui.geometry.Offset(0f, 40f))
        }
        rule.mainClock.advanceTimeByFrame()
        rule.mainClock.advanceTimeByFrame()
        assertTrue("tijdens scrollen geen uitzondering", view!!.systemGestureExclusionRects.isEmpty())

        rule.onNodeWithTag(TestTags.DETAIL_PANE).performTouchInput { up() }
        rule.mainClock.autoAdvance = true
        // Weer naar beneden (strook in beeld) en laten uitlopen: uitzondering is terug.
        rule.onNodeWithTag(TestTags.DETAIL_PANE).performTouchInput { swipeUp() }
        rule.mainClock.advanceTimeBy(3_000)
        rule.waitForIdle()
        assertTrue("na het scrollen weer uitgezonderd", view!!.systemGestureExclusionRects.isNotEmpty())
    }

    private fun cycleBuiten() {
        tapBuitenEnCheck("BUITEN NU", null, "8,5°", arrows = true)
        rule.onNodeWithTag(TestTags.OUTDOOR).performClick(); rule.waitForIdle()
        tapBuitenEnCheck("MAX BUITEN", "VANDAAG", "12,5°", arrows = false)
        rule.onNodeWithTag(TestTags.OUTDOOR).performClick(); rule.waitForIdle()
        tapBuitenEnCheck("MIN BUITEN", "VANDAAG", "-4,5°", arrows = false)
        rule.onNodeWithTag(TestTags.OUTDOOR).performClick(); rule.waitForIdle()
        tapBuitenEnCheck("BUITEN NU", null, "8,5°", arrows = true)
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun telefoon_tikOpBuitenWisseltNuMaxMin() { show(extremesState()); cycleBuiten() }

    @Test @Config(qualifiers = "w600dp-h800dp")
    fun smalleFoldable_tikOpBuitenWisseltNuMaxMin() { show(extremesState()); cycleBuiten() }

    @Test @Config(qualifiers = "w1280dp-h800dp-land")
    fun tablet_tikOpBuitenWisseltNuMaxMin() { show(extremesState()); cycleBuiten() }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun getallenBlijvenOpEenLijnBijTweeRegels() {
        show(extremesState())
        rule.onNodeWithTag(TestTags.OUTDOOR).performClick(); rule.waitForIdle()
        val buiten = rule.onNodeWithText("12,5°", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val vandaag = rule.onNode(hasText("–") and hasAnyAncestor(hasTestTag(TestTags.ENERGY_TODAY)), useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        assertEquals("waarden op één lijn", buiten.top, vandaag.top, 1f)
    }

    // --------------------------------------------------------------- op temperatuur

    private fun reachedState() = UiState(
        screen = Screen.Units, units = sampleUnits(), outdoorTemperature = 8.5, atTarget = setOf("kantoor"),
    )

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun telefoon_kaartToontOpTemperatuurEnHuidig() {
        show(reachedState())
        rule.onNode(hasText("Op temperatuur") and hasAnyAncestor(hasTestTag(TestTags.OVERVIEW))).assertIsDisplayed()
        rule.onAllNodes(hasText("HUIDIG") and hasAnyAncestor(hasTestTag(TestTags.OVERVIEW))).fetchSemanticsNodes().let {
            assertEquals("HUIDIG op elke kaart", 3, it.size)
        }
        rule.onAllNodes(hasText("KAMER")).fetchSemanticsNodes().let { assertEquals(0, it.size) }
    }

    @Test @Config(qualifiers = "w700dp-h840dp")
    fun foldable_bedieningToontOpTemperatuur() {
        show(reachedState())
        rule.onNode(hasText("Huidig 22° · ") and hasAnyAncestor(hasTestTag(TestTags.DETAIL_PANE))).assertIsDisplayed()
        // Statusregel én midden van de draaiknop.
        rule.onAllNodes(hasText("Op temperatuur") and hasAnyAncestor(hasTestTag(TestTags.DETAIL_PANE)))
            .fetchSemanticsNodes().let { assertEquals(2, it.size) }
        rule.onNode(androidx.compose.ui.test.hasContentDescription("doel bereikt", substring = true)).assertExists()
    }

    @Test @Config(qualifiers = "w700dp-h840dp")
    fun nietBereiktGeenOpTemperatuur() {
        show()
        rule.onAllNodes(hasText("Op temperatuur")).fetchSemanticsNodes().let { assertEquals(0, it.size) }
        rule.onNode(hasText("Verwarmt naar 21°") and hasAnyAncestor(hasTestTag(TestTags.DETAIL_PANE))).assertIsDisplayed()
    }

    @Test @Config(qualifiers = "w411dp-h891dp")
    fun vandaagStaatTussenBuitenEnActief() {
        show()
        val buiten = rule.onNodeWithText("BUITEN NU", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val vandaag = rule.onNodeWithText("VANDAAG", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val actief = rule.onNodeWithText("ACTIEF", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(
            "volgorde: buiten=$buiten vandaag=$vandaag actief=$actief",
            buiten.center.x < vandaag.center.x && vandaag.center.x < actief.center.x,
        )
        assertEquals("op één regel: $buiten / $vandaag", buiten.top, vandaag.top, 1f)
        assertEquals("op één regel: $vandaag / $actief", vandaag.top, actief.top, 1f)
    }

    private fun density(): Float =
        androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
            .resources.displayMetrics.density
}

/** Neemt aanroepen op in plaats van MELCloud te benaderen. */
private class FakeActions : AircoActions {
    val powerCalls = mutableListOf<Pair<String, Boolean>>()
    val tempSteps = mutableListOf<Pair<String, Int>>()
    val allPowerCalls = mutableListOf<Boolean>()
    override fun onForeground() {}
    override fun onBackground() {}
    override fun clearMessage() {}
    override fun refresh(userInitiated: Boolean, background: Boolean) {}
    override fun logout() {}
    override fun setAllPower(on: Boolean) { allPowerCalls += on }
    override fun setPower(unit: AircoUnit, on: Boolean) { powerCalls += unit.id to on }
    override fun setMode(unit: AircoUnit, mode: Mode) {}
    override fun setFanSpeed(unit: AircoUnit, speed: FanSpeed) {}
    override fun changeTemperature(unit: AircoUnit, direction: Int) { tempSteps += unit.id to direction }
    override fun setTemperature(unit: AircoUnit, value: Double) {}
    var yesterdayLoads = 0
    override fun loadEnergyYesterday() { yesterdayLoads++ }
    var summaryTaps = 0
    override fun onSummaryTap() { summaryTaps++ }
}

private fun sampleUnits(): List<AircoUnit> {
    val caps = Capabilities(
        modes = listOf(Mode.Heat, Mode.Cool, Mode.Dry, Mode.Fan, Mode.Automatic),
        numberOfFanSpeeds = 5,
        hasAutoFan = true,
        halfDegrees = true,
        heatRange = 16.0..31.0,
        coolDryRange = 16.0..31.0,
        autoRange = 16.0..31.0,
    )
    fun unit(id: String, name: String, on: Boolean, mode: Mode, room: Double, target: Double, fan: FanSpeed) = AircoUnit(
        id = id, name = name, building = "Thuis", isConnected = true, isInError = false, power = on,
        roomTemperature = room, setTemperature = target, mode = mode, fanSpeed = fan, capabilities = caps,
    )
    return listOf(
        unit("kantoor", "Kantoor", on = true, mode = Mode.Heat, room = 22.0, target = 21.0, fan = FanSpeed.One),
        unit("keuken", "Keuken", on = false, mode = Mode.Heat, room = 22.0, target = 21.5, fan = FanSpeed.One),
        unit("slaapkamer", "Slaapkamer", on = false, mode = Mode.Cool, room = 20.0, target = 19.0, fan = FanSpeed.Auto),
    )
}
