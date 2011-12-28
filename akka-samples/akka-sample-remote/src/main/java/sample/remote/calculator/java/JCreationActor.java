/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.UntypedActor;

import java.text.DecimalFormat;
import java.text.NumberFormat;

public class JCreationActor extends UntypedActor {
    private static final NumberFormat formatter = new DecimalFormat("#0.00");

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof InternalMsg.MathOpMsg) {
            InternalMsg.MathOpMsg msg = (InternalMsg.MathOpMsg) message;
            msg.getActor().tell(msg.getMathOp(), getSelf());
        } else if (message instanceof Op.MathResult) {
            if (message instanceof Op.MultiplicationResult) {
                Op.MultiplicationResult result = (Op.MultiplicationResult) message;
                System.out.println("Mul result: " + result.getN1() + " * " +
                        result.getN2() + " = " + result.getResult());
            } else if (message instanceof Op.DivisionResult) {
                Op.DivisionResult result = (Op.DivisionResult) message;
                System.out.println("Div result: " + result.getN1() + " / " +
                        result.getN2() + " = " + formatter.format(result.getResult()));
            }
        }
    }
}
