/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actorlambda;

//#imports
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

//#imports

//#my-actor
public class MyActor extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  @Override
  public Receive createReceive() {
    return receiveBuilder()
      .match(String.class, s -> {
        log.info("Received String message: {}", s);
        //#my-actor
        //#reply
        sender().tell(s, self());
        //#reply
        //#my-actor
      })
      .matchAny(o -> log.info("received unknown message"))
      .build();
  }
}
//#my-actor
