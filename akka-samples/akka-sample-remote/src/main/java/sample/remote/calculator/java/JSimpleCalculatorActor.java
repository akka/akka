/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.UntypedActor;

//#actor
public class JSimpleCalculatorActor extends UntypedActor {
  @Override
  public void onReceive(Object message) {

    if (message instanceof Op.Add) {
      Op.Add add = (Op.Add) message;
      System.out.println("Calculating " + add.getN1() + " + " + add.getN2());
      getSender()
          .tell(
              new Op.AddResult(add.getN1(), add.getN2(), add.getN1()
                  + add.getN2()), getSelf());

    } else if (message instanceof Op.Subtract) {
      Op.Subtract subtract = (Op.Subtract) message;
      System.out.println("Calculating " + subtract.getN1() + " - "
          + subtract.getN2());
      getSender().tell(
          new Op.SubtractResult(subtract.getN1(), subtract.getN2(),
              subtract.getN1() - subtract.getN2()), getSelf());

    } else {
      unhandled(message);
    }
  }
}
// #actor
