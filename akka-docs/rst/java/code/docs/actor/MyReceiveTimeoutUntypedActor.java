/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actor;

//#receive-timeout
import akka.actor.ActorRef;
import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import scala.concurrent.duration.Duration;

public class MyReceiveTimeoutUntypedActor extends UntypedActor {
  //#receive-timeout
  ActorRef target = getContext().system().deadLetters();
  //#receive-timeout

  public MyReceiveTimeoutUntypedActor() {
    // To set an initial delay
    getContext().setReceiveTimeout(Duration.create("30 seconds"));
  }

  public void onReceive(Object message) {
    if (message.equals("Hello")) {
      // To set in a response to a message
      getContext().setReceiveTimeout(Duration.create("1 second"));
      //#receive-timeout
      target = getSender();
      target.tell("Hello world", getSelf());
      //#receive-timeout
    } else if (message instanceof ReceiveTimeout) {
      // To turn it off
      getContext().setReceiveTimeout(Duration.Undefined());
      //#receive-timeout
      target.tell("timeout", getSelf());
      //#receive-timeout
    } else {
      unhandled(message);
    }
  }
}
//#receive-timeout
