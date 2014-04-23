/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.AkkaSpec
import akka.testkit.EventFilter
import com.typesafe.config.ConfigFactory
import akka.stream.scaladsl.Flow
import akka.testkit.TestProbe
import scala.util.control.NoStackTrace

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class FlowTransformSpec extends AkkaSpec(ConfigFactory.parseString("akka.actor.debug.receive=off\nakka.loglevel=INFO")) {

  import system.dispatcher

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2))

  "A Flow with transform operations" must {
    "produce one-to-one transformation as expected" in {
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          var tot = 0
          override def onNext(elem: Int) = {
            tot += elem
            List(tot)
          }
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(1)
      consumer.expectNext(1)
      consumer.expectNoMsg(200.millis)
      subscription.requestMore(2)
      consumer.expectNext(3)
      consumer.expectNext(6)
      consumer.expectComplete()
    }

    "produce one-to-several transformation as expected" in {
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          var tot = 0
          override def onNext(elem: Int) = {
            tot += elem
            Vector.fill(elem)(tot)
          }
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(4)
      consumer.expectNext(1)
      consumer.expectNext(3)
      consumer.expectNext(3)
      consumer.expectNext(6)
      consumer.expectNoMsg(200.millis)
      subscription.requestMore(100)
      consumer.expectNext(6)
      consumer.expectNext(6)
      consumer.expectComplete()
    }

    "produce dropping transformation as expected" in {
      val p = Flow(List(1, 2, 3, 4).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          var tot = 0
          override def onNext(elem: Int) = {
            tot += elem
            if (elem % 2 == 0) Nil else List(tot)
          }
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(1)
      consumer.expectNext(1)
      consumer.expectNoMsg(200.millis)
      subscription.requestMore(1)
      consumer.expectNext(6)
      subscription.requestMore(1)
      consumer.expectComplete()
    }

    "produce multi-step transformation as expected" in {
      val p = Flow(List("a", "bc", "def").iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[String, Int] {
          var concat = ""
          override def onNext(elem: String) = {
            concat += elem
            List(concat.length)
          }
        }).
        transform(new Transformer[Int, Int] {
          var tot = 0
          override def onNext(length: Int) = {
            tot += length
            List(tot)
          }
        }).
        toProducer(materializer)
      val c1 = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c1)
      val sub1 = c1.expectSubscription()
      val c2 = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c2)
      val sub2 = c2.expectSubscription()
      sub1.requestMore(1)
      sub2.requestMore(2)
      c1.expectNext(1)
      c2.expectNext(1)
      c2.expectNext(4)
      c1.expectNoMsg(200.millis)
      sub1.requestMore(2)
      sub2.requestMore(2)
      c1.expectNext(4)
      c1.expectNext(10)
      c2.expectNext(10)
      c1.expectComplete()
      c2.expectComplete()
    }

    "invoke onComplete when done" in {
      val p = Flow(List("a").iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[String, String] {
          var s = ""
          override def onNext(element: String) = {
            s += element
            Nil
          }
          override def onComplete() = List(s + "B")
        }).
        toProducer(materializer)
      val c = StreamTestKit.consumerProbe[String]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(1)
      c.expectNext("aB")
      c.expectComplete()
    }

    "invoke cleanup when done" in {
      val cleanupProbe = TestProbe()
      val p = Flow(List("a").iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[String, String] {
          var s = ""
          override def onNext(element: String) = {
            s += element
            Nil
          }
          override def onComplete() = List(s + "B")
          override def cleanup() = cleanupProbe.ref ! s
        }).
        toProducer(materializer)
      val c = StreamTestKit.consumerProbe[String]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(1)
      c.expectNext("aB")
      c.expectComplete()
      cleanupProbe.expectMsg("a")
    }

    "invoke cleanup when done consume" in {
      val cleanupProbe = TestProbe()
      val p = Flow(List("a").iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[String, String] {
          var s = "x"
          override def onNext(element: String) = {
            s = element
            List(element)
          }
          override def cleanup() = cleanupProbe.ref ! s
        }).
        consume(materializer)
      cleanupProbe.expectMsg("a")
    }

    "invoke cleanup when done after error" in {
      val cleanupProbe = TestProbe()
      val p = Flow(List("a", "b", "c").iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[String, String] {
          var s = ""
          override def onNext(in: String) = {
            if (in == "b") throw new IllegalArgumentException("Not b") with NoStackTrace
            else {
              val out = s + in
              s += in.toUpperCase
              List(out)
            }
          }
          override def onComplete() = List(s + "B")
          override def cleanup() = cleanupProbe.ref ! s
        }).
        toProducer(materializer)
      val c = StreamTestKit.consumerProbe[String]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(1)
      c.expectNext("a")
      s.requestMore(1)
      c.expectError()
      cleanupProbe.expectMsg("A")
    }

    "allow cancellation using isComplete" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          var s = ""
          override def onNext(element: Int) = {
            s += element
            List(element)
          }
          override def isComplete = s == "1"
        }).
        toProducer(materializer)
      val proc = p.expectSubscription
      val c = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(10)
      proc.sendNext(1)
      proc.sendNext(2)
      c.expectNext(1)
      c.expectComplete()
      proc.expectCancellation()
    }

    "call onComplete after isComplete signaled completion" in {
      val cleanupProbe = TestProbe()
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          var s = ""
          override def onNext(element: Int) = {
            s += element
            List(element)
          }
          override def isComplete = s == "1"
          override def onComplete() = List(s.length + 10)
          override def cleanup() = cleanupProbe.ref ! s
        }).
        toProducer(materializer)
      val proc = p.expectSubscription
      val c = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(10)
      proc.sendNext(1)
      proc.sendNext(2)
      c.expectNext(1)
      c.expectNext(11)
      c.expectComplete()
      proc.expectCancellation()
      cleanupProbe.expectMsg("1")
    }

    "report error when exception is thrown" in {
      val errProbe = TestProbe()
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          override def onNext(elem: Int) = {
            if (elem == 2) throw new IllegalArgumentException("two not allowed")
            else List(elem, elem)
          }
          override def onError(cause: Throwable): Unit = errProbe.ref ! cause
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      EventFilter[IllegalArgumentException]("two not allowed") intercept {
        subscription.requestMore(100)
        consumer.expectNext(1)
        consumer.expectNext(1)
        consumer.expectError().getMessage should be("two not allowed")
        consumer.expectNoMsg(200.millis)
      }
      errProbe.expectMsgType[IllegalArgumentException].getMessage should be("two not allowed")
    }

    "support cancel as expected" in {
      val p = Flow(List(1, 2, 3).iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          override def onNext(elem: Int) = List(elem, elem)
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(2)
      consumer.expectNext(1)
      subscription.cancel()
      consumer.expectNext(1)
      consumer.expectNoMsg(500.millis)
      subscription.requestMore(2)
      consumer.expectNoMsg(200.millis)
    }

    "support producing elements from empty inputs" in {
      val p = Flow(List.empty[Int].iterator).toProducer(materializer)
      val p2 = Flow(p).
        transform(new Transformer[Int, Int] {
          override def onNext(elem: Int) = Nil
          override def onComplete() = List(1, 2, 3)
        }).
        toProducer(materializer)
      val consumer = StreamTestKit.consumerProbe[Int]
      p2.produceTo(consumer)
      val subscription = consumer.expectSubscription()
      subscription.requestMore(4)
      consumer.expectNext(1)
      consumer.expectNext(2)
      consumer.expectNext(3)
      consumer.expectComplete()

    }
  }

}
