/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.actor;

//#receive-timeout
import akka.actor.ReceiveTimeout;
import akka.actor.UntypedActor;
import akka.util.Duration;

public class MyReceivedTimeoutUntypedActor extends UntypedActor {

  public MyReceivedTimeoutUntypedActor() {
    getContext().setReceiveTimeout(Duration.parse("30 seconds"));
  }

  public void onReceive(Object message) {
    if (message.equals("Hello")) {
      getSender().tell("Hello world");
    } else if (message == ReceiveTimeout.getInstance()) {
      throw new RuntimeException("received timeout");
    } else {
      unhandled(message);
    }
  }
}
//#receive-timeout