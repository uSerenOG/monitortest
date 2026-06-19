package monitor;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.net.http.HttpClient;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Scraper for maxgaming.dk's Pokemon category (168+ products, paginated 60 at a
 * time). Pagination on the friendly URL is JavaScript-driven, so we don't rely
 * on finding "next" links in the HTML. Instead:
 *
 *   Strategy 1: request the friendly category URL with ?visa=<from>-<to> ranges.
 *   Strategy 2 (fallback): if that doesn't grow past one page, pull the category
 *               id (artgrp) out of the raw HTML and page the /shop endpoint.
 *
 * Both strategies keep only /dk/pokemon/<slug> products, so a wrong id can never
 * pollute the results - it just contributes nothing.
 *
 * Parsing avoids CSS class names: find product links, climb to the card holding
 * the price, read price + Danish stock label ("Paa lager" = in stock).
 */
final class MaxGamingScraper implements Scraper {

    private static final String SITE = "maxgaming.dk";
    private static final String CATEGORY_URL =
            "https://www.maxgaming.dk/dk/hjem-fritid/samlekortspil/pokemon";
    private static final int PAGE = 60;            // products per page on this site
    private static final int MAX_PRODUCTS = 600;   // safety bound

    private static final Pattern PRICE = Pattern.compile(
            "(\\d{1,3}(?:[.\\s]\\d{3})*|\\d+)(?:,(\\d{1,2}))?\\s*kr",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern ARTGRP = Pattern.compile("artgrp[\"'=:\\s]{1,4}(\\d{2,7})");

    public String name() { return SITE; }

    public List<Product> fetch(HttpClient client, String keyword) throws Exception {
        Map<String, Product> byId = new LinkedHashMap<>();

        // Strategy 1: friendly URL + ?visa=
        paginate(client, byId, from -> CATEGORY_URL + "?visa=" + from + "-" + (from + PAGE - 1));

        // Strategy 2: discover artgrp from raw HTML, page the /shop endpoint.
        if (byId.size() <= PAGE) {
            String html = safeGet(client, CATEGORY_URL);
            for (String artgrp : artgrpCandidates(html)) {
                int before = byId.size();
                paginate(client, byId, from -> "https://www.maxgaming.dk/shop?funk=steg_tva"
                        + "&Visn=Std&artgrp=" + artgrp + "&visa=" + from + "-" + (from + PAGE - 1));
                if (byId.size() > before) break;   // this id is the right category
            }
        }

        System.err.println("[" + SITE + "] " + byId.size() + " products");
        return new ArrayList<>(byId.values());
    }

    /** Fetch successive pages from urlFor(from) until a page adds no new products. */
    private void paginate(HttpClient client, Map<String, Product> byId, IntFunction<String> urlFor)
            throws InterruptedException {
        for (int from = 1; from <= MAX_PRODUCTS; from += PAGE) {
            int before = byId.size();
            String url = urlFor.apply(from);
            String html = safeGet(client, url);
            if (html == null) break;
            parseProducts(Jsoup.parse(html, url), byId);
            if (byId.size() == before) break;      // no new products -> done (or paging unsupported)
            Thread.sleep(700);                      // be polite between requests
        }
    }

    private static String safeGet(HttpClient client, String url) {
        try {
            return Scrapers.get(client, url);
        } catch (Exception e) {
            System.err.println("[" + SITE + "] fetch failed: " + e.getMessage());
            return null;
        }
    }

    private static List<String> artgrpCandidates(String html) {
        if (html == null) return List.of();
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        Matcher m = ARTGRP.matcher(html);
        while (m.find()) ids.add(m.group(1));
        return new ArrayList<>(ids);
    }

    private static void parseProducts(Document doc, Map<String, Product> byId) {
        for (Element a : doc.select("a[href]")) {
            String slug = productSlug(a.absUrl("href"));
            if (slug == null || byId.containsKey(slug)) continue;

            Element card = cardFor(a);
            if (card == null) continue;
            String cardText = card.text();

            Double price = parsePrice(cardText);
            if (price == null) continue;                     // no price -> not a real product card

            String name = productName(card, a, slug);
            boolean inStock = parseStock(cardText);
            String url = "https://www.maxgaming.dk/dk/pokemon/" + slug;

            byId.put(slug, new Product(SITE, slug, name, price, inStock, url));
        }
    }

    /** Returns the slug if href is a /dk/pokemon/<slug> product page, else null. */
    private static String productSlug(String href) {
        int i = href.indexOf("/dk/pokemon/");
        if (i < 0) return null;
        String rest = href.substring(i + "/dk/pokemon/".length());
        rest = rest.split("[?#]")[0].replaceAll("/+$", "");
        if (rest.isEmpty() || rest.contains("/")) return null;  // category/sub-path, not a product
        return rest;
    }

    /** Climb from the link to the nearest ancestor whose text contains a price. */
    private static Element cardFor(Element link) {
        Element el = link;
        for (int depth = 0; depth < 6 && el != null; depth++) {
            if (PRICE.matcher(el.text()).find()) return el;
            el = el.parent();
        }
        return null;
    }

    private static Double parsePrice(String text) {
        Matcher m = PRICE.matcher(text);
        if (!m.find()) return null;
        String whole = m.group(1).replaceAll("[.\\s]", "");
        String dec = m.group(2);
        try {
            return Double.parseDouble(dec == null ? whole : whole + "." + dec);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static boolean parseStock(String text) {
        String t = text.toLowerCase(Locale.ROOT);
        boolean out = t.contains("udsolgt") || t.contains("ikke paa lager")
                || t.contains("ikke på lager") || t.contains("slut i lager")
                || t.contains("midlertidigt udsolgt") || t.contains("forudbestil");
        boolean in = t.contains("paa lager") || t.contains("på lager")
                || t.contains("i lager") || t.contains("faa stk") || t.contains("få stk")
                || t.contains("faa paa lager") || t.contains("få på lager");
        return in && !out;
    }

    private static String productName(Element card, Element link, String slug) {
        String linkText = link.text().trim();
        if (linkText.length() > 2) return linkText;
        Element img = card.selectFirst("img[alt]");
        if (img != null && !img.attr("alt").isBlank()) return img.attr("alt").trim();
        String words = slug.replace('-', ' ').trim();
        return words.isEmpty() ? slug : Character.toUpperCase(words.charAt(0)) + words.substring(1);
    }
}
