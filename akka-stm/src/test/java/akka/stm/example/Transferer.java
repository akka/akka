package akka.stm.example;

import akka.stm.*;
import static akka.stm.StmUtils.retry;
import akka.actor.*;
import akka.util.FiniteDuration;
import java.util.concurrent.TimeUnit;

public class Transferer extends UntypedActor {
    TransactionFactory txFactory = new TransactionFactoryBuilder()
        .setBlockingAllowed(true)
        .setTrackReads(true)
        .setTimeout(new FiniteDuration(60, TimeUnit.SECONDS))
        .build();

    public void onReceive(Object message) throws Exception {
        if (message instanceof Transfer) {
            Transfer transfer = (Transfer) message;
            final Ref<Double> from = transfer.from;
            final Ref<Double> to = transfer.to;
            final double amount = transfer.amount;
            new Atomic(txFactory) {
                public Object atomically() {
                    if (from.get() < amount) {
                        System.out.println("Transferer: not enough money - retrying");
                        retry();
                    }
                    System.out.println("Transferer: transferring");
                    from.set(from.get() - amount);
                    to.set(to.get() + amount);
                    return null;
                }
            }.execute();
        }
    }
}
