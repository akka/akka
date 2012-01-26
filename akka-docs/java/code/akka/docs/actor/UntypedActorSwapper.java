/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.actor;

import static akka.docs.actor.UntypedActorSwapper.Swap.SWAP;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.ActorSystem;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.japi.Procedure;

//#swapper
public class UntypedActorSwapper {

  public static class Swap {
    public static Swap SWAP = new Swap();

    private Swap() {
    }
  }

  public static class Swapper extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    public void onReceive(Object message) {
      if (message == SWAP) {
        log.info("Hi");
        getContext().become(new Procedure<Object>() {
          @Override
          public void apply(Object message) {
            log.info("Ho");
            getContext().unbecome(); // resets the latest 'become' (just for fun)
          }
        });
      } else {
        unhandled(message);
      }
    }
  }

  public static void main(String... args) {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef swap = system.actorOf(new Props(Swapper.class));
    swap.tell(SWAP); // logs Hi
    swap.tell(SWAP); // logs Ho
    swap.tell(SWAP); // logs Hi
    swap.tell(SWAP); // logs Ho
    swap.tell(SWAP); // logs Hi
    swap.tell(SWAP); // logs Ho
  }

}
//#swapper
