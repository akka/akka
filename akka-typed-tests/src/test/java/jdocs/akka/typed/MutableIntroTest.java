/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed;

//#imports
import akka.typed.ActorRef;
import akka.typed.Behavior;
import akka.typed.javadsl.Actor;
import akka.typed.javadsl.Actor.Receive;
import akka.typed.javadsl.ActorContext;
//#imports
import java.util.ArrayList;
import java.util.List;

public class MutableIntroTest {

  //#chatroom-actor
  public static class ChatRoom {
    //#chatroom-protocol
    static interface Command {}
    public static final class GetSession implements Command {
      public final String screenName;
      public final ActorRef<SessionEvent> replyTo;
      public GetSession(String screenName, ActorRef<SessionEvent> replyTo) {
        this.screenName = screenName;
        this.replyTo = replyTo;
      }
    }
    //#chatroom-protocol
    //#chatroom-behavior
    private static final class PostSessionMessage implements Command {
      public final String screenName;
      public final String message;
      public PostSessionMessage(String screenName, String message) {
        this.screenName = screenName;
        this.message = message;
      }
    }
    //#chatroom-behavior
    //#chatroom-protocol

    static interface SessionEvent {}
    public static final class SessionGranted implements SessionEvent {
      public final ActorRef<PostMessage> handle;
      public SessionGranted(ActorRef<PostMessage> handle) {
        this.handle = handle;
      }
    }
    public static final class SessionDenied implements SessionEvent {
      public final String reason;
      public SessionDenied(String reason) {
        this.reason = reason;
      }
    }
    public static final class MessagePosted implements SessionEvent {
      public final String screenName;
      public final String message;
      public MessagePosted(String screenName, String message) {
        this.screenName = screenName;
        this.message = message;
      }
    }

    public static final class PostMessage {
      public final String message;
      public PostMessage(String message) {
        this.message = message;
      }
    }
    //#chatroom-protocol
    //#chatroom-behavior

    public static Behavior<Command> behavior() {
      return Actor.mutable(ChatRoomBehavior::new);
    }

    public static class ChatRoomBehavior extends Actor.MutableBehavior<Command> {
      final ActorContext<Command> ctx;
      final List<ActorRef<SessionEvent>> sessions = new ArrayList<ActorRef<SessionEvent>>();

      public ChatRoomBehavior(ActorContext<Command> ctx) {
        this.ctx = ctx;
      }

      @Override
      public Receive<Command> createReceive() {
        return receiveBuilder()
          .onMessage(GetSession.class, getSession -> {
            ActorRef<PostMessage> wrapper = ctx.spawnAdapter(p ->
              new PostSessionMessage(getSession.screenName, p.message));
            getSession.replyTo.tell(new SessionGranted(wrapper));
            sessions.add(getSession.replyTo);
            return Actor.same();
          })
          .onMessage(PostSessionMessage.class, post -> {
            MessagePosted mp = new MessagePosted(post.screenName, post.message);
            sessions.forEach(s -> s.tell(mp));
            return this;
          })
          .build();
      }
    }

    //#chatroom-behavior
  }
  //#chatroom-actor

}
