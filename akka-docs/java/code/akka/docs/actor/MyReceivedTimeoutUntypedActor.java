package akka.docs.actor;

//#receive-timeout
import akka.actor.Actors;
import akka.actor.ReceiveTimeout;
import akka.actor.UnhandledMessageException;
import akka.actor.UntypedActor;
import akka.util.Duration;

public class MyReceivedTimeoutUntypedActor extends UntypedActor {

  public MyReceivedTimeoutUntypedActor() {
    getContext().setReceiveTimeout(Duration.parse("30 seconds"));
  }

  public void onReceive(Object message) throws Exception {
    if (message.equals("Hello")) {
      getSender().tell("Hello world");
    } else if (message == Actors.receiveTimeout()) {
      throw new RuntimeException("received timeout");
    } else {
      throw new UnhandledMessageException(message, getSelf());
    }
  }
}
//#receive-timeout