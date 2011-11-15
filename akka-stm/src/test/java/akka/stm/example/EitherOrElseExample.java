package akka.stm.example;

import akka.stm.*;
import akka.actor.*;

public class EitherOrElseExample {
    public static void main(String[] args) {
        System.out.println();
        System.out.println("EitherOrElse example");
        System.out.println();

        ActorSystem application = new ActorSystem("UntypedTransactorExample");

        final Ref<Integer> left = new Ref<Integer>(100);
        final Ref<Integer> right = new Ref<Integer>(100);

        ActorRef brancher = application.actorOf(new Props().withCreator(Brancher.class));

        brancher.tell(new Branch(left, right, 500));

        new Atomic() {
            public Object atomically() {
                return right.set(right.get() + 1000);
            }
        }.execute();

        brancher.stop();
    }
}
