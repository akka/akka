/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.ActorRef
import akka.actor.Props
import scala.collection.immutable
import scala.annotation.unchecked.uncheckedVariance
import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }
import org.reactivestreams.{ Publisher, Subscriber, Subscription }
import akka.stream.scaladsl.OperationAttributes._
import akka.stream.impl.{ ActorBasedFlowMaterializer, ActorProcessorFactory, FanoutProcessorImpl, BlackholeSubscriber }
import akka.stream.stage._
import java.util.concurrent.atomic.AtomicReference

sealed trait ActorFlowSink[-In] extends Sink[In] {

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
  def attach(flowPublisher: Publisher[In @uncheckedVariance], materializer: ActorBasedFlowMaterializer, flowName: String): MaterializedType

  /**
   * This method is only used for Sinks that return true from [[#isActive]], which then must
   * implement it.
   */
  def create(materializer: ActorBasedFlowMaterializer, flowName: String): (Subscriber[In] @uncheckedVariance, MaterializedType) =
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
 * A sink that does not need to create a user-accessible object during materialization.
 */
trait SimpleActorFlowSink[-In] extends ActorFlowSink[In] {
  override type MaterializedType = Unit
}

/**
 * A sink that will create an object during materialization that the user will need
 * to retrieve in order to access aspects of this sink (could be a completion Future
 * or a cancellation handle, etc.)
 */
trait KeyedActorFlowSink[-In] extends ActorFlowSink[In] with KeyedSink[In]

object PublisherSink {
  def apply[T](): PublisherSink[T] = new PublisherSink[T]
  def withFanout[T](initialBufferSize: Int, maximumBufferSize: Int): FanoutPublisherSink[T] =
    new FanoutPublisherSink[T](initialBufferSize, maximumBufferSize)
}

/**
 * Holds the downstream-most [[org.reactivestreams.Publisher]] interface of the materialized flow.
 * The stream will not have any subscribers attached at this point, which means that after prefetching
 * elements to fill the internal buffers it will assert back-pressure until
 * a subscriber connects and creates demand for elements to be emitted.
 */
class PublisherSink[In] extends KeyedActorFlowSink[In] {
  type MaterializedType = Publisher[In]

  override def attach(flowPublisher: Publisher[In], materializer: ActorBasedFlowMaterializer, flowName: String) = flowPublisher

  override def toString: String = "PublisherSink"
}

final case class FanoutPublisherSink[In](initialBufferSize: Int, maximumBufferSize: Int) extends KeyedActorFlowSink[In] {
  type MaterializedType = Publisher[In]

  override def attach(flowPublisher: Publisher[In], materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val fanoutActor = materializer.actorOf(
      Props(new FanoutProcessorImpl(materializer.settings, initialBufferSize, maximumBufferSize)), s"$flowName-fanoutPublisher")
    val fanoutProcessor = ActorProcessorFactory[In, In](fanoutActor)
    flowPublisher.subscribe(fanoutProcessor)
    fanoutProcessor
  }
}

object HeadSink {
  def apply[T](): HeadSink[T] = new HeadSink[T]

  /** INTERNAL API */
  private[akka] class HeadSinkSubscriber[In](p: Promise[In]) extends Subscriber[In] {
    private val sub = new AtomicReference[Subscription]
    override def onSubscribe(s: Subscription): Unit =
      if (!sub.compareAndSet(null, s)) s.cancel()
      else s.request(1)

    override def onNext(t: In): Unit = { p.trySuccess(t); sub.get.cancel() }
    override def onError(t: Throwable): Unit = p.tryFailure(t)
    override def onComplete(): Unit = p.tryFailure(new NoSuchElementException("empty stream"))
  }

}

/**
 * Holds a [[scala.concurrent.Future]] that will be fulfilled with the first
 * thing that is signaled to this stream, which can be either an element (after
 * which the upstream subscription is canceled), an error condition (putting
 * the Future into the corresponding failed state) or the end-of-stream
 * (failing the Future with a NoSuchElementException).
 */
class HeadSink[In] extends KeyedActorFlowSink[In] {

  type MaterializedType = Future[In]

  def attach(flowPublisher: Publisher[In], materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val (sub, f) = create(materializer, flowName)
    flowPublisher.subscribe(sub)
    f
  }
  override def isActive = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val p = Promise[In]()
    val sub = new HeadSink.HeadSinkSubscriber[In](p)
    (sub, p.future)
  }

  override def toString: String = "HeadSink"
}

/**
 * Attaches a subscriber to this stream which will just discard all received
 * elements.
 */
final case object BlackholeSink extends SimpleActorFlowSink[Any] {
  override def attach(flowPublisher: Publisher[Any], materializer: ActorBasedFlowMaterializer, flowName: String): Unit =
    flowPublisher.subscribe(create(materializer, flowName)._1)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) =
    (new BlackholeSubscriber[Any](materializer.settings.maxInputBufferSize), ())
}

