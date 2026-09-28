package dev.pti.spike.web;

import com.github.f4b6a3.uuid.UuidCreator;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PingController {

    record Pong(String status, UUID requestId, boolean virtualThread) {}

    @GetMapping("/api/v1/ping")
    Pong ping() {
        return new Pong(
                "ok", UuidCreator.getTimeOrderedEpoch(), Thread.currentThread().isVirtual());
    }
}
