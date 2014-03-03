/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.actor;

//#imports
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;
import scala.PartialFunction;
import scala.runtime.BoxedUnit;

//#imports

//#my-actor
public class MyActor extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(context().system(), this);

  @Override
  public PartialFunction<Object, BoxedUnit> receive() {
    return ReceiveBuilder.
      match(String.class, s -> {
        log.info("Received String message: {}", s);
        //#my-actor
        //#reply
        sender().tell(s, self());
        //#reply
        //#my-actor
      }).
      matchAny(o -> log.info("received unknown message")).build();
  }
}
//#my-actor
