/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl

import java.util.concurrent.Callable
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.concurrent.Future
import scala.util.Failure
import scala.util.Success
import org.reactivestreams.api.Producer
import akka.japi.Function
import akka.japi.Function2
import akka.japi.Procedure
import akka.japi.Util.immutableSeq
import akka.stream.FlowMaterializer
import akka.stream.scaladsl.{ Flow ⇒ SFlow }
import akka.stream.Transformer
import akka.stream.RecoveryTransformer
import org.reactivestreams.api.Consumer

/**
 * Java API
 */
object Flow {

  /**
   * Construct a transformation of the given producer. The transformation steps
   * are executed by a series of [[org.reactivestreams.api.Processor]] instances
   * that mediate the flow of elements downstream and the propagation of
   * back-pressure upstream.
   */
  def create[T](producer: Producer[T]): Flow[T] = new FlowAdapter(SFlow.apply(producer))

  /**
   * Start a new flow from the given Iterator. The produced stream of elements
   * will continue until the iterator runs empty or fails during evaluation of
   * the <code>next()</code> method. Elements are pulled out of the iterator
   * in accordance with the demand coming from the downstream transformation
   * steps.
   */
  def create[T](iterator: java.util.Iterator[T]): Flow[T] =
    new FlowAdapter(SFlow.apply(iterator.asScala))

  /**
   * Start a new flow from the given Iterable. This is like starting from an
   * Iterator, but every Consumer directly attached to the Producer of this
   * stream will see an individual flow of elements (always starting from the
   * beginning) regardless of when they subscribed.
   */
  def create[T](iterable: java.lang.Iterable[T]): Flow[T] = {
    val iterAdapter: immutable.Iterable[T] = new immutable.Iterable[T] {
      override def iterator: Iterator[T] = iterable.iterator().asScala
    }
    new FlowAdapter(SFlow.apply(iterAdapter))
  }

  /**
   * Define the sequence of elements to be produced by the given Callable.
   * The stream ends normally when evaluation of the Callable results in
   * a [[akka.stream.Stop]] exception being thrown; it ends exceptionally
   * when any other exception is thrown.
   */
  def create[T](block: Callable[T]): Flow[T] = new FlowAdapter(SFlow.apply(() ⇒ block.call()))

}

/**
 * Java API: The Flow DSL allows the formulation of stream transformations based on some
 * input. The starting point can be a collection, an iterator, a block of code
 * which is evaluated repeatedly or a [[org.reactivestreams.api.Producer]].
 *
 * See <a href="https://github.com/reactive-streams/reactive-streams/">Reactive Streams</a> for details.
 *
 * Each DSL element produces a new Flow that can be further transformed, building
 * up a description of the complete transformation pipeline. In order to execute
 * this pipeline the Flow must be materialized by calling the [[#toFuture]], [[#consume]],
 * [[#onComplete]], or [[#toProducer]] methods on it.
 *
 * It should be noted that the streams modeled by this library are “hot”,
 * meaning that they asynchronously flow through a series of processors without
 * detailed control by the user. In particular it is not predictable how many
 * elements a given transformation step might buffer before handing elements
 * downstream, which means that transformation functions may be invoked more
 * often than for corresponding transformations on strict collections like
 * `List`. *An important consequence* is that elements that were produced
 * into a stream may be discarded by later processors, e.g. when using the
 * [[#take]] combinator.
 *
 * By default every operation is executed within its own [[akka.actor.Actor]]
 * to enable full pipelining of the chained set of computations. This behavior
 * is determined by the [[akka.stream.FlowMaterializer]] which is required
 * by those methods that materialize the Flow into a series of
 * [[org.reactivestreams.api.Processor]] instances. The returned reactive stream
 * is fully started and active.
 */
abstract class Flow[T] {

  /**
   * Transform this stream by applying the given function to each of the elements
   * as they pass through this processing step.
   */
  def map[U](f: Function[T, U]): Flow[U]

  /**
   * Only pass on those elements that satisfy the given predicate.
   */
  def filter(p: Predicate[T]): Flow[T]

  /**
   * Transform this stream by applying the given partial function to each of the elements
   * on which the function is defined as they pass through this processing step.
   * Non-matching elements are filtered out.
   *
   * Use [[akka.japi.pf.PFBuilder]] to construct the `PartialFunction`.
   */
  def collect[U](pf: PartialFunction[T, U]): Flow[U]

  /**
   * Invoke the given procedure for each received element and produce a Unit value
   * upon reaching the normal end of the stream. Please note that also in this case
   * the flow needs to be materialized (e.g. using [[#consume]]) to initiate its
   * execution.
   */
  def foreach(c: Procedure[T]): Flow[Unit]

  /**
   * Invoke the given function for every received element, giving it its previous
   * output (or the given “zero” value) and the element as input. The returned stream
   * will receive the return value of the final function evaluation when the input
   * stream ends.
   */
  def fold[U](zero: U, f: Function2[U, T, U]): Flow[U]

  /**
   * Discard the given number of elements at the beginning of the stream.
   */
  def drop(n: Int): Flow[T]

