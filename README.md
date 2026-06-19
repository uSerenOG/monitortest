# monitor (Java + GitHub Actions)

Monitors Danish retail sites for Pokemon products and pings a Discord channel on
new / removed / restocked / price-changed items. Runs free on GitHub Actions.

## How it works

- GitHub Actions runs the program on a cron schedule. **It runs once per trigger
  and exits** - there is no internal loop.
- Each run loads the previous snapshot from `state.json`, scrapes each site,
  diffs new vs old, posts changes to Discord, and writes `state.json` back. The
  workflow then **commits `state.json` to the repo** so the next run can read it.
- The first run per site is silent (it just seeds the baseline), so you don't
  get spammed with an alert for every product that already exists.

## Important limits of the free GitHub Actions approach

- **Make the repo public.** Public repos get unlimited free Actions minutes.
  A private repo on the Free plan only has 2,000 minutes/month, which a
  5-minute job blows through in a few days. Your webhook lives in a repo
  Secret, which stays hidden even on a public repo.
- **5-minute minimum, and not punctual.** Cron can't run more often than every
  5 minutes, and scheduled runs are routinely delayed 5-30 min (sometimes more)
  and can be dropped under high load. Good for general awareness; not for
  sniping drops that sell out in seconds.
- **Auto-disable after 60 days of repo inactivity.** The bot's own state commits
  may not reset that timer, so push a manual commit occasionally to keep it on.

## Setup

1. **Discord webhook:** Server Settings -> Integrations -> Webhooks -> New
   Webhook -> pick a channel -> Copy Webhook URL.
2. **Repo secret:** GitHub repo -> Settings -> Secrets and variables -> Actions
   -> New repository secret -> name `DISCORD_WEBHOOK_URL`, paste the URL.
3. Push this project to a **public** GitHub repo. The workflow in
   `.github/workflows/monitor.yml` schedules itself; use the "Run workflow"
   button (workflow_dispatch) to test immediately.

## Run locally first (recommended)

This project includes the **Maven Wrapper**, so you do NOT need Maven installed -
only a JDK 21. The wrapper downloads Maven itself on first run.

Windows (PowerShell):

```powershell
.\mvnw.cmd package
$env:DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/..."
java -jar target/pokemon-monitor.jar
```

macOS/Linux:

```bash
./mvnw package
export DISCORD_WEBHOOK_URL="https://discord.com/api/webhooks/..."
java -jar target/pokemon-monitor.jar
```

(If you have Maven installed, plain `mvn package` works too. You can also just
hit the Run button on `Monitor.java` in VS Code with the Extension Pack for Java -
no build tool needed for local testing.)

Run it twice: the first pass seeds, the second reports real changes.

## Writing a scraper for each site (the real work)

Each retailer is different, so you implement one `Scraper` per site in
`Scrapers.java`. To find what to parse:

1. Open the site, search `pokemon`.
2. DevTools (F12) -> **Network** -> filter `Fetch/XHR` -> reload.
3. If a request returns **JSON** with the products (name, price, an
   availability field), call that URL directly and map the JSON. Cleanest
   (Pattern A).
4. Otherwise check page source for `<script type="application/ld+json">`. If the
   products are described there, use the `jsonLdProducts()` helper (Pattern B).
5. Otherwise parse the HTML product cards with Jsoup.

Then copy `ExampleSearchScraper`, rename it, set the URL, fill in parsing, and
add it to the `Scrapers.ALL` list.

Tips:
- `bilka.dk`, `foetex.dk`, `br.dk` are all Salling Group and likely share a
  backend - crack one and the others may be near-copies.
- `maxgaming.dk` is a different platform; expect its own parser.
- Keep requests polite (the 5-min cadence already is). Some sites use Cloudflare
  bot protection; if plain GETs get blocked, that site needs a headless browser
  (e.g. Playwright/Selenium) rather than `HttpClient`.
