/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.dataflow

import language.postfixOps

import scala.reflect.{ ClassTag, classTag }
import akka.actor.{ Actor, Status, Props }
import akka.actor.Status._
import akka.pattern.ask
import akka.testkit.{ EventFilter, filterEvents, filterException }
import scala.concurrent.{ Await, Promise, Future }
import scala.concurrent.duration._
import akka.testkit.{ DefaultTimeout, TestLatch, AkkaSpec }
import java.util.concurrent.TimeoutException

object DataflowSpec {
  class TestActor extends Actor {
    def receive = {
      case "Hello" ⇒ sender ! "World"
      case "Failure" ⇒
        sender ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
      case "NoReply" ⇒
    }
  }

  class TestDelayActor(await: TestLatch) extends Actor {
    def receive = {
      case "Hello"   ⇒ Await.ready(await, TestLatch.DefaultTimeout); sender ! "World"
      case "NoReply" ⇒ Await.ready(await, TestLatch.DefaultTimeout)
      case "Failure" ⇒
        Await.ready(await, TestLatch.DefaultTimeout)
        sender ! Status.Failure(new RuntimeException("Expected exception; to test fault-tolerance"))
    }
  }
}

class DataflowSpec extends AkkaSpec with DefaultTimeout {
  import DataflowSpec._
  import system.dispatcher
  "Dataflow API" must {
    "futureComposingWithContinuations" in {

      val actor = system.actorOf(Props[TestActor])

      val x = Future("Hello")
      val y = x flatMap (actor ? _) mapTo classTag[String]

      val r = flow(x() + " " + y() + "!")

      assert(Await.result(r, timeout.duration) === "Hello World!")

      system.stop(actor)
    }

    "futureComposingWithContinuationsFailureDivideZero" in {
      filterException[ArithmeticException] {

        val x = Future("Hello")
        val y = x map (_.length)

        val r = flow(x() + " " + y.map(_ / 0).map(_.toString).apply, 100)

        intercept[java.lang.ArithmeticException](Await.result(r, timeout.duration))
      }
    }

    "futureComposingWithContinuationsFailureCastInt" in {
      filterException[ClassCastException] {

        val actor = system.actorOf(Props[TestActor])

        val x = Future(3)
        val y = (actor ? "Hello").mapTo[Int]

        val r = flow(x() + y(), 100)

        intercept[ClassCastException](Await.result(r, timeout.duration))
      }
    }

    "futureComposingWithContinuationsFailureCastNothing" in {
      pending
      filterException[ClassCastException] {

        val actor = system.actorOf(Props[TestActor])

        val x = Future("Hello")
        val y = (actor ? "Hello").mapTo[Nothing]

        val r = flow(x() + y())

        intercept[ClassCastException](Await.result(r, timeout.duration))
      }
    }

    "futureCompletingWithContinuations" in {

      val x, y, z = Promise[Int]()
      val ly, lz = new TestLatch

      val result = flow {
        y completeWith x.future
        ly.open() // not within continuation

        z << x
        lz.open() // within continuation, will wait for 'z' to complete
        z() + y()
      }

      Await.ready(ly, 100 milliseconds)
      intercept[TimeoutException] { Await.ready(lz, 100 milliseconds) }

      flow { x << 5 }

      assert(Await.result(y.future, timeout.duration) === 5)
      assert(Await.result(z.future, timeout.duration) === 5)
      Await.ready(lz, timeout.duration)
      assert(Await.result(result, timeout.duration) === 10)

      val a, b, c = Promise[Int]()

      val result2 = flow {
        val n = (a << c).value.get.get + 10
        b << (c() - 2)
        a() + n * b()
      }

      c completeWith Future(5)

      assert(Await.result(a.future, timeout.duration) === 5)
      assert(Await.result(b.future, timeout.duration) === 3)
      assert(Await.result(result2, timeout.duration) === 50)
    }

    "futureDataFlowShouldEmulateBlocking1" in {

      val one, two = Promise[Int]()
      val simpleResult = flow {
        one() + two()
      }

      assert(Seq(one.future, two.future, simpleResult).forall(_.isCompleted == false))

      flow { one << 1 }

      Await.ready(one.future, 1 minute)

      assert(one.isCompleted)
      assert(Seq(two.future, simpleResult).forall(_.isCompleted == false))

      flow { two << 9 }

      Await.ready(two.future, 1 minute)

      assert(Seq(one, two).forall(_.isCompleted == true))
      assert(Await.result(simpleResult, timeout.duration) === 10)

    }

    "futureDataFlowShouldEmulateBlocking2" in {

      val x1, x2, y1, y2 = Promise[Int]()
      val lx, ly, lz = new TestLatch
      val result = flow {
        lx.open()
        x1 << y1
        ly.open()
        x2 << y2
        lz.open()
        x1() + x2()
      }
      Await.ready(lx, 2 seconds)
      assert(!ly.isOpen)
      assert(!lz.isOpen)
      assert(List(x1, x2, y1, y2).forall(_.isCompleted == false))

      flow { y1 << 1 } // When this is set, it should cascade down the line

      Await.ready(ly, 2 seconds)
      assert(Await.result(x1.future, 1 minute) === 1)
      assert(!lz.isOpen)

      flow { y2 << 9 } // When this is set, it should cascade down the line

      Await.ready(lz, 2 seconds)
      assert(Await.result(x2.future, 1 minute) === 9)

      assert(List(x1, x2, y1, y2).forall(_.isCompleted))

      assert(Await.result(result, 1 minute) === 10)
    }

    "dataFlowAPIshouldbeSlick" in {

      val i1, i2, s1, s2 = new TestLatch

      val callService1 = Future { i1.open(); Await.ready(s1, TestLatch.DefaultTimeout); 1 }
      val callService2 = Future { i2.open(); Await.ready(s2, TestLatch.DefaultTimeout); 9 }

      val result = flow { callService1() + callService2() }

      assert(!s1.isOpen)
      assert(!s2.isOpen)
      assert(!result.isCompleted)
      Await.ready(i1, 2 seconds)
      Await.ready(i2, 2 seconds)
      s1.open()
      s2.open()
      assert(Await.result(result, timeout.duration) === 10)
    }

    "futureCompletingWithContinuationsFailure" in {
      filterException[ArithmeticException] {

        val x, y, z = Promise[Int]()
        val ly, lz = new TestLatch

        val result = flow {
          y << x
          ly.open()
          val oops = 1 / 0
          z << x
          lz.open()
          z() + y() + oops
        }
        intercept[TimeoutException] { Await.ready(ly, 100 milliseconds) }
        intercept[TimeoutException] { Await.ready(lz, 100 milliseconds) }
        flow { x << 5 }

        assert(Await.result(y.future, timeout.duration) === 5)
        intercept[java.lang.ArithmeticException](Await.result(result, timeout.duration))
        assert(z.future.value === None)
        assert(!lz.isOpen)
      }
    }

    "futureContinuationsShouldNotBlock" in {

      val latch = new TestLatch
      val future = Future {
        Await.ready(latch, TestLatch.DefaultTimeout)
        "Hello"
      }

      val result = flow {
        Some(future()).filter(_ == "Hello")
      }

      assert(!result.isCompleted)

      latch.open()

      assert(Await.result(result, timeout.duration) === Some("Hello"))
    }

    "futureFlowShouldBeTypeSafe" in {

      val rString = flow {
        val x = Future(5)
        x().toString
      }

      val rInt = flow {
        val x = rString.apply
        val y = Future(5)
        x.length + y()
      }

      assert(checkType(rString, classTag[String]))
      assert(checkType(rInt, classTag[Int]))
      assert(!checkType(rInt, classTag[String]))
      assert(!checkType(rInt, classTag[Nothing]))
      assert(!checkType(rInt, classTag[Any]))

      Await.result(rString, timeout.duration)
      Await.result(rInt, timeout.duration)
    }

    "futureFlowSimpleAssign" in {
      val x, y, z = Promise[Int]()

      flow {
        z << x() + y()
      }
      flow { x << 40 }
      flow { y << 2 }

      assert(Await.result(z.future, timeout.duration) === 42)
    }

    "should capture first exception with dataflow" in {
      val f1 = flow { 40 / 0 }
      intercept[java.lang.ArithmeticException](Await result (f1, TestLatch.DefaultTimeout))
    }
  }

  def checkType[A: ClassTag, B](in: Future[A], ref: ClassTag[B]): Boolean = implicitly[ClassTag[A]].runtimeClass eq ref.runtimeClass

}