/**
 * Attaches a subscriber to this stream.
 */
final case class SubscriberSink[In](subscriber: Subscriber[In]) extends SimpleActorFlowSink[In] {
  override def attach(flowPublisher: Publisher[In], materializer: ActorBasedFlowMaterializer, flowName: String) =
    flowPublisher.subscribe(subscriber)
  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) = (subscriber, ())
}

object OnCompleteSink {
  private val SuccessUnit = Success[Unit](())
}

/**
 * When the flow is completed, either through an error or normal
 * completion, apply the provided function with [[scala.util.Success]]
 * or [[scala.util.Failure]].
 */
final case class OnCompleteSink[In](callback: Try[Unit] ⇒ Unit) extends SimpleActorFlowSink[In] {

  override def attach(flowPublisher: Publisher[In], materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val section = (s: Source[In]) ⇒ s.transform(() ⇒ new PushStage[In, Unit] {
      override def onPush(elem: In, ctx: Context[Unit]): Directive = ctx.pull()
      override def onUpstreamFailure(cause: Throwable, ctx: Context[Unit]): TerminationDirective = {
        callback(Failure(cause))
        ctx.fail(cause)
      }
      override def onUpstreamFinish(ctx: Context[Unit]): TerminationDirective = {
        callback(OnCompleteSink.SuccessUnit)
        ctx.finish()
      }
    })

    Source(flowPublisher).
      section(name("onCompleteSink"))(section).
      to(BlackholeSink).
      run()(materializer.withNamePrefix(flowName))
  }
}

/**
 * Invoke the given procedure for each received element. The sink holds a [[scala.concurrent.Future]]
 * that will be completed with `Success` when reaching the normal end of the stream, or completed
 * with `Failure` if there is an error is signaled in the stream.
 */
final case class ForeachSink[In](f: In ⇒ Unit) extends KeyedActorFlowSink[In] {

  override type MaterializedType = Future[Unit]

  override def attach(flowPublisher: Publisher[In], materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val promise = Promise[Unit]()
    val section = (s: Source[In]) ⇒ s.transform(() ⇒ new PushStage[In, Unit] {
      override def onPush(elem: In, ctx: Context[Unit]): Directive = {
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
    })

    Source(flowPublisher).
      section(name("foreach"))(section).
      to(BlackholeSink).
      run()(materializer.withNamePrefix(flowName))
    promise.future
  }
}

/**
 * Invoke the given function for every received element, giving it its previous
 * output (or the given `zero` value) and the element as input. The sink holds a
 * [[scala.concurrent.Future]] that will be completed with value of the final
 * function evaluation when the input stream ends, or completed with `Failure`
 * if there is an error is signaled in the stream.
 */
final case class FoldSink[U, In](zero: U)(f: (U, In) ⇒ U) extends KeyedActorFlowSink[In] {

  type MaterializedType = Future[U]

  override def attach(flowPublisher: Publisher[In], materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val promise = Promise[U]()
    val section = (s: Source[In]) ⇒ s.transform(() ⇒ new PushStage[In, U] {
      private var aggregator = zero

      override def onPush(elem: In, ctx: Context[U]): Directive = {
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
    })

    Source(flowPublisher).
      section(name("fold"))(section).
      to(BlackholeSink).
      run()(materializer.withNamePrefix(flowName))
    promise.future
  }
}

/**
 * A sink that immediately cancels its upstream upon materialization.
 */
final case object CancelSink extends SimpleActorFlowSink[Any] {

  override def attach(flowPublisher: Publisher[Any], materializer: ActorBasedFlowMaterializer, flowName: String): Unit = {
    flowPublisher.subscribe(new Subscriber[Any] {
      override def onError(t: Throwable): Unit = ()
      override def onSubscribe(s: Subscription): Unit = s.cancel()
      override def onComplete(): Unit = ()
      override def onNext(t: Any): Unit = ()
    })
  }
}

/**
 * Creates and wraps an actor into [[org.reactivestreams.Subscriber]] from the given `props`,
 * which should be [[akka.actor.Props]] for an [[akka.stream.actor.ActorSubscriber]].
 */
final case class PropsSink[In](props: Props) extends KeyedActorFlowSink[In] {

  type MaterializedType = ActorRef

  override def attach(flowPublisher: Publisher[In], materializer: ActorBasedFlowMaterializer, flowName: String): ActorRef = {
    val (subscriber, subscriberRef) = create(materializer, flowName)
    flowPublisher.subscribe(subscriber)
    subscriberRef
  }

  override def isActive: Boolean = true
  override def create(materializer: ActorBasedFlowMaterializer, flowName: String) = {
    val subscriberRef = materializer.actorOf(props, name = s"$flowName-props")
    (akka.stream.actor.ActorSubscriber[In](subscriberRef), subscriberRef)
  }

}
