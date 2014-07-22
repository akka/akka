/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.collection.immutable
import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import akka.testkit.EventFilter
import scala.util.Failure
import scala.util.control.NoStackTrace
import akka.stream.scaladsl.Flow
import scala.util.Try
import scala.util.Success

object FlowTransformRecoverSpec {
  abstract class TryRecoveryTransformer[T, U] extends Transformer[T, U] {
    def onNext(element: Try[T]): immutable.Seq[U]

    override def onNext(element: T): immutable.Seq[U] = onNext(Success(element))
    override def onError(cause: Throwable) = ()
    override def onTermination(cause: Option[Throwable]): immutable.Seq[U] = cause match {
      case None    ⇒ Nil
      case Some(e) ⇒ onNext(Failure(e))
    }
  }
}

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowTransformRecoverSpec extends AkkaSpec {
  import FlowTransformRecoverSpec._

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  "A Flow with transformRecover operations" must {
    "produce one-to-one transformation as expected" in {
      val p = Flow(List(1, 2, 3).iterator).toPublisher(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          var tot = 0
          override def onNext(elem: Int) = {
            tot += elem
            List(tot)
          }
          override def onError(e: Throwable) = ()
          override def onTermination(e: Option[Throwable]) = e match {
            case None    ⇒ Nil
            case Some(_) ⇒ List(-1)
          }
        }).
        toPublisher(materializer)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(1)
      subscriber.expectNext(1)
      subscriber.expectNoMsg(200.millis)
      subscription.request(2)
      subscriber.expectNext(3)
      subscriber.expectNext(6)
      subscriber.expectComplete()
    }

    "produce one-to-several transformation as expected" in {
      val p = Flow(List(1, 2, 3).iterator).toPublisher(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          var tot = 0
          override def onNext(elem: Int) = {
            tot += elem
            Vector.fill(elem)(tot)
          }
          override def onError(e: Throwable) = ()
          override def onTermination(e: Option[Throwable]) = e match {
            case None    ⇒ Nil
            case Some(_) ⇒ List(-1)
          }
        }).
        toPublisher(materializer)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(4)
      subscriber.expectNext(1)
      subscriber.expectNext(3)
      subscriber.expectNext(3)
      subscriber.expectNext(6)
      subscriber.expectNoMsg(200.millis)
      subscription.request(100)
      subscriber.expectNext(6)
      subscriber.expectNext(6)
      subscriber.expectComplete()
    }

    "produce dropping transformation as expected" in {
      val p = Flow(List(1, 2, 3, 4).iterator).toPublisher(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          var tot = 0
          override def onNext(elem: Int) = {
            tot += elem
            if (elem % 2 == 0) Nil else List(tot)
          }
          override def onError(e: Throwable) = ()
          override def onTermination(e: Option[Throwable]) = e match {
            case None    ⇒ Nil
            case Some(_) ⇒ List(-1)
          }
        }).
        toPublisher(materializer)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(1)
      subscriber.expectNext(1)
      subscriber.expectNoMsg(200.millis)
      subscription.request(1)
      subscriber.expectNext(6)
      subscription.request(1)
      subscriber.expectComplete()
    }

    "produce multi-step transformation as expected" in {
      val p = Flow(List("a", "bc", "def").iterator).toPublisher(materializer)
      val p2 = Flow(p).
        transform(new TryRecoveryTransformer[String, Int] {
          var concat = ""
          override def onNext(element: Try[String]) = {
            concat += element
            List(concat.length)
          }
        }).
        transform(new Transformer[Int, Int] {
          var tot = 0
          override def onNext(length: Int) = {
            tot += length
            List(tot)
          }
          override def onError(e: Throwable) = ()
          override def onTermination(e: Option[Throwable]) = e match {
            case None    ⇒ Nil
            case Some(_) ⇒ List(-1)
          }
        }).
        toPublisher(materializer)
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c1)
      val sub1 = c1.expectSubscription()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c2)
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      sub2.request(2)
      c1.expectNext(10)
      c2.expectNext(10)
      c2.expectNext(31)
      c1.expectNoMsg(200.millis)
      sub1.request(2)
      sub2.request(2)
      c1.expectNext(31)
      c1.expectNext(64)
      c2.expectNext(64)
      c1.expectComplete()
      c2.expectComplete()
    }

    "invoke onComplete when done" in {
      val p = Flow(List("a").iterator).toPublisher(materializer)
      val p2 = Flow(p).
        transform(new TryRecoveryTransformer[String, String] {
          var s = ""
          override def onNext(element: Try[String]) = {
            s += element
            Nil
          }
          override def onTermination(e: Option[Throwable]) = List(s + "B")
        }).
        toPublisher(materializer)
      val c = StreamTestKit.SubscriberProbe[String]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(1)
      c.expectNext("Success(a)B")
      c.expectComplete()
    }

    "allow cancellation using isComplete" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val p2 = Flow(p).
        transform(new TryRecoveryTransformer[Int, Int] {
          var s = ""
          override def onNext(element: Try[Int]) = {
            s += element
            List(element.get)
          }
          override def isComplete = s == "Success(1)"
        }).
        toPublisher(materializer)
      val proc = p.expectSubscription
      val c = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(10)
      proc.sendNext(1)
      proc.sendNext(2)
      c.expectNext(1)
      c.expectComplete()
      proc.expectCancellation()
    }

    "call onComplete after isComplete signaled completion" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val p2 = Flow(p).
        transform(new TryRecoveryTransformer[Int, Int] {
          var s = ""
          override def onNext(element: Try[Int]) = {
            s += element
            List(element.get)
          }
          override def isComplete = s == "Success(1)"
          override def onTermination(e: Option[Throwable]) = List(s.length + 10)
        }).
        toPublisher(materializer)
      val proc = p.expectSubscription
      val c = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(10)
      proc.sendNext(1)
      proc.sendNext(2)
      c.expectNext(1)
      c.expectNext(20)
      c.expectComplete()
      proc.expectCancellation()
    }

    "report error when exception is thrown" in {
      val p = Flow(List(1, 2, 3).iterator).toPublisher(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          override def onNext(elem: Int) = {
            if (elem == 2) throw new IllegalArgumentException("two not allowed")
            else List(elem, elem)
          }
          override def onError(e: Throwable) = List(-1)
        }).
        toPublisher(materializer)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      EventFilter[IllegalArgumentException]("two not allowed") intercept {
        subscription.request(1)
        subscriber.expectNext(1)
        subscriber.expectNoMsg(200.millis)
        subscription.request(100)
        subscriber.expectNext(1)
        subscriber.expectError().getMessage should be("two not allowed")
        subscriber.expectNoMsg(200.millis)
      }
    }

    "report error after emitted elements" in {
      EventFilter[IllegalArgumentException]("two not allowed") intercept {
        val p2 = Flow(List(1, 2, 3).iterator).
          mapConcat { elem ⇒
            if (elem == 2) throw new IllegalArgumentException("two not allowed")
            else (1 to 5).map(elem * 100 + _)
          }.
          transform(new Transformer[Int, Int] {
            override def onNext(elem: Int) = List(elem)
            override def onError(e: Throwable) = ()
            override def onTermination(e: Option[Throwable]) = e match {
              case None    ⇒ Nil
              case Some(_) ⇒ List(-1, -2, -3)
            }
          }).
          toPublisher(materializer)
        val subscriber = StreamTestKit.SubscriberProbe[Int]()
        p2.subscribe(subscriber)
        val subscription = subscriber.expectSubscription()

        subscription.request(1)
        subscriber.expectNext(101)
        subscriber.expectNoMsg(100.millis)
        subscription.request(1)
        subscriber.expectNext(102)
        subscriber.expectNoMsg(100.millis)
        subscription.request(1)
        subscriber.expectNext(103)
        subscriber.expectNoMsg(100.millis)
        subscription.request(1)
        subscriber.expectNext(104)
        subscriber.expectNoMsg(100.millis)
        subscription.request(1)
        subscriber.expectNext(105)
        subscriber.expectNoMsg(100.millis)

        subscription.request(1)
        subscriber.expectNext(-1)
        subscriber.expectNoMsg(100.millis)
        subscription.request(10)
        subscriber.expectNext(-2)
        subscriber.expectNext(-3)
        subscriber.expectComplete()
        subscriber.expectNoMsg(200.millis)
      }
    }

    case class TE(message: String) extends RuntimeException(message) with NoStackTrace

    "transform errors in sequence with normal messages" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val p2 = Flow(p).
        transform(new Transformer[Int, String] {
          var s = ""
          override def onNext(element: Int) = {
            s += element.toString
            List(s)
          }
          override def onError(ex: Throwable) = ()
          override def onTermination(ex: Option[Throwable]) = {
            ex match {
              case None ⇒ Nil
              case Some(e) ⇒
                s += e.getMessage
                List(s)
            }
          }
        }).
        toPublisher(materializer)
      val proc = p.expectSubscription()
      val c = StreamTestKit.SubscriberProbe[String]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      proc.sendNext(0)
      proc.sendError(TE("1"))
      // Request late to prove the in-sequence nature
      s.request(10)
      c.expectNext("0")
      c.expectNext("01")
      c.expectComplete()
    }

    "forward errors when received and thrown" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          override def onNext(in: Int) = List(in)
          override def onError(e: Throwable) = throw e
        }).
        toPublisher(materializer)
      val proc = p.expectSubscription()
      val c = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(10)
      EventFilter[TE](occurrences = 1) intercept {
        proc.sendError(TE("1"))
        c.expectError(TE("1"))
      }
    }

    "support cancel as expected" in {
      val p = Flow(List(1, 2, 3).iterator).toPublisher(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          override def onNext(elem: Int) = List(elem, elem)
          override def onError(e: Throwable) = List(-1)
        }).
        toPublisher(materializer)
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(2)
      subscriber.expectNext(1)
      subscription.cancel()
      subscriber.expectNext(1)
      subscriber.expectNoMsg(500.millis)
      subscription.request(2)
      subscriber.expectNoMsg(200.millis)
    }
  }

}