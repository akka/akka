/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.actor.Props

import scala.collection.immutable
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import akka.stream.Transformer
import akka.stream.impl.{ FanoutProcessorImpl, BlackholeSubscriber }
import akka.stream.impl2.{ ActorProcessorFactory, ActorBasedFlowMaterializer }
import java.util.concurrent.atomic.AtomicReference

/**
 * This trait is a marker for a pluggable stream sink. Concrete instances should
 * implement [[SinkWithKey]] or [[SimpleSink]], otherwise a custom [[FlowMaterializer]]
 * will have to be used to be able to attach them.
 *
 * All Sinks defined in this package rely upon an [[akka.stream.impl2.ActorBasedFlowMaterializer]] being
 * made available to them in order to use the <code>attach</code> method. Other
 * FlowMaterializers can be used but must then implement the functionality of these
 * Sink nodes themselves (or construct an ActorBasedFlowMaterializer).
 */
trait Sink[-Out]

/**
 * A sink that does not need to create a user-accessible object during materialization.
 */
trait SimpleSink[-Out] extends Sink[Out] {
  /**
   * Attach this sink to the given [[org.reactivestreams.Publisher]]. Using the given
   * [[FlowMaterializer]] is completely optional, especially if this sink belongs to
   * a different Reactive Streams implementation. It is the responsibility of the
   * caller to provide a suitable FlowMaterializer that can be used for running
   * Flows if necessary.
   *
   * @param flowPublisher the Publisher to consume elements from
   * @param materializer a FlowMaterializer that may be used for creating flows
   * @param flowName the name of the current flow, which should be used in log statements or error messages
   */
  def attach(flowPublisher: Publisher[Out @uncheckedVariance], materializer: ActorBasedFlowMaterializer, flowName: String): Unit
  /**
   * This method is only used for Sinks that return true from [[#isActive]], which then must
   * implement it.
   */
  def create(materializer: ActorBasedFlowMaterializer, flowName: String): Subscriber[Out] @uncheckedVariance =
    throw new UnsupportedOperationException(s"forgot to implement create() for $getClass that says isActive==true")
  /**
   * This method indicates whether this Sink can create a Subscriber instead of being
   * attached to a Publisher. This is only used if the Flow does not contain any
   * operations.
   */
  def isActive: Boolean = false

}

/**
 * A sink that will create an object during materialization that the user will need
 * to retrieve in order to access aspects of this sink (could be a completion Future
 * or a cancellation handle, etc.)
 */
trait SinkWithKey[-Out, T] extends Sink[Out] {
  /**
   * Attach this sink to the given [[org.reactivestreams.Publisher]]. Using the given
   * [[FlowMaterializer]] is completely optional, especially if this sink belongs to
   * a different Reactive Streams implementation. It is the responsibility of the
   * caller to provide a suitable FlowMaterializer that can be used for running
   * Flows if necessary.
   *
   * @param flowPublisher the Publisher to consume elements from
   * @param materializer a FlowMaterializer that may be used for creating flows
   * @param flowName the name of the current flow, which should be used in log statements or error messages
   */
  def attach(flowPublisher: Publisher[Out @uncheckedVariance], materializer: ActorBasedFlowMaterializer, flowName: String): T
  /**
   * This method is only used for Sinks that return true from [[#isActive]], which then must
   * implement it.
   */
  def create(materializer: ActorBasedFlowMaterializer, flowName: String): (Subscriber[Out] @uncheckedVariance, T) =
    throw new UnsupportedOperationException(s"forgot to implement create() for $getClass that says isActive==true")
  /**
   * This method indicates whether this Sink can create a Subscriber instead of being
   * attached to a Publisher. This is only used if the Flow does not contain any
   * operations.
   */
  def isActive: Boolean = false

  // these are unique keys, case class equality would break them
  final override def equals(other: Any): Boolean = super.equals(other)
  final override def hashCode: Int = super.hashCode
}

/**
 * Holds the downstream-most [[org.reactivestreams.Publisher]] interface of the materialized flow.
 * The stream will not have any subscribers attached at this point, which means that after prefetching
 * elements to fill the internal buffers it will assert back-pressure until
 * a subscriber connects and creates demand for elements to be emitted.
 */
object PublisherSink {
  private val instance = new PublisherSink[Nothing]
  def apply[T]: PublisherSink[T] = instance.asInstanceOf[PublisherSink[T]]
  def withFanout[T](initialBufferSize: Int, maximumBufferSize: Int): FanoutPublisherSink[T] =
    new FanoutPublisherSink[T](initialBufferSize, maximumBufferSize)
}

