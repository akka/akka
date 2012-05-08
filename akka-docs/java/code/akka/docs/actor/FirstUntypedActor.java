/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.PoisonPill;
import akka.actor.UntypedActor;

//#context-actorOf
public class FirstUntypedActor extends UntypedActor {
  ActorRef myActor = getContext().actorOf(new Props(MyActor.class), "myactor");

  //#context-actorOf

  public void onReceive(Object message) {
    myActor.forward(message, getContext());
    myActor.tell(PoisonPill.getInstance());
  }
}
