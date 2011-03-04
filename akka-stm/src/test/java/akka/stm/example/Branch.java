package akka.stm.example;

import akka.stm.*;

public class Branch {
    public Ref<Integer> left;
    public Ref<Integer> right;
    public int amount;

    public Branch(Ref<Integer> left, Ref<Integer> right, int amount) {
        this.left = left;
        this.right = right;
        this.amount = amount;
    }
}
