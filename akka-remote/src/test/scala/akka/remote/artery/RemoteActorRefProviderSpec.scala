/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import scala.concurrent.duration._
import akka.actor.{ ActorIdentity, ActorSystem, ExtendedActorSystem, Identify, RootActorPath }
import akka.testkit.{ AkkaSpec, ImplicitSender }
import akka.testkit.TestActors
import com.typesafe.config.ConfigFactory
import akka.testkit.EventFilter
import akka.actor.InternalActorRef
import akka.remote.RemoteActorRef
import akka.actor.EmptyLocalActorRef

object RemoteActorRefProviderSpec {

  val config = ConfigFactory.parseString(s"""
     akka {
       actor.provider = "akka.remote.RemoteActorRefProvider"
       remote.artery.enabled = on
       remote.artery.hostname = localhost
       remote.artery.port = 0
     }
  """)

}

class RemoteActorRefProviderSpec extends AkkaSpec(RemoteActorRefProviderSpec.config) with ImplicitSender {
  import RemoteActorRefProviderSpec._

  val addressA = system.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  system.actorOf(TestActors.echoActorProps, "echo")

  val systemB = ActorSystem("systemB", system.settings.config)
  val addressB = systemB.asInstanceOf[ExtendedActorSystem].provider.getDefaultAddress
  systemB.actorOf(TestActors.echoActorProps, "echo")

  override def afterTermination(): Unit = shutdown(systemB)

  "RemoteActorRefProvider" must {

    "resolve local actor selection" in {
      val sel = system.actorSelection(s"artery://${system.name}@${addressA.host.get}:${addressA.port.get}/user/echo")
      sel.anchor.asInstanceOf[InternalActorRef].isLocal should be(true)
    }

    "resolve remote actor selection" in {
      val sel = system.actorSelection(s"artery://${systemB.name}@${addressB.host.get}:${addressB.port.get}/user/echo")
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
