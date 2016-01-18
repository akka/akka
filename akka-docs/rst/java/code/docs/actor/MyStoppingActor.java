/**
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor;

//#my-stopping-actor
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class MyStoppingActor extends UntypedActor {

  ActorRef child = null;

  // ... creation of child ...

  public void onReceive(Object message) throws Exception {
    if (message.equals("interrupt-child")) {
      context().stop(child);
    } else if (message.equals("done")) {
      context().stop(getSelf());
    } else {
      unhandled(message);
    }
  }
}
//#my-stopping-actor

