package akka.transactor.example;

import akka.transactor.Coordinated;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.stm.*;

public class UntypedCoordinatedCounter extends UntypedActor {
    private Ref<Integer> count = new Ref(0);

    private void increment() {
        System.out.println("incrementing");
        count.set(count.get() + 1);
    }

    public void onReceive(Object incoming) throws Exception {
        if (incoming instanceof Coordinated) {
            Coordinated coordinated = (Coordinated) incoming;
            Object message = coordinated.getMessage();
            if (message instanceof Increment) {
                Increment increment = (Increment) message;
                if (increment.hasFriend()) {
                    increment.getFriend().sendOneWay(coordinated.coordinate(new Increment()));
                }
                coordinated.atomic(new Atomic() {
                    public Object atomically() {
                        increment();
                        return null;
                    }
                });
            }
        } else if (incoming instanceof String) {
            String message = (String) incoming;
            if (message.equals("GetCount")) {
                getContext().replyUnsafe(count.get());
            }
        }
    }
}