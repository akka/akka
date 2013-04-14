/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor;

//#receive-timeout
import akka.actor.ActorRef;
import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import scala.concurrent.duration.Duration;

public class MyReceiveTimeoutUntypedActor extends UntypedActor {
  
  ActorRef target = getContext().system().deadLetters();

  public MyReceiveTimeoutUntypedActor() {
    // To set an initial delay
    getContext().setReceiveTimeout(Duration.create("30 seconds"));
  }

  public void onReceive(Object message) {
    if (message.equals("Hello")) {
      // To set in a response to a message
      getContext().setReceiveTimeout(Duration.create("1 second"));
      target = getSender();
      target.tell("Hello world", getSelf());
    } else if (message instanceof ReceiveTimeout) {
      // To turn it off
      getContext().setReceiveTimeout(Duration.Undefined());
      target.tell("timeout", getSelf());
    } else {
      unhandled(message);
    }
  }
}
//#receive-timeout
