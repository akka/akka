package docs.stream.cookbook

import akka.stream.scaladsl.{ Sink, Source }
import akka.stream.testkit._

import scala.concurrent.duration._

object HoldOps {
  //#hold-version-1
  import akka.stream.stage._
  class HoldWithInitial[T](initial: T) extends DetachedStage[T, T] {
    private var currentValue: T = initial

    override def onPush(elem: T, ctx: DetachedContext[T]): UpstreamDirective = {
      currentValue = elem
      ctx.pull()
    }

    override def onPull(ctx: DetachedContext[T]): DownstreamDirective = {
      ctx.push(currentValue)
    }

  }
  //#hold-version-1

  //#hold-version-2
  import akka.stream.stage._
  class HoldWithWait[T] extends DetachedStage[T, T] {
    private var currentValue: T = _
    private var waitingFirstValue = true

    override def onPush(elem: T, ctx: DetachedContext[T]): UpstreamDirective = {
      currentValue = elem
      waitingFirstValue = false
      if (ctx.isHoldingDownstream) ctx.pushAndPull(currentValue)
      else ctx.pull()
    }

    override def onPull(ctx: DetachedContext[T]): DownstreamDirective = {
      if (waitingFirstValue) ctx.holdDownstream()
      else ctx.push(currentValue)
    }

  }
  //#hold-version-2
}

class RecipeHold extends RecipeSpec {
  import HoldOps._

  "Recipe for creating a holding element" must {

    "work for version 1" in {

      val pub = TestPublisher.probe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()
      val source = Source(pub)
      val sink = Sink(sub)

      source.transform(() => new HoldWithInitial(0)).to(sink).run()

      val subscription = sub.expectSubscription()
      sub.expectNoMsg(100.millis)

      subscription.request(1)
      sub.expectNext(0)

      subscription.request(1)
      sub.expectNext(0)

      pub.sendNext(1)
      pub.sendNext(2)

      subscription.request(2)
      sub.expectNext(2)
      sub.expectNext(2)

      pub.sendComplete()
      subscription.request(1)
      sub.expectComplete()
    }

    "work for version 2" in {

      val pub = TestPublisher.probe[Int]()
      val sub = TestSubscriber.manualProbe[Int]()
      val source = Source(pub)
      val sink = Sink(sub)

      source.transform(() => new HoldWithWait).to(sink).run()

      val subscription = sub.expectSubscription()
      sub.expectNoMsg(100.millis)

      subscription.request(1)
      sub.expectNoMsg(100.millis)

      pub.sendNext(1)
      sub.expectNext(1)

      pub.sendNext(2)
      pub.sendNext(3)

      subscription.request(2)
      sub.expectNext(3)
      sub.expectNext(3)

      pub.sendComplete()
      subscription.request(1)
      sub.expectComplete()
    }

  }

}
