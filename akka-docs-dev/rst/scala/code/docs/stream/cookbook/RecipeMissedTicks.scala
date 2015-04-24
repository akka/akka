package docs.stream.cookbook

import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit._

import scala.concurrent.duration._

class RecipeMissedTicks extends RecipeSpec {

  "Recipe for collecting missed ticks" must {

    "work" in {
      type Tick = Unit

      val pub = TestPublisher.probe[Tick]()
      val sub = TestSubscriber.manualProbe[Int]()
      val tickStream = Source(pub)
      val sink = Sink(sub)

      //#missed-ticks
      // tickStream is a Source[Tick]
      val missedTicks: Source[Int, Unit] =
        tickStream.conflate(seed = (_) => 0)(
          (missedTicks, tick) => missedTicks + 1)
      //#missed-ticks

      missedTicks.to(sink).run()

      pub.sendNext(())
      pub.sendNext(())
      pub.sendNext(())
      pub.sendNext(())

      val subscription = sub.expectSubscription()
      subscription.request(1)
      sub.expectNext(3)

      subscription.request(1)
      sub.expectNoMsg(100.millis)

      pub.sendNext(())
      sub.expectNext(0)

      pub.sendComplete()
      subscription.request(1)
      sub.expectComplete()
    }

  }

}
