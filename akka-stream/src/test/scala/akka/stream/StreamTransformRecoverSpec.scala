/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.testkit.AkkaSpec
import akka.stream.impl.IteratorProducer
import akka.testkit.EventFilter
import scala.util.Failure
import scala.util.control.NoStackTrace

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class StreamTransformRecoverSpec extends AkkaSpec {

  import system.dispatcher

  val gen = ProcessorGenerator(GeneratorSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2))

  "A Stream with transformRecover operations" must {
    "produce one-to-one transformation as expected" in {
      val p = new IteratorProducer(List(1, 2, 3).iterator)
      val p2 = Stream(p).
        transformRecover(0)((tot, elem) ⇒ (tot + elem.get, List(tot + elem.get))).
        toProducer(gen)
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
      val p = new IteratorProducer(List(1, 2, 3).iterator)
      val p2 = Stream(p).
        transformRecover(0)((tot, elem) ⇒ (tot + elem.get, Vector.fill(elem.get)(tot + elem.get))).
        toProducer(gen)
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
      val p = new IteratorProducer(List(1, 2, 3, 4).iterator)
      val p2 = Stream(p).
        transformRecover(0)((tot, elem) ⇒ (tot + elem.get, if (elem.get % 2 == 0) Nil else List(tot + elem.get))).
        toProducer(gen)
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
      val p = new IteratorProducer(List("a", "bc", "def").iterator)
      val p2 = Stream(p).
        transformRecover("") { (str, elem) ⇒
          val concat = str + elem
          (concat, List(concat.length))
        }.
        transformRecover(0)((tot, length) ⇒ (tot + length.get, List(tot + length.get))).
        toProducer(gen)
      val c1 = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c1)
      val sub1 = c1.expectSubscription()
      val c2 = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c2)
      val sub2 = c2.expectSubscription()
      sub1.requestMore(1)
      sub2.requestMore(2)
      c1.expectNext(10)
      c2.expectNext(10)
      c2.expectNext(31)
      c1.expectNoMsg(200.millis)
      sub1.requestMore(2)
      sub2.requestMore(2)
      c1.expectNext(31)
      c1.expectNext(64)
      c2.expectNext(64)
      c1.expectComplete()
      c2.expectComplete()
    }

    "invoke onComplete when done" in {
      val p = new IteratorProducer(List("a").iterator)
      val p2 = Stream(p).transformRecover("")((s, in) ⇒ (s + in, Nil), x ⇒ List(x + "B")).toProducer(gen)
      val c = StreamTestKit.consumerProbe[String]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(1)
      c.expectNext("Success(a)B")
      c.expectComplete()
    }

    "allow cancellation using isComplete" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Stream(p).transformRecover("")((s, in) ⇒ (s + in, List(in.get)), isComplete = _ == "Success(1)").toProducer(gen)
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
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Stream(p).transformRecover("")(
        (s, in) ⇒ (s + in, List(in.get)),
        onComplete = x ⇒ List(x.size + 10),
        isComplete = _ == "Success(1)")
        .toProducer(gen)
      val proc = p.expectSubscription
      val c = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(10)
      proc.sendNext(1)
      proc.sendNext(2)
      c.expectNext(1)
      c.expectNext(20)
      c.expectComplete()
      proc.expectCancellation()
    }

    "report error when exception is thrown" in {
      val p = new IteratorProducer(List(1, 2, 3).iterator)
      val p2 = Stream(p).
        transformRecover(0) { (_, elem) ⇒
          if (elem.get == 2) throw new IllegalArgumentException("two not allowed") else (0, List(elem.get, elem.get))
        }.
        toProducer(gen)
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
    }

    case class TE(message: String) extends RuntimeException(message) with NoStackTrace

    "transform errors when received" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Stream(p).transformRecover("")(
        { case (s, Failure(ex)) ⇒ (s + ex.getMessage, List(ex)) },
        onComplete = x ⇒ List(TE(x.size + "10")))
        .toProducer(gen)
      val proc = p.expectSubscription
      val c = StreamTestKit.consumerProbe[Throwable]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(10)
      proc.sendError(TE("1"))
      c.expectNext(TE("1"))
      c.expectNext(TE("110"))
      c.expectComplete()
    }

    "forward errors when received and thrown" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Stream(p).transformRecover("")((_, in) ⇒ "" -> List(in.get)).toProducer(gen)
      val proc = p.expectSubscription
      val c = StreamTestKit.consumerProbe[Int]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(10)
      EventFilter[TE](occurrences = 1) intercept {
        proc.sendError(TE("1"))
        c.expectError(TE("1"))
      }
    }

    "support cancel as expected" in {
      val p = new IteratorProducer(List(1, 2, 3).iterator)
      val p2 = Stream(p).
        transformRecover(0) { (_, elem) ⇒ (0, List(elem.get, elem.get)) }.
        toProducer(gen)
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
  }

}