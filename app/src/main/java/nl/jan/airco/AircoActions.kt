package nl.jan.airco

import nl.jan.airco.data.AircoUnit
import nl.jan.airco.data.FanSpeed
import nl.jan.airco.data.Mode

/**
 * Alles wat het units-scherm aan het ViewModel vraagt. Als interface, zodat de
 * UI-tests het scherm met nepdata kunnen tonen zonder netwerk of login.
 */
interface AircoActions {
    fun onForeground()
    fun onBackground()
    fun clearMessage()
    fun refresh(userInitiated: Boolean = false, background: Boolean = false)
    fun logout()
    fun setAllPower(on: Boolean)
    fun setPower(unit: AircoUnit, on: Boolean)
    fun setMode(unit: AircoUnit, mode: Mode)
    fun setFanSpeed(unit: AircoUnit, speed: FanSpeed)
    fun changeTemperature(unit: AircoUnit, direction: Int)
    fun setTemperature(unit: AircoUnit, value: Double)
    /** Verbruik van gisteren ophalen (eenmalig per dag), voor de tik op "Vandaag". */
    fun loadEnergyYesterday()
    /** Tik op Buiten/Vandaag: na een korte pauze verse cijfers als ze verouderd zijn. */
    fun onSummaryTap()
    /** Antwoord op "hoger dan 25°?": Ja verstuurt, Nee zet terug. */
    fun confirmHot()
    fun cancelHot()
}
