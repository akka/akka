/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;

/**
 * Here is an example on how to create and use an UntypedActor.
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
public class SampleUntypedActor extends UntypedActor {

  public void onReceive(Object message, UntypedActorRef self) throws Exception {
        if (message instanceof String) {
      String msg = (String)message;
            System.out.println("Received message: " + msg);

          if (msg.equals("UseReply")) {
                // Reply to original sender of message using the 'replyUnsafe' method
                self.replyUnsafe(msg + ":" + self.getUuid());
          
          } else if (msg.equals("UseSender") && self.getSender().isDefined()) { 
                // Reply to original sender of message using the sender reference
                // also passing along my own refererence (the self)
                self.getSender().get().sendOneWay(msg, self); 

          } else if (msg.equals("UseSenderFuture") && self.getSenderFuture().isDefined()) {     
                // Reply to original sender of message using the sender future reference
                self.getSenderFuture().get().completeWithResult(msg);

          } else if (msg.equals("SendToSelf")) {
                // Send fire-forget message to the actor itself recursively
                self.sendOneWay(msg);

          } else if (msg.equals("ForwardMessage")) {
                // Retreive an actor from the ActorRegistry by ID and get an ActorRef back
                ActorRef actorRef = ActorRegistry.actorsFor("some-actor-id")[0];
                // Wrap the ActorRef in an UntypedActorRef and forward the message to this actor
            UntypedActorRef.wrap(actorRef).forward(msg, self);
              
      } else throw new IllegalArgumentException("Unknown message: " + message);
    } else throw new IllegalArgumentException("Unknown message: " + message);
  }
  
  public static void main(String[] args) {
    UntypedActorRef actor = UntypedActor.actorOf(SampleUntypedActor.class);
    actor.start();
    actor.sendOneWay("SendToSelf");
    actor.stop();
  }
}
