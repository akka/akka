/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.circuitbreaker;

// #imports1

import static akka.pattern.Patterns.pipe;

import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.CircuitBreaker;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

// #imports1

// #circuit-breaker-initialization
public class DangerousJavaActor extends AbstractActor {

  private final CircuitBreaker breaker;
  private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  public DangerousJavaActor() {
    this.breaker =
        CircuitBreaker.lookup("dangerous-breaker", getContext().getSystem())
            .addOnOpenListener(this::notifyMeOnOpen);
  }

  public void notifyMeOnOpen() {
    log.warning("My CircuitBreaker is now open, and will not close for one minute");
  }
  // #circuit-breaker-initialization

  // #circuit-breaker-usage
  public String dangerousCall() {
    return "This really isn't that dangerous of a call after all";
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .match(
            String.class,
            "is my middle name"::equals,
            m ->
                pipe(
                        breaker.callWithCircuitBreakerCS(
                            () -> CompletableFuture.supplyAsync(this::dangerousCall)),
                        getContext().getDispatcher())
                    .to(sender()))
        .match(
            String.class,
            "block for me"::equals,
            m -> {
              sender().tell(breaker.callWithSyncCircuitBreaker(this::dangerousCall), self());
            })
        .build();
  }
  // #circuit-breaker-usage

  private void showManualApi() {
    // #manual-construction
    CircuitBreaker breaker =
        CircuitBreaker.create(
            getContext().getSystem().getScheduler(),
            // maxFailures
            5,
            // callTimeout
            Duration.ofSeconds(10),
            // resetTimeout
            Duration.ofMinutes(1));
    // #manual-construction
  }
}
