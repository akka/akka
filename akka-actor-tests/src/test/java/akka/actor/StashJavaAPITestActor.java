package akka.actor;

import static org.junit.Assert.*;

public class StashJavaAPITestActor extends UntypedActorWithStash {
  int count = 0;

  public void onReceive(Object msg) {
    if (msg instanceof String) {
      if (count < 0) {
        getSender().tell(new Integer(((String) msg).length()), getSelf());
      } else if (count == 2) {
        count = -1;
        unstashAll();
      } else {
        count += 1;
        stash();
      }
    } else if (msg instanceof Integer) {
      int value = ((Integer) msg).intValue();
      assertEquals(value, 5);
    }
  }
}
