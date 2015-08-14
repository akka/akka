/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.stage

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.testkit.AkkaSpec
import akka.stream.testkit.scaladsl.TestSink

import scala.concurrent.duration._

class AsyncStageSpec extends AkkaSpec {

  implicit val mat = ActorMaterializer()

  sealed abstract class Tick
  object Tick extends Tick

  val elementDelay = new AsyncStage[Int, Int, Tick] {
    var buf: Int = 0

    override def preStart(ctx: AsyncContext[Int, Tick]) = {
      ctx.scheduleOnce(1.second, Tick)
    }

    override def onPull(ctx: AsyncContext[Int, Tick]): DownstreamDirective =
      ctx.holdDownstream()

    override def onPush(elem: Int, ctx: AsyncContext[Int, Tick]): UpstreamDirective = {
      buf = elem
      ctx.holdUpstream()
    }

    override def onUpstreamFinish(ctx: AsyncContext[Int, Tick]): TerminationDirective =
      if (buf == 0) ctx.finish()
      else ctx.absorbTermination()

    override def onAsyncInput(event: Tick, ctx: AsyncContext[Int, Tick]): Directive = {
      ctx.scheduleOnce(1.second, Tick)
      buf match {
        case 0 ⇒ ctx.ignore()
        case n ⇒
          buf = 0
          ctx.pushAndFinish(n)
      }
    }
  }

  "An AsyncStage" must {

    "emit element after delay" in {
      val probe = Source
        .single(1)
        .transform(() ⇒ elementDelay)
        .runWith(TestSink.probe[Int]())

      probe
        .request(2)
        .expectNoMsg(500.millis)
        .expectNext(1)
        .expectComplete()
    }

    "throttle for given ops/sec" in {
      pending

      // .throttle(ops = 100, in = 1.second) // emit no more than 100 elements in a second
    }
  }
}