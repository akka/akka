/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.circuitbreaker;

import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.pattern.CircuitBreaker;
import scala.concurrent.duration.Duration;

import static akka.dispatch.Futures.future;
import static akka.pattern.Patterns.pipe;

public class TellPatternJavaActor extends UntypedActor {

  private final CircuitBreaker breaker;
  private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  public TellPatternJavaActor() {
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

  public void handleExpected( String s ) { log.info(s); }

  public void handleUnExpected( String s ) { log.warning(s); }

  //#circuit-breaker-tell-pattern
  class ExpectedRemoteResponse{
    private final String _s;
    public ExpectedRemoteResponse( String s ) {
      _s = s;
    }
    String getMessage() { return _s; }
  }

  class UnExpectedRemoteResponse{
    private final String _s;
    public UnExpectedRemoteResponse( String s ) {
      _s = s;
    }
    String getMessage() { return _s; }
  }

  @Override
  public void onReceive(Object message) {
    if ( message instanceof ExpectedRemoteResponse ) {
      String m = ((ExpectedRemoteResponse) message).getMessage();
      handleExpected(m);
      breaker.succeed();
    } else if ( message instanceof UnExpectedRemoteResponse ) {
      String m = ((ExpectedRemoteResponse) message).getMessage();
      handleUnExpected(m);
      breaker.fail();
    } else if ( message instanceof ReceiveTimeout ) {
      breaker.fail();
    }
  }
  //#

}
