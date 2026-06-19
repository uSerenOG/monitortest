package monitor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

/** One implementation per site. Returns the products matching the keyword. */
interface Scraper {
    String name();
    List<Product> fetch(HttpClient client, String keyword) throws Exception;
}

public class Scrapers {

    /** Register your active scrapers here. */
    public static final List<Scraper> ALL = List.of(
        new MaxGamingScraper(),
        new BrScraper()
);

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    /** Plain GET returning the body, with a realistic User-Agent. */
    static String get(HttpClient client, String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept-Language", "da-DK,da;q=0.9,en;q=0.8")
                .GET().build();
        HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() >= 400) throw new RuntimeException("HTTP " + r.statusCode() + " for " + url);
        return r.body();
    }

    /**
     * Extract schema.org Product/Offer nodes from a page's JSON-LD blocks.
     * Many shops embed this; when present it's the most stable signal and
     * survives most front-end redesigns.
     */
    static List<Product> jsonLdProducts(String html, String site, String fallbackUrl) {
        List<Product> out = new ArrayList<>();
        Document doc = Jsoup.parse(html);
        for (Element script : doc.select("script[type=application/ld+json]")) {
            JsonNode root;
            try {
                root = MAPPER.readTree(script.data());
            } catch (Exception e) {
                continue;
            }
            for (JsonNode node : flatten(root)) {
                if (!node.path("@type").asText("").equals("Product")) continue;
                JsonNode offers = node.path("offers");
                if (offers.isArray() && offers.size() > 0) offers = offers.get(0);
                String avail = offers.path("availability").asText("").toLowerCase();
                boolean inStock = avail.contains("instock") || avail.contains("limitedavailability");
                Double price = offers.has("price") ? parseDouble(offers.path("price").asText()) : null;
                String url = node.path("url").asText(fallbackUrl);
                out.add(new Product(site, url, node.path("name").asText("?"), price, inStock, url));
            }
        }
        return out;
    }

    private static List<JsonNode> flatten(JsonNode root) {
        List<JsonNode> nodes = new ArrayList<>();
        if (root.isArray()) root.forEach(nodes::add);
        else if (root.has("@graph")) root.get("@graph").forEach(nodes::add);
        else nodes.add(root);
        return nodes;
    }

    private static Double parseDouble(String s) {
        try { return Double.parseDouble(s.replace(",", ".")); }
        catch (Exception e) { return null; }
    }

    /**
     * TEMPLATE - copy this per site, rename, set the URL, fill in parsing.
     *
     * To find what to parse: open the site, search the keyword, open DevTools
     * (F12) -> Network -> filter Fetch/XHR -> reload. If you see a request
     * returning JSON with the products, call that URL directly (Pattern A,
     * cleanest). Otherwise parse JSON-LD (Pattern B, below) or HTML cards.
     */
    static final class ExampleSearchScraper implements Scraper {
        public String name() { return "example.dk"; }

        public List<Product> fetch(HttpClient client, String keyword) throws Exception {
            String url = "https://www.example.dk/search?q=" + keyword;
            String html = get(client, url);
            return jsonLdProducts(html, name(), url);
            // Pattern A example (preferred when the site has a JSON search API):
            //   String json = get(client, "https://www.example.dk/api/search?q=" + keyword);
            //   JsonNode hits = MAPPER.readTree(json).path("products");
            //   ... map each hit to new Product(name(), id, title, price, inStock, link) ...
        }
    }
}
