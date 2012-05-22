/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.jrouting;

import akka.actor.UntypedActor;

//#printlnActor
public class PrintlnActor extends UntypedActor {
  public void onReceive(Object msg) {
    System.out.println(String.format("Received message '%s' in actor %s", msg, getSelf().path().name()));
  }
}

//#printlnActor
