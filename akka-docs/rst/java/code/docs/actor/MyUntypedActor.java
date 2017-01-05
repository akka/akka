/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actor;

//#my-untyped-actor
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class MyUntypedActor extends UntypedActor {
  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  public void onReceive(Object message) throws Exception {
    if (message instanceof String) {
      log.info("Received String message: {}", message);
      getSender().tell(message, getSelf());
    } else
      unhandled(message);
  }
}
//#my-untyped-actor

