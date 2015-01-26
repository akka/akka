/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.stream.testkit.AkkaSpec
import akka.stream.stage._

trait InterpreterSpecKit extends AkkaSpec {

  case object OnComplete
  case object Cancel
  case class OnError(cause: Throwable)
  case class OnNext(elem: Any)
  case object RequestOne

  private[akka] case class Doubler[T]() extends PushPullStage[T, T] {
    var oneMore: Boolean = false
    var lastElem: T = _

    override def onPush(elem: T, ctx: Context[T]): Directive = {
      lastElem = elem
      oneMore = true
      ctx.push(elem)
    }

    override def onPull(ctx: Context[T]): Directive = {
      if (oneMore) {
        oneMore = false
        ctx.push(lastElem)
      } else ctx.pull()
    }
  }

  private[akka] case class KeepGoing[T]() extends PushPullStage[T, T] {
    var lastElem: T = _

    override def onPush(elem: T, ctx: Context[T]): Directive = {
      lastElem = elem
      ctx.push(elem)
    }

    override def onPull(ctx: Context[T]): Directive = {
      if (ctx.isFinishing) {
        ctx.push(lastElem)
      } else ctx.pull()
    }

    override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = ctx.absorbTermination()
  }

  abstract class TestSetup(ops: Seq[Stage[_, _]], forkLimit: Int = 100, overflowToHeap: Boolean = false) {
    private var lastEvent: Set[Any] = Set.empty

    val upstream = new UpstreamProbe
    val downstream = new DownstreamProbe
    val interpreter = new OneBoundedInterpreter(upstream +: ops :+ downstream, forkLimit, overflowToHeap)
    interpreter.init()

    def lastEvents(): Set[Any] = {
      val result = lastEvent
      lastEvent = Set.empty
      result
    }

    class UpstreamProbe extends BoundaryStage {

      override def onDownstreamFinish(ctx: BoundaryContext): TerminationDirective = {
        lastEvent += Cancel
        ctx.finish()
      }

      override def onPull(ctx: BoundaryContext): Directive = {
        lastEvent += RequestOne
        ctx.exit()
      }

      override def onPush(elem: Any, ctx: BoundaryContext): Directive =
        throw new UnsupportedOperationException("Cannot push the boundary")

      def onNext(elem: Any): Unit = enter().push(elem)
      def onComplete(): Unit = enter().finish()
      def onError(cause: Throwable): Unit = enter().fail(cause)

    }

    class DownstreamProbe extends BoundaryStage {
      override def onPush(elem: Any, ctx: BoundaryContext): Directive = {
        lastEvent += OnNext(elem)
        ctx.exit()
      }

      override def onUpstreamFinish(ctx: BoundaryContext): TerminationDirective = {
        lastEvent += OnComplete
        ctx.finish()
      }

      override def onUpstreamFailure(cause: Throwable, ctx: BoundaryContext): TerminationDirective = {
        lastEvent += OnError(cause)
        ctx.finish()
      }

      override def onPull(ctx: BoundaryContext): Directive =
        throw new UnsupportedOperationException("Cannot pull the boundary")

      def requestOne(): Unit = enter().pull()

      def cancel(): Unit = enter().finish()
    }

  }
}
