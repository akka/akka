/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.io

import scala.annotation.tailrec
import scala.concurrent.duration._
import org.scalatest.exceptions.TestFailedException
import akka.actor.{ ActorSystem, ActorRef, Terminated }
import akka.testkit.TestProbe

object TestUtils {

  def verifyActorTermination(actor: ActorRef)(implicit system: ActorSystem): Unit = {
    val watcher = TestProbe()
    watcher.watch(actor)
    assert(watcher.expectMsgType[Terminated].actor == actor)
  }

  // why doesn't scalatest provide this by default? (specs2 does with its `eventually` matcher modifier!)
  // Due to its general utility, should this be moved to the testkit?
  @tailrec final def within(timeLeft: Duration, retrySpan: Duration = 10.millis)(test: ⇒ Unit): Unit = {
    val start = System.currentTimeMillis
    lazy val end = System.currentTimeMillis
    val tryAgain = try {
      test
      false
    } catch {
      case (_: TestFailedException | _: AssertionError) if timeLeft.toMillis + start - end > 0 ⇒ true
    }
    if (tryAgain) {
      Thread.sleep(retrySpan.toMillis)
      within(timeLeft - Duration(end - start, MILLISECONDS) - retrySpan)(test)
    }
  }
}
