package docs.stream.cookbook

import akka.stream.ClosedShape
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
      val tickToKeepAlivePacket: Flow[Tick, ByteString, Unit] = Flow[Tick]
        .conflate(seed = (tick) => keepaliveMessage)((msg, newTick) => msg)

      val graph = RunnableGraph.fromGraph(FlowGraph.create() { implicit builder =>
        import FlowGraph.Implicits._
        val unfairMerge = builder.add(MergePreferred[ByteString](1))

        // If data is available then no keepalive is injected
        dataStream ~> unfairMerge.preferred
        ticks ~> tickToKeepAlivePacket ~> unfairMerge ~> sink
        ClosedShape
      })
      //#inject-keepalive

      graph.run()

      val subscription = sub.expectSubscription()

      // FIXME RK: remove (because I think this cannot deterministically be tested and it might also not do what it should anymore)

      tickPub.sendNext(())

      // pending data will overcome the keepalive
      dataPub.sendNext(ByteString(1))
      dataPub.sendNext(ByteString(2))
      dataPub.sendNext(ByteString(3))

      subscription.request(1)
      sub.expectNext(ByteString(1))
      subscription.request(2)
      sub.expectNext(ByteString(2))
      // This still gets through because there is some intrinsic fairness caused by the FIFO queue in the interpreter
      // Expecting here a preferred element also only worked true accident with the old Pump.
      sub.expectNext(keepaliveMessage)

      subscription.request(1)
      sub.expectNext(ByteString(3))

      subscription.request(1)
      tickPub.sendNext(())
      sub.expectNext(keepaliveMessage)

      dataPub.sendComplete()
      tickPub.sendComplete()

      sub.expectComplete()

    }

  }

}
