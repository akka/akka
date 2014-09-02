/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import scala.language.higherKinds
import scala.collection.immutable
import scala.concurrent.Future
import akka.stream._
import akka.stream.impl.BlackholeSubscriber
import akka.stream.impl2.Ast._
import scala.annotation.unchecked.uncheckedVariance
import akka.stream.impl.BlackholeSubscriber
import scala.concurrent.Promise
import akka.stream.impl.EmptyPublisher
import akka.stream.impl.IterablePublisher
import akka.stream.impl2.ActorBasedFlowMaterializer
import org.reactivestreams._
import scala.concurrent.duration.FiniteDuration

sealed trait Flow

object FlowFrom {
  /**
   * Helper to create `Flow` without [[Source]].
   * Example usage: `FlowFrom[Int]`
   */
  def apply[T]: ProcessorFlow[T, T] = ProcessorFlow[T, T](Nil)

  /**
   * Helper to create `Flow` with [[Source]] from `Publisher`.
   *
   * Construct a transformation starting with given publisher. The transformation steps
   * are executed by a series of [[org.reactivestreams.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def apply[T](publisher: Publisher[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(PublisherSource(publisher))

  /**
   * Helper to create `Flow` with [[Source]] from `Iterator`.
   * Example usage: `FlowFrom(Seq(1,2,3).iterator)`
   *
   * Start a new `Flow` from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the `next()` method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def apply[T](iterator: Iterator[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(IteratorSource(iterator))

  /**
   * Helper to create `Flow` with [[Source]] from `Iterable`.
   * Example usage: `FlowFrom(Seq(1,2,3))`
   *
   * Starts a new `Flow` from the given `Iterable`. This is like starting from an
   * Iterator, but every Subscriber directly attached to the Publisher of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def apply[T](iterable: immutable.Iterable[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(IterableSource(iterable))

  /**
   * Define the sequence of elements to be produced by the given closure.
   * The stream ends normally when evaluation of the `Callable` returns a `None`.
   * The stream ends exceptionally when an exception is thrown from the `Callable`.
   */
  def apply[T](f: () ⇒ Option[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(ThunkSource(f))

  /**
   * Start a new `Flow` from the given `Future`. The stream will consist of
   * one element when the `Future` is completed with a successful value, which
   * may happen before or after materializing the `Flow`.
   * The stream terminates with an error if the `Future` is completed with a failure.
   */
  def apply[T](future: Future[T]): FlowWithSource[T, T] = FlowFrom[T].withSource(FutureSource(future))

  /**
   * Elements are produced from the tick closure periodically with the specified interval.
   * The tick element will be delivered to downstream consumers that has requested any elements.
   * If a consumer has not requested any elements at the point in time when the tick
   * element is produced it will not receive that tick element later. It will
   * receive new tick elements as soon as it has requested more elements.
   */
  def apply[T](initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ T): FlowWithSource[T, T] =
    FlowFrom[T].withSource(TickSource(initialDelay, interval, tick))

}

trait Source[+In] {
  def attach(flowSubscriber: Subscriber[In] @uncheckedVariance, materializer: FlowMaterializer, flowName: String): Any
}

trait SourceKey[+In, T] extends Source[In] {
  override def attach(flowSubscriber: Subscriber[In] @uncheckedVariance, materializer: FlowMaterializer, flowName: String): T
  // these are unique keys, case class equality would break them
  final override def equals(other: Any): Boolean = super.equals(other)
  final override def hashCode: Int = super.hashCode
}

/**
 * Default input.
 * Allows to materialize a Flow with this input to Subscriber.
 */
final case class SubscriberSource[In]() extends SourceKey[In, Subscriber[In]] {
  override def attach(flowSubscriber: Subscriber[In], materializer: FlowMaterializer, flowName: String): Subscriber[In] =
    flowSubscriber

  def subscriber(m: MaterializedSource): Subscriber[In] = m.getSourceFor(this)
}

/**
 * [[Source]] from `Publisher`.
 */
final case class PublisherSource[In](p: Publisher[In]) extends Source[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: FlowMaterializer, flowName: String): AnyRef = {
    p.subscribe(flowSubscriber)
    None
  }
}

/**
 * [[Source]] from `Iterator`
 */
final case class IteratorSource[In](iterator: Iterator[In]) extends Source[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: FlowMaterializer, flowName: String): AnyRef = {
    val p: Publisher[In] = materializer.materializeSource(this, flowName)
    p.subscribe(flowSubscriber)
    None
  }
}

/**
 * [[Source]] from `Iterable`
 */
final case class IterableSource[In](iterable: immutable.Iterable[In]) extends Source[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: FlowMaterializer, flowName: String): AnyRef = {
    val p: Publisher[In] = materializer.materializeSource(this, flowName)
    p.subscribe(flowSubscriber)
    None
  }
}

/**
 * [[Source]] from closure
 */
