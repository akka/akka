/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package docs.akka.typed

//#imports
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Behavior
import akka.actor.typed.Terminated
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.scaladsl.Behaviors
import akka.testkit.typed.TestKit

import scala.concurrent.Await
import scala.concurrent.Future
import scala.concurrent.duration._
//#imports

import akka.actor.typed.TypedAkkaSpecWithShutdown

object IntroSpec {

  //#hello-world-actor
  object HelloWorld {
    final case class Greet(whom: String, replyTo: ActorRef[Greeted])
    final case class Greeted(whom: String)

    val greeter = Behaviors.immutable[Greet] { (_, msg) ⇒
      println(s"Hello ${msg.whom}!")
      msg.replyTo ! Greeted(msg.whom)
      Behaviors.same
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

    val behavior: Behavior[Command] =
      chatRoom(List.empty)

    private def chatRoom(sessions: List[ActorRef[SessionEvent]]): Behavior[Command] =
      Behaviors.immutable[Command] { (ctx, msg) ⇒
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
            Behaviors.same
        }
      }
    //#chatroom-behavior
  }
  //#chatroom-actor

}

class IntroSpec extends TestKit with TypedAkkaSpecWithShutdown {

  import IntroSpec._

  "Hello world" must {
    "must say hello" in {
      // TODO Implicits.global is not something we would like to encourage in docs
      //#hello-world
      import HelloWorld._
      // using global pool since we want to run tasks after system.terminate
      import scala.concurrent.ExecutionContext.Implicits.global

      val system: ActorSystem[Greet] = ActorSystem(greeter, "hello")

      val future: Future[Greeted] = system ? (Greet("world", _))

      for {
        greeting ← future.recover { case ex ⇒ ex.getMessage }
        done ← {
          println(s"result: $greeting")
          system.terminate()
        }
      } println("system terminated")
      //#hello-world
    }

    "must chat" in {
      //#chatroom-gabbler
      import ChatRoom._

      val gabbler =
        Behaviors.immutable[SessionEvent] { (_, msg) ⇒
          msg match {
            //#chatroom-gabbler
            // We document that the compiler warns about the missing handler for `SessionDenied`
            case SessionDenied(reason) ⇒
              println(s"cannot start chat room session: $reason")
              Behaviors.stopped
            //#chatroom-gabbler
            case SessionGranted(handle) ⇒
              handle ! PostMessage("Hello World!")
              Behaviors.same
            case MessagePosted(screenName, message) ⇒
              println(s"message has been posted by '$screenName': $message")
              Behaviors.stopped
          }
        }
      //#chatroom-gabbler

      //#chatroom-main
      val main: Behavior[String] =
        Behaviors.deferred { ctx ⇒
          val chatRoom = ctx.spawn(ChatRoom.behavior, "chatroom")
          val gabblerRef = ctx.spawn(gabbler, "gabbler")
          ctx.watch(gabblerRef)

          Behaviors.immutablePartial[String] {
            case (_, "go") ⇒
              chatRoom ! GetSession("ol’ Gabbler", gabblerRef)
              Behaviors.same
          } onSignal {
            case (_, Terminated(ref)) ⇒
              Behaviors.stopped
          }
        }

      val system = ActorSystem(main, "ChatRoomDemo")
      system ! "go"
      Await.result(system.whenTerminated, 3.seconds)
      //#chatroom-main
    }
  }

}
