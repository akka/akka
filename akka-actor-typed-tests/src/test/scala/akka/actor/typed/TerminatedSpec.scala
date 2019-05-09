/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.testkit.typed.scaladsl.TestInbox
import org.scalatest.{ Matchers, WordSpec }

class TerminatedSpec extends WordSpec with Matchers {

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
      }) shouldEqual (probe.ref, ex)

    }

  }

}
