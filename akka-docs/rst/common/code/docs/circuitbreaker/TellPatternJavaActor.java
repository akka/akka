/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.circuitbreaker;

import akka.actor.ActorRef;
import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.CircuitBreaker;
import scala.concurrent.duration.Duration;

public class TellPatternJavaActor extends UntypedActor {

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
  public void onReceive(Object payload) {
    if ( "call".equals(payload) && breaker.isClosed() ) {
      target.tell("message", getSelf());
    } else if ( "response".equals(payload) ) {
      breaker.succeed();
    } else if ( payload instanceof Throwable ) {
      breaker.fail();
    } else if ( payload instanceof ReceiveTimeout ) {
      breaker.fail();
    }
  }
  //#circuit-breaker-tell-pattern

}
