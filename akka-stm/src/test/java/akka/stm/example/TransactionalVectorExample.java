package akka.stm.example;

import akka.stm.*;

public class TransactionalVectorExample {
    public static void main(String[] args) {
        System.out.println();
        System.out.println("TransactionalVector example");
        System.out.println();

        final TransactionalVector<Address> addresses = new TransactionalVector<Address>();

        // fill addresses vector (in a transaction)
        new Atomic() {
            public Object atomically() {
                addresses.add(new Address("somewhere"));
                addresses.add(new Address("somewhere else"));
                return null;
            }
        }.execute();

        System.out.println("addresses: " + addresses);

        // access addresses vector (in a transaction)
        Address address = new Atomic<Address>() {
            public Address atomically() {
                return addresses.get(0);
            }
        }.execute();

        System.out.println("address: " + address);
    }
}
