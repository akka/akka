/**
 * Copyright (C) 2014-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import java.io.{ InputStream, OutputStream, File }
import akka.dispatch.ExecutionContexts
import akka.actor.{ Status, ActorRef, Props }
import akka.stream.actor.ActorSubscriber
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl._
import akka.stream.impl.io.{ InputStreamSinkStage, OutputStreamSink, FileSink }
import akka.stream.stage.{ Context, PushStage, SyncDirective, TerminationDirective }
import akka.stream.{ javadsl, _ }
import akka.util.ByteString
import org.reactivestreams.{ Publisher, Subscriber }

import scala.annotation.tailrec
import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

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
  def runWith[Mat2](source: Graph[SourceShape[In], Mat2])(implicit materializer: Materializer): Mat2 =
    Source.fromGraph(source).to(this).run()

  def mapMaterializedValue[Mat2](f: Mat ⇒ Mat2): Sink[In, Mat2] =
    new Sink(module.transformMaterializedValue(f.asInstanceOf[Any ⇒ Any]))

  override def withAttributes(attr: Attributes): Sink[In, Mat] =
    new Sink(module.withAttributes(attr).nest())

  override def named(name: String): Sink[In, Mat] = withAttributes(Attributes.name(name))

  /** Converts this Scala DSL element to it's Java DSL counterpart. */
  def asJava: javadsl.Sink[In, Mat] = new javadsl.Sink(this)
}

object Sink {

  /** INTERNAL API */
  private[stream] def shape[T](name: String): SinkShape[T] = SinkShape(Inlet(name + ".in"))

  /**
   * A graph with the shape of a sink logically is a sink, this method makes
   * it so also in type.
   */
  def fromGraph[T, M](g: Graph[SinkShape[T], M]): Sink[T, M] =
    g match {
      case s: Sink[T, M]         ⇒ s
      case s: javadsl.Sink[T, M] ⇒ s.asScala
      case other                 ⇒ new Sink(other.module)
    }

  /**
   * Helper to create [[Sink]] from `Subscriber`.
   */
  def apply[T](subscriber: Subscriber[T]): Sink[T, Unit] =
    new Sink(new SubscriberSink(subscriber, DefaultAttributes.subscriberSink, shape("SubscriberSink")))

  /**
   * A `Sink` that immediately cancels its upstream after materialization.
   */
  def cancelled[T]: Sink[T, Unit] =
    new Sink[Any, Unit](new CancelSink(DefaultAttributes.cancelledSink, shape("CancelledSink")))

  /**
   * A `Sink` that materializes into a `Future` of the first value received.
   * If the stream completes before signaling at least a single element, the Future will be failed with a [[NoSuchElementException]].
   * If the stream signals an error errors before signaling at least a single element, the Future will be failed with the streams exception.
   *
   * See also [[headOption]].
   */
  def head[T]: Sink[T, Future[T]] =
    Sink.fromGraph(new HeadOptionStage[T]).withAttributes(DefaultAttributes.headSink)
      .mapMaterializedValue(e ⇒ e.map(_.getOrElse(throw new NoSuchElementException("head of empty stream")))(ExecutionContexts.sameThreadExecutionContext))

  /**
   * A `Sink` that materializes into a `Future` of the optional first value received.
   * If the stream completes before signaling at least a single element, the value of the Future will be [[None]].
   * If the stream signals an error errors before signaling at least a single element, the Future will be failed with the streams exception.
   *
   * See also [[head]].
   */
  def headOption[T]: Sink[T, Future[Option[T]]] =
    Sink.fromGraph(new HeadOptionStage[T]).withAttributes(DefaultAttributes.headOptionSink)

  /**
   * A `Sink` that materializes into a `Future` of the last value received.
   * If the stream completes before signaling at least a single element, the Future will be failed with a [[NoSuchElementException]].
   * If the stream signals an error errors before signaling at least a single element, the Future will be failed with the streams exception.
   *
   * See also [[lastOption]].
   */
  def last[T]: Sink[T, Future[T]] = Sink.fromGraph(new LastOptionStage[T]).withAttributes(DefaultAttributes.lastSink)
    .mapMaterializedValue(e ⇒ e.map(_.getOrElse(throw new NoSuchElementException("last of empty stream")))(ExecutionContexts.sameThreadExecutionContext))

