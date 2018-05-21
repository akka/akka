/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.scaladsl

// NOTE: do not optimize import, unused import is here on purpose for docs
//#scalatest-glue
import akka.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }

abstract class AbstractActorSpec extends WordSpec with ActorTestKit with Matchers with BeforeAndAfterAll {
  override protected def afterAll(): Unit = {
    shutdownTestKit()
  }
}
//#scalatest-glue
