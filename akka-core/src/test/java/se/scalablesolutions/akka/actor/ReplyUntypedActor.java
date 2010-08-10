package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;

public class ReplyUntypedActor extends UntypedActor {
  public void onReceive(Object message) throws Exception {
    if (message instanceof String) {
      String str = (String)message;
      if (str.equals("ReplyToSendOneWayUsingReply")) {
        getContext().replyUnsafe("Reply");
      } else if (str.equals("ReplyToSendOneWayUsingSender")) {
        getContext().getSender().get().sendOneWay("Reply");
      } else if (str.equals("ReplyToSendRequestReplyUsingReply")) {
        getContext().replyUnsafe("Reply");
      } else if (str.equals("ReplyToSendRequestReplyUsingFuture")) {
        getContext().getSenderFuture().get().completeWithResult("Reply");
      } else if (str.equals("ReplyToSendRequestReplyFutureUsingReply")) {
        getContext().replyUnsafe("Reply");
      } else if (str.equals("ReplyToSendRequestReplyFutureUsingFuture")) {
        getContext().getSenderFuture().get().completeWithResult("Reply");
      } else throw new IllegalArgumentException("Unknown message: " + str);
    } else throw new IllegalArgumentException("Unknown message: " + message);
  }
}
