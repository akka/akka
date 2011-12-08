package akka.docs.actor;

import static akka.docs.actor.UntypedActorSwapper.Swap.SWAP;
import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.actor.UnhandledMessageException;
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

    public void onReceive(Object message) throws Exception {
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
        throw new UnhandledMessageException(message, getSelf());
      }
    }
  }

  public static void main(String... args) {
    ActorSystem system = ActorSystem.create("MySystem");
    ActorRef swap = system.actorOf(Swapper.class);
    swap.tell(SWAP); // logs Hi
    swap.tell(SWAP); // logs Ho
    swap.tell(SWAP); // logs Hi
    swap.tell(SWAP); // logs Ho
    swap.tell(SWAP); // logs Hi
    swap.tell(SWAP); // logs Ho
  }

}
//#swapper