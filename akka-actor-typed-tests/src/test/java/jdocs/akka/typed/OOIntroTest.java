/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.akka.typed;

// #imports
import akka.actor.typed.ActorRef;
import akka.actor.typed.ActorSystem;
import akka.actor.typed.Behavior;
import akka.actor.typed.Terminated;
import akka.actor.typed.javadsl.*;
// #imports
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class OOIntroTest {

  // #chatroom-behavior
  public static class ChatRoom {
    // #chatroom-behavior
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

    public static Behavior<RoomCommand> create() {
      return Behaviors.setup(ChatRoomBehavior::new);
    }

    public static class ChatRoomBehavior extends AbstractBehavior<RoomCommand> {
      final ActorContext<RoomCommand> context;
      final List<ActorRef<SessionCommand>> sessions = new ArrayList<>();

      private ChatRoomBehavior(ActorContext<RoomCommand> context) {
        this.context = context;
      }

      @Override
      public Receive<RoomCommand> createReceive() {
        ReceiveBuilder<RoomCommand> builder = newReceiveBuilder();

        builder.onMessage(GetSession.class, this::onGetSession);
        builder.onMessage(PublishSessionMessage.class, this::onPublishSessionMessage);

        return builder.build();
      }

      private Behavior<RoomCommand> onGetSession(GetSession getSession)
          throws UnsupportedEncodingException {
        ActorRef<SessionEvent> client = getSession.replyTo;
        ActorRef<SessionCommand> ses =
            context.spawn(
                new SessionBehavior(context.getSelf(), getSession.screenName, client),
                URLEncoder.encode(getSession.screenName, StandardCharsets.UTF_8.name()));
        // narrow to only expose PostMessage
        client.tell(new SessionGranted(ses.narrow()));
        sessions.add(ses);
        return this;
      }

      private Behavior<RoomCommand> onPublishSessionMessage(PublishSessionMessage pub) {
        NotifyClient notification =
            new NotifyClient((new MessagePosted(pub.screenName, pub.message)));
        sessions.forEach(s -> s.tell(notification));
        return this;
      }
    }

    static class SessionBehavior extends AbstractBehavior<ChatRoom.SessionCommand> {
      private final ActorRef<RoomCommand> room;
      private final String screenName;
      private final ActorRef<SessionEvent> client;

      SessionBehavior(
          ActorRef<RoomCommand> room, String screenName, ActorRef<SessionEvent> client) {
        this.room = room;
        this.screenName = screenName;
        this.client = client;
      }

      @Override
      public Receive<SessionCommand> createReceive() {
        return newReceiveBuilder()
            .onMessage(PostMessage.class, this::onPostMessage)
            .onMessage(NotifyClient.class, this::onNotifyClient)
            .build();
      }

      private Behavior<SessionCommand> onPostMessage(PostMessage post) {
        // from client, publish to others via the room
        room.tell(new PublishSessionMessage(screenName, post.message));
        return Behaviors.same();
      }

      private Behavior<SessionCommand> onNotifyClient(NotifyClient notification) {
        // published from the room
        client.tell(notification.message);
        return Behaviors.same();
      }
    }
  }
  // #chatroom-behavior

  // #chatroom-gabbler
  public static class Gabbler extends AbstractBehavior<ChatRoom.SessionEvent> {
    public static Behavior<ChatRoom.SessionEvent> create() {
      return Behaviors.setup(Gabbler::new);
    }

    private ActorContext<ChatRoom.SessionEvent> context;

    private Gabbler(ActorContext<ChatRoom.SessionEvent> context) {
      this.context = context;
    }

    @Override
    public Receive<ChatRoom.SessionEvent> createReceive() {
      ReceiveBuilder<ChatRoom.SessionEvent> builder = newReceiveBuilder();
      return builder
          .onMessage(ChatRoom.SessionDenied.class, this::onSessionDenied)
          .onMessage(ChatRoom.SessionGranted.class, this::onSessionGranted)
          .onMessage(ChatRoom.MessagePosted.class, this::onMessagePosted)
          .build();
    }

    private Behavior<ChatRoom.SessionEvent> onSessionDenied(ChatRoom.SessionDenied message) {
      context.getLog().info("cannot start chat room session: {}", message.reason);
      return Behaviors.stopped();
    }

    private Behavior<ChatRoom.SessionEvent> onSessionGranted(ChatRoom.SessionGranted message) {
      message.handle.tell(new ChatRoom.PostMessage("Hello World!"));
      return Behaviors.same();
    }

    private Behavior<ChatRoom.SessionEvent> onMessagePosted(ChatRoom.MessagePosted message) {
      context
          .getLog()
          .info("message has been posted by '{}': {}", message.screenName, message.message);
      return Behaviors.stopped();
    }
  }
  // #chatroom-gabbler

  public static void runChatRoom() {

    // #chatroom-main
    Behavior<Void> main =
        Behaviors.setup(
            context -> {
              ActorRef<ChatRoom.RoomCommand> chatRoom =
                  context.spawn(ChatRoom.create(), "chatRoom");
              ActorRef<ChatRoom.SessionEvent> gabbler = context.spawn(Gabbler.create(), "gabbler");
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

  public static void main(String[] args) throws Exception {
    runChatRoom();
  }
}
