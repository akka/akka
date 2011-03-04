package akka.stm.example;

import akka.stm.*;
import static akka.stm.StmUtils.retry;
import akka.actor.*;
import akka.util.FiniteDuration;
import java.util.concurrent.TimeUnit;

public class Brancher extends UntypedActor {
    TransactionFactory txFactory = new TransactionFactoryBuilder()
        .setBlockingAllowed(true)
        .setTrackReads(true)
        .setTimeout(new FiniteDuration(60, TimeUnit.SECONDS))
        .build();

    public void onReceive(Object message) throws Exception {
        if (message instanceof Branch) {
            Branch branch = (Branch) message;
            final Ref<Integer> left = branch.left;
            final Ref<Integer> right = branch.right;
            final double amount = branch.amount;
            new Atomic<Integer>(txFactory) {
                public Integer atomically() {
                    return new EitherOrElse<Integer>() {
                        public Integer either() {
                            if (left.get() < amount) {
                                System.out.println("not enough on left - retrying");
                                retry();
                            }
                            System.out.println("going left");
                            return left.get();
                        }
                        public Integer orElse() {
                            if (right.get() < amount) {
                                System.out.println("not enough on right - retrying");
                                retry();
                            }
                            System.out.println("going right");
                            return right.get();
                        }
                    }.execute();
                }
            }.execute();
        }
    }
}
