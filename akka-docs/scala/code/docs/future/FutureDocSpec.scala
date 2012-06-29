/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package docs.future

import language.postfixOps

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._
import akka.actor.Actor
import akka.actor.Props
import akka.actor.Status.Failure
import akka.util.Timeout
import scala.concurrent.util.duration._
import java.lang.IllegalStateException
import akka.dispatch.{ Future, Promise }
import scala.concurrent.{ Await, ExecutionContext }

object FutureDocSpec {

  class MyActor extends Actor {
    def receive = {
      case x: String       ⇒ sender ! x.toUpperCase
      case x: Int if x < 0 ⇒ sender ! Failure(new ArithmeticException("Negative values not supported"))
      case x: Int          ⇒ sender ! x
    }
  }

  case object GetNext

  class OddActor extends Actor {
    var n = 1
    def receive = {
      case GetNext ⇒
        sender ! n
        n += 2
    }
  }
}

class FutureDocSpec extends AkkaSpec {
  import FutureDocSpec._

  "demonstrate usage custom ExecutionContext" in {
    val yourExecutorServiceGoesHere = java.util.concurrent.Executors.newSingleThreadExecutor()
    //#diy-execution-context
    import akka.dispatch.{ ExecutionContext, Promise }

    implicit val ec = ExecutionContext.fromExecutorService(yourExecutorServiceGoesHere)

    // Do stuff with your brand new shiny ExecutionContext
    val f = Promise.successful("foo")

    // Then shut your ExecutionContext down at some
    // appropriate place in your program/application
    ec.shutdown()
    //#diy-execution-context
  }

  "demonstrate usage of blocking from actor" in {
    val actor = system.actorOf(Props[MyActor])
    val msg = "hello"
    //#ask-blocking
    import scala.concurrent.Await
    import akka.pattern.ask
    import akka.util.Timeout
    import scala.concurrent.util.duration._

    implicit val timeout = Timeout(5 seconds)
    val future = actor ? msg // enabled by the “ask” import
    val result = Await.result(future, timeout.duration).asInstanceOf[String]
    //#ask-blocking
    result must be("HELLO")
  }

  "demonstrate usage of mapTo" in {
    val actor = system.actorOf(Props[MyActor])
    val msg = "hello"
    implicit val timeout = Timeout(5 seconds)
    //#map-to
    import akka.dispatch.Future
    import akka.pattern.ask

    val future: Future[String] = ask(actor, msg).mapTo[String]
    //#map-to
    Await.result(future, timeout.duration) must be("HELLO")
  }

  "demonstrate usage of simple future eval" in {
    //#future-eval
    import scala.concurrent.Await
    import akka.dispatch.Future
    import scala.concurrent.util.duration._

    val future = Future {
      "Hello" + "World"
    }
    val result = Await.result(future, 1 second)
    //#future-eval
    result must be("HelloWorld")
  }

  "demonstrate usage of map" in {
    //#map
    val f1 = Future {
      "Hello" + "World"
    }
    val f2 = f1 map { x ⇒
      x.length
    }
    val result = Await.result(f2, 1 second)
    result must be(10)
    f1.value must be(Some(Right("HelloWorld")))
    //#map
  }

  "demonstrate wrong usage of nested map" in {
    //#wrong-nested-map
    val f1 = Future {
      "Hello" + "World"
    }
    val f2 = Promise.successful(3)
    val f3 = f1 map { x ⇒
      f2 map { y ⇒
        x.length * y
      }
    }
    //#wrong-nested-map
    Await.ready(f3, 1 second)
  }

  "demonstrate usage of flatMap" in {
    //#flat-map
    val f1 = Future {
      "Hello" + "World"
    }
    val f2 = Promise.successful(3)
    val f3 = f1 flatMap { x ⇒
      f2 map { y ⇒
        x.length * y
      }
    }
    val result = Await.result(f3, 1 second)
    result must be(30)
    //#flat-map
  }

