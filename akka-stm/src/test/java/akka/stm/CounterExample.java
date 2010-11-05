package akka.stm;

import akka.stm.Ref;
import akka.stm.local.Atomic;

public class CounterExample {
    final static Ref<Integer> ref = new Ref<Integer>(0);

    public static int counter() {
        return new Atomic<Integer>() {
            public Integer atomically() {
                int inc = ref.get() + 1;
                ref.set(inc);
                return inc;
            }
        }.execute();
    }

    public static void main(String[] args) {
        System.out.println();
        System.out.println("Counter example");
        System.out.println();
        System.out.println("counter 1: " + counter());
        System.out.println("counter 2: " + counter());
    }
}
