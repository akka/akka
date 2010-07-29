package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;

public class ReplyUntypedActor extends UntypedActor {
  public void onReceive(Object message, UntypedActorRef context) throws Exception {
        if (message instanceof String) {
      String str = (String)message;

            if (str.equals("ReplyToSendOneWayUsingReply")) {
              context.replyUnsafe("Reply");
      } else if (str.equals("ReplyToSendOneWayUsingSender")) {
        context.getSender().get().sendOneWay("Reply");

      } else if (str.equals("ReplyToSendRequestReplyUsingReply")) {
              context.replyUnsafe("Reply");
      } else if (str.equals("ReplyToSendRequestReplyUsingFuture")) {
        context.getSenderFuture().get().completeWithResult("Reply");

      } else if (str.equals("ReplyToSendRequestReplyFutureUsingReply")) {
              context.replyUnsafe("Reply");
      } else if (str.equals("ReplyToSendRequestReplyFutureUsingFuture")) {
        context.getSenderFuture().get().completeWithResult("Reply");

      } else throw new IllegalArgumentException("Unknown message: " + str);
        } else throw new IllegalArgumentException("Unknown message: " + message);
  }
}
