package akka.actor;

import static org.junit.Assert.*;

public class StashJavaAPITestActors {

    /*
     * Helper method to make the tests of UntypedActorWithStash, UntypedActorWithUnboundedStash and
     * UntypedActorWithUnrestrictedStash more DRY since mixin is not possible.
     */
    private static int testReceive(Object msg, int count, ActorRef sender, ActorRef self, UnrestrictedStash stash) {
        if (msg instanceof String) {
            if (count < 0) {
                sender.tell(new Integer(((String) msg).length()), self);
            } else if (count == 2) {
                stash.unstashAll();
                return -1;
            } else {
                stash.stash();
                return count + 1;
            }
        } else if (msg instanceof Integer) {
            int value = ((Integer) msg).intValue();
            assertEquals(value, 5);
        }
        return count;
    }

    public static class WithStash extends UntypedActorWithStash {
        int count = 0;

        public void onReceive(Object msg) {
            count = testReceive(msg, count, getSender(), getSelf(), this);
        }
    }

    public static class WithUnboundedStash extends UntypedActorWithUnboundedStash {
        int count = 0;

        public void onReceive(Object msg) {
            count = testReceive(msg, count, getSender(), getSelf(), this);
        }
    }

    public static class WithUnrestrictedStash extends UntypedActorWithUnrestrictedStash {
        int count = 0;

        public void onReceive(Object msg) {
            count = testReceive(msg, count, getSender(), getSelf(), this);
        }
    }
}

