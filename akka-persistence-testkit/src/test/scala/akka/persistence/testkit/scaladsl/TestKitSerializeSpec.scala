/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.scaladsl

import akka.actor.{ ActorSystem, Props }
import akka.persistence.testkit._

class TestKitSerializeSpec extends CommonTestkitTests {
  override lazy val system: ActorSystem =
    CommonUtils.initSystemWithEnabledPlugin("TestKitSerializeSpec", true, true)

  override def specificTests(): Unit = "fail next nonserializable persisted" in {

    val pid = randomPid()

    val a = system.actorOf(Props(classOf[A], pid, None))

    a ! new C

    watch(a)
    expectTerminated(a)

  }
}