  "demonstrate usage of filter" in {
    //#filter
    val future1 = Promise.successful(4)
    val future2 = future1.filter(_ % 2 == 0)
    val result = Await.result(future2, 1 second)
    result must be(4)

    val failedFilter = future1.filter(_ % 2 == 1).recover {
      case m: MatchError ⇒ 0 //When filter fails, it will have a MatchError
    }
    val result2 = Await.result(failedFilter, 1 second)
    result2 must be(0) //Can only be 0 when there was a MatchError
    //#filter
  }

  "demonstrate usage of for comprehension" in {
    //#for-comprehension
    val f = for {
      a ← Future(10 / 2) // 10 / 2 = 5
      b ← Future(a + 1) //  5 + 1 = 6
      c ← Future(a - 1) //  5 - 1 = 4
      if c > 3 // Future.filter
    } yield b * c //  6 * 4 = 24

    // Note that the execution of futures a, b, and c
    // are not done in parallel.

    val result = Await.result(f, 1 second)
    result must be(24)
    //#for-comprehension
  }

  "demonstrate wrong way of composing" in {
    val actor1 = system.actorOf(Props[MyActor])
    val actor2 = system.actorOf(Props[MyActor])
    val actor3 = system.actorOf(Props[MyActor])
    val msg1 = 1
    val msg2 = 2
    implicit val timeout = Timeout(5 seconds)
    import scala.concurrent.Await
    import akka.pattern.ask
    //#composing-wrong

    val f1 = ask(actor1, msg1)
    val f2 = ask(actor2, msg2)

    val a = Await.result(f1, 1 second).asInstanceOf[Int]
    val b = Await.result(f2, 1 second).asInstanceOf[Int]

    val f3 = ask(actor3, (a + b))

    val result = Await.result(f3, 1 second).asInstanceOf[Int]
    //#composing-wrong
    result must be(3)
  }

  "demonstrate composing" in {
    val actor1 = system.actorOf(Props[MyActor])
    val actor2 = system.actorOf(Props[MyActor])
    val actor3 = system.actorOf(Props[MyActor])
    val msg1 = 1
    val msg2 = 2
    implicit val timeout = Timeout(5 seconds)
    import scala.concurrent.Await
    import akka.pattern.ask
    //#composing

    val f1 = ask(actor1, msg1)
    val f2 = ask(actor2, msg2)

    val f3 = for {
      a ← f1.mapTo[Int]
      b ← f2.mapTo[Int]
      c ← ask(actor3, (a + b)).mapTo[Int]
    } yield c

    val result = Await.result(f3, 1 second).asInstanceOf[Int]
    //#composing
    result must be(3)
  }

  "demonstrate usage of sequence with actors" in {
    implicit val timeout = Timeout(5 seconds)
    val oddActor = system.actorOf(Props[OddActor])
    //#sequence-ask
    // oddActor returns odd numbers sequentially from 1 as a List[Future[Int]]
    val listOfFutures = List.fill(100)(akka.pattern.ask(oddActor, GetNext).mapTo[Int])

    // now we have a Future[List[Int]]
    val futureList = Future.sequence(listOfFutures)

    // Find the sum of the odd numbers
    val oddSum = Await.result(futureList.map(_.sum), 1 second).asInstanceOf[Int]
    oddSum must be(10000)
    //#sequence-ask
  }

  "demonstrate usage of sequence" in {
    //#sequence
    val futureList = Future.sequence((1 to 100).toList.map(x ⇒ Future(x * 2 - 1)))
    val oddSum = Await.result(futureList.map(_.sum), 1 second).asInstanceOf[Int]
    oddSum must be(10000)
    //#sequence
  }

  "demonstrate usage of traverse" in {
    //#traverse
    val futureList = Future.traverse((1 to 100).toList)(x ⇒ Future(x * 2 - 1))
    val oddSum = Await.result(futureList.map(_.sum), 1 second).asInstanceOf[Int]
    oddSum must be(10000)
    //#traverse
  }