  /**
   * Terminate processing (and cancel the upstream producer) after the given
   * number of elements. Due to input buffering some elements may have been
   * requested from upstream producers that will then not be processed downstream
   * of this step.
   */
  def take(n: Int): Flow[T]

  /**
   * Chunk up this stream into groups of the given size, with the last group
   * possibly smaller than requested due to end-of-stream.
   */
  def grouped(n: Int): Flow[java.util.List[T]]

  /**
   * Transform each input element into a sequence of output elements that is
   * then flattened into the output stream.
   */
  def mapConcat[U](f: Function[T, java.util.List[U]]): Flow[U]

  /**
   * Generic transformation of a stream: for each element the [[akka.stream.Transformer#onNext]]
   * function is invoked and expecting a (possibly empty) sequence of output elements
   * to be produced.
   * After handing off the elements produced from one input element to the downstream
   * consumers, the [[akka.stream.Transformer#isComplete]] predicate determines whether to end
   * stream processing at this point; in that case the upstream subscription is
   * canceled. Before signaling normal completion to the downstream consumers,
   * the [[akka.stream.Transformer#onComplete]] function is invoked to produce a (possibly empty)
   * sequence of elements in response to the end-of-stream event.
   *
   * After normal completion or error the [[akka.stream.Transformer#cleanup]] function is called.
   *
   * It is possible to keep state in the concrete [[akka.stream.Transformer]] instance with
   * ordinary instance variables. The [[akka.stream.Transformer]] is executed by an actor and
   * therefore you don not have to add any additional thread safety or memory
   * visibility constructs to access the state from the callback methods.
   */
  def transform[U](transformer: Transformer[T, U]): Flow[U]

  /**
   * This transformation stage works exactly like [[#transform]] with the
   * change that failure signaled from upstream will invoke
   * [[akka.stream.RecoveryTransformer#onError]], which can emit an additional sequence of
   * elements before the stream ends.
   *
   * After normal completion or error the [[akka.stream.RecoveryTransformer#cleanup]] function is called.
   */
  def transformRecover[U](transformer: RecoveryTransformer[T, U]): Flow[U]

  /**
   * This operation demultiplexes the incoming stream into separate output
   * streams, one for each element key. The key is computed for each element
   * using the given function. When a new key is encountered for the first time
   * it is emitted to the downstream consumer together with a fresh
   * producer that will eventually produce all the elements of the substream
   * for that key. Not consuming the elements from the created streams will
   * stop this processor from processing more elements, therefore you must take
   * care to unblock (or cancel) all of the produced streams even if you want
   * to consume only one of them.
   */
  def groupBy[K](f: Function[T, K]): Flow[Pair[K, Producer[T]]]

  /**
   * This operation applies the given predicate to all incoming elements and
   * emits them to a stream of output streams, always beginning a new one with
   * the current element if the given predicate returns true for it. This means
   * that for the following series of predicate values, three substreams will
   * be produced with lengths 1, 2, and 3:
   *
   * {{{
   * false,             // element goes into first substream
   * true, false,       // elements go into second substream
   * true, false, false // elements go into third substream
   * }}}
   */
  def splitWhen(p: Predicate[T]): Flow[Producer[T]]

  /**
   * Merge this stream with the one emitted by the given producer, taking
   * elements as they arrive from either side (picking randomly when both
   * have elements ready).
   */
  def merge[U >: T](other: Producer[U]): Flow[U]

  /**
   * Zip this stream together with the one emitted by the given producer.
   * This transformation finishes when either input stream reaches its end,
   * cancelling the subscription to the other one.
   */
  def zip[U](other: Producer[U]): Flow[Pair[T, U]]

  /**
   * Concatenate the given other stream to this stream so that the first element
   * emitted by the given producer is emitted after the last element of this
   * stream.
   */
  def concat[U >: T](next: Producer[U]): Flow[U]

  /**
   * Fan-out the stream to another consumer. Each element is produced to
   * the `other` consumer as well as to downstream consumers. It will
   * not shutdown until the subscriptions for `other` and at least
   * one downstream consumer have been established.
   */
  def tee(other: Consumer[_ >: T]): Flow[T]

  /**
   * Returns a [[scala.concurrent.Future]] that will be fulfilled with the first
   * thing that is signaled to this stream, which can be either an element (after
   * which the upstream subscription is canceled), an error condition (putting
   * the Future into the corresponding failed state) or the end-of-stream
   * (failing the Future with a NoSuchElementException). *This operation
   * materializes the flow and initiates its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def toFuture(materializer: FlowMaterializer): Future[T]

  /**
   * Attaches a consumer to this stream which will just discard all received
   * elements. *This will materialize the flow and initiate its execution.*
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def consume(materializer: FlowMaterializer): Unit

  /**
   * When this flow is completed, either through an error or normal
   * completion, call the [[OnCompleteCallback#onComplete]] method.
   *
   * *This operation materializes the flow and initiates its execution.*
   */
  def onComplete(materializer: FlowMaterializer)(callback: OnCompleteCallback): Unit

