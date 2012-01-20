/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.future

import org.scalatest.{ BeforeAndAfterAll, WordSpec }
import org.scalatest.matchers.MustMatchers
import akka.testkit._
import akka.actor.Actor
import akka.actor.Props
import akka.actor.Status.Failure
import akka.dispatch.Future
import akka.dispatch.Await
import akka.util.duration._
import akka.dispatch.Promise

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

  "demonstrate usage of blocking from actor" in {
    val actor = system.actorOf(Props[MyActor])
    val msg = "hello"
    //#ask-blocking
    import akka.dispatch.Await
    import akka.pattern.ask

    implicit val timeout = system.settings.ActorTimeout
    val future = actor ? msg // enabled by the “ask” import
    val result = Await.result(future, timeout.duration).asInstanceOf[String]
    //#ask-blocking
    result must be("HELLO")
  }

  "demonstrate usage of mapTo" in {
    val actor = system.actorOf(Props[MyActor])
    val msg = "hello"
    implicit val timeout = system.settings.ActorTimeout
    //#map-to
    import akka.dispatch.Future
    import akka.pattern.ask

    val future: Future[String] = ask(actor, msg).mapTo[String]
    //#map-to
    Await.result(future, timeout.duration) must be("HELLO")
  }

  "demonstrate usage of simple future eval" in {
    //#future-eval
    import akka.dispatch.Await
    import akka.dispatch.Future
    import akka.util.duration._

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

  "demonstrate usage of for comprehension" in {
    //#for-comprehension
    val f = for {
      a ← Future(10 / 2) // 10 / 2 = 5
      b ← Future(a + 1) //  5 + 1 = 6
      c ← Future(a - 1) //  5 - 1 = 4
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
    implicit val timeout = system.settings.ActorTimeout
    import akka.dispatch.Await
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
    implicit val timeout = system.settings.ActorTimeout
    import akka.dispatch.Await
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
    implicit val timeout = system.settings.ActorTimeout
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
    implicit val timeout = system.settings.ActorTimeout
    val actor = system.actorOf(Props[MyActor])
    val msg1 = -1
    //#recover
    val future = akka.pattern.ask(actor, msg1) recover {
      case e: ArithmeticException ⇒ 0
    }
    //#recover
    Await.result(future, 1 second) must be(0)
  }

}
