/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

// #imports
import akka.NotUsed;
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
// #imports
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class OOIntroTest {

  // #chatroom-actor
  public static class ChatRoom {
    // #chatroom-protocol
    static interface RoomCommand {}

    public static final class GetSession implements RoomCommand {
      public final String screenName;
      public final ActorRef<SessionEvent> replyTo;

      public GetSession(String screenName, ActorRef<SessionEvent> replyTo) {
        this.screenName = screenName;
        this.replyTo = replyTo;
      }
    }
    // #chatroom-protocol
    // #chatroom-behavior
    private static final class PublishSessionMessage implements RoomCommand {
      public final String screenName;
      public final String message;

      public PublishSessionMessage(String screenName, String message) {
        this.screenName = screenName;
        this.message = message;
      }
    }
    // #chatroom-behavior
    // #chatroom-protocol

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
    // #chatroom-protocol
    // #chatroom-behavior

    public static Behavior<RoomCommand> behavior() {
      return Behaviors.setup(ChatRoomBehavior::new);
    }

    public static class ChatRoomBehavior extends AbstractBehavior<RoomCommand> {
      final ActorContext<RoomCommand> context;
      final List<ActorRef<SessionCommand>> sessions = new ArrayList<>();

      public ChatRoomBehavior(ActorContext<RoomCommand> context) {
        this.context = context;
      }

      @Override
      public Receive<RoomCommand> createReceive() {
        return receiveBuilder()
            .onMessage(
                GetSession.class,
                getSession -> {
                  ActorRef<SessionEvent> client = getSession.replyTo;
                  ActorRef<SessionCommand> ses =
                      context.spawn(
                          session(context.getSelf(), getSession.screenName, client),
                          URLEncoder.encode(getSession.screenName, StandardCharsets.UTF_8.name()));
                  // narrow to only expose PostMessage
                  client.tell(new SessionGranted(ses.narrow()));
                  sessions.add(ses);
                  return this;
                })
            .onMessage(
                PublishSessionMessage.class,
                pub -> {
                  NotifyClient notification =
                      new NotifyClient((new MessagePosted(pub.screenName, pub.message)));
                  sessions.forEach(s -> s.tell(notification));
                  return this;
                })
            .build();
      }
    }

    public static Behavior<ChatRoom.SessionCommand> session(
        ActorRef<RoomCommand> room, String screenName, ActorRef<SessionEvent> client) {
      return Behaviors.receive(ChatRoom.SessionCommand.class)
          .onMessage(
              PostMessage.class,
              (context, post) -> {
                // from client, publish to others via the room
                room.tell(new PublishSessionMessage(screenName, post.message));
                return Behaviors.same();
              })
          .onMessage(
              NotifyClient.class,
              (context, notification) -> {
                // published from the room
                client.tell(notification.message);
                return Behaviors.same();
              })
          .build();
    }
    // #chatroom-behavior
  }
  // #chatroom-actor

  // #chatroom-gabbler
  public abstract static class Gabbler {
    private Gabbler() {}

    public static Behavior<ChatRoom.SessionEvent> behavior() {
      return Behaviors.receive(ChatRoom.SessionEvent.class)
          .onMessage(
              ChatRoom.SessionDenied.class,
              (context, message) -> {
                System.out.println("cannot start chat room session: " + message.reason);
                return Behaviors.stopped();
              })
          .onMessage(
              ChatRoom.SessionGranted.class,
              (context, message) -> {
                message.handle.tell(new ChatRoom.PostMessage("Hello World!"));
                return Behaviors.same();
              })
          .onMessage(
              ChatRoom.MessagePosted.class,
              (context, message) -> {
                System.out.println(
                    "message has been posted by '" + message.screenName + "': " + message.message);
                return Behaviors.stopped();
              })
          .build();
    }
  }
  // #chatroom-gabbler

  public static void runChatRoom() throws Exception {

    // #chatroom-main
    Behavior<Void> main =
        Behaviors.setup(
            context -> {
              ActorRef<ChatRoom.RoomCommand> chatRoom =
                  context.spawn(ChatRoom.behavior(), "chatRoom");
              ActorRef<ChatRoom.SessionEvent> gabbler =
                  context.spawn(Gabbler.behavior(), "gabbler");
              context.watch(gabbler);
              chatRoom.tell(new ChatRoom.GetSession("ol’ Gabbler", gabbler));

              return Behaviors.<Void>receiveSignal(
                  (c, sig) -> {
                    if (sig instanceof Terminated) return Behaviors.stopped();
                    else return Behaviors.unhandled();
                  });
            });

    final ActorSystem<Void> system = ActorSystem.create(main, "ChatRoomDemo");
    // #chatroom-main
  }
}
