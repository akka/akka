/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actor;

//#imports
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;

//#imports

//#my-actor
public class MyJavaActor extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(context().system(), this);

  public MyJavaActor() {
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
