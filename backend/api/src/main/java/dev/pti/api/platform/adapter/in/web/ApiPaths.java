package dev.pti.api.platform.adapter.in.web;

/** Path prefixes of the API (DOC-31 §2). Controllers write their path as {@code ApiPaths.V1 + "/routes"}. */
public final class ApiPaths {

    /** Every public endpoint lives under it (DR-39). */
    public static final String V1 = "/api/v1";

    /** Endpoints inside the network, not proxied by nginx, without the version prefix. */
    public static final String INTERNAL = "/internal";

    private ApiPaths() {}
}
