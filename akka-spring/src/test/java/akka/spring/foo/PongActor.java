package akka.spring.foo;

import akka.actor.UntypedActor;

/**
 * test class
 */
public class PongActor extends UntypedActor {

  public void onReceive(Object message) throws Exception {
    if (message instanceof String) {
      System.out.println("Pongeceived String message: " + message);
      getContext().replyUnsafe(message + " from " + getContext().getUuid());
    } else {
      throw new IllegalArgumentException("Unknown message: " + message);
    }
  }
}
