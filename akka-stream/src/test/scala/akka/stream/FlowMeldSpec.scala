/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.{ StreamTestKit, ScriptedTest }
import scala.concurrent.forkjoin.ThreadLocalRandom.{ current ⇒ random }
import akka.stream.scaladsl.Flow
import akka.stream.impl.ActorBasedFlowMaterializer

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowMeldSpec extends AkkaSpec with ScriptedTest {

  val m = FlowMaterializer(MaterializerSettings(
    dispatcher = "akka.test.stream-dispatcher"))

  "A Flow with meldable operations" must {

    "work with zero operations" in {
      val c = StreamTestKit.consumerProbe[Int]
      Flow(1 to 3).produceTo(m, c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectNext(1)
      c.expectNext(2)
      c.expectNext(3)
      c.expectComplete()
    }

    "work with one operation" in {
      val c = StreamTestKit.consumerProbe[String]
      Flow(1 to 3).map(n ⇒ "elem-" + n).produceTo(m, c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectNext("elem-1")
      c.expectNext("elem-2")
      c.expectNext("elem-3")
      c.expectComplete()
    }

    "meld two operations" in {
      val c = StreamTestKit.consumerProbe[String]
      Flow(1 to 5).
        filter(_ % 2 == 0).
        map(n ⇒ "elem-" + n).
        produceTo(m, c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectNext("elem-2")
      c.expectNext("elem-4")
      c.expectComplete()
    }

    "meld three operations" in {
      val c = StreamTestKit.consumerProbe[String]
      Flow(1 to 10).
        filter(_ % 2 == 0).
        collect {
          case n if n <= 4 ⇒ n * 2
        }.
        map(n ⇒ "elem-" + n).
        produceTo(m, c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectNext("elem-4")
      c.expectNext("elem-8")
      c.expectComplete()
    }

    "meld many operations" in {
      val c = StreamTestKit.consumerProbe[String]
      Flow(-1 to 5).
        drop(2).
        filter(_ % 2 == 0).
        map(_.toString).
        map(_.toInt).
        map(n ⇒ "elem-" + n).
        map(_.toUpperCase).
        produceTo(m, c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectNext("ELEM-2")
      c.expectNext("ELEM-4")
      c.expectComplete()
    }

    "work with combinations of meldable and not meldable operators" in {
      val c = StreamTestKit.consumerProbe[String]
      Flow(1 to 5).
        filter(_ % 2 == 0).
        mapConcat(n ⇒ List(n, n)).
        map(n ⇒ "elem-" + n).
        map(_.toUpperCase).
        drop(1).
        take(2).
        map(_.toLowerCase).
        produceTo(m, c)
      val sub = c.expectSubscription()
      sub.requestMore(10)
      c.expectNext("elem-2")
      c.expectNext("elem-4")
      c.expectComplete()
    }

  }

}