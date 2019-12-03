/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.{ ActorSystem, Props }
import org.scalatest.WordSpecLike
import akka.persistence.testkit._

class SnapshotNOTSerializeSpec extends WordSpecLike with CommonSnapshotTests {

  override lazy val system: ActorSystem =
    CommonUtils.initSystemWithEnabledPlugin("SnapshotNOTSerializeSpec", false, false)

  import testKit._

  override def specificTests(): Unit =
    "succeed if trying to save nonserializable snapshot" in {

      val pid = randomPid()

      val a = system.actorOf(Props(classOf[A], pid, Some(testActor)))

      val c = new C

      a ! NewSnapshot(c)

      expectNextPersisted(pid, c)

    }

}
