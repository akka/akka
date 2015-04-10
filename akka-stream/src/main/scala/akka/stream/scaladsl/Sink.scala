/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.javadsl
import akka.actor.{ ActorRef, Props }
import akka.stream.impl._
import akka.stream.{ SinkShape, Inlet, Outlet, Graph, OperationAttributes }
import akka.stream.OperationAttributes._
import akka.stream.stage.{ TerminationDirective, Directive, Context, PushStage }
import org.reactivestreams.{ Publisher, Subscriber }
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ Promise, Future }
import scala.util.{ Success, Failure, Try }
import akka.stream.FlowMaterializer
import akka.stream.impl.StreamLayout.Module
import scala.util.control.NonFatal
import akka.stream.Supervision
import akka.stream.stage.SyncDirective

/**
 * A `Sink` is a set of stream processing steps that has one open input and an attached output.
 * Can be used as a `Subscriber`
 */
final class Sink[-In, +Mat](private[stream] override val module: Module)
  extends Graph[SinkShape[In], Mat] {

  override val shape: SinkShape[In] = module.shape.asInstanceOf[SinkShape[In]]

  /**
   * Connect this `Sink` to a `Source` and run it. The returned value is the materialized value
   * of the `Source`, e.g. the `Subscriber` of a [[Source#subscriber]].
   */
  def runWith[Mat2](source: Source[In, Mat2])(implicit materializer: FlowMaterializer): Mat2 =
    source.to(this).run()

  def mapMaterialized[Mat2](f: Mat ⇒ Mat2): Sink[In, Mat2] =
    new Sink(module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  def withAttributes(attr: OperationAttributes): Sink[In, Mat] =
    new Sink(module.withAttributes(attr).wrap())

  def named(name: String): Sink[In, Mat] = withAttributes(OperationAttributes.name(name))

  /** Converts this Scala DSL element to it's Java DSL counterpart. */
  def asJava: javadsl.Sink[In, Mat] = new javadsl.Sink(this)
}

object Sink extends SinkApply {

  import OperationAttributes.none

  private def shape[T](name: String): SinkShape[T] = SinkShape(new Inlet(name + ".in"))

  /**
   * A graph with the shape of a sink logically is a sink, this method makes
   * it so also in type.
   */
  def wrap[T, M](g: Graph[SinkShape[T], M]): Sink[T, M] = new Sink(g.module)

  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def apply[T](subscriber: Subscriber[T]): Sink[T, Unit] =
    new Sink(new SubscriberSink(subscriber, none, shape("SubscriberSink")))

  /**
   * A `Sink` that immediately cancels its upstream after materialization.
   */
  def cancelled[T]: Sink[T, Unit] = new Sink[Any, Unit](new CancelSink(none, shape("CancelledSink")))

  /**
   * A `Sink` that materializes into a `Future` of the first value received.
   */
  def head[T]: Sink[T, Future[T]] = new Sink(new HeadSink[T](none, shape("HeadSink")))

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]].
   * that can handle one [[org.reactivestreams.Subscriber]].
   */
  def publisher[T]: Sink[T, Publisher[T]] = new Sink(new PublisherSink[T](none, shape("PublisherSink")))

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]]
   * that can handle more than one [[org.reactivestreams.Subscriber]].
   */
  def fanoutPublisher[T](initialBufferSize: Int, maximumBufferSize: Int): Sink[T, Publisher[T]] =
    new Sink(new FanoutPublisherSink[T](initialBufferSize, maximumBufferSize, none, shape("FanoutPublisherSink")))

  /**
   * A `Sink` that will consume the stream and discard the elements.
   */
  def ignore: Sink[Any, Unit] =
    new Sink(new BlackholeSink(none, shape("BlackholeSink")))

  /**
   * A `Sink` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream..
   */
  def foreach[T](f: T ⇒ Unit): Sink[T, Future[Unit]] = {

    def newForeachStage(): (PushStage[T, Unit], Future[Unit]) = {
      val promise = Promise[Unit]()

      val stage = new PushStage[T, Unit] {
        override def onPush(elem: T, ctx: Context[Unit]): SyncDirective = {
          f(elem)
          ctx.pull()
        }
        override def onUpstreamFailure(cause: Throwable, ctx: Context[Unit]): TerminationDirective = {
          promise.failure(cause)
          ctx.fail(cause)
        }
        override def onUpstreamFinish(ctx: Context[Unit]): TerminationDirective = {
          promise.success(())
          ctx.finish()
        }

        override def decide(cause: Throwable): Supervision.Directive = {
          // supervision will be implemented by #16916
          promise.tryFailure(cause)
          super.decide(cause)
        }
      }

      (stage, promise.future)
    }

    Flow[T].transformMaterializing(newForeachStage).to(Sink.ignore).named("ForeachSink")
  }

  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   */
  def fold[U, T](zero: U)(f: (U, T) ⇒ U): Sink[T, Future[U]] = {

    def newFoldStage(): (PushStage[T, U], Future[U]) = {
      val promise = Promise[U]()

      val stage = new PushStage[T, U] {
        private var aggregator = zero

        override def onPush(elem: T, ctx: Context[U]): SyncDirective = {
          aggregator = f(aggregator, elem)
          ctx.pull()
        }

        override def onUpstreamFailure(cause: Throwable, ctx: Context[U]): TerminationDirective = {
          promise.failure(cause)
          ctx.fail(cause)
        }

        override def onUpstreamFinish(ctx: Context[U]): TerminationDirective = {
          promise.success(aggregator)
          ctx.finish()
        }

        override def decide(cause: Throwable): Supervision.Directive = {
          // supervision will be implemented by #16916
          promise.tryFailure(cause)
          super.decide(cause)
        }
      }

      (stage, promise.future)
    }

    Flow[T].transformMaterializing(newFoldStage).to(Sink.ignore).named("FoldSink")
  }

  /**
   * A `Sink` that when the flow is completed, either through a failure or normal
   * completion, apply the provided function with [[scala.util.Success]]
   * or [[scala.util.Failure]].
   */
  def onComplete[T](callback: Try[Unit] ⇒ Unit): Sink[T, Unit] = {

    def newOnCompleteStage(): PushStage[T, Unit] = {
      new PushStage[T, Unit] {
        override def onPush(elem: T, ctx: Context[Unit]): SyncDirective = ctx.pull()
        override def onUpstreamFailure(cause: Throwable, ctx: Context[Unit]): TerminationDirective = {
          callback(Failure(cause))
          ctx.fail(cause)
        }
        override def onUpstreamFinish(ctx: Context[Unit]): TerminationDirective = {
          callback(Success[Unit](()))
          ctx.finish()
        }
      }
    }

    Flow[T].transform(newOnCompleteStage).to(Sink.ignore).named("OnCompleteSink")
  }

  /**
   * Sends the elements of the stream to the given `ActorRef`.
   * If the target actor terminates the stream will be cancelled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure a [[akka.actor.Status.Failure]]
   * message will be sent to the destination actor.
   *
   * It will request at most `maxInputBufferSize` number of elements from
   * upstream, but there is no back-pressure signal from the destination actor,
   * i.e. if the actor is not consuming the messages fast enough the mailbox
   * of the actor will grow. For potentially slow consumer actors it is recommended
   * to use a bounded mailbox with zero `mailbox-push-timeout-time` or use a rate
   * limiting stage in front of this `Sink`.
   */
  def actorRef[T](ref: ActorRef, onCompleteMessage: Any): Sink[T, Unit] =
    new Sink(new ActorRefSink(ref, onCompleteMessage, none, shape("ActorRefSink")))

  /**
   * Creates a `Sink` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` should
   * be [[akka.stream.actor.ActorSubscriber]].
   */
  def actorSubscriber[T](props: Props): Sink[T, ActorRef] =
    new Sink(new ActorSubscriberSink(props, none, shape("ActorSubscriberSink")))

}
