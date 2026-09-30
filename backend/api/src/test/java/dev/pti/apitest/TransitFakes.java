package dev.pti.apitest;

import dev.pti.api.platform.application.RequireActiveFeed;
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
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * The ports of the transit feature, and the lookup of the ACTIVE feed, replaced by one {@link InMemoryTransit}, so that
 * the web tests never reach for a database: a request that gets past the security filters runs the real use case
 * against memory. {@link ApiWebTestSupport} imports it; a test that wants data autowires the {@code InMemoryTransit}
 * and calls {@code reset()} first.
 */
@TestConfiguration(proxyBeanMethods = false)
public class TransitFakes {

    @Bean
    InMemoryTransit inMemoryTransit() {
        return new InMemoryTransit();
    }

    /**
     * The use cases of the feature are wired with the {@code readerTx} transaction, which would open a real one on
     * the {@code reader} datasource: the bean is swapped for a runner that just runs the work.
     */
    @Bean
    static BeanPostProcessor directReaderTransactions() {
        return new BeanPostProcessor() {
            @Override
            public @Nullable Object postProcessAfterInitialization(Object bean, String beanName) {
                return "readerTx".equals(beanName) ? new DirectTransactions() : bean;
            }
        };
    }

    @Bean
    @Primary
    RequireActiveFeed fakeRequireActiveFeed(InMemoryTransit transit) {
        return new RequireActiveFeed(transit.activeFeed);
    }

    @Bean
    @Primary
    RouteCatalogReader fakeRouteCatalog(InMemoryTransit transit) {
        return transit.routeCatalog;
    }

    @Bean
    @Primary
    RouteDetailReader fakeRouteDetail(InMemoryTransit transit) {
        return transit.routeDetail;
    }

    @Bean
    @Primary
    RouteDelayReader fakeRouteDelays(InMemoryTransit transit) {
        return transit.routeDelays;
    }

    @Bean
    @Primary
    EtaProfileReader fakeEtaProfile(InMemoryTransit transit) {
        return transit.etaProfile;
    }

    @Bean
    @Primary
    LiveVehicleReader fakeLiveVehicles(InMemoryTransit transit) {
        return transit.liveVehicles;
    }

    @Bean
    @Primary
    OpenBunchingReader fakeOpenBunching(InMemoryTransit transit) {
        return transit.openBunching;
    }

    @Bean
    @Primary
    StopReader fakeStops(InMemoryTransit transit) {
        return transit.stopReader;
    }

    @Bean
    @Primary
    StopRoutesReader fakeStopRoutes(InMemoryTransit transit) {
        return transit.stopRoutesReader;
    }

    @Bean
    @Primary
    StopDisruptionReader fakeStopDisruptions(InMemoryTransit transit) {
        return transit.stopDisruptions;
    }

    @Bean
    @Primary
    ArrivalReader fakeArrivals(InMemoryTransit transit) {
        return transit.arrivals;
    }
}
