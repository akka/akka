/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.akka.typed;

//#imports
import akka.typed.ActorRef;
import akka.typed.ActorSystem;
import akka.typed.Behavior;
import akka.typed.Terminated;
import akka.typed.javadsl.Actor;
import akka.typed.javadsl.AskPattern;
import akka.util.Timeout;

//#imports
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import scala.concurrent.Await;
import scala.concurrent.duration.Duration;

public class IntroTest {

  //#hello-world-actor
  public abstract static class HelloWorld {
    //no instances of this class, it's only a name space for messages
    // and static methods
    private HelloWorld() {
    }

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

    public static final Behavior<Greet> greeter = Actor.immutable((ctx, msg) -> {
      System.out.println("Hello " + msg.whom + "!");
      msg.replyTo.tell(new Greeted(msg.whom));
      return Actor.same();
    });
  }
  //#hello-world-actor

  public static void main(String[] args) {
    //#hello-world
    final ActorSystem<HelloWorld.Greet> system =
      ActorSystem.create(HelloWorld.greeter, "hello");

    final CompletionStage<HelloWorld.Greeted> reply =
      AskPattern.ask(system,
        (ActorRef<HelloWorld.Greeted> replyTo) -> new HelloWorld.Greet("world", replyTo),
        new Timeout(3, TimeUnit.SECONDS), system.scheduler());

    reply.thenAccept(greeting -> {
      System.out.println("result: " + greeting.whom);
      system.terminate();
    });
    //#hello-world
  }

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
      return chatRoom(new ArrayList<ActorRef<SessionEvent>>());
    }

    private static Behavior<Command> chatRoom(List<ActorRef<SessionEvent>> sessions) {
      return Actor.immutable(Command.class)
        .onMessage(GetSession.class, (ctx, getSession) -> {
          ActorRef<PostMessage> wrapper = ctx.spawnAdapter(p ->
            new PostSessionMessage(getSession.screenName, p.message));
          getSession.replyTo.tell(new SessionGranted(wrapper));
          List<ActorRef<SessionEvent>> newSessions =
            new ArrayList<ActorRef<SessionEvent>>(sessions);
          newSessions.add(getSession.replyTo);
          return chatRoom(newSessions);
        })
        .onMessage(PostSessionMessage.class, (ctx, post) -> {
          MessagePosted mp = new MessagePosted(post.screenName, post.message);
          sessions.forEach(s -> s.tell(mp));
          return Actor.same();
        })
        .build();
    }
    //#chatroom-behavior

  }
  //#chatroom-actor

  //#chatroom-gabbler
  public static abstract class Gabbler {
    private Gabbler() {
    }

    public static Behavior<ChatRoom.SessionEvent> behavior() {
      return Actor.immutable(ChatRoom.SessionEvent.class)
        .onMessage(ChatRoom.SessionDenied.class, (ctx, msg) -> {
          System.out.println("cannot start chat room session: " + msg.reason);
          return Actor.stopped();
        })
        .onMessage(ChatRoom.SessionGranted.class, (ctx, msg) -> {
          msg.handle.tell(new ChatRoom.PostMessage("Hello World!"));
          return Actor.same();
        })
        .onMessage(ChatRoom.MessagePosted.class, (ctx, msg) -> {
          System.out.println("message has been posted by '" +
            msg.screenName +"': " + msg.message);
          return Actor.stopped();
        })
        .build();
    }

  }
  //#chatroom-gabbler

  public static void runChatRoom() throws Exception {

    //#chatroom-main
    Behavior<Void> main = Actor.deferred(ctx -> {
      ActorRef<ChatRoom.Command> chatRoom =
        ctx.spawn(ChatRoom.behavior(), "chatRoom");
      ActorRef<ChatRoom.SessionEvent> gabbler =
          ctx.spawn(Gabbler.behavior(), "gabbler");
      ctx.watch(gabbler);
      chatRoom.tell(new ChatRoom.GetSession("ol’ Gabbler", gabbler));

      return Actor.immutable(Void.class)
        .onSignal(Terminated.class, (c, sig) -> Actor.stopped())
        .build();
    });

    final ActorSystem<Void> system =
      ActorSystem.create(main, "ChatRoomDemo");

    Await.result(system.whenTerminated(), Duration.create(3, TimeUnit.SECONDS));
    //#chatroom-main
  }

}
