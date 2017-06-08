/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.circuitbreaker;

//#imports1

import akka.actor.AbstractActor;
import akka.event.LoggingAdapter;
import scala.concurrent.duration.Duration;
import akka.pattern.CircuitBreaker;
import akka.event.Logging;

import static akka.pattern.PatternsCS.pipe;

import java.util.concurrent.CompletableFuture;

//#imports1

//#circuit-breaker-initialization
public class DangerousJavaActor extends AbstractActor {

  private final CircuitBreaker breaker;
  private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  public DangerousJavaActor() {
    this.breaker = new CircuitBreaker(
      getContext().dispatcher(), getContext().system().scheduler(),
      5, Duration.create(10, "s"), Duration.create(1, "m"))
      .onOpen(this::notifyMeOnOpen);
  }

  public void notifyMeOnOpen() {
    log.warning("My CircuitBreaker is now open, and will not close for one minute");
  }
//#circuit-breaker-initialization

  //#circuit-breaker-usage
  public String dangerousCall() {
    return "This really isn't that dangerous of a call after all";
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder().
      match(String.class, "is my middle name"::equals, m -> pipe(
        breaker.callWithCircuitBreakerCS(() ->
          CompletableFuture.supplyAsync(this::dangerousCall)
        ), getContext().dispatcher()
      ).to(sender()))
      .match(String.class, "block for me"::equals, m -> {
        sender().tell(breaker
          .callWithSyncCircuitBreaker(this::dangerousCall), self());
      })
      .build();
  }
//#circuit-breaker-usage

}