  "demonstrate usage of fold" in {
    //#fold
    val futures = for (i ← 1 to 1000) yield Future(i * 2) // Create a sequence of Futures
    val futureSum = Future.fold(futures)(0)(_ + _)
    Await.result(futureSum, 1 second) must be(1001000)
    //#fold
  }

  "demonstrate usage of reduce" in {
    //#reduce
    val futures = for (i ← 1 to 1000) yield Future(i * 2) // Create a sequence of Futures
    val futureSum = Future.reduce(futures)(_ + _)
    Await.result(futureSum, 1 second) must be(1001000)
    //#reduce
  }

  "demonstrate usage of recover" in {
    implicit val timeout = Timeout(5 seconds)
    val actor = system.actorOf(Props[MyActor])
    val msg1 = -1
    //#recover
    val future = akka.pattern.ask(actor, msg1) recover {
      case e: ArithmeticException ⇒ 0
    }
    //#recover
    Await.result(future, 1 second) must be(0)
  }

  "demonstrate usage of recoverWith" in {
    implicit val timeout = Timeout(5 seconds)
    val actor = system.actorOf(Props[MyActor])
    val msg1 = -1
    //#try-recover
    val future = akka.pattern.ask(actor, msg1) recoverWith {
      case e: ArithmeticException        ⇒ Promise.successful(0)
      case foo: IllegalArgumentException ⇒ Promise.failed[Int](new IllegalStateException("All br0ken!"))
    }
    //#try-recover
    Await.result(future, 1 second) must be(0)
  }

  "demonstrate usage of zip" in {
    val future1 = Future { "foo" }
    val future2 = Future { "bar" }
    //#zip
    val future3 = future1 zip future2 map { case (a, b) ⇒ a + " " + b }
    //#zip
    Await.result(future3, 1 second) must be("foo bar")
  }

  "demonstrate usage of andThen" in {
    def loadPage(s: String) = s
    val url = "foo bar"
    def log(cause: Throwable) = ()
    def watchSomeTV = ()
    //#and-then
    val result = Future { loadPage(url) } andThen {
      case Left(exception) ⇒ log(exception)
    } andThen {
      case _ ⇒ watchSomeTV
    }
    //#and-then
    Await.result(result, 1 second) must be("foo bar")
  }

  "demonstrate usage of fallbackTo" in {
    val future1 = Future { "foo" }
    val future2 = Future { "bar" }
    val future3 = Future { "pigdog" }
    //#fallback-to
    val future4 = future1 fallbackTo future2 fallbackTo future3
    //#fallback-to
    Await.result(future4, 1 second) must be("foo")
  }

  "demonstrate usage of onSuccess & onFailure & onComplete" in {
    {
      val future = Future { "foo" }
      //#onSuccess
      future onSuccess {
        case "bar"     ⇒ println("Got my bar alright!")
        case x: String ⇒ println("Got some random string: " + x)
      }
      //#onSuccess
      Await.result(future, 1 second) must be("foo")
    }
    {
      val future = Promise.failed[String](new IllegalStateException("OHNOES"))
      //#onFailure
      future onFailure {
        case ise: IllegalStateException if ise.getMessage == "OHNOES" ⇒
        //OHNOES! We are in deep trouble, do something!
        case e: Exception ⇒
        //Do something else
      }
      //#onFailure
    }
    {
      val future = Future { "foo" }
      def doSomethingOnSuccess(r: String) = ()
      def doSomethingOnFailure(t: Throwable) = ()
      //#onComplete
      future onComplete {
        case Right(result) ⇒ doSomethingOnSuccess(result)
        case Left(failure) ⇒ doSomethingOnFailure(failure)
      }
      //#onComplete
      Await.result(future, 1 second) must be("foo")
    }
  }

  "demonstrate usage of Promise.success & Promise.failed" in {
    //#successful
    val future = Promise.successful("Yay!")
    //#successful
    //#failed
    val otherFuture = Promise.failed[String](new IllegalArgumentException("Bang!"))
    //#failed
    Await.result(future, 1 second) must be("Yay!")
    intercept[IllegalArgumentException] { Await.result(otherFuture, 1 second) }
  }

}
