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

  //#chatroom-actor
  object ChatRoom {
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

    def behavior(): Behavior[RoomCommand] =
      Behaviors.setup[RoomCommand](context => new ChatRoomBehavior(context))

    class ChatRoomBehavior(context: ActorContext[RoomCommand]) extends AbstractBehavior[RoomCommand] {
      private var sessions: List[ActorRef[SessionCommand]] = List.empty

      override def onMessage(message: RoomCommand): Behavior[RoomCommand] = {
        message match {
          case GetSession(screenName, client) =>
            // create a child actor for further interaction with the client
            val ses = context.spawn(
              session(context.self, screenName, client),
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

    private def session(
        room: ActorRef[PublishSessionMessage],
        screenName: String,
        client: ActorRef[SessionEvent]): Behavior[SessionCommand] =
      Behaviors.receiveMessage {
        case PostMessage(message) =>
          // from client, publish to others via the room
          room ! PublishSessionMessage(screenName, message)
          Behaviors.same
        case NotifyClient(message) =>
          // published from the room
          client ! message
          Behaviors.same
      }
    //#chatroom-behavior
  }
  //#chatroom-actor

}

class OOIntroSpec extends ScalaTestWithActorTestKit with WordSpecLike {

  import OOIntroSpec._

  "A chat room" must {
    "chat" in {
      //#chatroom-gabbler
      import ChatRoom._

      val gabbler =
        Behaviors.receiveMessage[SessionEvent] {
          case SessionDenied(reason) =>
            println(s"cannot start chat room session: $reason")
            Behaviors.stopped
          case SessionGranted(handle) =>
            handle ! PostMessage("Hello World!")
            Behaviors.same
          case MessagePosted(screenName, message) =>
            println(s"message has been posted by '$screenName': $message")
            Behaviors.stopped
        }
      //#chatroom-gabbler

      //#chatroom-main
      val main: Behavior[String] =
        Behaviors.setup { context =>
          val chatRoom = context.spawn(ChatRoom.behavior(), "chatroom")
          val gabblerRef = context.spawn(gabbler, "gabbler")
          context.watch(gabblerRef)

          Behaviors
            .receiveMessagePartial[String] {
              case "go" =>
                chatRoom ! GetSession("ol’ Gabbler", gabblerRef)
                Behaviors.same
            }
            .receiveSignal {
              case (_, Terminated(_)) =>
                println("Stopping guardian")
                Behaviors.stopped
            }
        }

      val system = ActorSystem(main, "ChatRoomDemo")
      system ! "go"
      //#chatroom-main
    }
  }
}
