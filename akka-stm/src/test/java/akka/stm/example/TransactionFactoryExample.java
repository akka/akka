package akka.stm.example;

import akka.stm.*;

import org.multiverse.api.ThreadLocalTransaction;
import org.multiverse.api.TransactionConfiguration;

public class TransactionFactoryExample {
    public static void main(String[] args) {
        System.out.println();
        System.out.println("TransactionFactory example");
        System.out.println();

        TransactionFactory txFactory = new TransactionFactoryBuilder()
            .setFamilyName("example")
            .setReadonly(true)
            .build();

        new Atomic(txFactory) {
            public Object atomically() {
                // check config has been passed to multiverse
                TransactionConfiguration config = ThreadLocalTransaction.getThreadLocalTransaction().getConfiguration();
                System.out.println("family name: " + config.getFamilyName());
                System.out.println("readonly: " + config.isReadonly());
                return null;
            }
        }.execute();
    }
}
