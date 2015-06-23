/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.fusing

import akka.event.Logging
import akka.stream.stage._
import akka.stream.testkit.AkkaSpec
import akka.stream.{ ActorFlowMaterializer, Attributes }
import akka.testkit.TestProbe

trait InterpreterLifecycleSpecKit {
  private[akka] case class PreStartAndPostStopIdentity[T](
    onStart: LifecycleContext ⇒ Unit = _ ⇒ (),
    onStop: () ⇒ Unit = () ⇒ (),
    onUpstreamCompleted: () ⇒ Unit = () ⇒ (),
    onUpstreamFailed: Throwable ⇒ Unit = ex ⇒ ())
    extends PushStage[T, T] {
    override def preStart(ctx: LifecycleContext) = onStart(ctx)

    override def onPush(elem: T, ctx: Context[T]) = ctx.push(elem)

    override def onUpstreamFinish(ctx: Context[T]): TerminationDirective = {
      onUpstreamCompleted()
      super.onUpstreamFinish(ctx)
    }

    override def onUpstreamFailure(cause: Throwable, ctx: Context[T]): TerminationDirective = {
      onUpstreamFailed(cause)
      super.onUpstreamFailure(cause, ctx)
    }

    override def postStop() = onStop()
  }

  private[akka] case class PreStartFailer[T](pleaseThrow: () ⇒ Unit) extends PushStage[T, T] {

    override def preStart(ctx: LifecycleContext) =
      pleaseThrow()

    override def onPush(elem: T, ctx: Context[T]) = ctx.push(elem)
  }

  private[akka] case class PostStopFailer[T](ex: () ⇒ Throwable) extends PushStage[T, T] {
    override def onUpstreamFinish(ctx: Context[T]) = ctx.finish()
    override def onPush(elem: T, ctx: Context[T]) = ctx.push(elem)

    override def postStop(): Unit = throw ex()
  }

  // This test is related to issue #17351
  private[akka] class PushFinishStage(onPostStop: () ⇒ Unit = () ⇒ ()) extends PushStage[Any, Any] {
    override def onPush(elem: Any, ctx: Context[Any]): SyncDirective =
      ctx.pushAndFinish(elem)

    override def onUpstreamFinish(ctx: Context[Any]): TerminationDirective =
      ctx.fail(akka.stream.testkit.Utils.TE("Cannot happen"))

    override def postStop(): Unit =
      onPostStop()
  }

}

trait InterpreterSpecKit extends AkkaSpec with InterpreterLifecycleSpecKit {

  case object OnComplete
  case object Cancel
  case class OnError(cause: Throwable)
  case class OnNext(elem: Any)
  case object RequestOne
  case object RequestAnother

  private[akka] case class Doubler[T]() extends PushPullStage[T, T] {
    var oneMore: Boolean = false
    var lastElem: T = _

    override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
      lastElem = elem
      oneMore = true
      ctx.push(elem)
    }

    override def onPull(ctx: Context[T]): SyncDirective = {
      if (oneMore) {
        oneMore = false
        ctx.push(lastElem)
      } else ctx.pull()
    }
  }

  private[akka] case class KeepGoing[T]() extends PushPullStage[T, T] {
    var lastElem: T = _

    override def onPush(elem: T, ctx: Context[T]): SyncDirective = {
      lastElem = elem
      ctx.push(elem)
    }

    override def onPull(ctx: Context[T]): SyncDirective = {
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
    val sidechannel = TestProbe()
    val interpreter = new OneBoundedInterpreter(upstream +: ops :+ downstream,
      (op, ctx, event) ⇒ sidechannel.ref ! ActorInterpreter.AsyncInput(op, ctx, event),
      Logging(system, classOf[TestSetup]),
      ActorFlowMaterializer(),
      Attributes.none,
      forkLimit, overflowToHeap)
    interpreter.init()

    def lastEvents(): Set[Any] = {
      val result = lastEvent
      lastEvent = Set.empty
      result
    }

    private[akka] class UpstreamProbe extends BoundaryStage {

      override def onDownstreamFinish(ctx: BoundaryContext): TerminationDirective = {
        lastEvent += Cancel
        ctx.finish()
      }

      override def onPull(ctx: BoundaryContext): Directive = {
        if (lastEvent(RequestOne))
          lastEvent += RequestAnother
        else
          lastEvent += RequestOne
        ctx.exit()
      }

      override def onPush(elem: Any, ctx: BoundaryContext): Directive =
        throw new UnsupportedOperationException("Cannot push the boundary")

      def onNext(elem: Any): Unit = enterAndPush(elem)
      def onComplete(): Unit = enterAndFinish()
      def onNextAndComplete(elem: Any): Unit = {
        context.enter()
        context.pushAndFinish(elem)
        context.execute()
      }
      def onError(cause: Throwable): Unit = enterAndFail(cause)

    }

    private[akka] class DownstreamProbe extends BoundaryStage {
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

      def requestOne(): Unit = enterAndPull()

      def cancel(): Unit = enterAndFinish()
    }

  }
}
