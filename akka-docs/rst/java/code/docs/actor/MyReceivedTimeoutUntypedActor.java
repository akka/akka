/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor;

//#receive-timeout
import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import scala.concurrent.duration.Duration;

public class MyReceivedTimeoutUntypedActor extends UntypedActor {

  public MyReceivedTimeoutUntypedActor() {
    // To set an initial delay
    getContext().setReceiveTimeout(Duration.create("30 seconds"));
  }

  public void onReceive(Object message) {
    if (message.equals("Hello")) {
      // To set in a response to a message
      getContext().setReceiveTimeout(Duration.create("10 seconds"));
      getSender().tell("Hello world", getSelf());
    } else if (message == ReceiveTimeout.getInstance()) {
      // To turn it off
      getContext().setReceiveTimeout(Duration.Undefined());
      throw new RuntimeException("received timeout");
    } else {
      unhandled(message);
    }
  }
}
//#receive-timeout