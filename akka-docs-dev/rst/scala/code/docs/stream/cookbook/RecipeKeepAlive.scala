package docs.stream.cookbook

import akka.stream.scaladsl._
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.StreamTestKit.{ SubscriberProbe, PublisherProbe }
import akka.util.ByteString

class RecipeKeepAlive extends RecipeSpec {

  "Recipe for injecting keepalive messages" must {

    "work" in {

      type Tick = Unit

      val tickPub = PublisherProbe[Tick]()
      val dataPub = PublisherProbe[ByteString]()
      val sub = SubscriberProbe[ByteString]()
      val ticks = Source(tickPub)

      val dataStream = Source(dataPub)
      val keepaliveMessage = ByteString(11)
      val sink = Sink(sub)

      //#inject-keepalive
      val keepAliveStream: Source[ByteString, Unit] = ticks
        .conflate(seed = (tick) => keepaliveMessage)((msg, newTick) => msg)

      val graph = FlowGraph.closed() { implicit builder =>
        import FlowGraph.Implicits._
        val unfairMerge = builder.add(MergePreferred[ByteString](1))

        dataStream ~> unfairMerge.preferred
        // If data is available then no keepalive is injected
        keepAliveStream ~> unfairMerge ~> sink
      }
      //#inject-keepalive

      graph.run()

      val manualTicks = new StreamTestKit.AutoPublisher(tickPub)
      val manualData = new StreamTestKit.AutoPublisher(dataPub)

      val subscription = sub.expectSubscription()

      manualTicks.sendNext(())

      // pending data will overcome the keepalive
      manualData.sendNext(ByteString(1))
      manualData.sendNext(ByteString(2))
      manualData.sendNext(ByteString(3))

      subscription.request(1)
      sub.expectNext(ByteString(1))
      subscription.request(2)
      sub.expectNext(ByteString(2))
      sub.expectNext(ByteString(3))

      subscription.request(1)
      sub.expectNext(keepaliveMessage)

      subscription.request(1)
      manualTicks.sendNext(())
      sub.expectNext(keepaliveMessage)

      manualData.sendComplete()
      manualTicks.sendComplete()

      sub.expectComplete()

    }

  }

}
