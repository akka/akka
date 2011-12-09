package akka.docs.actor;

import akka.actor.ActorRef;
import static akka.actor.Actors.*;
import akka.actor.UntypedActor;

//#context-actorOf
public class FirstUntypedActor extends UntypedActor {
  ActorRef myActor = getContext().actorOf(MyActor.class);

  //#context-actorOf

  public void onReceive(Object message) {
    myActor.forward(message, getContext());
    myActor.tell(poisonPill());
  }
}
