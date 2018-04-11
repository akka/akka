/**
 * Copyright (C) 2014-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

//#imports
import akka.NotUsed
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Terminated }
//#imports

import akka.testkit.typed.scaladsl.ActorTestKit
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import scala.concurrent.Await
import scala.concurrent.duration._

import akka.actor.typed.TypedAkkaSpecWithShutdown

object IntroSpec {

  //#hello-world-actor
  object HelloWorld {
    final case class Greet(whom: String, replyTo: ActorRef[Greeted])
    final case class Greeted(whom: String, from: ActorRef[Greet])

    val greeter: Behavior[Greet] = Behaviors.receive { (ctx, msg) ⇒
      ctx.log.info("Hello {}!", msg.whom)
      msg.replyTo ! Greeted(msg.whom, ctx.self)
      Behaviors.same
    }
  }
  //#hello-world-actor

  //#hello-world-bot
  object HelloWorldBot {

    def bot(greetingCounter: Int, max: Int): Behavior[HelloWorld.Greeted] =
      Behaviors.receive { (ctx, msg) ⇒
        val n = greetingCounter + 1
        ctx.log.info("Greeting {} for {}", n, msg.whom)
        if (n == max) {
          Behaviors.stopped
        } else {
          msg.from ! HelloWorld.Greet(msg.whom, ctx.self)
          bot(n, max)
        }
      }
  }
  //#hello-world-bot

  //#hello-world-main
  object HelloWorldMain {

    final case class Start(name: String)

    val main: Behavior[Start] =
      Behaviors.setup { context ⇒
        val greeter = context.spawn(HelloWorld.greeter, "greeter")

        Behaviors.receiveMessage { msg ⇒
          val replyTo = context.spawn(HelloWorldBot.bot(greetingCounter = 0, max = 3), msg.name)
          greeter ! HelloWorld.Greet(msg.name, replyTo)
          Behaviors.same
        }
      }
  }
  //#hello-world-main

  //#chatroom-actor
  object ChatRoom {
    //#chatroom-protocol
    sealed trait RoomCommand
    final case class GetSession(screenName: String, replyTo: ActorRef[SessionEvent])
      extends RoomCommand
    //#chatroom-protocol
    //#chatroom-behavior
    private final case class PublishSessionMessage(screenName: String, message: String)
      extends RoomCommand
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

    val behavior: Behavior[RoomCommand] =
      chatRoom(List.empty)

    private def chatRoom(sessions: List[ActorRef[SessionCommand]]): Behavior[RoomCommand] =
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case GetSession(screenName, client) ⇒
            // create a child actor for further interaction with the client
            val ses = ctx.spawn(
              session(ctx.self, screenName, client),
              name = URLEncoder.encode(screenName, StandardCharsets.UTF_8.name))
            client ! SessionGranted(ses)
            chatRoom(ses :: sessions)
          case PublishSessionMessage(screenName, message) ⇒
            val notification = NotifyClient(MessagePosted(screenName, message))
            sessions foreach (_ ! notification)
            Behaviors.same
        }
      }

    private def session(
      room:       ActorRef[PublishSessionMessage],
      screenName: String,
      client:     ActorRef[SessionEvent]): Behavior[SessionCommand] =
      Behaviors.receive { (ctx, msg) ⇒
        msg match {
          case PostMessage(message) ⇒
            // from client, publish to others via the room
            room ! PublishSessionMessage(screenName, message)
            Behaviors.same
          case NotifyClient(message) ⇒
            // published from the room
            client ! message
            Behaviors.same
        }
      }
    //#chatroom-behavior
  }
  //#chatroom-actor

}

class IntroSpec extends ActorTestKit with TypedAkkaSpecWithShutdown {

  import IntroSpec._

  "Hello world" must {
    "say hello" in {
      //#hello-world

      val system: ActorSystem[HelloWorldMain.Start] =
        ActorSystem(HelloWorldMain.main, "hello")

      system ! HelloWorldMain.Start("World")
      system ! HelloWorldMain.Start("Akka")

      //#hello-world

      Thread.sleep(500) // it will not fail if too short
      ActorTestKit.shutdown(system)
    }

    "chat" in {
      //#chatroom-gabbler
      import ChatRoom._

      val gabbler: Behavior[SessionEvent] =
        Behaviors.receiveMessage {
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
      //#chatroom-gabbler

      //#chatroom-main
      val main: Behavior[NotUsed] =
        Behaviors.setup { ctx ⇒
          val chatRoom = ctx.spawn(ChatRoom.behavior, "chatroom")
          val gabblerRef = ctx.spawn(gabbler, "gabbler")
          ctx.watch(gabblerRef)
          chatRoom ! GetSession("ol’ Gabbler", gabblerRef)

          Behaviors.receiveSignal {
            case (_, Terminated(ref)) ⇒
              Behaviors.stopped
          }
        }

      val system = ActorSystem(main, "ChatRoomDemo")
      Await.result(system.whenTerminated, 3.seconds)
      //#chatroom-main
    }
  }

}
