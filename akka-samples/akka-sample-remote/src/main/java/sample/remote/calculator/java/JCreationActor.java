/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.ActorRef;
import akka.actor.UntypedActor;

//#actor
public class JCreationActor extends UntypedActor {

  private final ActorRef remoteActor;

  public JCreationActor(ActorRef remoteActor) {
    this.remoteActor = remoteActor;
  }

  @Override
  public void onReceive(Object message) throws Exception {

    if (message instanceof Op.MathOp) {
      // send message to server actor
      remoteActor.tell(message, getSelf());

    } else if (message instanceof Op.MultiplicationResult) {
      Op.MultiplicationResult result = (Op.MultiplicationResult) message;
      System.out.printf("Mul result: %d * %d = %d\n",
          result.getN1(), result.getN2(), result.getResult());

    } else if (message instanceof Op.DivisionResult) {
      Op.DivisionResult result = (Op.DivisionResult) message;
      System.out.printf("Div result: %.0f / %d = %.2f\n",
          result.getN1(), result.getN2(), result.getResult());

    } else {
      unhandled(message);
    }
  }
}
//#actor
