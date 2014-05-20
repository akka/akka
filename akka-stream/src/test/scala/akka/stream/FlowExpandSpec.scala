/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import akka.stream.scaladsl.Flow
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowExpandSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  "Expand" must {

    "pass-through elements unchanged when there is no rate difference" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      // Simply repeat the last element as an extrapolation step
      Flow(producer).expand[Int, Int](seed = i ⇒ i, extrapolate = i ⇒ (i, i)).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      for (i ← 1 to 100) {
        // Order is important here: If the request comes first it will be extrapolated!
        autoProducer.sendNext(i)
        sub.requestMore(1)
        consumer.expectNext(i)
      }

      sub.cancel()
    }

    "expand elements while upstream is silent" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      // Simply repeat the last element as an extrapolation step
      Flow(producer).expand[Int, Int](seed = i ⇒ i, extrapolate = i ⇒ (i, i)).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      autoProducer.sendNext(42)

      for (i ← 1 to 100) {
        sub.requestMore(1)
        consumer.expectNext(42)
      }

      autoProducer.sendNext(-42)
      sub.requestMore(1)
      consumer.expectNext(-42)

      sub.cancel()
    }

    "work on a variable rate chain" in {
      val future = Flow((1 to 100).iterator)
        .map { i ⇒ if (ThreadLocalRandom.current().nextBoolean()) Thread.sleep(10); i }
        .expand[Int, Int](seed = i ⇒ i, extrapolate = i ⇒ (i, i))
        .fold(Set.empty[Int])(_ + _)
        .toFuture(materializer)

      Await.result(future, 10.seconds) should be(Set.empty[Int] ++ (1 to 100))
    }

    "backpressure producer when consumer is slower" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      Flow(producer).expand[Int, Int](seed = i ⇒ i, extrapolate = i ⇒ (i, i)).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      autoProducer.sendNext(1)
      sub.requestMore(1)
      consumer.expectNext(1)
      sub.requestMore(1)
      consumer.expectNext(1)

      var pending = autoProducer.pendingRequests
      // Deplete pending requests coming from input buffer
      while (pending > 0) {
        autoProducer.subscription.sendNext(2)
        pending -= 1
      }

      // The above sends are absorbed in the input buffer, and will result in two one-sized batch requests
      pending += autoProducer.subscription.expectRequestMore()
      pending += autoProducer.subscription.expectRequestMore()
      while (pending > 0) {
        autoProducer.subscription.sendNext(2)
        pending -= 1
      }

      producer.expectNoMsg(1.second)

      sub.requestMore(2)
      consumer.expectNext(2)
      consumer.expectNext(2)

      // Now production is resumed
      autoProducer.subscription.expectRequestMore()

    }
  }

}
