package jdocs.circuitbreaker;

import akka.actor.AbstractActor;
import akka.pattern.CircuitBreaker;
import scala.concurrent.duration.Duration;

import java.util.Optional;
import java.util.function.BiFunction;

public class EvenNoFailureJavaExample extends AbstractActor {
    //#even-no-as-failure
    private final CircuitBreaker breaker;

    public EvenNoFailureJavaExample() {
        this.breaker = new CircuitBreaker(
                getContext().dispatcher(), getContext().system().scheduler(),
                5, Duration.create(10, "s"), Duration.create(1, "m"));
    }

    public int luckyNumber() {
        BiFunction<Optional<Integer>, Optional<Throwable>, Boolean> evenNoAsFailure =
                (result, err) -> (result.isPresent() && result.get() % 2 == 0);

        // this will return 8888 and increase failure count at the same time
        return this.breaker.callWithSyncCircuitBreaker(() -> 8888, evenNoAsFailure);
    }

    //#even-no-as-failure
    @Override
    public Receive createReceive() {
        return null;
    }
}
