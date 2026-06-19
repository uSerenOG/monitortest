package monitor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads/saves the snapshot to a JSON file. Because GitHub Actions runs are
 * ephemeral, this file is committed back to the repo by the workflow so the
 * next run can read it.
 */
public class Store {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);

    /** On-disk shape of state.json. */
    static final class State {
        public Set<String> seeded = new HashSet<>();
        public Map<String, List<Product>> snapshots = new HashMap<>();
    }

    private final State state;

    private Store(State state) { this.state = state; }

    static Store load(String path) throws IOException {
        Path p = Path.of(path);
        if (Files.exists(p) && Files.size(p) > 2) {        // > "{}"
            return new Store(MAPPER.readValue(Files.readString(p), State.class));
        }
        return new Store(new State());
    }

    boolean isSeeded(String site) {
        return state.seeded.contains(site);
    }

    Map<String, Product> snapshot(String site) {
        Map<String, Product> map = new HashMap<>();
        for (Product p : state.snapshots.getOrDefault(site, List.of())) {
            map.put(p.productId(), p);
        }
        return map;
    }

    void save(String site, List<Product> products) {
        state.snapshots.put(site, new ArrayList<>(products));
        state.seeded.add(site);
    }

    void persist(String path) throws IOException {
        Files.writeString(Path.of(path), MAPPER.writeValueAsString(state));
    }
}
