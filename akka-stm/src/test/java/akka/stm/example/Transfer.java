package akka.stm.example;

import akka.stm.*;

public class Transfer {
    public Ref<Double> from;
    public Ref<Double> to;
    public double amount;

    public Transfer(Ref<Double> from, Ref<Double> to, double amount) {
        this.from = from;
        this.to = to;
        this.amount = amount;
    }
}
