package akka.docs.actor;

//#my-untyped-actor
import akka.actor.UntypedActor;
import akka.actor.UnhandledMessageException;
import akka.event.Logging;
import akka.event.LoggingAdapter;

public class MyUntypedActor extends UntypedActor {
  LoggingAdapter log = Logging.getLogger(getContext().system(), this);

  public void onReceive(Object message) throws Exception {
    if (message instanceof String)
      log.info("Received String message: {}", message);
    else
      throw new UnhandledMessageException(message, getSelf());
  }
}
//#my-untyped-actor

