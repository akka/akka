/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.javadsl

import java.util.UUID

import akka.actor.{ ActorSystem, Props }
import akka.persistence.testkit._
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

import scala.collection.JavaConverters._

class TestKitNOTSerializeSpec extends WordSpecLike with CommonTestkitTests {

  override lazy val system =
    //todo probably implement method for setting plugin in Persistence for testing purposes
    ActorSystem(
      s"persistence-testkit-${UUID.randomUUID()}",
      PersistenceTestKitPlugin.config
        .withFallback(
          ConfigFactory.parseMap(
            Map(
              "akka.persistence.testkit.messages.serialize" -> false,
              "akka.persistence.testkit.snapshots.serialize" -> false).asJava))
        .withFallback(ConfigFactory.parseString("akka.loggers = [\"akka.testkit.TestEventListener\"]"))
        .withFallback(ConfigFactory.defaultApplication()))

  import testKit._

  override def specificTests() = "save next nonserializable persisted" in {

    val pid = randomPid()

    val a = system.actorOf(Props(classOf[A], pid, None))

    val c = new C

    a ! c

    expectNextPersisted(pid, c)

  }

}
