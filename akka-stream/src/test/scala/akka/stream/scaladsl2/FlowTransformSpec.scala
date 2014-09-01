/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.testkit.{ AkkaSpec, StreamTestKit }
import akka.testkit.{ EventFilter, TestProbe }
import com.typesafe.config.ConfigFactory
import scala.collection.immutable.Seq
import scala.concurrent.duration._
import scala.util.control.NoStackTrace
import akka.stream.Transformer
import akka.stream.MaterializerSettings

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowTransformSpec extends AkkaSpec(ConfigFactory.parseString("akka.actor.debug.receive=off\nakka.loglevel=INFO")) {

  implicit val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  "A Flow with transform operations" must {
    "produce one-to-one transformation as expected" in {
      val p = FlowFrom(List(1, 2, 3)).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[Int, Int] {
          var tot = 0
          override def onNext(elem: Int) = {
            tot += elem
            List(tot)
          }
        }).
        toPublisher()
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
      val p = FlowFrom(List(1, 2, 3)).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[Int, Int] {
          var tot = 0
          override def onNext(elem: Int) = {
            tot += elem
            Vector.fill(elem)(tot)
          }
        }).
        toPublisher()
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
      val p = FlowFrom(List(1, 2, 3, 4)).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[Int, Int] {
          var tot = 0
          override def onNext(elem: Int) = {
            tot += elem
            if (elem % 2 == 0) {
              Nil
            } else {
              List(tot)
            }
          }
        }).
        toPublisher()
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
      val p = FlowFrom(List("a", "bc", "def")).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[String, Int] {
          var concat = ""
          override def onNext(elem: String) = {
            concat += elem
            List(concat.length)
          }
        }).
        transform("transform", () ⇒ new Transformer[Int, Int] {
          var tot = 0
          override def onNext(length: Int) = {
            tot += length
            List(tot)
          }
        }).
        toPublisher()
      val c1 = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c1)
      val sub1 = c1.expectSubscription()
      val c2 = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c2)
      val sub2 = c2.expectSubscription()
      sub1.request(1)
      sub2.request(2)
      c1.expectNext(1)
      c2.expectNext(1)
      c2.expectNext(4)
      c1.expectNoMsg(200.millis)
      sub1.request(2)
      sub2.request(2)
      c1.expectNext(4)
      c1.expectNext(10)
      c2.expectNext(10)
      c1.expectComplete()
      c2.expectComplete()
    }

    "invoke onComplete when done" in {
      val p = FlowFrom(List("a")).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[String, String] {
          var s = ""
          override def onNext(element: String) = {
            s += element
            Nil
          }
          override def onTermination(e: Option[Throwable]) = List(s + "B")
        }).
        toPublisher()
      val c = StreamTestKit.SubscriberProbe[String]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(1)
      c.expectNext("aB")
      c.expectComplete()
    }

    "invoke cleanup when done" in {
      val cleanupProbe = TestProbe()
      val p = FlowFrom(List("a")).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[String, String] {
          var s = ""
          override def onNext(element: String) = {
            s += element
            Nil
          }
          override def onTermination(e: Option[Throwable]) = List(s + "B")
          override def cleanup() = cleanupProbe.ref ! s
        }).
        toPublisher()
      val c = StreamTestKit.SubscriberProbe[String]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(1)
      c.expectNext("aB")
      c.expectComplete()
      cleanupProbe.expectMsg("a")
    }

    "invoke cleanup when done consume" in {
      val cleanupProbe = TestProbe()
      val p = FlowFrom(List("a")).toPublisher()
      FlowFrom(p).
        transform("transform", () ⇒ new Transformer[String, String] {
          var s = "x"
          override def onNext(element: String) = {
            s = element
            List(element)
          }
          override def cleanup() = cleanupProbe.ref ! s
        }).
        withSink(BlackholeSink()).run()
      cleanupProbe.expectMsg("a")
    }

    "invoke cleanup when done after error" in {
      val cleanupProbe = TestProbe()
      val p = FlowFrom(List("a", "b", "c")).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[String, String] {
          var s = ""
          override def onNext(in: String) = {
            if (in == "b") {
              throw new IllegalArgumentException("Not b") with NoStackTrace
            } else {
              val out = s + in
              s += in.toUpperCase
              List(out)
            }
          }
          override def onTermination(e: Option[Throwable]) = List(s + "B")
          override def cleanup() = cleanupProbe.ref ! s
        }).
        toPublisher()
      val c = StreamTestKit.SubscriberProbe[String]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(1)
      c.expectNext("a")
      s.request(1)
      c.expectError()
      cleanupProbe.expectMsg("A")
    }

    "allow cancellation using isComplete" in {
      val p = StreamTestKit.PublisherProbe[Int]()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[Int, Int] {
          var s = ""
          override def onNext(element: Int) = {
            s += element
            List(element)
          }
          override def isComplete = s == "1"
        }).
        toPublisher()
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
      val cleanupProbe = TestProbe()
      val p = StreamTestKit.PublisherProbe[Int]()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[Int, Int] {
          var s = ""
          override def onNext(element: Int) = {
            s += element
            List(element)
          }
          override def isComplete = s == "1"
          override def onTermination(e: Option[Throwable]) = List(s.length + 10)
          override def cleanup() = cleanupProbe.ref ! s
        }).
        toPublisher()
      val proc = p.expectSubscription
      val c = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(c)
      val s = c.expectSubscription()
      s.request(10)
      proc.sendNext(1)
      proc.sendNext(2)
      c.expectNext(1)
      c.expectNext(11)
      c.expectComplete()
      proc.expectCancellation()
      cleanupProbe.expectMsg("1")
    }

    "report error when exception is thrown" in {
      val p = FlowFrom(List(1, 2, 3)).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[Int, Int] {
          override def onNext(elem: Int) = {
            if (elem == 2) {
              throw new IllegalArgumentException("two not allowed")
            } else {
              List(elem, elem)
            }
          }
        }).
        toPublisher()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      EventFilter[IllegalArgumentException]("two not allowed") intercept {
        subscription.request(100)
        subscriber.expectNext(1)
        subscriber.expectNext(1)
        subscriber.expectError().getMessage should be("two not allowed")
        subscriber.expectNoMsg(200.millis)
      }
    }

    "support cancel as expected" in {
      val p = FlowFrom(List(1, 2, 3)).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[Int, Int] {
          override def onNext(elem: Int) = List(elem, elem)
        }).
        toPublisher()
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

    "support producing elements from empty inputs" in {
      val p = FlowFrom(List.empty[Int]).toPublisher()
      val p2 = FlowFrom(p).
        transform("transform", () ⇒ new Transformer[Int, Int] {
          override def onNext(elem: Int) = Nil
          override def onTermination(e: Option[Throwable]) = List(1, 2, 3)
        }).
        toPublisher()
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      p2.subscribe(subscriber)
      val subscription = subscriber.expectSubscription()
      subscription.request(4)
      subscriber.expectNext(1)
      subscriber.expectNext(2)
      subscriber.expectNext(3)
      subscriber.expectComplete()

    }

    "support converting onComplete into onError" in {
      val subscriber = StreamTestKit.SubscriberProbe[Int]()
      FlowFrom(List(5, 1, 2, 3)).transform("transform", () ⇒ new Transformer[Int, Int] {
        var expectedNumberOfElements: Option[Int] = None
        var count = 0
        override def onNext(elem: Int) =
          if (expectedNumberOfElements.isEmpty) {
            expectedNumberOfElements = Some(elem)
            Nil
          } else {
            count += 1
            List(elem)
          }
        override def onTermination(err: Option[Throwable]) = err match {
          case Some(e) ⇒ Nil
          case None ⇒
            expectedNumberOfElements match {
              case Some(expected) if count != expected ⇒
                throw new RuntimeException(s"Expected $expected, got $count") with NoStackTrace
              case _ ⇒ Nil
            }
        }
      }).publishTo(subscriber)

      val subscription = subscriber.expectSubscription()
      subscription.request(10)

      subscriber.expectNext(1)
      subscriber.expectNext(2)
      subscriber.expectNext(3)
      subscriber.expectError().getMessage should be("Expected 5, got 3")
    }

    "be safe to reuse" in {
      val flow = FlowFrom(1 to 3).transform("transform", () ⇒
        new Transformer[Int, Int] {
          var count = 0

          override def onNext(elem: Int): Seq[Int] = {
            count += 1
            List(count)
          }
        })

      val s1 = StreamTestKit.SubscriberProbe[Int]()
      flow.publishTo(s1)
      s1.expectSubscription().request(3)
      s1.expectNext(1, 2, 3)
      s1.expectComplete()

      val s2 = StreamTestKit.SubscriberProbe[Int]()
      flow.publishTo(s2)
      s2.expectSubscription().request(3)
      s2.expectNext(1, 2, 3)
      s2.expectComplete()
    }
  }

}
