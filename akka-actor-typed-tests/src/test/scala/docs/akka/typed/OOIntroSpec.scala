/*
 * Copyright (C) 2014-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

//#imports
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import akka.actor.typed._
import akka.actor.typed.scaladsl.{ AbstractBehavior, ActorContext, Behaviors }

import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.WordSpecLike
//#imports

object OOIntroSpec {

  //#chatroom-behavior
  object ChatRoom {
    //#chatroom-behavior
    //#chatroom-protocol
    sealed trait RoomCommand
    final case class GetSession(screenName: String, replyTo: ActorRef[SessionEvent]) extends RoomCommand
    //#chatroom-protocol
    //#chatroom-behavior
    private final case class PublishSessionMessage(screenName: String, message: String) extends RoomCommand
    //#chatroom-behavior
    //#chatroom-protocol

    sealed trait SessionEvent
    final case class SessionGranted(handle: ActorRef[PostMessage]) extends SessionEvent
    final case class SessionDenied(reason: String) extends SessionEvent
    final case class MessagePosted(screenName: String, message: String) extends SessionEvent

    trait SessionCommand
    final case class PostMessage(message: String) extends SessionCommand
    private final case class NotifyClient(message: MessagePosted) extends SessionCommand
    //#chatroom-protocol
    //#chatroom-behavior

    def apply(): Behavior[RoomCommand] =
      Behaviors.setup(context => new ChatRoomBehavior(context))

    class ChatRoomBehavior(context: ActorContext[RoomCommand]) extends AbstractBehavior[RoomCommand] {
      private var sessions: List[ActorRef[SessionCommand]] = List.empty

      override def onMessage(message: RoomCommand): Behavior[RoomCommand] = {
        message match {
          case GetSession(screenName, client) =>
            // create a child actor for further interaction with the client
            val ses = context.spawn(
              new SessionBehavior(context.self, screenName, client),
              name = URLEncoder.encode(screenName, StandardCharsets.UTF_8.name))
            client ! SessionGranted(ses)
            sessions = ses :: sessions
            this
          case PublishSessionMessage(screenName, message) =>
            val notification = NotifyClient(MessagePosted(screenName, message))
            sessions.foreach(_ ! notification)
            this
        }
      }
    }

    private class SessionBehavior(
        room: ActorRef[PublishSessionMessage],
        screenName: String,
        client: ActorRef[SessionEvent])
        extends AbstractBehavior[SessionCommand] {

      override def onMessage(msg: SessionCommand): Behavior[SessionCommand] = {
        msg match {
          case PostMessage(message) =>
            // from client, publish to others via the room
            room ! PublishSessionMessage(screenName, message)
            Behaviors.same
          case NotifyClient(message) =>
            // published from the room
            client ! message
            Behaviors.same
        }
      }
    }
  }
  //#chatroom-behavior

  //#chatroom-gabbler
  object Gabbler {
    import ChatRoom._

    def apply(): Behavior[SessionEvent] =
      Behaviors.setup { context =>
        Behaviors.receiveMessage {
          case SessionDenied(reason) =>
            context.log.info("cannot start chat room session: {}", reason)
            Behaviors.stopped
          case SessionGranted(handle) =>
            handle ! PostMessage("Hello World!")
            Behaviors.same
          case MessagePosted(screenName, message) =>
            context.log.info("message has been posted by '{}': {}", screenName, message)
            Behaviors.stopped
        }
      }
    //#chatroom-gabbler
  }

}

class OOIntroSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  import OOIntroSpec._

  "A chat room" must {
    "chat" in {
      //#chatroom-main
      val main: Behavior[String] =
        Behaviors.setup { context =>
          val chatRoom = context.spawn(ChatRoom(), "chatroom")
          val gabblerRef = context.spawn(Gabbler(), "gabbler")
          context.watch(gabblerRef)

          Behaviors
            .receiveMessagePartial[String] {
              case "go" =>
                chatRoom ! ChatRoom.GetSession("ol’ Gabbler", gabblerRef)
                Behaviors.same
            }
            .receiveSignal {
              case (_, Terminated(_)) =>
                context.log.info("Stopping guardian")
                Behaviors.stopped
            }
        }

      val system = ActorSystem(main, "ChatRoomDemo")
      system ! "go"
      //#chatroom-main
    }
  }
}
