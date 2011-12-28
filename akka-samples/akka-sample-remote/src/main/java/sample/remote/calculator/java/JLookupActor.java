/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.UntypedActor;

public class JLookupActor extends UntypedActor {

    @Override
    public void onReceive(Object message) throws Exception {
        if (message instanceof InternalMsg.MathOpMsg) {
            InternalMsg.MathOpMsg msg = (InternalMsg.MathOpMsg) message;
            msg.getActor().tell(msg.getMathOp(), getSelf());
        } else if (message instanceof Op.MathResult) {
            if (message instanceof Op.AddResult) {
                Op.AddResult result = (Op.AddResult) message;
                System.out.println("Add result: " + result.getN1() + " + " +
                        result.getN2() + " = " + result.getResult());
            } else if (message instanceof Op.SubtractResult) {
                Op.SubtractResult result = (Op.SubtractResult) message;
                System.out.println("Sub result: " + result.getN1() + " - " +
                        result.getN2() + " = " + result.getResult());
            }
        }
    }
}
