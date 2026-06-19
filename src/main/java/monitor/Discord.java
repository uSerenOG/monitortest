package monitor;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Posts events to a Discord webhook. No bot/token needed - just the webhook URL. */
public class Discord {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // kind -> {title, embed colour}
    private static final Map<String, Object[]> STYLE = Map.of(
            "NEW",          new Object[]{"New product",   0x3498DB},
            "RESTOCK",      new Object[]{"Back in stock",  0x2ECC71},
            "OUT_OF_STOCK", new Object[]{"Out of stock",   0xE74C3C},
            "PRICE",        new Object[]{"Price change",   0xF1C40F},
            "REMOVED",      new Object[]{"Removed",        0x95A5A6}
    );

    private final String webhook;
    private final HttpClient client;

    public Discord(String webhook, HttpClient client) {
        this.webhook = webhook;
        this.client = client;
    }

    void notify(Event ev) {
        Product p = ev.product();
        Object[] style = STYLE.getOrDefault(ev.kind(), new Object[]{ev.kind(), 0x99AAB5});

        if (webhook == null || webhook.isBlank()) {
            System.out.println("[no webhook] " + ev.kind() + " | " + p.name());
            return;
        }

        String price = p.price() != null ? String.format("%.2f kr", p.price()) : "-";

        Map<String, Object> embed = new HashMap<>();
        embed.put("title", style[0] + " - " + p.site());
        embed.put("description", "**" + p.name() + "**");
        embed.put("color", style[1]);
        if (p.url() != null && p.url().startsWith("http")) {
            embed.put("url", p.url());                     // Discord rejects non-URL values here
        }
        embed.put("fields", List.of(
                Map.of("name", "Price", "value", price, "inline", true),
                Map.of("name", "Stock", "value", p.inStock() ? "In stock" : "Out", "inline", true)
        ));

        try {
            String body = MAPPER.writeValueAsString(Map.of("embeds", List.of(embed)));
            HttpRequest req = HttpRequest.newBuilder(URI.create(webhook))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();
            HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() >= 300) {
                System.err.println("discord webhook failed: " + r.statusCode() + " " + r.body());
            }
        } catch (Exception e) {
            System.err.println("discord post error: " + e.getMessage());
        }
    }
}
