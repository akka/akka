/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.UntypedActor;

//#actor
public class JAdvancedCalculatorActor extends UntypedActor {
  @Override
  public void onReceive(Object message) throws Exception {

    if (message instanceof Op.Multiply) {
      Op.Multiply multiply = (Op.Multiply) message;
      System.out.println("Calculating " + multiply.getN1() + " * "
          + multiply.getN2());
      getSender().tell(
          new Op.MultiplicationResult(multiply.getN1(), multiply.getN2(),
              multiply.getN1() * multiply.getN2()), getSelf());

    } else if (message instanceof Op.Divide) {
      Op.Divide divide = (Op.Divide) message;
      System.out.println("Calculating " + divide.getN1() + " / "
          + divide.getN2());
      getSender().tell(
          new Op.DivisionResult(divide.getN1(), divide.getN2(), divide.getN1()
              / divide.getN2()), getSelf());

    } else {
      unhandled(message);
    }
  }
}
// #actor
