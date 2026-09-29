package dev.pti.etl.fault;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.common.error.FatalException;
import dev.pti.common.error.TransientInfraException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** DOC-19 §8. */
class ConfigurableFaultInjectorTest {

    private final List<Integer> halts = new ArrayList<>();
    private final ConfigurableFaultInjector injector = new ConfigurableFaultInjector(halts::add);

    @Test
    void unarmedPointsDoNothing() {
        assertThatCode(() -> injector.hit(FaultPoint.BEFORE_WRITE)).doesNotThrowAnyException();
        FaultInjector.NOOP.hit(FaultPoint.BEFORE_WRITE);
    }

    @Test
    void letsHitsThroughThenFailsTheGivenNumberOfTimes() {
        injector.arm(FaultPoint.BEFORE_WRITE, FaultAction.THROW_TRANSIENT, 1, 2);
        injector.hit(FaultPoint.BEFORE_WRITE);
        assertThatThrownBy(() -> injector.hit(FaultPoint.BEFORE_WRITE)).isInstanceOf(TransientInfraException.class);
        assertThatThrownBy(() -> injector.hit(FaultPoint.BEFORE_WRITE)).isInstanceOf(TransientInfraException.class);
        assertThatCode(() -> injector.hit(FaultPoint.BEFORE_WRITE)).doesNotThrowAnyException();
    }

    @Test
    void fatalAndHalt() {
        injector.arm(FaultPoint.AFTER_PROCESS, FaultAction.THROW_FATAL, 0, 1);
        injector.arm(FaultPoint.AFTER_WRITE_BEFORE_COMMIT, FaultAction.HALT, 0, 1);
        assertThatThrownBy(() -> injector.hit(FaultPoint.AFTER_PROCESS)).isInstanceOf(FatalException.class);
        injector.hit(FaultPoint.AFTER_WRITE_BEFORE_COMMIT);
        assertThat(halts).containsExactly(137);
    }

    @Test
    void disarm() {
        injector.arm(FaultPoint.BEFORE_READ, FaultAction.THROW_FATAL, 0, 1);
        injector.disarmAll();
        assertThatCode(() -> injector.hit(FaultPoint.BEFORE_READ)).doesNotThrowAnyException();
    }

    @Test
    void readsTheTestProperties() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("pti.test.fault.after-write-before-commit", "throw-transient")
                .withProperty("pti.test.fault.after-n", "2");
        ConfigurableFaultInjector configured = ConfigurableFaultInjector.fromEnvironment(env);
        configured.hit(FaultPoint.AFTER_WRITE_BEFORE_COMMIT);
        configured.hit(FaultPoint.AFTER_WRITE_BEFORE_COMMIT);
        assertThatThrownBy(() -> configured.hit(FaultPoint.AFTER_WRITE_BEFORE_COMMIT))
                .isInstanceOf(TransientInfraException.class);
        configured.hit(FaultPoint.AFTER_WRITE_BEFORE_COMMIT);
    }

    @Test
    void keysAndActions() {
        assertThat(FaultPoint.AFTER_COMMIT_BEFORE_ACK.key()).isEqualTo("after-commit-before-ack");
        assertThat(FaultAction.parse(" halt ")).isEqualTo(FaultAction.HALT);
    }
}
