# Airco

Eigen Android-app om Mitsubishi-airco's via **MELCloud Home** te bedienen.
Gebaseerd op de API-kennis uit [OlivierZal/melcloud-api](https://github.com/OlivierZal/melcloud-api),
geport naar Kotlin zodat de app direct met MELCloud praat (geen server nodig).

## Wat kan hij

- Inloggen via de echte MELCloud Home-loginpagina (je wachtwoord komt niet in de app, alleen tokens)
- Alle airco's uit al je gebouwen (ook gedeelde) in één overzicht
- Per unit: aan/uit, temperatuur (+/−, met de min/max van je unit per modus), modus en ventilatorstand
- Kamertemperatuur, offline- en storingsindicatie
- Vernieuwt bij openen, via trekken-om-te-vernieuwen en een paar seconden na elke wijziging

## APK bouwen

### Optie A — GitHub (geen Android Studio nodig)
1. Maak een **privé** repo op GitHub en push deze map ernaartoe.
2. Onder *Actions* draait "Build APK" automatisch.
3. Open de afgeronde run → *Artifacts* → download `airco-apk` (zip met `app-debug.apk`).
4. Zet de APK op je telefoon en installeer (sta "installeren uit onbekende bron" toe voor je bestandsbeheer/browser).

### Optie B — Android Studio
Open de map, laat Gradle synchroniseren, sluit je telefoon aan (USB-debugging aan) en klik ▶.

De repo bevat een vaste debug-sleutel (`app/debug.keystore`), dus nieuwe builds installeren gewoon over de oude heen.
Daarom: houd de repo privé.

## Structuur

```
app/src/main/java/nl/jan/airco/
  data/Auth.kt          OIDC + PKCE login (PAR → authorize → token), refresh
  data/MelcloudApi.kt   GET /context, PUT /monitor/ataunit/{id}, token-refresh bij 401
  data/Models.kt        parsing van /context naar AircoUnit + capabilities
  data/TokenStore.kt    tokens in privé SharedPreferences
  AircoViewModel.kt     state, optimistische updates, debounce op temperatuur
  ui/LoginScreen.kt     WebView die de melcloudhome:// redirect onderschept
  ui/UnitsScreen.kt     overzicht + bediening
```

## Goed om te weten

- Onofficiële API: Mitsubishi kan hem wijzigen. Als iets stukgaat, kijk wat er in
  `src/api/home.ts` / `src/api/token-auth.ts` van melcloud-api veranderd is.
- MELCloud heeft een rate limit; de app vraagt daarom niet continu de status op en
  bundelt snelle +/−-tikken tot één verzoek.
