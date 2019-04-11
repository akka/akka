/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit

import language.postfixOps

import akka.actor._
import scala.concurrent.duration._

class JavaTestKitSpec extends AkkaSpec with DefaultTimeout {

  "JavaTestKit" must {

    "be able to receiveN messages" in {
      new JavaTestKit(system) {
        val sent = List(1, 2, 3, 4, 5)
        for (m <- sent) { getRef() ! m }
        val received = receiveN(sent.size, 5 seconds)
        sent.toSet should be(received.toSet)
      }
    }

    "be able to receiveN messages with default duration" in {
      new JavaTestKit(system) {
        val sent = List(1, 2, 3)
        for (m <- sent) { getRef() ! m }
        val received = receiveN(sent.size)
        sent.toSet should be(received.toSet)
      }
    }

    "be able to expectTerminated" in {
      new JavaTestKit(system) {
        val actor = system.actorOf(Props(new Actor { def receive = { case _ => } }))

        watch(actor)
        system.stop(actor)
        expectTerminated(actor).existenceConfirmed should ===(true)

        watch(actor)
        expectTerminated(5 seconds, actor).actor should ===(actor)
      }
    }

  }

}
