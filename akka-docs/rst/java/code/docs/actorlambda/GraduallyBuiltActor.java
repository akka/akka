/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.actorlambda;

//#imports
import akka.actor.AbstractActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.pf.ReceiveBuilder;
import akka.japi.pf.UnitPFBuilder;

//#imports

//#actor
public class GraduallyBuiltActor extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(context().system(), this);

  public GraduallyBuiltActor() {
    UnitPFBuilder<Object> builder = ReceiveBuilder.create();
    builder.match(String.class, s -> {
      log.info("Received String message: {}", s);
      //#actor
      //#reply
      sender().tell(s, self());
      //#reply
      //#actor
    });
    // do some other stuff in between
    builder.matchAny(o -> log.info("received unknown message"));
    receive(builder.build());
  }
}
//#actor
