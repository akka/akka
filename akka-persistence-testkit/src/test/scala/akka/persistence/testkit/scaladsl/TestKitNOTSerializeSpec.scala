/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.Props
import akka.persistence.testkit._

class TestKitNOTSerializeSpec extends CommonTestkitTests {

  override lazy val system = initSystemWithEnabledPlugin("TestKitNOTSerializeSpec", false, false)

  import testKit._

  override def specificTests() = "save next nonserializable persisted" in {
    val pid = randomPid()
    val a = system.actorOf(Props(classOf[A], pid, None))
    val c = new C
    a ! c

    expectNextPersisted(pid, c)
  }

}
