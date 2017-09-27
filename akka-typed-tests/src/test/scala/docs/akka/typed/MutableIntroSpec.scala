/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.akka.typed

//#imports
import akka.typed._
import akka.typed.scaladsl.Actor
import akka.typed.scaladsl.ActorContext
import akka.typed.scaladsl.AskPattern._
import scala.concurrent.Future
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
      Actor.mutable[Command](ctx ⇒ new ChatRoomBehavior(ctx))

    class ChatRoomBehavior(ctx: ActorContext[Command]) extends Actor.MutableBehavior[Command] {
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

class MutableIntroSpec extends TypedSpec {
  import MutableIntroSpec._

  def `must chat`(): Unit = {
    //#chatroom-gabbler
    import ChatRoom._

    val gabbler =
      Actor.immutable[SessionEvent] { (_, msg) ⇒
        msg match {
          case SessionDenied(reason) ⇒
            println(s"cannot start chat room session: $reason")
            Actor.stopped
          case SessionGranted(handle) ⇒
            handle ! PostMessage("Hello World!")
            Actor.same
          case MessagePosted(screenName, message) ⇒
            println(s"message has been posted by '$screenName': $message")
            Actor.stopped
        }
      }
    //#chatroom-gabbler

    //#chatroom-main
    val main: Behavior[akka.NotUsed] =
      Actor.deferred { ctx ⇒
        val chatRoom = ctx.spawn(ChatRoom.behavior(), "chatroom")
        val gabblerRef = ctx.spawn(gabbler, "gabbler")
        ctx.watch(gabblerRef)
        chatRoom ! GetSession("ol’ Gabbler", gabblerRef)

        Actor.immutable[akka.NotUsed] {
          (_, _) ⇒ Actor.unhandled
        } onSignal {
          case (ctx, Terminated(ref)) ⇒
            Actor.stopped
        }
      }

    val system = ActorSystem(main, "ChatRoomDemo")
    Await.result(system.whenTerminated, 1.second)
    //#chatroom-main
  }

}
