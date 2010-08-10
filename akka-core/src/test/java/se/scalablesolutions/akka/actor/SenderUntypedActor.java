package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.*;
import se.scalablesolutions.akka.dispatch.CompletableFuture;

public class SenderUntypedActor extends UntypedActor {
  private UntypedActorRef replyActor = null;
  
  public void onReceive(Object message) throws Exception {
    if (message instanceof UntypedActorRef) replyActor = (UntypedActorRef)message;
    else if (message instanceof String) {
      if (replyActor == null) throw new IllegalStateException("Need to receive a ReplyUntypedActor before any other message.");
      String str = (String)message;

      if (str.equals("ReplyToSendOneWayUsingReply")) {
        replyActor.sendOneWay("ReplyToSendOneWayUsingReply", getContext());
      } else if (str.equals("ReplyToSendOneWayUsingSender")) {
        replyActor.sendOneWay("ReplyToSendOneWayUsingSender", getContext());

      } else if (str.equals("ReplyToSendRequestReplyUsingReply")) {
        UntypedActorTestState.log = (String)replyActor.sendRequestReply("ReplyToSendRequestReplyUsingReply", getContext());
        UntypedActorTestState.finished.await();
      } else if (str.equals("ReplyToSendRequestReplyUsingFuture")) {
        UntypedActorTestState.log = (String)replyActor.sendRequestReply("ReplyToSendRequestReplyUsingFuture", getContext());
        UntypedActorTestState.finished.await();

      } else if (str.equals("ReplyToSendRequestReplyFutureUsingReply")) {
        CompletableFuture future = (CompletableFuture)replyActor.sendRequestReplyFuture("ReplyToSendRequestReplyFutureUsingReply", getContext());
        future.await();
        UntypedActorTestState.log = (String)future.result().get();
        UntypedActorTestState.finished.await();
      } else if (str.equals("ReplyToSendRequestReplyFutureUsingFuture")) {
        CompletableFuture future = (CompletableFuture)replyActor.sendRequestReplyFuture("ReplyToSendRequestReplyFutureUsingFuture", getContext());
        future.await();
        UntypedActorTestState.log = (String)future.result().get();
        UntypedActorTestState.finished.await();

          } else if (str.equals("Reply")) {
        UntypedActorTestState.log = "Reply";
        UntypedActorTestState.finished.await();
      }
    }
  }
}
