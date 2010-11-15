package akka.transactor.example;

import akka.transactor.Coordinated;
import akka.transactor.Atomically;
import akka.actor.ActorRef;
import akka.actor.UntypedActor;
import akka.stm.Ref;

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
                coordinated.atomic(new Atomically() {
                    public void atomically() {
                        increment();
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
