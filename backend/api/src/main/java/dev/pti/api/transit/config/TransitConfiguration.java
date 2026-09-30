package dev.pti.api.transit.config;

import dev.pti.api.platform.application.RequireActiveFeed;
import dev.pti.api.platform.application.port.DataAsOfReader;
import dev.pti.api.transit.adapter.in.web.TimeRanges;
import dev.pti.api.transit.application.GetRoute;
import dev.pti.api.transit.application.GetRouteDelayProfile;
import dev.pti.api.transit.application.GetRouteDelays;
import dev.pti.api.transit.application.GetStop;
import dev.pti.api.transit.application.ListLiveVehicles;
import dev.pti.api.transit.application.ListRoutes;
import dev.pti.api.transit.application.ListStopArrivals;
import dev.pti.api.transit.application.SearchStops;
import dev.pti.api.transit.application.port.ArrivalReader;
import dev.pti.api.transit.application.port.EtaProfileReader;
import dev.pti.api.transit.application.port.LiveVehicleReader;
import dev.pti.api.transit.application.port.OpenBunchingReader;
import dev.pti.api.transit.application.port.RouteCatalogReader;
import dev.pti.api.transit.application.port.RouteDelayReader;
import dev.pti.api.transit.application.port.RouteDetailReader;
import dev.pti.api.transit.application.port.StopDisruptionReader;
import dev.pti.api.transit.application.port.StopReader;
import dev.pti.api.transit.application.port.StopRoutesReader;
import dev.pti.api.transit.domain.ArrivalSettings;
import dev.pti.api.transit.domain.ConfidenceThresholds;
import dev.pti.api.transit.domain.OnTimeTolerance;
import dev.pti.common.time.BusinessClock;
import dev.pti.common.tx.TransactionRunner;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the use cases of the {@code transit} feature: routes, stops, live vehicles and arrivals (DOC-32 §3). Every
 * one reads through the {@code readerTx} transaction (DOC-31 §10.1). Its JDBC readers are components: they name the
 * cache and query timer of the platform in their constructors, which a {@code config} class may not (A-14).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    VehiclesProperties.class,
    TimeRangeProperties.class,
    OtpProperties.class,
    EtaProperties.class
})
class TransitConfiguration {

    @Bean
    ListRoutes listRoutes(
            RequireActiveFeed requireActiveFeed,
            RouteCatalogReader routes,
            @Qualifier("readerTx") TransactionRunner tx) {
        return new ListRoutes(requireActiveFeed, routes, tx);
    }

    @Bean
    GetRoute getRoute(
            RequireActiveFeed requireActiveFeed,
            RouteDetailReader details,
            @Qualifier("readerTx") TransactionRunner tx) {
        return new GetRoute(requireActiveFeed, details, tx);
    }

    @Bean
    GetRouteDelays getRouteDelays(
            RequireActiveFeed requireActiveFeed,
            RouteCatalogReader routes,
            RouteDelayReader delays,
            DataAsOfReader asOf,
            OtpProperties otp,
            @Qualifier("readerTx") TransactionRunner tx) {
        return new GetRouteDelays(
                requireActiveFeed,
                routes,
                delays,
                asOf,
                new OnTimeTolerance(otp.earlyTolerance(), otp.lateTolerance()),
                tx);
    }

    @Bean
    GetRouteDelayProfile getRouteDelayProfile(
            RequireActiveFeed requireActiveFeed,
            RouteDetailReader details,
            EtaProfileReader eta,
            DataAsOfReader asOf,
            BusinessClock clock,
            EtaProperties properties,
            @Qualifier("readerTx") TransactionRunner tx) {
        return new GetRouteDelayProfile(requireActiveFeed, details, eta, asOf, clock, confidence(properties), tx);
    }

    @Bean
    ListLiveVehicles listLiveVehicles(
            RequireActiveFeed requireActiveFeed,
            LiveVehicleReader vehicles,
            OpenBunchingReader bunching,
            DataAsOfReader asOf,
            BusinessClock clock,
            VehiclesProperties properties,
            @Qualifier("readerTx") TransactionRunner tx) {
        return new ListLiveVehicles(
                requireActiveFeed, vehicles, bunching, asOf, clock, properties.maxAge(), properties.maxItems(), tx);
    }

    @Bean
    SearchStops searchStops(
            RequireActiveFeed requireActiveFeed,
            StopReader stops,
            StopRoutesReader stopRoutes,
            @Qualifier("readerTx") TransactionRunner tx) {
        return new SearchStops(requireActiveFeed, stops, stopRoutes, tx);
    }

    @Bean
    GetStop getStop(
            RequireActiveFeed requireActiveFeed,
            StopReader stops,
            StopRoutesReader stopRoutes,
            RouteCatalogReader routes,
            StopDisruptionReader disruptions,
            @Qualifier("readerTx") TransactionRunner tx) {
        return new GetStop(requireActiveFeed, stops, stopRoutes, routes, disruptions, tx);
    }

    @Bean
    ListStopArrivals listStopArrivals(
            RequireActiveFeed requireActiveFeed,
            StopReader stops,
            ArrivalReader arrivals,
            DataAsOfReader asOf,
            BusinessClock clock,
            EtaProperties properties,
            @Qualifier("readerTx") TransactionRunner tx) {
        ArrivalSettings settings = new ArrivalSettings(
                properties.arrivals().defaultLimit(),
                properties.arrivals().horizon(),
                properties.realtimeEnabled(),
                properties.realtimeMaxAge(),
                confidence(properties));
        return new ListStopArrivals(requireActiveFeed, stops, arrivals, asOf, clock, settings, tx);
    }

    @Bean
    TimeRanges timeRanges(BusinessClock clock, TimeRangeProperties properties) {
        return new TimeRanges(clock, properties.maxRange());
    }

    private static ConfidenceThresholds confidence(EtaProperties properties) {
        return new ConfidenceThresholds(
                properties.confidence().mediumMin(), properties.confidence().highMin());
    }
}
