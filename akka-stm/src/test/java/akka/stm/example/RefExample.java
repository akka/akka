package akka.stm.example;

import akka.stm.*;

public class RefExample {
    public static void main(String[] args) {
        System.out.println();
        System.out.println("Ref example");
        System.out.println();

        final Ref<Integer> ref = new Ref<Integer>(0);

        Integer value1 = new Atomic<Integer>() {
            public Integer atomically() {
                return ref.get();
            }
        }.execute();

        System.out.println("value 1: " + value1);

        new Atomic() {
            public Object atomically() {
                return ref.set(5);
            }
        }.execute();

        Integer value2 = new Atomic<Integer>() {
            public Integer atomically() {
                return ref.get();
            }
        }.execute();

        System.out.println("value 2: " + value2);
    }
}
