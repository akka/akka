/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.testkit.typed.scaladsl.TestInbox
import akka.actor.testkit.typed.scaladsl.LogCapturing
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TerminatedSpec extends AnyWordSpec with Matchers with LogCapturing {

  "Child Failed" must {
    "should be pattern matchable" in {

      val probe = TestInbox[String]()
      val ex = new RuntimeException("oh dear")
      val childFailed = new ChildFailed(probe.ref, ex)

      (childFailed match {
        case Terminated(r) => r
      }) shouldEqual probe.ref

      (childFailed match {
        case ChildFailed(ref, e) => (ref, e)
      }) shouldEqual ((probe.ref, ex))

    }

  }

}
