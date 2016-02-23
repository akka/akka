/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actorlambda;

//#imports
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;

//#imports

//#my-actor
public class MyActor extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(context().system(), this);

  public MyActor() {
    receive(ReceiveBuilder.
      match(String.class, s -> {
        log.info("Received String message: {}", s);
        //#my-actor
        //#reply
        sender().tell(s, self());
        //#reply
        //#my-actor
      }).
      matchAny(o -> log.info("received unknown message")).build()
    );
  }
}
//#my-actor
