package monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/**
 * Scraper for br.dk's "Pokemon kort" category.
 *
 * br.dk is a single-page app whose products are served by Algolia (a hosted
 * search API), so there's nothing to scrape from the HTML. This talks straight
 * to that JSON API - the same call the website's browser makes - and pages
 * through the results. The search key below is Algolia's public, search-only
 * frontend key (sent from every visitor's browser), so it's fine in source.
 *
 * The exact attribute names inside each product record aren't documented, so
 * the parser tries the likely names and, on the first page, prints the fields
 * it actually saw to stderr - use that to confirm/adjust the mappings.
 */
final class BrScraper implements Scraper {

    private static final String SITE = "br.dk";
    private static final String APP_ID = "DRP4O45G5T";
    private static final String API_KEY = "f3a34fc94874579eaf3cd39fef660948";
    private static final String INDEX = "prod_BR_PRODUCTS";
    private static final String ENDPOINT = "https://drp4o45g5t-dsn.algolia.net/1/indexes/*/queries";
    private static final String CATEGORY_URL =
            "https://www.br.dk/maerker/o-aa/pokemon/pokemon-kort/pl/pokemon-kort/";

    // Exact params the site sends (minus the session userToken); page=N is swapped per request.
    private static final String PARAMS =
            "query=&attributesToRetrieve=%5B%22*%22%5D&filters=is_exposed%3Atrue%20AND%20cfh_nodes%3A%22CFH.CollectionCards%22%20AND%20f_brand%3A%22Pokemon%22%20OR%20f_brand%3A%22Pok%C3%A9mon%22%20OR%20facets.productSeriesToys%3A%22Pok%C3%A9mon%22&distinct=true&facetingAfterDistinct=false&page=0&hitsPerPage=59&facets=%5B%22price_filter%22%2C%22f_stock_availability%22%2C%22brand%22%2C%22f_category%22%2C%22f_campaign_name%22%2C%22facets.childrenToys.ageList%22%5D&clickAnalytics=true&getRankingInfo=true";

    private static final ObjectMapper M = new ObjectMapper();

    public String name() { return SITE; }

    public List<Product> fetch(HttpClient client, String keyword) throws Exception {
        List<Product> out = new ArrayList<>();
        int page = 0, nbPages = 1;
        boolean debugged = false;

        while (page < nbPages && page < 20) {
            JsonNode result = query(client, page);
            if (result == null) break;
            nbPages = result.path("nbPages").asInt(1);
            JsonNode hits = result.path("hits");

            if (!debugged && hits.isArray() && hits.size() > 0) {
                debugSchema(hits.get(0));
                debugged = true;
            }
            for (JsonNode hit : hits) {
                Product p = toProduct(hit);
                if (p != null) out.add(p);
            }
            page++;
            Thread.sleep(400);   // be polite
        }

        System.err.println("[" + SITE + "] " + out.size() + " products");
        return out;
    }

    private JsonNode query(HttpClient client, int page) throws Exception {
        String params = PARAMS.replaceAll("page=\\d+", "page=" + page);

        ObjectNode req = M.createObjectNode();
        ArrayNode reqs = req.putArray("requests");
        ObjectNode one = reqs.addObject();
        one.put("indexName", INDEX);
        one.put("params", params);
        req.put("strategy", "none");

        HttpRequest http = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .header("X-Algolia-Application-Id", APP_ID)
                .header("X-Algolia-API-Key", API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(M.writeValueAsString(req)))
                .build();

        HttpResponse<String> r = client.send(http, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 300) {
            String b = r.body();
            System.err.println("[" + SITE + "] algolia HTTP " + r.statusCode() + ": "
                    + b.substring(0, Math.min(200, b.length())));
            return null;
        }
        JsonNode results = M.readTree(r.body()).path("results");
        return results.isArray() && results.size() > 0 ? results.get(0) : null;
    }

    private static Product toProduct(JsonNode hit) {
        String id = text(hit, "objectID");
        if (id == null) return null;
        String name = firstText(hit, "name", "title", "displayName", "productName",
                "product_name", "f_title", "seoTitle");
        Double price = firstNumber(hit, "price_filter", "price", "salesPrice", "f_price",
                "priceValue", "current_price");
        boolean inStock = parseStock(hit);
        String url = productUrl(hit);
        return new Product(SITE, id, name != null ? name : id, price, inStock, url);
    }

    private static boolean parseStock(JsonNode hit) {
        JsonNode n = hit.get("f_stock_availability");
        String v = null;
        if (n != null && n.isArray() && n.size() > 0) v = n.get(0).asText();
        else if (n != null && !n.isNull()) v = n.asText();
        if (v == null) return true;   // unknown -> assume available
        String t = v.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("udsolgt") || t.contains("ikke") || t.contains("out") || t.contains("sold"))
            return false;
        return t.contains("lager") || t.contains("instock") || t.contains("tilgængelig");
    }

    private static String productUrl(JsonNode hit) {
        String u = firstText(hit, "canonical_url", "url", "productUrl", "canonicalUrl", "link");
        if (u != null) return u.startsWith("http") ? u : "https://www.br.dk" + (u.startsWith("/") ? "" : "/") + u;
        String slug = firstText(hit, "slug", "seoName", "path", "urlSlug");
        if (slug != null) return "https://www.br.dk" + (slug.startsWith("/") ? "" : "/") + slug;
        return CATEGORY_URL;   // fallback so the Discord link is always valid
    }

    // --- small JSON helpers ---

    private static String text(JsonNode hit, String key) {
        JsonNode n = hit.get(key);
        return (n != null && !n.isNull() && !n.asText().isBlank()) ? n.asText() : null;
    }

    private static String firstText(JsonNode hit, String... keys) {
        for (String k : keys) {
            String v = text(hit, k);
            if (v != null) return v;
        }
        return null;
    }

    private static JsonNode firstNode(JsonNode hit, String... keys) {
        for (String k : keys) {
            JsonNode n = hit.get(k);
            if (n != null && !n.isNull()) return n;
        }
        return null;
    }

    private static Double firstNumber(JsonNode hit, String... keys) {
        for (String k : keys) {
            JsonNode n = hit.get(k);
            if (n == null || n.isNull()) continue;
            if (n.isNumber()) return n.asDouble();
            try { return Double.parseDouble(n.asText().replace(",", ".")); }
            catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private static void debugSchema(JsonNode hit) {
        StringBuilder sb = new StringBuilder("[" + SITE + "] sample hit fields: ");
        Iterator<String> it = hit.fieldNames();
        int c = 0;
        while (it.hasNext() && c < 50) { sb.append(it.next()).append(", "); c++; }
        System.err.println(sb);
        System.err.println("[" + SITE + "] sample => name=" + firstText(hit, "name", "title", "displayName")
                + " | price_filter=" + hit.path("price_filter")
                + " | f_stock_availability=" + hit.path("f_stock_availability")
                + " | objectID=" + hit.path("objectID"));
    }
}
