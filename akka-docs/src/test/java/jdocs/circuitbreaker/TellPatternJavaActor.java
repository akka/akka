/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.circuitbreaker;

import akka.actor.ActorRef;
import akka.actor.ReceiveTimeout;
import akka.actor.AbstractActor.Receive;
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.CircuitBreaker;
import scala.concurrent.duration.Duration;

public class TellPatternJavaActor extends AbstractActor {

  private final ActorRef       target;
  private final CircuitBreaker breaker;
  private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  public TellPatternJavaActor(ActorRef targetActor) {
    this.target  = targetActor;
    this.breaker = new CircuitBreaker(
      getContext().dispatcher(), getContext().system().scheduler(),
      5, Duration.create(10, "s"), Duration.create(1, "m"))
      .onOpen(new Runnable() {
        public void run() {
          notifyMeOnOpen();
        }
      });
  }

  public void notifyMeOnOpen() {
    log.warning("My CircuitBreaker is now open, and will not close for one minute");
  }

  //#circuit-breaker-tell-pattern
  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(String.class, payload -> "call".equals(payload) && breaker.isClosed(), payload -> 
        target.tell("message", self())
      )
      .matchEquals("response", payload -> 
        breaker.succeed()
      )
      .match(Throwable.class, t ->
        breaker.fail()
      )
      .match(ReceiveTimeout.class, t ->    
        breaker.fail()
      )
      .build();
  }
  //#circuit-breaker-tell-pattern

}
