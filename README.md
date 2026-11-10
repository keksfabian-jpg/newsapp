# NewsTrader

Android-App, die News, Wirtschaftskalender und Earnings mit Liquidity-Sweeps (Asia-/Vortages-Range) kombiniert und daraus Trade-Vorschläge macht. Alle Vorschläge werden automatisch als Paper-Trades ausgewertet.

- Kerzen: Yahoo Finance (15 min)
- Wirtschaftskalender: ForexFactory-Feed
- News & Earnings: Finnhub (kostenloser Key)
- Sentiment: Claude API (eigener Key)
- Hintergrund-Update alle 15 min per WorkManager, Push bei neuem Vorschlag und bei Ergebnis

Im Reiter „Strategien“ laufen 13 Ansätze parallel als Paper-Trades (u. a. ORB, ICT Silver Bullet, London Breakout, Intraday-Momentum, Overnight, Fable-Trendtag). Ergebnisse werden wie KO-Scheine gerechnet, das Hebel-Profil (Auto, 10×–300×) ist umschaltbar.

Jeder Push auf `main` baut per GitHub Actions eine signierte APK und legt sie als Release ab.

Keine Anlageberatung – regelbasierte Signale zum Testen.
