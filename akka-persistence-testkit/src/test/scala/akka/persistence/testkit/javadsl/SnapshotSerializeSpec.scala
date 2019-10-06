/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.javadsl

import java.io.NotSerializableException
import java.util.UUID

import akka.actor.{ ActorSystem, Props }
import akka.persistence.SaveSnapshotFailure
import akka.persistence.testkit._
import akka.persistence.testkit.PersistenceTestKitSnapshotPlugin
import com.typesafe.config.ConfigFactory
import org.scalatest.WordSpecLike

import scala.collection.JavaConverters._

class SnapshotSerializeSpec extends WordSpecLike with CommonSnapshotTests {

  override lazy val system: ActorSystem =
    //todo probably implement method for setting plugin in Persistence for testing purposes
    ActorSystem(
      s"persistence-testkit-${UUID.randomUUID()}",
      PersistenceTestKitSnapshotPlugin.config
        .withFallback(PersistenceTestKitPlugin.config)
        .withFallback(
          ConfigFactory.parseMap(
            Map(
              "akka.persistence.testkit.messages.serialize" -> true,
              "akka.persistence.testkit.snapshots.serialize" -> true).asJava))
        .withFallback(ConfigFactory.parseString("akka.loggers = [\"akka.testkit.TestEventListener\"]"))
        .withFallback(ConfigFactory.defaultApplication()))

  override def specificTests(): Unit =
    "fail if tries to save nonserializable snapshot" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      a ! NewSnapshot(new C)

      expectMsg((List.empty, 0L))
      expectMsgPF() { case SaveSnapshotFailure(_, _: NotSerializableException) => }

    }

}
