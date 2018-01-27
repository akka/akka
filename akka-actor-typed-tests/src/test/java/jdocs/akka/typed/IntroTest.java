/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package jdocs.akka.typed;

//#imports
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.AskPattern;
import akka.util.Timeout;

//#imports
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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

    public static final Behavior<Greet> greeter = Behaviors.immutable((ctx, msg) -> {
      System.out.println("Hello " + msg.whom + "!");
      msg.replyTo.tell(new Greeted(msg.whom));
      return Behaviors.same();
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
    static interface RoomCommand {}
    public static final class GetSession implements RoomCommand {
      public final String screenName;
      public final ActorRef<SessionEvent> replyTo;
      public GetSession(String screenName, ActorRef<SessionEvent> replyTo) {
        this.screenName = screenName;
        this.replyTo = replyTo;
      }
    }
    //#chatroom-protocol
    //#chatroom-behavior
    private static final class PublishSessionMessage implements RoomCommand {
      public final String screenName;
      public final String message;
      public PublishSessionMessage(String screenName, String message) {
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

    static interface SessionCommand {}
    public static final class PostMessage implements SessionCommand {
      public final String message;
      public PostMessage(String message) {
        this.message = message;
      }
    }
    private static final class NotifyClient implements SessionCommand {
      final MessagePosted message;
      NotifyClient(MessagePosted message) {
        this.message = message;
      }
    }
    //#chatroom-protocol
    //#chatroom-behavior

    public static Behavior<RoomCommand> behavior() {
      return chatRoom(new ArrayList<ActorRef<SessionCommand>>());
    }

    private static Behavior<RoomCommand> chatRoom(List<ActorRef<SessionCommand>> sessions) {
      return Behaviors.immutable(RoomCommand.class)
        .onMessage(GetSession.class, (ctx, getSession) -> {
          ActorRef<SessionEvent> client = getSession.replyTo;
          ActorRef<SessionCommand> ses = ctx.spawn(
              session(ctx.getSelf(), getSession.screenName, client),
              URLEncoder.encode(getSession.screenName, StandardCharsets.UTF_8.name()));
          // narrow to only expose PostMessage
          client.tell(new SessionGranted(ses.narrow()));
          List<ActorRef<SessionCommand>> newSessions = new ArrayList<>(sessions);
          newSessions.add(ses);
          return chatRoom(newSessions);
        })
        .onMessage(PublishSessionMessage.class, (ctx, pub) -> {
          NotifyClient notification =
              new NotifyClient((new MessagePosted(pub.screenName, pub.message)));
          sessions.forEach(s -> s.tell(notification));
          return Behaviors.same();
        })
        .build();
    }

    public static Behavior<ChatRoom.SessionCommand> session(
        ActorRef<RoomCommand> room,
        String screenName,
        ActorRef<SessionEvent> client) {
      return Behaviors.immutable(ChatRoom.SessionCommand.class)
          .onMessage(PostMessage.class, (ctx, post) -> {
            // from client, publish to others via the room
            room.tell(new PublishSessionMessage(screenName, post.message));
            return Behaviors.same();
          })
          .onMessage(NotifyClient.class, (ctx, notification) -> {
            // published from the room
            client.tell(notification.message);
            return Behaviors.same();
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
      return Behaviors.immutable(ChatRoom.SessionEvent.class)
        .onMessage(ChatRoom.SessionDenied.class, (ctx, msg) -> {
          System.out.println("cannot start chat room session: " + msg.reason);
          return Behaviors.stopped();
        })
        .onMessage(ChatRoom.SessionGranted.class, (ctx, msg) -> {
          msg.handle.tell(new ChatRoom.PostMessage("Hello World!"));
          return Behaviors.same();
        })
        .onMessage(ChatRoom.MessagePosted.class, (ctx, msg) -> {
          System.out.println("message has been posted by '" +
            msg.screenName +"': " + msg.message);
          return Behaviors.stopped();
        })
        .build();
    }

  }
  //#chatroom-gabbler

  public static void runChatRoom() throws Exception {

    //#chatroom-main
    Behavior<Void> main = Behaviors.deferred(ctx -> {
      ActorRef<ChatRoom.RoomCommand> chatRoom =
        ctx.spawn(ChatRoom.behavior(), "chatRoom");
      ActorRef<ChatRoom.SessionEvent> gabbler =
          ctx.spawn(Gabbler.behavior(), "gabbler");
      ctx.watch(gabbler);
      chatRoom.tell(new ChatRoom.GetSession("ol’ Gabbler", gabbler));

      return Behaviors.immutable(Void.class)
        .onSignal(Terminated.class, (c, sig) -> Behaviors.stopped())
        .build();
    });

    final ActorSystem<Void> system =
      ActorSystem.create(main, "ChatRoomDemo");

    Await.result(system.whenTerminated(), Duration.create(3, TimeUnit.SECONDS));
    //#chatroom-main
  }

}
