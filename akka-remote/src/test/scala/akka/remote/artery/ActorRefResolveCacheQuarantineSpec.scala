/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.artery

import scala.concurrent.duration._
import akka.remote.RARP
import akka.testkit.DeadLettersFilter
import akka.testkit.ImplicitSender
import akka.testkit.TestActors
import akka.testkit.TestEvent.Mute
import akka.pattern.ask
import akka.util.Timeout

/**
 * Reproducer of issue #29828
 */
class ActorRefResolveCacheQuarantineSpec
    extends ArteryMultiNodeSpec("""
      akka.remote.artery.advanced.remove-quarantined-association-after = 2 seconds
      """)
    with ImplicitSender {
  import RemoteFailureSpec._

  private implicit val timeout: Timeout = 3.seconds

  system.eventStream.publish(Mute(DeadLettersFilter(classOf[Ping])(occurrences = Int.MaxValue)))

  "ActorRefResolveCache" should {

    "not use cached quarantined association" in {
      system.actorOf(TestActors.echoActorProps, name = "echo")

      val clientSystem1 = newRemoteSystem()
      val remoteSelection1 = clientSystem1.actorSelection(rootActorPath(system) / "user" / "echo")

      // PromiseActorRef (temp) doesn't include a uid in the ActorRef
      val reply1 = remoteSelection1 ? "hello-1"
      reply1.futureValue shouldBe "hello-1"

      shutdown(clientSystem1)

      // wait for it to be removed fully, remove-quarantined-association-after
      Thread.sleep(4000)

      val port1 = RARP(clientSystem1).provider.getDefaultAddress.getPort().get
      val clientSystem2 =
        newRemoteSystem(
          name = Some(clientSystem1.name),
          extraConfig = Some(s"akka.remote.artery.canonical.port = $port1"))
      val remoteSelection2 = clientSystem2.actorSelection(rootActorPath(system) / "user" / "echo")

      val reply2 = remoteSelection2 ? "hello-2"
      reply2.futureValue shouldBe "hello-2"
    }

  }
}
