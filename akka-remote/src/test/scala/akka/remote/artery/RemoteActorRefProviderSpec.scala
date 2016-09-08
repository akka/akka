/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import akka.actor.{ EmptyLocalActorRef, InternalActorRef }
import akka.remote.RemoteActorRef
import akka.testkit.{ EventFilter, TestActors }

class RemoteActorRefProviderSpec extends ArteryMultiNodeSpec {

  val addressA = address(localSystem)
  system.actorOf(TestActors.echoActorProps, "echo")

  val systemB = newRemoteSystem()
  val addressB = address(systemB)
  systemB.actorOf(TestActors.echoActorProps, "echo")

  "RemoteActorRefProvider" must {

    "resolve local actor selection" in {
      val sel = system.actorSelection(s"akka://${system.name}@${addressA.host.get}:${addressA.port.get}/user/echo")
      sel.anchor.asInstanceOf[InternalActorRef].isLocal should be(true)
    }

    "resolve remote actor selection" in {
      val sel = system.actorSelection(s"akka://${systemB.name}@${addressB.host.get}:${addressB.port.get}/user/echo")
      sel.anchor.getClass should ===(classOf[RemoteActorRef])
      sel.anchor.asInstanceOf[InternalActorRef].isLocal should be(false)
    }

    "detect wrong protocol" in {
      EventFilter[IllegalArgumentException](start = "No root guardian at", occurrences = 1).intercept {
        val sel = system.actorSelection(s"akka.tcp://${systemB.name}@${addressB.host.get}:${addressB.port.get}/user/echo")
        sel.anchor.getClass should ===(classOf[EmptyLocalActorRef])
      }
    }

  }

}