class PublisherSink[Out] extends SinkWithKey[Out, Publisher[Out]] {
  def attach(flowPublisher: Publisher[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Out] = flowPublisher
  def publisher(m: MaterializedSink): Publisher[Out] = m.getSinkFor(this)

  override def toString: String = "PublisherSink"
}

class FanoutPublisherSink[Out](initialBufferSize: Int, maximumBufferSize: Int) extends SinkWithKey[Out, Publisher[Out]] {
  def publisher(m: MaterializedSink): Publisher[Out] = m.getSinkFor(this)
  override def attach(flowPublisher: Publisher[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Publisher[Out] = {
    val fanoutActor = materializer.actorOf(
      Props(new FanoutProcessorImpl(materializer.settings, initialBufferSize, maximumBufferSize)), s"$flowName-fanoutPublisher")
    val fanoutProcessor = ActorProcessorFactory[Out, Out](fanoutActor)
    flowPublisher.subscribe(fanoutProcessor)
    fanoutProcessor
  }

  override def toString: String = "Fanout"
}

object FutureSink {
  def apply[T]: FutureSink[T] = new FutureSink[T]
}

/**
 * Holds a [[scala.concurrent.Future]] that will be fulfilled with the first
 * thing that is signaled to this stream, which can be either an element (after
 * which the upstream subscription is canceled), an error condition (putting
 * the Future into the corresponding failed state) or the end-of-stream
 * (failing the Future with a NoSuchElementException).
 */
class FutureSink[Out] extends SinkWithKey[Out, Future[Out]] {
  def attach(flowPublisher: Publisher[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Future[Out] = {
    val (sub, f) = create(materializer, flowName)
    flowPublisher.subscribe(sub)
    f
  }
  override def isActive = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): (Subscriber[Out], Future[Out]) = {
    val p = Promise[Out]()
    val sub = new Subscriber[Out] { // TODO #15804 verify this using the RS TCK
      private val sub = new AtomicReference[Subscription]
      override def onSubscribe(s: Subscription): Unit =
        if (!sub.compareAndSet(null, s)) s.cancel()
        else s.request(1)
      override def onNext(t: Out): Unit = { p.trySuccess(t); sub.get.cancel() }
      override def onError(t: Throwable): Unit = p.tryFailure(t)
      override def onComplete(): Unit = p.tryFailure(new NoSuchElementException("empty stream"))
    }
    (sub, p.future)
  }

  def future(m: MaterializedSink): Future[Out] = m.getSinkFor(this)

  override def toString: String = "FutureSink"
}

/**
 * Attaches a subscriber to this stream which will just discard all received
 * elements.
 */
final case object BlackholeSink extends SimpleSink[Any] {
  override def attach(flowPublisher: Publisher[Any], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    flowPublisher.subscribe(create(materializer, flowName))
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Subscriber[Any] =
    new BlackholeSubscriber[Any](materializer.settings.maxInputBufferSize)
}

/**
 * Attaches a subscriber to this stream.
 */
final case class SubscriberSink[Out](subscriber: Subscriber[Out]) extends SimpleSink[Out] {
  override def attach(flowPublisher: Publisher[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    flowPublisher.subscribe(subscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String): Subscriber[Out] = subscriber
}

object OnCompleteSink {
  private val SuccessUnit = Success[Unit](())
}

/**
 * When the flow is completed, either through an error or normal
 * completion, apply the provided function with [[scala.util.Success]]
 * or [[scala.util.Failure]].
 */
final case class OnCompleteSink[Out](callback: Try[Unit] ⇒ Unit) extends SimpleSink[Out] {
  override def attach(flowPublisher: Publisher[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    FlowFrom(flowPublisher).transform("onCompleteSink", () ⇒ new Transformer[Out, Unit] {
      override def onNext(in: Out) = Nil
      override def onError(e: Throwable) = {
        callback(Failure(e))
        throw e
      }
      override def onTermination(e: Option[Throwable]) = {
        callback(OnCompleteSink.SuccessUnit)
        Nil
      }
    }).consume()(materializer.withNamePrefix(flowName))
}

/**
 * Invoke the given procedure for each received element. The sink holds a [[scala.concurrent.Future]]
 * that will be completed with `Success` when reaching the normal end of the stream, or completed
 * with `Failure` if there is an error is signaled in the stream.
 */
final case class ForeachSink[Out](f: Out ⇒ Unit) extends SinkWithKey[Out, Future[Unit]] {
  override def attach(flowPublisher: Publisher[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Future[Unit] = {
    val promise = Promise[Unit]()
    FlowFrom(flowPublisher).transform("foreach", () ⇒ new Transformer[Out, Unit] {
      override def onNext(in: Out) = { f(in); Nil }
      override def onError(cause: Throwable): Unit = ()
      override def onTermination(e: Option[Throwable]) = {
        e match {
          case None    ⇒ promise.success(())
          case Some(e) ⇒ promise.failure(e)
        }
        Nil
      }
    }).consume()(materializer.withNamePrefix(flowName))
    promise.future
  }
  def future(m: MaterializedSink): Future[Unit] = m.getSinkFor(this)
}

/**
 * Invoke the given function for every received element, giving it its previous
 * output (or the given `zero` value) and the element as input. The sink holds a
 * [[scala.concurrent.Future]] that will be completed with value of the final
 * function evaluation when the input stream ends, or completed with `Failure`
 * if there is an error is signaled in the stream.
 */
final case class FoldSink[U, Out](zero: U)(f: (U, Out) ⇒ U) extends SinkWithKey[Out, Future[U]] {
  override def attach(flowPublisher: Publisher[Out], materializer: ActorBasedFlowMaterializer, flowName: String): Future[U] = {
    val promise = Promise[U]()

    FlowFrom(flowPublisher).transform("fold", () ⇒ new Transformer[Out, U] {
      var state: U = zero
      override def onNext(in: Out): immutable.Seq[U] = { state = f(state, in); Nil }
      override def onTermination(e: Option[Throwable]) = {
        e match {
          case None    ⇒ promise.success(state)
          case Some(e) ⇒ promise.failure(e)
        }
        Nil
      }
    }).consume()(materializer.withNamePrefix(flowName))

    promise.future
  }
  def future(m: MaterializedSink): Future[U] = m.getSinkFor(this)
}

