/**
 *  Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */
package sample.remote.calculator.java;

import akka.actor.ActorRef;
import akka.actor.ActorIdentity;
import akka.actor.Identify;
import akka.actor.UntypedActor;
import akka.actor.ReceiveTimeout;

//#actor
public class JLookupActor extends UntypedActor {

  private final String path;
  private ActorRef remoteActor = null;

  public JLookupActor(String path) {
    this.path = path;
    sendIdentifyRequest();
  }

  private void sendIdentifyRequest() {
    getContext().actorSelection(path).tell(new Identify(path), getSelf());
  }

  @Override
  public void onReceive(Object message) throws Exception {

    if (message instanceof ActorIdentity) {
      remoteActor = ((ActorIdentity) message).getRef();

    } else if (message.equals(ReceiveTimeout.getInstance())) {
      sendIdentifyRequest();

    } else if (remoteActor == null) {
      System.out.println("Not ready yet");

    } else if (message instanceof Op.MathOp) {
      // send message to server actor
      remoteActor.tell(message, getSelf());

    } else if (message instanceof Op.AddResult) {
      Op.AddResult result = (Op.AddResult) message;
      System.out.printf("Add result: %d + %d = %d\n", result.getN1(), 
        result.getN2(), result.getResult());

    } else if (message instanceof Op.SubtractResult) {
      Op.SubtractResult result = (Op.SubtractResult) message;
      System.out.printf("Sub result: %d - %d = %d\n", result.getN1(), 
        result.getN2(), result.getResult());

    } else {
      unhandled(message);
    }
  }
}
//#actor
