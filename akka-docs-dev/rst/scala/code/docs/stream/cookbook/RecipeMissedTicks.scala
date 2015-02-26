package docs.stream.cookbook

import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit.StreamTestKit
import akka.stream.testkit.StreamTestKit.{ SubscriberProbe, PublisherProbe }

import scala.concurrent.duration._

class RecipeMissedTicks extends RecipeSpec {

  "Recipe for collecting missed ticks" must {

    "work" in {
      type Tick = Unit

      val pub = PublisherProbe[Tick]()
      val sub = SubscriberProbe[Int]()
      val tickStream = Source(pub)
      val sink = Sink(sub)

      //#missed-ticks
      // tickStream is a Source[Tick]
      val missedTicks: Source[Int, Unit] =
        tickStream.conflate(seed = (_) => 0)(
          (missedTicks, tick) => missedTicks + 1)
      //#missed-ticks

      missedTicks.to(sink).run()
      val manualSource = new StreamTestKit.AutoPublisher(pub)

      manualSource.sendNext(())
      manualSource.sendNext(())
      manualSource.sendNext(())
      manualSource.sendNext(())

      val subscription = sub.expectSubscription()
      subscription.request(1)
      sub.expectNext(3)

      subscription.request(1)
      sub.expectNoMsg(100.millis)

      manualSource.sendNext(())
      sub.expectNext(0)

      manualSource.sendComplete()
      subscription.request(1)
      sub.expectComplete()
    }

  }

}