  /**
   * A `Sink` that materializes into a `Future` of the optional last value received.
   * If the stream completes before signaling at least a single element, the value of the Future will be [[None]].
   * If the stream signals an error errors before signaling at least a single element, the Future will be failed with the streams exception.
   *
   * See also [[last]].
   */
  def lastOption[T]: Sink[T, Future[Option[T]]] = Sink.fromGraph(new LastOptionStage[T]).withAttributes(DefaultAttributes.lastOptionSink)

  /**
   * A `Sink` that materializes into a [[org.reactivestreams.Publisher]].
   *
   * If `fanout` is `true`, the materialized `Publisher` will support multiple `Subscriber`s and
   * the size of the `inputBuffer` configured for this stage becomes the maximum number of elements that
   * the fastest [[org.reactivestreams.Subscriber]] can be ahead of the slowest one before slowing
   * the processing down due to back pressure.
   *
   * If `fanout` is `false` then the materialized `Publisher` will only support a single `Subscriber` and
   * reject any additional `Subscriber`s.
   */
  def publisher[T](fanout: Boolean): Sink[T, Publisher[T]] =
    new Sink(
      if (fanout) new FanoutPublisherSink[T](DefaultAttributes.fanoutPublisherSink, shape("FanoutPublisherSink"))
      else new PublisherSink[T](DefaultAttributes.publisherSink, shape("PublisherSink")))

  /**
   * A `Sink` that will consume the stream and discard the elements.
   */
  def ignore: Sink[Any, Future[Unit]] =
    new Sink(new SinkholeSink(DefaultAttributes.ignoreSink, shape("SinkholeSink")))

  /**
   * A `Sink` that will invoke the given procedure for each received element. The sink is materialized
   * into a [[scala.concurrent.Future]] will be completed with `Success` when reaching the
   * normal end of the stream, or completed with `Failure` if there is a failure signaled in
   * the stream..
   */
  def foreach[T](f: T ⇒ Unit): Sink[T, Future[Unit]] =
    Flow[T].map(f).toMat(Sink.ignore)(Keep.right).named("foreachSink")

  /**
   * Combine several sinks with fun-out strategy like `Broadcast` or `Balance` and returns `Sink`.
   */
  def combine[T, U](first: Sink[U, _], second: Sink[U, _], rest: Sink[U, _]*)(strategy: Int ⇒ Graph[UniformFanOutShape[T, U], Unit]): Sink[T, Unit] =

    Sink.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._
      val d = b.add(strategy(rest.size + 2))
      d.out(0) ~> first
      d.out(1) ~> second

      @tailrec def combineRest(idx: Int, i: Iterator[Sink[U, _]]): SinkShape[T] =
        if (i.hasNext) {
          d.out(idx) ~> i.next()
          combineRest(idx + 1, i)
        } else new SinkShape(d.in)

