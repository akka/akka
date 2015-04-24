package docs.stream.cookbook

import akka.stream.scaladsl._
import akka.stream.testkit._
import akka.util.ByteString

class RecipeKeepAlive extends RecipeSpec {

  "Recipe for injecting keepalive messages" must {

    "work" in {

      type Tick = Unit

      val tickPub = TestPublisher.probe[Tick]()
      val dataPub = TestPublisher.probe[ByteString]()
      val sub = TestSubscriber.manualProbe[ByteString]()
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

      val subscription = sub.expectSubscription()

      tickPub.sendNext(())

      // pending data will overcome the keepalive
      dataPub.sendNext(ByteString(1))
      dataPub.sendNext(ByteString(2))
      dataPub.sendNext(ByteString(3))

      subscription.request(1)
      sub.expectNext(ByteString(1))
      subscription.request(2)
      sub.expectNext(ByteString(2))
      sub.expectNext(ByteString(3))

      subscription.request(1)
      sub.expectNext(keepaliveMessage)

      subscription.request(1)
      tickPub.sendNext(())
      sub.expectNext(keepaliveMessage)

      dataPub.sendComplete()
      tickPub.sendComplete()

      sub.expectComplete()

    }

  }

}