final case class ThunkSource[In](f: () ⇒ Option[In]) extends Source[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: FlowMaterializer, flowName: String): AnyRef = {
    val p: Publisher[In] = materializer.materializeSource(this, flowName)
    p.subscribe(flowSubscriber)
    None
  }
}

/**
 * [[Source]] from closure
 */
final case class TickSource[In](initialDelay: FiniteDuration, interval: FiniteDuration, tick: () ⇒ In) extends Source[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: FlowMaterializer, flowName: String): AnyRef = {
    val p: Publisher[In] = materializer.materializeSource(this, flowName)
    p.subscribe(flowSubscriber)
    None
  }
}

/**
 * [[Source]] from `Future`
 */
final case class FutureSource[In](future: Future[In]) extends Source[In] {
  override def attach(flowSubscriber: Subscriber[In], materializer: FlowMaterializer, flowName: String): AnyRef = {
    val p: Publisher[In] = materializer.materializeSource(this, flowName)
    p.subscribe(flowSubscriber)
    None
  }
}

trait Sink[-Out] {
  def attach(flowPublisher: Publisher[Out @uncheckedVariance], materializer: FlowMaterializer): Any
}

trait SinkKey[-Out, T] extends Sink[Out] {
  override def attach(flowPublisher: Publisher[Out @uncheckedVariance], materializer: FlowMaterializer): T
  // these are unique keys, case class equality would break them
  final override def equals(other: Any): Boolean = super.equals(other)
  final override def hashCode: Int = super.hashCode
}

/**
 * Default output.
 * Allows to materialize a Flow with this output to Publisher.
 */
// FIXME: make case object
final case class PublisherSink[Out]() extends SinkKey[Out, Publisher[Out]] {
  def attach(flowPublisher: Publisher[Out], materializer: FlowMaterializer): Publisher[Out] = flowPublisher
  def publisher(m: MaterializedSink): Publisher[Out] = m.getSinkFor(this)
}

/**
 * Output to nirvana.
 */
final case object BlackholeSink extends Sink[Any] {
  override def attach(flowPublisher: Publisher[Any], materializer: FlowMaterializer): AnyRef = {
    val s = new BlackholeSubscriber[Any](materializer.settings.maxInputBufferSize)
    flowPublisher.subscribe(s)
    s
  }
}

/**
 * [[Sink]] to a Subscriber.
 */
final case class SubscriberSink[Out](subscriber: Subscriber[Out]) extends Sink[Out] {
  override def attach(flowPublisher: Publisher[Out], materializer: FlowMaterializer): AnyRef = {
    flowPublisher.subscribe(subscriber)
    subscriber
  }
}

/**
 * Foreach output. Invokes the given function for each element. Completes the [[#future]] when
 * all elements processed, or stream failed.
 */
final case class ForeachSink[Out](f: Out ⇒ Unit) extends SinkKey[Out, Future[Unit]] {
  override def attach(flowPublisher: Publisher[Out], materializer: FlowMaterializer): Future[Unit] = {
    val promise = Promise[Unit]()
    FlowFrom(flowPublisher).transform("foreach", () ⇒ new Transformer[Out, Unit] {
      override def onNext(in: Out) = { f(in); Nil }
      override def onTermination(e: Option[Throwable]) = {
        e match {
          case None    ⇒ promise.success(())
          case Some(e) ⇒ promise.failure(e)
        }
        Nil
      }
    }).consume()(materializer)
    promise.future
  }
  def future(m: MaterializedSink): Future[Unit] = m.getSinkFor(this)
}

/**
 * Fold output. Reduces output stream according to the given fold function.
 */
final case class FoldSink[T, Out](zero: T)(f: (T, Out) ⇒ T) extends Sink[Out] {
  override def attach(flowPublisher: Publisher[Out], materializer: FlowMaterializer): AnyRef = ???
  def future: Future[T] = ???
}

/**
 * Marker interface for flows that have a free (attachable) input side.
 */
sealed trait HasNoSource[-In] extends Flow

/**
 * Marker interface for flows that have a free (attachable) output side.
 */
sealed trait HasNoSink[+Out] extends Flow

/**
 * Operations offered by flows with a free output side: the DSL flows left-to-right only.
 */
trait FlowOps[-In, +Out] extends HasNoSink[Out] {
  type Repr[-I, +O] <: FlowOps[I, O]

  // Storing ops in reverse order
  protected def andThen[U](op: AstNode): Repr[In, U]

  def map[T](f: Out ⇒ T): Repr[In, T] =
    transform("map", () ⇒ new Transformer[Out, T] {
      override def onNext(in: Out) = List(f(in))
    })

  def transform[T](name: String, mkTransformer: () ⇒ Transformer[Out, T]): Repr[In, T] = {
    andThen(Transform(name, mkTransformer.asInstanceOf[() ⇒ Transformer[Any, Any]]))
  }
}

/**
 * Flow without attached input and without attached output, can be used as a `Processor`.
 */
