/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed;

//#imports
import akka.typed.ActorRef;
import akka.typed.Behavior;
import akka.typed.ExtensibleBehavior;
import akka.typed.Signal;
import akka.typed.javadsl.Actor;
import akka.typed.javadsl.ActorContext;
//#imports
import java.util.ArrayList;
import java.util.List;

public class IntroSpec {

  //#hello-world-actor
  public static class HelloWorld {
    public static final class Greet{
      public final String whom;
      public final ActorRef<Greeted> replyTo;

      public Greet(String whom, ActorRef<Greeted> replyTo) {
        this.whom = whom;
        this.replyTo = replyTo;
      }
    }

    public static final class Greeted {
      public final String whom;

      public Greeted(String whom) {
        this.whom = whom;
      }
    }

    public static final Behavior<Greet> greeter = Actor.stateless((ctx, msg) -> {
      System.out.println("Hello " + msg.whom + "!");
      msg.replyTo.tell(new Greeted(msg.whom));
    });
  }
  //#hello-world-actor

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

    public static Behavior<Command> chatRoom() {
      return chatRoom(new ArrayList<ActorRef<SessionEvent>>());
    }

    private static Behavior<Command> chatRoom(List<ActorRef<SessionEvent>> sessions) {
      return Actor.stateful((ctx, msg) -> {
        if (msg instanceof GetSession) {
          GetSession getSession = (GetSession) msg;
          ActorRef<PostMessage> wrapper = ctx.createAdapter(p ->
            new PostSessionMessage(getSession.screenName, p.message));
          getSession.replyTo.tell(new SessionGranted(wrapper));
          // TODO mutable collection :(
          List<ActorRef<SessionEvent>> newSessions = new ArrayList<ActorRef<SessionEvent>>(sessions);
          newSessions.add(getSession.replyTo);
          return chatRoom(newSessions);
        } else if (msg instanceof PostSessionMessage) {
          PostSessionMessage post = (PostSessionMessage) msg;
          MessagePosted mp = new MessagePosted(post.screenName, post.message);
          sessions.forEach(s -> s.tell(mp));
          return Actor.same();
        } else {
          return Actor.unhandled();
        }
      });
    }
    //#chatroom-behavior

    public static Behavior<Command> mutableChatRoom() {
      return Actor.deferred(c -> {
        final List<ActorRef<SessionEvent>> sessions = new ArrayList<ActorRef<SessionEvent>>();
        int postCount = 0;

        return Actor.stateful((ctx, msg) -> {
          if (msg instanceof GetSession) {
            GetSession getSession = (GetSession) msg;
            ActorRef<PostMessage> wrapper = ctx.createAdapter(p ->
              new PostSessionMessage(getSession.screenName, p.message));
            getSession.replyTo.tell(new SessionGranted(wrapper));
            sessions.add(getSession.replyTo);
            return Actor.same();
          } else if (msg instanceof PostSessionMessage) {
            PostSessionMessage post = (PostSessionMessage) msg;
            MessagePosted mp = new MessagePosted(post.screenName, post.message);
            // Local variable postCount defined in an enclosing scope must be final or effectively final
            //postCount += 1;
            sessions.forEach(s -> s.tell(mp));
            return Actor.same();
          } else {
            return Actor.unhandled();
          }
        });
      });
    }

    public static Behavior<Command> mutableChatRoom2() {
      // TODO Type safety: The expression of type IntroSpec.ChatRoom.MutableChatRoom2 needs unchecked conversion to
      //      conform to Behavior<IntroSpec.ChatRoom.Command>
      return Actor.deferred(ctx -> new MutableChatRoom2());
    }

    public static class MutableChatRoom2 extends ExtensibleBehavior<Command> {
      final List<ActorRef<SessionEvent>> sessions = new ArrayList<ActorRef<SessionEvent>>();
      int postCount = 0;

      @Override
      public Behavior<Command> message(akka.typed.ActorContext<Command> ctx, Command msg) {
        if (msg instanceof GetSession) {
          GetSession getSession = (GetSession) msg;
          ActorRef<PostMessage> wrapper = ctx.createAdapter(p ->
            new PostSessionMessage(getSession.screenName, p.message));
          getSession.replyTo.tell(new SessionGranted(wrapper));
          sessions.add(getSession.replyTo);
          return Actor.same();
        } else if (msg instanceof PostSessionMessage) {
          PostSessionMessage post = (PostSessionMessage) msg;
          MessagePosted mp = new MessagePosted(post.screenName, post.message);
          postCount += 1;
          sessions.forEach(s -> s.tell(mp));
          return Actor.same();
        } else {
          return Actor.unhandled();
        }
      }

      @Override
      public Behavior<Command> management(akka.typed.ActorContext<Command> ctx, Signal msg) {
        return Actor.same();
      }
    }

    public static Behavior<Command> mutableChatRoom3() {
      return Actor.mutable(ctx -> new MutableChatRoom3(ctx));
    }

    public static class MutableChatRoom3 extends Actor.MutableBehavior<Command> {
      final ActorContext<Command> ctx;
      final List<ActorRef<SessionEvent>> sessions = new ArrayList<ActorRef<SessionEvent>>();

      public MutableChatRoom3(ActorContext<Command> ctx) {
        this.ctx = ctx;
      }

      @Override
      public Behavior<Command> onMessage(Command msg) {
        if (msg instanceof GetSession) {
          GetSession getSession = (GetSession) msg;
          ActorRef<PostMessage> wrapper = ctx.createAdapter(p ->
            new PostSessionMessage(getSession.screenName, p.message));
          getSession.replyTo.tell(new SessionGranted(wrapper));
          sessions.add(getSession.replyTo);
          return Actor.same();
        } else if (msg instanceof PostSessionMessage) {
          PostSessionMessage post = (PostSessionMessage) msg;
          MessagePosted mp = new MessagePosted(post.screenName, post.message);
          sessions.forEach(s -> s.tell(mp));
          return Actor.same();
        } else {
          return Actor.unhandled();
        }
      }

    }


  }
  //#chatroom-actor

}
