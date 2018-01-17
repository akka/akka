/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package docs.akka.typed

//#imports
import akka.actor.typed._
import akka.actor.typed.scaladsl.ActorBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.testkit.typed.TestKit

import scala.concurrent.duration._
import scala.concurrent.Await
//#imports

object MutableIntroSpec {

  //#chatroom-actor
  object ChatRoom {
    //#chatroom-protocol
    sealed trait Command
    final case class GetSession(screenName: String, replyTo: ActorRef[SessionEvent])
      extends Command
    //#chatroom-protocol
    //#chatroom-behavior
    private final case class PostSessionMessage(screenName: String, message: String)
      extends Command
    //#chatroom-behavior
    //#chatroom-protocol

    sealed trait SessionEvent
    final case class SessionGranted(handle: ActorRef[PostMessage]) extends SessionEvent
    final case class SessionDenied(reason: String) extends SessionEvent
    final case class MessagePosted(screenName: String, message: String) extends SessionEvent

    final case class PostMessage(message: String)
    //#chatroom-protocol
    //#chatroom-behavior

    def behavior(): Behavior[Command] =
      ActorBehavior.mutable[Command](ctx ⇒ new ChatRoomBehavior(ctx))

    class ChatRoomBehavior(ctx: ActorContext[Command]) extends ActorBehavior.MutableBehavior[Command] {
      private var sessions: List[ActorRef[SessionEvent]] = List.empty

      override def onMessage(msg: Command): Behavior[Command] = {
        msg match {
          case GetSession(screenName, client) ⇒
            val wrapper = ctx.spawnAdapter {
              p: PostMessage ⇒ PostSessionMessage(screenName, p.message)
            }
            client ! SessionGranted(wrapper)
            sessions = client :: sessions
            this
          case PostSessionMessage(screenName, message) ⇒
            val mp = MessagePosted(screenName, message)
            sessions foreach (_ ! mp)
            this
        }
      }

    }
    //#chatroom-behavior
  }
  //#chatroom-actor

}

class MutableIntroSpec extends TestKit with TypedAkkaSpecWithShutdown {

  import MutableIntroSpec._

  "A chat room" must {
    "chat" in {
      //#chatroom-gabbler
      import ChatRoom._

      val gabbler =
        ActorBehavior.immutable[SessionEvent] { (_, msg) ⇒
          msg match {
            case SessionDenied(reason) ⇒
              println(s"cannot start chat room session: $reason")
              ActorBehavior.stopped
            case SessionGranted(handle) ⇒
              handle ! PostMessage("Hello World!")
              ActorBehavior.same
            case MessagePosted(screenName, message) ⇒
              println(s"message has been posted by '$screenName': $message")
              ActorBehavior.stopped
          }
        }
      //#chatroom-gabbler

      //#chatroom-main
      val main: Behavior[String] =
        ActorBehavior.deferred { ctx ⇒
          val chatRoom = ctx.spawn(ChatRoom.behavior(), "chatroom")
          val gabblerRef = ctx.spawn(gabbler, "gabbler")
          ctx.watch(gabblerRef)

          ActorBehavior.immutablePartial[String] {
            case (_, "go") ⇒
              chatRoom ! GetSession("ol’ Gabbler", gabblerRef)
              ActorBehavior.same
          } onSignal {
            case (_, Terminated(_)) ⇒
              println("Stopping guardian")
              ActorBehavior.stopped
          }
        }

      val system = ActorSystem(main, "ChatRoomDemo")
      system ! "go"
      Await.result(system.whenTerminated, 1.second)
      //#chatroom-main
    }
  }
}
