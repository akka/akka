/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.actor;

import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.PoisonPill;
import akka.actor.UntypedActor;

//#context-actorOf
public class FirstUntypedActor extends UntypedActor {
  ActorRef myActor = getContext().actorOf(Props.create(MyActor.class), "myactor");

  //#context-actorOf

  public void onReceive(Object message) {
    myActor.forward(message, getContext());
    myActor.tell(PoisonPill.getInstance(), ActorRef.noSender());
  }
}
