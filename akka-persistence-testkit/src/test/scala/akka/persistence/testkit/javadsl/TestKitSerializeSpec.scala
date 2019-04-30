/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.javadsl

import java.util.UUID

import akka.actor.{ ActorSystem, Props }
import akka.persistence.testkit._
import com.typesafe.config.ConfigFactory

class TestKitSerializeSpec extends CommonTestkitTests {
  override lazy val system: ActorSystem =
    //todo probably implement method for setting plugin in Persistence for testing purposes
    ActorSystem(
      s"persistence-testkit-${UUID.randomUUID()}",
      PersistenceTestKitPlugin.config
        .withFallback(ConfigFactory.defaultApplication())
        .withFallback(ConfigFactory.parseString("akka.loggers = [\"akka.testkit.TestEventListener\"]")))

  override def specificTests(): Unit = "fail next nonserializable persisted" in {

    val pid = randomPid()

    val a = system.actorOf(Props(classOf[A], pid, None))

    a ! new C

    watch(a)
    expectTerminated(a)

  }
}
