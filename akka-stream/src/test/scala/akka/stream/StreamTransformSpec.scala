/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import scala.concurrent.duration._
import akka.stream.testkit.StreamTestKit
import akka.testkit.AkkaSpec
import akka.testkit.EventFilter
import com.typesafe.config.ConfigFactory

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class StreamTransformSpec extends AkkaSpec(ConfigFactory.parseString("akka.actor.debug.receive=off\nakka.loglevel=INFO")) {

  import system.dispatcher

  val gen = ProcessorGenerator(GeneratorSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2))

  "A Stream with transform operations" must {
    "produce one-to-one transformation as expected" in {
      val p = Stream(List(1, 2, 3).iterator).toProducer(gen)
      val p2 = Stream(p).
        transform(0)((tot, elem) ⇒ (tot + elem, List(tot + elem))).
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
      val p = Stream(List(1, 2, 3).iterator).toProducer(gen)
      val p2 = Stream(p).
        transform(0)((tot, elem) ⇒ (tot + elem, Vector.fill(elem)(tot + elem))).
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
      val p = Stream(List(1, 2, 3, 4).iterator).toProducer(gen)
      val p2 = Stream(p).
        transform(0)((tot, elem) ⇒ (tot + elem, if (elem % 2 == 0) Nil else List(tot + elem))).
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
      val p = Stream(List("a", "bc", "def").iterator).toProducer(gen)
      val p2 = Stream(p).
        transform("") { (str, elem) ⇒
          val concat = str + elem
          (concat, List(concat.length))
        }.
        transform(0)((tot, length) ⇒ (tot + length, List(tot + length))).
        toProducer(gen)
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
      val p = Stream(List("a").iterator).toProducer(gen)
      val p2 = Stream(p).transform("")((s, in) ⇒ (s + in, Nil), x ⇒ List(x + "B")).toProducer(gen)
      val c = StreamTestKit.consumerProbe[String]
      p2.produceTo(c)
      val s = c.expectSubscription()
      s.requestMore(1)
      c.expectNext("aB")
      c.expectComplete()
    }

    "allow cancellation using isComplete" in {
      val p = StreamTestKit.producerProbe[Int]
      val p2 = Stream(p).transform("")((s, in) ⇒ (s + in, List(in)), isComplete = _ == "1").toProducer(gen)
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
      val p2 = Stream(p).transform("")((s, in) ⇒ (s + in, List(in)), onComplete = x ⇒ List(x.size + 10), isComplete = _ == "1").toProducer(gen)
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
    }

    "report error when exception is thrown" in {
      val p = Stream(List(1, 2, 3).iterator).toProducer(gen)
      val p2 = Stream(p).
        transform(0) { (_, elem) ⇒
          if (elem == 2) throw new IllegalArgumentException("two not allowed") else (0, List(elem, elem))
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

    "support cancel as expected" in {
      val p = Stream(List(1, 2, 3).iterator).toProducer(gen)
      val p2 = Stream(p).
        transform(0) { (_, elem) ⇒ (0, List(elem, elem)) }.
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

    "support producing elements from empty inputs" in {
      val p = Stream(List.empty[Int].iterator).toProducer(gen)
      val p2 = Stream(p).transform(List(1, 2, 3))((s, _) ⇒ (s, Nil), onComplete = s ⇒ s).
        toProducer(gen)
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
