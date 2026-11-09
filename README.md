# NewsTrader

Android-App, die News, Wirtschaftskalender und Earnings mit Liquidity-Sweeps (Asia-/Vortages-Range) kombiniert und daraus Trade-Vorschläge macht. Alle Vorschläge werden automatisch als Paper-Trades ausgewertet.

- Kerzen: Yahoo Finance (15 min)
- Wirtschaftskalender: ForexFactory-Feed
- News & Earnings: Finnhub (kostenloser Key)
- Sentiment: Claude API (eigener Key)
- Hintergrund-Update alle 15 min per WorkManager, Push bei neuem Vorschlag und bei Ergebnis

Jeder Push auf `main` baut per GitHub Actions eine signierte APK und legt sie als Release ab.

Keine Anlageberatung – regelbasierte Signale zum Testen.
