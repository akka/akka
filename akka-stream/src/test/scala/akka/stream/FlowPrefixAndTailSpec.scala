/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import akka.stream.scaladsl.Flow
import scala.concurrent.Await
import scala.concurrent.duration._
import akka.stream.impl.EmptyProducer
import org.reactivestreams.api.Producer
import scala.util.control.NoStackTrace

class FlowPrefixAndTailSpec extends AkkaSpec {

  val m = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  "PrefixAndTail" must {

    val testException = new Exception("test") with NoStackTrace

    "work on empty input" in {
      Await.result(Flow(Nil).prefixAndTail(10).toFuture(m), 3.seconds) should be((Nil, EmptyProducer))
    }

    "work on short input" in {
      Await.result(Flow(List(1, 2, 3)).prefixAndTail(10).toFuture(m), 3.seconds) should be((List(1, 2, 3), EmptyProducer))
    }

    "work on longer inputs" in {
      val (takes, tail) = Await.result(Flow((1 to 10).iterator).prefixAndTail(5).toFuture(m), 3.seconds)
      takes should be(1 to 5)
      Await.result(Flow(tail).grouped(6).toFuture(m), 3.seconds) should be(6 to 10)
    }

    "handle zero take count" in {
      val (takes, tail) = Await.result(Flow((1 to 10).iterator).prefixAndTail(0).toFuture(m), 3.seconds)
      takes should be(Nil)
      Await.result(Flow(tail).grouped(11).toFuture(m), 3.seconds) should be(1 to 10)
    }

    "work if size of take is equals to stream size" in {
      val (takes, tail) = Await.result(Flow((1 to 10).iterator).prefixAndTail(10).toFuture(m), 3.seconds)
      takes should be(1 to 10)
      val consumer = StreamTestKit.consumerProbe[Int]
      Flow(tail).produceTo(m, consumer)
      consumer.expectCompletedOrSubscriptionFollowedByComplete()
    }

    "handle onError when no substream open" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[(Seq[Int], Producer[Int])]

      Flow(producer).prefixAndTail(3).produceTo(m, consumer)

      val upstream = producer.expectSubscription()
      val downstream = consumer.expectSubscription()

      downstream.requestMore(1)

      upstream.expectRequestMore()
      upstream.sendNext(1)
      upstream.sendError(testException)

      consumer.expectError(testException)
    }

    "handle onError when substream is open" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[(Seq[Int], Producer[Int])]

      Flow(producer).prefixAndTail(1).produceTo(m, consumer)

      val upstream = producer.expectSubscription()
      val downstream = consumer.expectSubscription()

      downstream.requestMore(1000)

      upstream.expectRequestMore()
      upstream.sendNext(1)

      val (head, tail) = consumer.expectNext()
      head should be(List(1))
      consumer.expectComplete()

      val substreamConsumer = StreamTestKit.consumerProbe[Int]
      Flow(tail).produceTo(m, substreamConsumer)
      val subUpstream = substreamConsumer.expectSubscription()

      upstream.sendError(testException)
      substreamConsumer.expectError(testException)

    }

    "handle master stream cancellation" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[(Seq[Int], Producer[Int])]

      Flow(producer).prefixAndTail(3).produceTo(m, consumer)

      val upstream = producer.expectSubscription()
      val downstream = consumer.expectSubscription()

      downstream.requestMore(1)

      upstream.expectRequestMore()
      upstream.sendNext(1)

      downstream.cancel()
      upstream.expectCancellation()
    }

    "handle substream cancellation" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[(Seq[Int], Producer[Int])]

      Flow(producer).prefixAndTail(1).produceTo(m, consumer)

      val upstream = producer.expectSubscription()
      val downstream = consumer.expectSubscription()

      downstream.requestMore(1000)

      upstream.expectRequestMore()
      upstream.sendNext(1)

      val (head, tail) = consumer.expectNext()
      head should be(List(1))
      consumer.expectComplete()

      val substreamConsumer = StreamTestKit.consumerProbe[Int]
      Flow(tail).produceTo(m, substreamConsumer)
      substreamConsumer.expectSubscription().cancel()

      upstream.expectCancellation()

    }

  }

}