final case class ProcessorFlow[-In, +Out](ops: List[AstNode]) extends FlowOps[In, Out] with HasNoSource[In] {
  override type Repr[-I, +O] = ProcessorFlow[I, O]

  override protected def andThen[U](op: AstNode): Repr[In, U] = this.copy(ops = op :: ops)

  def withSink(out: Sink[Out]): FlowWithSink[In, Out] = FlowWithSink(out, ops)
  def withSource(in: Source[In]): FlowWithSource[In, Out] = FlowWithSource(in, ops)

  def prepend[T](f: ProcessorFlow[T, In]): ProcessorFlow[T, Out] = ProcessorFlow(ops ::: f.ops)
  def prepend[T](f: FlowWithSource[T, In]): FlowWithSource[T, Out] = f.append(this)

  def append[T](f: ProcessorFlow[Out, T]): ProcessorFlow[In, T] = ProcessorFlow(f.ops ++: ops)
  def append[T](f: FlowWithSink[Out, T]): FlowWithSink[In, T] = f.prepend(this)
}

/**
 *  Flow with attached output, can be used as a `Subscriber`.
 */
final case class FlowWithSink[-In, +Out](private[scaladsl2] val output: Sink[Out @uncheckedVariance], ops: List[AstNode]) extends HasNoSource[In] {

  def withSource(in: Source[In]): RunnableFlow[In, Out] = new RunnableFlow(in, output, ops)
  def withoutSink: ProcessorFlow[In, Out] = ProcessorFlow(ops)

  def prepend[T](f: ProcessorFlow[T, In]): FlowWithSink[T, Out] = FlowWithSink(output, ops ::: f.ops)
  def prepend[T](f: FlowWithSource[T, In]): RunnableFlow[T, Out] = new RunnableFlow(f.input, output, ops ::: f.ops)

  def toSubscriber()(implicit materializer: FlowMaterializer): Subscriber[In @uncheckedVariance] = {
    val subIn = SubscriberSource[In]()
    val mf = withSource(subIn).run()
    subIn.subscriber(mf)
  }
}

/**
 * Flow with attached input, can be used as a `Publisher`.
 */
final case class FlowWithSource[-In, +Out](private[scaladsl2] val input: Source[In @uncheckedVariance], ops: List[AstNode]) extends FlowOps[In, Out] {
  override type Repr[-I, +O] = FlowWithSource[I, O]

  override protected def andThen[U](op: AstNode): Repr[In, U] = this.copy(ops = op :: ops)

  def withSink(out: Sink[Out]): RunnableFlow[In, Out] = new RunnableFlow(input, out, ops)
  def withoutSource: ProcessorFlow[In, Out] = ProcessorFlow(ops)

  def append[T](f: ProcessorFlow[Out, T]): FlowWithSource[In, T] = FlowWithSource(input, f.ops ++: ops)
  def append[T](f: FlowWithSink[Out, T]): RunnableFlow[In, T] = new RunnableFlow(input, f.output, f.ops ++: ops)

  def toPublisher()(implicit materializer: FlowMaterializer): Publisher[Out @uncheckedVariance] = {
    val pubOut = PublisherSink[Out]()
    val mf = withSink(pubOut).run()
    pubOut.publisher(mf)
  }

  def publishTo(subscriber: Subscriber[Out @uncheckedVariance])(implicit materializer: FlowMaterializer): Unit =
    toPublisher().subscribe(subscriber)

  def consume()(implicit materializer: FlowMaterializer): Unit =
    withSink(BlackholeSink).run()

}

/**
 * Flow with attached input and output, can be executed.
 */
final case class RunnableFlow[-In, +Out](private[scaladsl2] val input: Source[In @uncheckedVariance],
                                         private[scaladsl2] val output: Sink[Out @uncheckedVariance], ops: List[AstNode]) extends Flow {
  def withoutSink: FlowWithSource[In, Out] = FlowWithSource(input, ops)
  def withoutSource: FlowWithSink[In, Out] = FlowWithSink(output, ops)

  def run()(implicit materializer: FlowMaterializer): MaterializedFlow =
    materializer.materialize(input, output, ops)
}

class MaterializedFlow(sourceKey: AnyRef, matSource: Any, sinkKey: AnyRef, matSink: Any) extends MaterializedSource with MaterializedSink {
  override def getSourceFor[T](key: SourceKey[_, T]): T =
    if (key == sourceKey) matSource.asInstanceOf[T]
    else throw new IllegalArgumentException(s"Source key [$key] doesn't match the source [$sourceKey] of this flow")

  def getSinkFor[T](key: SinkKey[_, T]): T =
    if (key == sinkKey) matSink.asInstanceOf[T]
    else throw new IllegalArgumentException(s"Sink key [$key] doesn't match the sink [$sinkKey] of this flow")
}

trait MaterializedSource {
  def getSourceFor[T](sourceKey: SourceKey[_, T]): T
}

trait MaterializedSink {
  def getSinkFor[T](sinkKey: SinkKey[_, T]): T
}
