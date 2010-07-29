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

  public void onReceive(Object message, UntypedActorRef context) throws Exception {
  	if (message instanceof String) {
      String msg = (String)message;
	    System.out.println("Received message: " + msg);

  	  if (msg.equals("UseReply")) {
  	  	// Reply to original sender of message using the 'replyUnsafe' method
  	  	context.replyUnsafe(msg + ":" + context.getUuid());
	  
  	  } else if (msg.equals("UseSender") && context.getSender().isDefined()) {	
  	  	// Reply to original sender of message using the sender reference
  	  	// also passing along my own refererence (the context)
  	  	context.getSender().get().sendOneWay(msg, context); 

  	  } else if (msg.equals("UseSenderFuture") && context.getSenderFuture().isDefined()) {	
  	  	// Reply to original sender of message using the sender future reference
  	  	context.getSenderFuture().get().completeWithResult(msg);

  	  } else if (msg.equals("SendToSelf")) {
  	  	// Send fire-forget message to the actor itself recursively
  	  	context.sendOneWay(msg);

  	  } else if (msg.equals("ForwardMessage")) {
  	  	// Retreive an actor from the ActorRegistry by ID and get an ActorRef back
  	  	ActorRef actorRef = ActorRegistry.actorsFor("some-actor-id").head();
  	  	// Wrap the ActorRef in an UntypedActorRef and forward the message to this actor
  	    UntypedActorRef.wrap(actorRef).forward(msg, context);
  	      
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
