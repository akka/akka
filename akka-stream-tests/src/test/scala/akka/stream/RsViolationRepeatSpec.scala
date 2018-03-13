/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream

import java.lang
import java.util.concurrent.atomic.AtomicReference
import java.util.function

import akka.stream.scaladsl.{ Flow, Sink, Source }
import akka.stream.testkit.StreamSpec
import org.reactivestreams.tck.flow.support.HelperPublisher
import org.reactivestreams.{ Subscriber, Subscription }

import scala.concurrent.duration._

class RsViolationRepeatSpec extends StreamSpec("akka.loglevel=DEBUG") {

  implicit val mat = ActorMaterializer()

  "The Flow-processor" should {

    "fail the fail" in {
      for {
        n ← 0 to 10000
      } {
        println(n)
        val subscriptions = new AtomicReference[Subscription]()
        val publisher = new HelperPublisher[Int](0, 0, new org.reactivestreams.tck.flow.support.Function[java.lang.Integer, Int] {
          def apply(in: Integer): Int = in
        }, system.dispatcher)
        val processor = Flow[Int].toProcessor.run()

        publisher.subscribe(processor)
        processor.subscribe(new Subscriber[Int] {

          def onError(t: Throwable): Unit =
            if (subscriptions.get() == null) throw new RuntimeException("onComplete before subscribe")

          def onComplete(): Unit =
            if (subscriptions.get() == null) throw new RuntimeException("onComplete before subscribe")

          def onNext(t: Int): Unit =
            if (subscriptions.get() == null) throw new RuntimeException("onNext before subscribe")

          def onSubscribe(s: Subscription): Unit =
            if (!subscriptions.compareAndSet(null, s)) throw new RuntimeException("onSubscribe but already subscribed")

        })
        awaitAssert(
          subscriptions.get() should not be null,
          max = 5.seconds
        )
        subscriptions.get().cancel()

      }

    }

  }

}
