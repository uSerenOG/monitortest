package monitor;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pokemon stock monitor - runs ONCE per invocation (GitHub Actions cron drives
 * the repetition; there is no internal loop). Each run:
 *   1. loads the previous snapshot from state.json
 *   2. scrapes each site for KEYWORD
 *   3. diffs new vs old -> events
 *   4. posts events to a Discord webhook
 *   5. writes state.json back (the workflow commits it to the repo)
 *
 * First run per site is silent (it just seeds the baseline).
 */
public class Monitor {

    static final String KEYWORD = env("KEYWORD", "pokemon");
    static final String WEBHOOK = env("DISCORD_WEBHOOK_URL", "");
    static final String STATE_FILE = env("STATE_FILE", "state.json");

    public static void main(String[] args) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        Store store = Store.load(STATE_FILE);
        Discord discord = new Discord(WEBHOOK, client);

        for (Scraper scraper : Scrapers.ALL) {
            List<Product> products;
            try {
                products = scraper.fetch(client, KEYWORD);
            } catch (Exception e) {                       // one bad site must not kill the run
                System.err.println("[" + scraper.name() + "] fetch failed: " + e.getMessage());
                continue;
            }

            if (!store.isSeeded(scraper.name())) {
                store.save(scraper.name(), products);
                System.out.println("[" + scraper.name() + "] seeded "
                        + products.size() + " products (no alerts on first run)");
                continue;
            }

            Map<String, Product> old = store.snapshot(scraper.name());
            List<Event> events = diff(old, products);
            for (Event ev : events) {
                discord.notify(ev);
                sleep(500);                               // stay under Discord's webhook rate limit
            }
            store.save(scraper.name(), products);
            System.out.println("[" + scraper.name() + "] " + products.size()
                    + " products, " + events.size() + " events");
        }

        store.persist(STATE_FILE);
    }

    static List<Event> diff(Map<String, Product> old, List<Product> now) {
        List<Event> events = new ArrayList<>();
        Map<String, Product> nowById = new HashMap<>();
        for (Product p : now) nowById.put(p.productId(), p);

        for (Product p : now) {
            Product o = old.get(p.productId());
            if (o == null) {
                events.add(new Event("NEW", p));
            } else if (!o.inStock() && p.inStock()) {
                events.add(new Event("RESTOCK", p));
            } else if (o.inStock() && !p.inStock()) {
                events.add(new Event("OUT_OF_STOCK", p));
            } else if (o.price() != null && p.price() != null && !o.price().equals(p.price())) {
                events.add(new Event("PRICE", p));
            }
        }
        for (Product o : old.values()) {
            if (!nowById.containsKey(o.productId())) events.add(new Event("REMOVED", o));
        }
        return events;
    }

    static String env(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) {}
    }
}

/** A single product as seen on a site. productId should be stable (SKU/slug/URL). */
record Product(String site, String productId, String name, Double price, boolean inStock, String url) {}

/** A detected change to push to Discord. */
record Event(String kind, Product product) {}
