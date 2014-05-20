/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.stream.testkit.{ StreamTestKit, AkkaSpec }
import akka.stream.scaladsl.Flow
import scala.concurrent.forkjoin.ThreadLocalRandom
import scala.concurrent.Await
import scala.concurrent.duration._

class FlowConflateSpec extends AkkaSpec {

  val materializer = FlowMaterializer(MaterializerSettings(
    initialInputBufferSize = 2,
    maximumInputBufferSize = 2,
    initialFanOutBufferSize = 2,
    maxFanOutBufferSize = 2,
    dispatcher = "akka.test.stream-dispatcher"))

  "Conflate" must {

    "pass-through elements unchanged when there is no rate difference" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      Flow(producer).conflate[Int](seed = i ⇒ i, aggregate = (sum, i) ⇒ sum + i).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      for (i ← 1 to 100) {
        sub.requestMore(1)
        autoProducer.sendNext(i)
        consumer.expectNext(i)
      }

      sub.cancel()
    }

    "conflate elements while downstream is silent" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      Flow(producer).conflate[Int](seed = i ⇒ i, aggregate = (sum, i) ⇒ sum + i).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      for (i ← 1 to 100) {
        autoProducer.sendNext(i)
      }
      consumer.expectNoMsg(1.second)
      sub.requestMore(1)
      consumer.expectNext(5050)
      sub.cancel()
    }

    "work on a variable rate chain" in {
      val future = Flow((1 to 1000).iterator)
        .conflate[Int](seed = i ⇒ i, aggregate = (sum, i) ⇒ sum + i)
        .map { i ⇒ if (ThreadLocalRandom.current().nextBoolean()) Thread.sleep(10); i }
        .fold(0)(_ + _)
        .toFuture(materializer)

      Await.result(future, 10.seconds) should be(500500)
    }

    "backpressure consumer when upstream is slower" in {
      val producer = StreamTestKit.producerProbe[Int]
      val consumer = StreamTestKit.consumerProbe[Int]

      Flow(producer).conflate[Int](seed = i ⇒ i, aggregate = (sum, i) ⇒ sum + i).produceTo(materializer, consumer)

      val autoProducer = new StreamTestKit.AutoProducer(producer)
      val sub = consumer.expectSubscription()

      sub.requestMore(1)
      autoProducer.sendNext(1)
      consumer.expectNext(1)

      sub.requestMore(1)
      consumer.expectNoMsg(1.second)
      autoProducer.sendNext(2)
      consumer.expectNext(2)

      autoProducer.sendNext(3)
      autoProducer.sendNext(4)
      sub.requestMore(1)
      consumer.expectNext(7)

      sub.requestMore(1)
      consumer.expectNoMsg(1.second)
      sub.cancel()

    }

  }

}
