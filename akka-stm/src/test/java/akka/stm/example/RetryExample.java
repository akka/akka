package akka.stm.example;

import akka.actor.ActorSystem;
import akka.stm.*;
import akka.actor.*;

public class RetryExample {
    public static void main(String[] args) {
        System.out.println();
        System.out.println("Retry example");
        System.out.println();

        ActorSystem application = ActorSystem.create("RetryExample");

        final Ref<Double> account1 = new Ref<Double>(100.0);
        final Ref<Double> account2 = new Ref<Double>(100.0);

        ActorRef transferer = application.actorOf(new Props().withCreator(Transferer.class));

        transferer.tell(new Transfer(account1, account2, 500.0));
        // Transferer: not enough money - retrying

        new Atomic() {
            public Object atomically() {
                return account1.set(account1.get() + 2000);
            }
        }.execute();
        // Transferer: transferring

        Double acc1 = new Atomic<Double>() {
            public Double atomically() {
                return account1.get();
            }
        }.execute();

        Double acc2 = new Atomic<Double>() {
            public Double atomically() {
                return account2.get();
            }
        }.execute();

        System.out.println("Account 1: " + acc1);
        // Account 1: 1600.0

        System.out.println("Account 2: " + acc2);
        // Account 2: 600.0

        transferer.stop();
    }
}
