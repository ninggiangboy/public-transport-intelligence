package dev.pti.api.sim.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * The control API of the simulator that the proxy lets through (DOC-25 §8, DOC-32 E-90): a method and a path after
 * {@code /sim}. Anything else is not forwarded and is a 404 for the caller, as if the route did not exist, so the proxy
 * cannot be used to reach other paths of the simulator.
 */
public final class SimulatorEndpoints {

    /** What the simulator's paths start with, and what they start with on the API. */
    public static final String SIMULATOR_PREFIX = "/sim";

    public static final String API_PREFIX = "/api/v1/sim";

    private record Endpoint(String method, Pattern path) {}

    private static final String NAME = "[a-z0-9-]+";
    private static final String ID = "[A-Za-z0-9-]+";

    private static final List<Endpoint> ALLOWED = List.of(
            new Endpoint("GET", Pattern.compile("^/sim/status$")),
            new Endpoint("PUT", Pattern.compile("^/sim/rate$")),
            new Endpoint("GET", Pattern.compile("^/sim/scenarios$")),
            new Endpoint("POST", Pattern.compile("^/sim/scenarios/" + NAME + "$")),
            new Endpoint("DELETE", Pattern.compile("^/sim/scenarios/" + NAME + "$")),
            new Endpoint("GET", Pattern.compile("^/sim/scenario-runs$")),
            new Endpoint("GET", Pattern.compile("^/sim/scenario-runs/" + ID + "$")),
            new Endpoint("DELETE", Pattern.compile("^/sim/scenario-runs/" + ID + "$")));

    private SimulatorEndpoints() {}

    /** True when the simulator has this endpoint (DOC-25 §8). */
    public static boolean allows(String method, String simulatorPath) {
        return ALLOWED.stream()
                .anyMatch(endpoint -> endpoint.method().equals(method)
                        && endpoint.path().matcher(simulatorPath).matches());
    }

    /**
     * A {@code Location} of the simulator ({@code /sim/scenario-runs/<id>}) as the API's own ({@code
     * /api/v1/sim/scenario-runs/<id>}); a {@code Location} that is not under {@code /sim} is left as it is.
     *
     * @param simulatorBaseUrl {@code pti.api.sim.base-url}, which an absolute {@code Location} may start with
     */
    public static String publicLocation(String location, String simulatorBaseUrl) {
        String path = location;
        String base = simulatorBaseUrl.endsWith("/")
                ? simulatorBaseUrl.substring(0, simulatorBaseUrl.length() - 1)
                : simulatorBaseUrl;
        if (path.startsWith(base + "/")) {
            path = path.substring(base.length());
        }
        return path.startsWith(SIMULATOR_PREFIX + "/")
                ? API_PREFIX + path.substring(SIMULATOR_PREFIX.length())
                : location;
    }
}
