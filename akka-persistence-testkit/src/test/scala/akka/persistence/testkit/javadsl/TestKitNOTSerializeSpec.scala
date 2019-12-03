/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.javadsl

import akka.actor.Props
import akka.persistence.testkit._
import org.scalatest.WordSpecLike

class TestKitNOTSerializeSpec extends WordSpecLike with CommonTestkitTests {

  override lazy val system =
    CommonUtils.initSystemWithEnabledPlugin("TestKitNOTSerializeSpec", false, false)

  import testKit._

  override def specificTests() = "save next nonserializable persisted" in {

    val pid = randomPid()

    val a = system.actorOf(Props(classOf[A], pid, None))

    val c = new C

    a ! c

    expectNextPersisted(pid, c)

  }

}