      combineRest(2, rest.iterator)
    })

  /**
   * A `Sink` that will invoke the given function to each of the elements
   * as they pass in. The sink is materialized into a [[scala.concurrent.Future]]
   *
   * If `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Stop]] the `Future` will be completed with failure.
   *
   * If `f` throws an exception and the supervision decision is
   * [[akka.stream.Supervision.Resume]] or [[akka.stream.Supervision.Restart]] the
   * element is dropped and the stream continues.
   *
   * @see [[#mapAsyncUnordered]]
   */
  def foreachParallel[T](parallelism: Int)(f: T ⇒ Unit)(implicit ec: ExecutionContext): Sink[T, Future[Unit]] =
    Flow[T].mapAsyncUnordered(parallelism)(t ⇒ Future(f(t))).toMat(Sink.ignore)(Keep.right)

  /**
   * A `Sink` that will invoke the given function for every received element, giving it its previous
   * output (or the given `zero` value) and the element as input.
   * The returned [[scala.concurrent.Future]] will be completed with value of the final
   * function evaluation when the input stream ends, or completed with `Failure`
   * if there is a failure signaled in the stream.
   */
  def fold[U, T](zero: U)(f: (U, T) ⇒ U): Sink[T, Future[U]] =
    Flow[T].fold(zero)(f).toMat(Sink.head)(Keep.right).named("foldSink")

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

    Flow[T].transform(newOnCompleteStage).to(Sink.ignore).named("onCompleteSink")
  }

  /**
   * Sends the elements of the stream to the given `ActorRef`.
   * If the target actor terminates the stream will be canceled.
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
    new Sink(new ActorRefSink(ref, onCompleteMessage, DefaultAttributes.actorRefSink, shape("ActorRefSink")))

  /**
   * Sends the elements of the stream to the given `ActorRef` that sends back back-pressure signal.
   * First element is always `onInitMessage`, then stream is waiting for acknowledgement message
   * `ackMessage` from the given actor which means that it is ready to process
   * elements. It also requires `ackMessage` message after each stream element
   * to make backpressure work.
   *
   * If the target actor terminates the stream will be canceled.
   * When the stream is completed successfully the given `onCompleteMessage`
   * will be sent to the destination actor.
   * When the stream is completed with failure - result of `onFailureMessage(throwable)`
   * function will be sent to the destination actor.
   */
  def actorRefWithAck[T](ref: ActorRef, onInitMessage: Any, ackMessage: Any, onCompleteMessage: Any,
                         onFailureMessage: (Throwable) ⇒ Any = Status.Failure): Sink[T, Unit] =
    Sink.fromGraph(new ActorRefBackpressureSinkStage(ref, onInitMessage, ackMessage, onCompleteMessage, onFailureMessage))

  /**
   * Creates a `Sink` that is materialized to an [[akka.actor.ActorRef]] which points to an Actor
   * created according to the passed in [[akka.actor.Props]]. Actor created by the `props` must
   * be [[akka.stream.actor.ActorSubscriber]].
   */
  def actorSubscriber[T](props: Props): Sink[T, ActorRef] = {
    require(classOf[ActorSubscriber].isAssignableFrom(props.actorClass()), "Actor must be ActorSubscriber")
    new Sink(new ActorSubscriberSink(props, DefaultAttributes.actorSubscriberSink, shape("ActorSubscriberSink")))
  }

  /**
   * Creates a `Sink` that is materialized as an [[akka.stream.SinkQueue]].
   * [[akka.stream.SinkQueue.pull]] method is pulling element from the stream and returns ``Future[Option[T]]``.
   * `Future` completes when element is available.
   *
   * `Sink` will request at most `bufferSize` number of elements from
   * upstream and then stop back pressure.
   *
   * @param bufferSize The size of the buffer in element count
   * @param timeout Timeout for ``SinkQueue.pull():Future[Option[T]]``
   */
  def queue[T](bufferSize: Int, timeout: FiniteDuration = 5.seconds): Sink[T, SinkQueue[T]] = {
    require(bufferSize >= 0, "bufferSize must be greater than or equal to 0")
    new Sink(new AcknowledgeSink(bufferSize, DefaultAttributes.acknowledgeSink, shape("AcknowledgeSink"), timeout))
  }

  /**
   * Creates a Sink which writes incoming [[ByteString]] elements to the given file and either overwrites
   * or appends to it.
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * This source is backed by an Actor which will use the dedicated `akka.stream.blocking-io-dispatcher`,
   * unless configured otherwise by using [[ActorAttributes]].
   */
  def file(f: File, append: Boolean = false): Sink[ByteString, Future[Long]] =
    new Sink(new FileSink(f, append, DefaultAttributes.fileSink, shape("FileSink")))

  /**
   * Creates a Sink which writes incoming [[ByteString]]s to an [[OutputStream]] created by the given function.
   *
   * Materializes a [[Future]] that will be completed with the size of the file (in bytes) at the streams completion.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   */
  def outputStream(out: () ⇒ OutputStream): Sink[ByteString, Future[Long]] =
    new Sink(new OutputStreamSink(out, DefaultAttributes.outputStreamSink, shape("OutputStreamSink")))

  /**
   * Creates a Sink which when materialized will return an [[InputStream]] which it is possible
   * to read the values produced by the stream this Sink is attached to.
   *
   * This Sink is intended for inter-operation with legacy APIs since it is inherently blocking.
   *
   * You can configure the default dispatcher for this Source by changing the `akka.stream.blocking-io-dispatcher` or
   * set it for a given Source by using [[ActorAttributes]].
   *
   * @param readTimeout the max time the read operation on the materialized InputStream should block
   */
  def inputStream(readTimeout: FiniteDuration = 5.seconds): Sink[ByteString, InputStream] =
    Sink.fromGraph(new InputStreamSinkStage(readTimeout)).withAttributes(DefaultAttributes.inputStreamSink)
}
