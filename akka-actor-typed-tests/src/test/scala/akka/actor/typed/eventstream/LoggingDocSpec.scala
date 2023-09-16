/*
 * Copyright (C) 2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.eventstream

import akka.actor.DeadLetter
import akka.actor.testkit.typed.scaladsl.LogCapturing
import akka.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import akka.actor.testkit.typed.scaladsl.TestProbe
import akka.actor.typed.ActorRef
import akka.actor.typed.ActorSystem
import akka.actor.typed.Props
import akka.actor.typed.SpawnProtocol
import akka.actor.typed.SpawnProtocol.Spawn
import akka.actor.typed.eventstream.EventStream.Publish
import akka.actor.typed.eventstream.EventStream.Subscribe
import akka.actor.typed.scaladsl.Behaviors
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

object LoggingDocSpec {

  //#deadletters
  import akka.actor.typed.Behavior
  import akka.actor.typed.eventstream.EventStream.Subscribe
  import akka.actor.typed.scaladsl.Behaviors

  object DeadLetterListener {

    def apply(): Behavior[String] = Behaviors.setup { context =>
      // subscribe DeadLetter at startup.
      val adapter = context.messageAdapter[DeadLetter](d => d.message.toString)
      context.system.eventStream ! Subscribe(adapter)

      Behaviors.receiveMessage {
        case msg: String =>
          println(msg)
          Behaviors.same
      }
    }
  }
  //#deadletters

  //#superclass-subscription-eventstream
  object ListenerActor {
    abstract class AllKindsOfMusic { def artist: String }
    case class Jazz(artist: String) extends AllKindsOfMusic
    case class Electronic(artist: String) extends AllKindsOfMusic

    def apply(): Behavior[ListenerActor.AllKindsOfMusic] = Behaviors.receive { (context, msg) =>
      msg match {
        case m: Jazz =>
          println(s"${context.self.path.name} is listening to: ${m.artist}")
          Behaviors.same
        case m: Electronic =>
          println(s"${context.self.path.name} is listening to: ${m.artist}")
          Behaviors.same
        case _ =>
          Behaviors.same
      }
    }
  }
  //#superclass-subscription-eventstream

}

class LoggingDocSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with LogCapturing {
  import LoggingDocSpec._
  import akka.actor.typed.scaladsl.AskPattern._

  "allow registration to dead letters" in {
    // #deadletters
    ActorSystem(Behaviors.setup[Void] { context =>
      context.spawn(DeadLetterListener(), "DeadLetterListener", Props.empty)
      Behaviors.empty
    }, "System")
    // #deadletters
  }

  "demonstrate superclass subscriptions on typed eventStream" in {
    import LoggingDocSpec.ListenerActor._
    //#superclass-subscription-eventstream

    implicit val system: ActorSystem[SpawnProtocol.Command] = ActorSystem(SpawnProtocol(), "SpawnProtocol")
    implicit val ec: ExecutionContext = system.executionContext

    val jazzListener: Future[ActorRef[Jazz]] =
      system.ask(Spawn(behavior = ListenerActor(), name = "jazz", props = Props.empty, _))
    val musicListener: Future[ActorRef[AllKindsOfMusic]] =
      system.ask(Spawn(behavior = ListenerActor(), name = "music", props = Props.empty, _))

    for (jazzListenerRef <- jazzListener; musicListenerRef <- musicListener) {
      system.eventStream ! Subscribe(jazzListenerRef)
      system.eventStream ! Subscribe(musicListenerRef)
    }

    // only musicListener gets this message, since it listens to *all* kinds of music:
    system.eventStream ! Publish(Electronic("Parov Stelar"))

    // jazzListener and musicListener will be notified about Jazz:
    system.eventStream ! Publish(Jazz("Sonny Rollins"))
    //#superclass-subscription-eventstream
  }

  "allow registration to suppressed dead letters" in {
    val listener: ActorRef[Any] = TestProbe().ref

    //#suppressed-deadletters
    import akka.actor.SuppressedDeadLetter
    system.eventStream ! Subscribe[SuppressedDeadLetter](listener)
    //#suppressed-deadletters

    //#all-deadletters
    import akka.actor.AllDeadLetters
    system.eventStream ! Subscribe[AllDeadLetters](listener)
    //#all-deadletters
  }

}
