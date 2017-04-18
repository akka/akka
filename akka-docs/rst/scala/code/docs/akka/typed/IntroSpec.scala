/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.akka.typed

//#imports
import akka.typed._
import akka.typed.scaladsl.Actor._
import akka.typed.scaladsl.AskPattern._
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.concurrent.Await
//#imports
import akka.testkit.AkkaSpec
import akka.typed.TypedSpec

object IntroSpec {

  //#hello-world-actor
  object HelloWorld {
    final case class Greet(whom: String, replyTo: ActorRef[Greeted])
    final case class Greeted(whom: String)

    val greeter = Stateless[Greet] { (_, msg) ⇒
      println(s"Hello ${msg.whom}!")
      msg.replyTo ! Greeted(msg.whom)
    }
  }
  //#hello-world-actor

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

    def chatRoom(sessions: List[ActorRef[SessionEvent]] = List.empty): Behavior[Command] =
      Stateful[Command] { (ctx, msg) ⇒
        msg match {
          case GetSession(screenName, client) ⇒
            val wrapper = ctx.spawnAdapter {
              p: PostMessage ⇒ PostSessionMessage(screenName, p.message)
            }
            client ! SessionGranted(wrapper)
            chatRoom(client :: sessions)
          case PostSessionMessage(screenName, message) ⇒
            val mp = MessagePosted(screenName, message)
            sessions foreach (_ ! mp)
            Same
        }
      }
    //#chatroom-behavior

    //#chatroom-actor
    def mutableChatRoom(): Behavior[Command] =
      Deferred[Command] { _ =>
        var sessions: List[ActorRef[SessionEvent]] = List.empty
        Stateful[Command] { (ctx, msg) ⇒
          msg match {
            case GetSession(screenName, client) ⇒
              val wrapper = ctx.spawnAdapter {
                p: PostMessage ⇒ PostSessionMessage(screenName, p.message)
              }
              client ! SessionGranted(wrapper)
              sessions = client :: sessions
              Same
            case PostSessionMessage(screenName, message) ⇒
              val mp = MessagePosted(screenName, message)
              sessions foreach (_ ! mp)
              Same
          }
        }
      }
    //#chatroom-actor
  }
  //#chatroom-actor

}

class IntroSpec extends TypedSpec {
  import IntroSpec._

  def `must say hello`(): Unit = {
    // TODO Implicits.global is not something we would like to encourage in docs
    //#hello-world
    import HelloWorld._
    // using global pool since we want to run tasks after system.terminate
    import scala.concurrent.ExecutionContext.Implicits.global

    val system: ActorSystem[Greet] = ActorSystem("hello", greeter)

    val future: Future[Greeted] = system ? (Greet("world", _))

    for {
      greeting ← future.recover { case ex ⇒ ex.getMessage }
      done ← { println(s"result: $greeting"); system.terminate() }
    } println("system terminated")
    //#hello-world
  }

  def `must chat`(): Unit = {
    //#chatroom-gabbler
    import ChatRoom._

    val gabbler =
      Stateful[SessionEvent] { (_, msg) ⇒
        msg match {
          case SessionDenied(reason) ⇒
            println(s"cannot start chat room session: $reason")
            Stopped
          case SessionGranted(handle) ⇒
            handle ! PostMessage("Hello World!")
            Same
          case MessagePosted(screenName, message) ⇒
            println(s"message has been posted by '$screenName': $message")
            Stopped
        }
      }
    //#chatroom-gabbler

    //#chatroom-main
    val main: Behavior[akka.NotUsed] =
      Deferred { ctx =>
        val chatRoom = ctx.spawn(ChatRoom.chatRoom(), "chatroom")
        val gabblerRef = ctx.spawn(gabbler, "gabbler")
        ctx.watch(gabblerRef)
        chatRoom ! GetSession("ol’ Gabbler", gabblerRef)

        Stateful(
          onMessage = (_, _) ⇒ Unhandled,
          onSignal = (ctx, sig) ⇒
          sig match {
            case Terminated(ref) ⇒
              Stopped
            case _ ⇒
              Unhandled
          }
        )
      }

    val system = ActorSystem("ChatRoomDemo", main)
    Await.result(system.whenTerminated, 1.second)
    //#chatroom-main
  }

}
