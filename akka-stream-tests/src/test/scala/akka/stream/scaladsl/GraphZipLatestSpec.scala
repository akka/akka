/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import akka.actor.ActorSystem
import akka.stream.testkit.TestPublisher.Probe
import akka.stream.testkit.scaladsl.{ TestSink, TestSource }
import akka.stream.{ ActorMaterializer, ClosedShape }
import akka.testkit.TestKit
import org.scalacheck.Gen
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.prop.PropertyChecks
import org.scalatest.{ BeforeAndAfterAll, GivenWhenThen, Matchers, WordSpecLike }

import scala.concurrent.duration._
import scala.language.postfixOps

class GraphZipLatestSpec
  extends TestKit(ActorSystem("ZipLatestSpec"))
  with WordSpecLike
  with Matchers
  with BeforeAndAfterAll
  with PropertyChecks
  with GivenWhenThen
  with ScalaFutures {
  implicit val materializer = ActorMaterializer()
  override def afterAll = TestKit.shutdownActorSystem(system)
  implicit val patience = PatienceConfig(5 seconds)

  "ZipLatest" must {
    "only emit when at least one pair is available" in {
      val (probe, bools, ints) = testGraph[Boolean, Int]

      Given("request for one element")
      probe.request(1)

      And("an element pushed on one of the sources")
      bools.sendNext(true)

      Then("does not emit yet")
      probe.expectNoMessage(0 seconds)

      And("an element pushed on the other source")
      ints.sendNext(1)

      Then("emits a single pair")
      probe.expectNext((true, 1))
    }

    "emits as soon as one source is available" in {
      val (probe, bools, ints) = testGraph[Boolean, Int]

      Given("request for 3 elements")
      probe.request(3)

      And("a first element pushed on either source")
      bools.sendNext(true)
      ints.sendNext(1)

      And("then 2 elements pushed only on one source")
      ints.sendNext(1)
      ints.sendNext(1)

      Then("3 elements are emitted")
      probe.expectNext((true, 1))
      probe.expectNext((true, 1))
      probe.expectNext((true, 1))
    }

    "does not emit the same pair upon two pulls with value types" in {
      val (probe, bools, ints) = testGraph[Boolean, Int]

      Given("request for one element")
      probe.request(1)

      And("one element pushed on each source")
      bools.sendNext(true)
      ints.sendNext(1)

      Then("emits a single pair")
      probe.expectNext((true, 1))

      And("another request")
      probe.request(1)

      Then("does not emit a duplicate")
      probe.expectNoMessage(0 seconds)

      And("sending complete")
      bools.sendComplete()

      Then("completes the stream")
      probe.expectComplete()
    }

    "does not emit the same pair upon two pulls with reference types" in new Fixture {
      val a = A(someString)
      val b = B(someInt)
      val (probe, as, bs) = testGraph[A, B]

      Given("request for one element")
      probe.request(1)

      And("one element pushed on each source")
      as.sendNext(a)
      bs.sendNext(b)

      Then("emits a single pair")
      probe.expectNext((a, b))

      And("another request")
      probe.request(1)

      Then("does not emit a duplicate")
      probe.expectNoMessage(0 seconds)

      And("sending complete")
      as.sendComplete()

      Then("completes the stream")
      probe.expectComplete()
    }

    "does not de-duplicate instances based on value" in new Fixture {
      Given("""
          |S1 -> A1 A2 A3   --\
          |                    > -- ZipLatest
          |S2 -> B1      B2 --/
        """.stripMargin)
      val a1 = A(someString)
      val a2 = A(someString)
      val a3 = A(someString)
      val b1 = B(someInt)
      val b2 = B(someInt)
      val (probe, as, bs) = testGraph[A, B]

      Then("""
          |O -> (A1, B1), (A2, B1), (A3, B1), (A3, B2)
        """.stripMargin)
      probe.request(4)

      as.sendNext(a1)
      bs.sendNext(b1)
      probe.expectNext((a1, b1))

      as.sendNext(a2)
      probe.expectNext((a2, b1))

      as.sendNext(a3)
      probe.expectNext((a3, b1))

      bs.sendNext(b2)
      probe.expectNext((a3, b2))
    }

    val first = (t: (Probe[Boolean], Probe[Int])) ⇒ t._1
    val second = (t: (Probe[Boolean], Probe[Int])) ⇒ t._2

    "complete when either source completes" in {
      forAll(Gen.oneOf(first, second)) { select ⇒
        val (probe, bools, ints) = testGraph[Boolean, Int]

        Given("either source completes")
        select((bools, ints)).sendComplete()

        Then("subscribes and completes")
        probe.expectSubscriptionAndComplete()
      }
    }

    "complete when either source completes and requesting element" in {
      forAll(Gen.oneOf(first, second)) { select ⇒
        val (probe, bools, ints) = testGraph[Boolean, Int]

        Given("either source completes")
        select((bools, ints)).sendComplete()

        And("request for one element")
        probe.request(1)

        Then("subscribes and completes")
        probe.expectComplete()
      }
    }

    "complete when either source completes with some pending element" in {
      forAll(Gen.oneOf(first, second)) { select ⇒
        val (probe, bools, ints) = testGraph[Boolean, Int]

        Given("one element pushed on each source")
        bools.sendNext(true)
        ints.sendNext(1)

        And("either source completes")
        select((bools, ints)).sendComplete()

        Then("should emit first element then complete")
        probe.requestNext((true, 1))
        probe.expectComplete()
      }
    }

    "complete when one source completes and the other continues pushing" in {

      val (probe, bools, ints) = testGraph[Boolean, Int]

      Given("one element pushed on each source")
      bools.sendNext(true)
      ints.sendNext(1)

      And("either source completes")
      bools.sendComplete()
      ints.sendNext(10)
      ints.sendNext(10)

      Then("should emit first element then complete")
      probe.requestNext((true, 1))
      probe.expectComplete()
    }

    "complete if no pending demand" in {
      forAll(Gen.oneOf(first, second)) { select ⇒
        val (probe, bools, ints) = testGraph[Boolean, Int]

        Given("request for one element")
        probe.request(1)

        Given("one element pushed on each source and tuple emitted")
        bools.sendNext(true)
        ints.sendNext(1)
        probe.expectNext((true, 1))

        And("either source completes")
        select((bools, ints)).sendComplete()

        Then("should complete")
        probe.expectComplete()
      }
    }

    "fail when either source has error" in {
      forAll(Gen.oneOf(first, second)) { select ⇒
        val (probe, bools, ints) = testGraph[Boolean, Int]
        val error = new RuntimeException

        Given("either source errors")
        select((bools, ints)).sendError(error)

        Then("subscribes and error")
        probe.expectSubscriptionAndError(error)
      }
    }

    "emit even if pair is the same" in {
      val (probe, bools, ints) = testGraph[Boolean, Int]

      Given("request for two elements")
      probe.request(2)

      And("one element pushed on each source")
      bools.sendNext(true)
      ints.sendNext(1)
      And("once again the same element on one source")
      ints.sendNext(1)

      And("followed by complete")
      bools.sendComplete()
      ints.sendComplete()

      Then("emits two equal pairs")
      probe.expectNext((true, 1))
      probe.expectNext((true, 1))

      And("then complete")
      probe.expectComplete()
    }

    "emit combined elements in proper order" in {
      val (probe, firstDigits, secondDigits) = testGraph[Int, Int]

      Given(s"numbers up to 99 in tuples")
      val allNumbers = for {
        firstDigit ← 0 to 9
        secondDigit ← 0 to 9
      } yield (firstDigit, secondDigit)

      allNumbers.groupBy(_._1).toList.sortBy(_._1).foreach {
        case (firstDigit, pairs) ⇒ {
          When(s"sending first digit $firstDigit")
          firstDigits.sendNext(firstDigit)
          pairs.map { case (_, digits) ⇒ digits }.foreach { secondDigit ⇒
            And(s"sending second digit $secondDigit")
            secondDigits.sendNext(secondDigit)
            probe.request(1)

            Then(s"should receive tuple ($firstDigit,$secondDigit)")
            probe.expectNext((firstDigit, secondDigit))
          }
        }
      }
    }
  }

  private class Fixture {
    val someString = "someString"
    val someInt = 1
    case class A(value: String)
    case class B(value: Int)
  }

  private def testGraph[A, B] =
    RunnableGraph
      .fromGraph(
        GraphDSL
          .create(
            TestSink.probe[(A, B)],
            TestSource.probe[A],
            TestSource.probe[B])(Tuple3.apply) { implicit b ⇒ (ts, as, bs) ⇒
              import GraphDSL.Implicits._
              val zipLatest = b.add(new ZipLatest[A, B]())
              as ~> zipLatest.in0
              bs ~> zipLatest.in1
              zipLatest.out ~> ts
              ClosedShape
            }
      )
      .run()

}
