package akka.stm.example;

import akka.stm.*;

public class TransactionalMapExample {
    public static void main(String[] args) {
        System.out.println();
        System.out.println("TransactionalMap example");
        System.out.println();

        final TransactionalMap<String, User> users = new TransactionalMap<String, User>();

        // fill users map (in a transaction)
        new Atomic() {
            public Object atomically() {
                users.put("bill", new User("bill"));
                users.put("mary", new User("mary"));
                users.put("john", new User("john"));
                return null;
            }
        }.execute();

        System.out.println("users: " + users);

        // access users map (in a transaction)
        User user = new Atomic<User>() {
            public User atomically() {
                return users.get("bill").get();
            }
        }.execute();

        System.out.println("user: " + user);
    }
}