  /**
   * Materialize this flow and return the downstream-most
   * [[org.reactivestreams.api.Producer]] interface. The stream will not have
   * any consumers attached at this point, which means that after prefetching
   * elements to fill the internal buffers it will assert back-pressure until
   * a consumer connects and creates demand for elements to be emitted.
   *
   * The given FlowMaterializer decides how the flow’s logical structure is
   * broken down into individual processing steps.
   */
  def toProducer(materializer: FlowMaterializer): Producer[T]

}

/**
 * @see [[Flow#onComplete]]
 */
trait OnCompleteCallback {
  /**
   * The parameter `e` will be `null` when the stream terminated
   * normally, otherwise it will be the exception that caused
   * the abnormal termination.
   */
  def onComplete(e: Throwable)
}

/**
 * Java API: Represents a tuple of two elements.
 */
case class Pair[A, B](a: A, b: B) // FIXME move this to akka.japi.Pair in akka-actor

/**
 * Java API: Defines a criteria and determines whether the parameter meets this criteria.
 */
trait Predicate[T] {
  // FIXME move this to akka.japi.Predicate in akka-actor 
  def test(param: T): Boolean
}

/**
 * INTERNAL API
 */
private[akka] class FlowAdapter[T](delegate: SFlow[T]) extends Flow[T] {
  override def map[U](f: Function[T, U]): Flow[U] = new FlowAdapter(delegate.map(f.apply))

  override def filter(p: Predicate[T]): Flow[T] = new FlowAdapter(delegate.filter(p.test))

  override def collect[U](pf: PartialFunction[T, U]): Flow[U] = new FlowAdapter(delegate.collect(pf))

  override def foreach(c: Procedure[T]): Flow[Unit] = new FlowAdapter(delegate.foreach(c.apply))

  override def fold[U](zero: U, f: Function2[U, T, U]): Flow[U] =
    new FlowAdapter(delegate.fold(zero) { case (a, b) ⇒ f.apply(a, b) })

  override def drop(n: Int): Flow[T] = new FlowAdapter(delegate.drop(n))

  override def take(n: Int): Flow[T] = new FlowAdapter(delegate.take(n))

  override def grouped(n: Int): Flow[java.util.List[T]] =
    new FlowAdapter(delegate.grouped(n).map(_.asJava)) // FIXME optimize to one step

  override def mapConcat[U](f: Function[T, java.util.List[U]]): Flow[U] =
    new FlowAdapter(delegate.mapConcat(elem ⇒ immutableSeq(f.apply(elem))))

  override def transform[U](transformer: Transformer[T, U]): Flow[U] =
    new FlowAdapter(delegate.transform(new Transformer[T, U] {
      override def onNext(in: T) = transformer.onNext(in)
      override def isComplete = transformer.isComplete
      override def onComplete() = transformer.onComplete()
      override def onError(cause: Throwable) = transformer.onError(cause)
      override def cleanup() = transformer.cleanup()
    }))

  override def transformRecover[U](transformer: RecoveryTransformer[T, U]): Flow[U] =
    new FlowAdapter(delegate.transform(new RecoveryTransformer[T, U] {
      override def onNext(in: T) = transformer.onNext(in)
      override def isComplete = transformer.isComplete
      override def onComplete() = transformer.onComplete()
      override def onError(cause: Throwable) = transformer.onError(cause)
      override def onErrorRecover(cause: Throwable) = transformer.onErrorRecover(cause)
      override def cleanup() = transformer.cleanup()
    }))

  override def groupBy[K](f: Function[T, K]): Flow[Pair[K, Producer[T]]] =
    new FlowAdapter(delegate.groupBy(f.apply).map { case (k, p) ⇒ Pair(k, p) }) // FIXME optimize to one step

  override def splitWhen(p: Predicate[T]): Flow[Producer[T]] =
    new FlowAdapter(delegate.splitWhen(p.test))

  override def merge[U >: T](other: Producer[U]): Flow[U] =
    new FlowAdapter(delegate.merge(other))

  override def zip[U](other: Producer[U]): Flow[Pair[T, U]] =
    new FlowAdapter(delegate.zip(other).map { case (k, p) ⇒ Pair(k, p) }) // FIXME optimize to one step

  override def concat[U >: T](next: Producer[U]): Flow[U] =
    new FlowAdapter(delegate.concat(next))

  override def tee(other: Consumer[_ >: T]): Flow[T] =
    new FlowAdapter(delegate.tee(other))

  override def toFuture(materializer: FlowMaterializer): Future[T] =
    delegate.toFuture(materializer)

  override def consume(materializer: FlowMaterializer): Unit =
    delegate.consume(materializer)

  override def onComplete(materializer: FlowMaterializer)(callback: OnCompleteCallback): Unit =
    delegate.onComplete(materializer) {
      case Success(_) ⇒ callback.onComplete(null)
      case Failure(e) ⇒ callback.onComplete(e)
    }

  override def toProducer(materializer: FlowMaterializer): Producer[T] =
    delegate.toProducer(materializer)

}