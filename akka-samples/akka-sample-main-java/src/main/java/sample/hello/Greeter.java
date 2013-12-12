/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.hello;

import akka.actor.UntypedActor;

public class Greeter extends UntypedActor {

  public static enum Msg {
    GREET, DONE;
  }

  @Override
  public void onReceive(Object msg) {
    if (msg == Msg.GREET) {
      System.out.println("Hello World!");
      getSender().tell(Msg.DONE, getSelf());
    } else
      unhandled(msg);
  }

}
