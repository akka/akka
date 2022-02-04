/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import scala.concurrent.duration._

import akka.remote.EndpointDisassociatedException
import akka.serialization.jackson.CborSerializable
import akka.testkit.{ EventFilter, ImplicitSender, TestActors, TestEvent }
import akka.testkit.DeadLettersFilter
import akka.testkit.TestEvent.Mute

object RemoteFailureSpec {
  final case class Ping(s: String) extends CborSerializable
}

class RemoteFailureSpec extends ArteryMultiNodeSpec with ImplicitSender {
  import RemoteFailureSpec._

  system.eventStream.publish(Mute(DeadLettersFilter(classOf[Ping])(occurrences = Int.MaxValue)))

  "Remoting" should {

    "not be exhausted by sending to broken connections" in {
      val remoteSystems = Vector.fill(5)(newRemoteSystem())

      remoteSystems.foreach { sys =>
        sys.eventStream.publish(TestEvent
          .Mute(EventFilter[EndpointDisassociatedException](), EventFilter.warning(pattern = "received dead letter.*")))
        sys.actorOf(TestActors.echoActorProps, name = "echo")
      }
      val remoteSelections = remoteSystems.map { sys =>
        system.actorSelection(rootActorPath(sys) / "user" / "echo")
      }

      system.actorOf(TestActors.echoActorProps, name = "echo")

      val localSelection = system.actorSelection(rootActorPath(system) / "user" / "echo")
      val n = 100

      // first everything is up and running
      (1 to n).foreach { x =>
        localSelection ! Ping("1")
        remoteSelections(x % remoteSystems.size) ! Ping("1")
      }

      within(5.seconds) {
        receiveN(n * 2).foreach { reply =>
          reply should ===(Ping("1"))
        }
      }

      // then we shutdown remote systems to simulate broken connections
      remoteSystems.foreach { sys =>
        shutdown(sys)
      }

      (1 to n).foreach { x =>
        localSelection ! Ping("2")
        remoteSelections(x % remoteSystems.size) ! Ping("2")
      }

      // ping messages to localEcho should go through even though we use many different broken connections
      within(5.seconds) {
        receiveN(n).foreach { reply =>
          reply should ===(Ping("2"))
        }
      }

    }

  }
}
