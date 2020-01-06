/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.javadsl

import akka.actor.Props
import akka.persistence.testkit._

class SnapshotNOTSerializeSpec extends CommonSnapshotTests {

  override lazy val system = initSystemWithEnabledPlugin("SnapshotNOTSerializeSpec", false, false)

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
