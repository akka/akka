/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote

import akka.testkit._
import scala.concurrent.duration._
import akka.actor.{ Address, ActorSystem }
import akka.util.ccompat._
import com.typesafe.config.ConfigFactory
import scala.collection.JavaConverters._

class DaemonicSpec extends AkkaSpec {

  "Remoting configured with daemonic = on" must {

    "shut down correctly after getting connection refused" in {
      // get all threads running before actor system is started
      val origThreads: Set[Thread] = Thread.getAllStackTraces.keySet().asScala.to(Set)
      // create a separate actor system that we can check the threads for
      val daemonicSystem = ActorSystem("daemonic", ConfigFactory.parseString("""
        akka.daemonic = on
        akka.actor.provider = remote
        akka.remote.netty.tcp.transport-class = "akka.remote.transport.netty.NettyTransport"
        akka.remote.netty.tcp.port = 0
        akka.log-dead-letters-during-shutdown = off
      """))

      try {
        val unusedPort = 86 // very unlikely to ever be used, "system port" range reserved for Micro Focus Cobol

        val unusedAddress = RARP(daemonicSystem).provider.getExternalAddressFor(Address(s"akka.tcp", "", "", unusedPort)).get
        val selection = daemonicSystem.actorSelection(s"$unusedAddress/user/SomeActor")
        selection ! "whatever"

        // get new non daemonic threads running
        awaitAssert({
          val newNonDaemons: Set[Thread] = Thread.getAllStackTraces.keySet().asScala.seq.
            filter(t ⇒ !origThreads(t) && !t.isDaemon).to(Set)
          newNonDaemons should ===(Set.empty[Thread])
        }, 4.seconds)

      } finally {
        shutdown(daemonicSystem)
      }
    }
  }
}
