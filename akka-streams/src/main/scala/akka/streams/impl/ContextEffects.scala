package akka.streams.impl

import akka.streams.Operation.{ FromProducerSource, Sink, Source }
import rx.async.api.Producer
import scala.concurrent.ExecutionContext
import rx.async.spi.{ Subscription, Subscriber, Publisher }

trait FanOut[I] extends Publisher[I] {
  def downstream: Downstream[I]
}

/**
 * Additional Effects supplied by the context to allow additional executing additional effects
 * of link internal sources and sinks.
 */
trait ContextEffects {
  /**
   * Subscribe to the given source and once subscribed call the `sinkConstructor` with upstream
   * effects.
   */
  def subscribeTo[O](source: Source[O])(sinkConstructor: Upstream ⇒ SyncSink[O]): Effect
  def subscribeFrom[O](sink: Sink[O])(sourceConstructor: Downstream[O] ⇒ SyncSource): Effect

  def expose[O](source: Source[O]): Producer[O]

  def internalProducer[O](constructor: Downstream[O] ⇒ SyncSource): Producer[O]

  def createFanOut[O](requestMore: Int ⇒ Unit): FanOut[O]

  implicit def executionContext: ExecutionContext
  def runInContext(body: ⇒ Effect): Unit
}

/** Tries to implement ContextEffect methods generally */
abstract class AbstractContextEffects extends ContextEffects {
  def subscribeTo[O](source: Source[O])(sinkConstructor: Upstream ⇒ SyncSink[O]): Effect =
    // TODO: think about how to avoid redundant creation of closures
    //       e.g. by letting OperationImpl provide constructors from static info
    ConnectInternalSourceSink(OperationImpl(_: Downstream[O], this, source), sinkConstructor)

  def subscribeFrom[O](sink: Sink[O])(sourceConstructor: Downstream[O] ⇒ SyncSource): Effect =
    ConnectInternalSourceSink(sourceConstructor, OperationImpl(_, this, sink))

  def expose[O](source: Source[O]): Producer[O] =
    source match {
      case FromProducerSource(i: InternalProducer[O]) ⇒ i
    }

  abstract class InternalProducerImpl[O](sourceConstructor: Downstream[O] ⇒ SyncSource) extends Producer[O] {
    def requestMore(elements: Int): Unit = Effect.run(internalSource.handleRequestMore(elements))
    val fanOut = createFanOut[O](requestMore)
    val internalSource = sourceConstructor(fanOut.downstream)

    def getPublisher: Publisher[O] = fanOut
  }

  override def internalProducer[O](sourceConstructor: Downstream[O] ⇒ SyncSource): Producer[O] =
    new InternalProducerImpl[O](sourceConstructor) with InternalProducer[O] {
      override def createSource(downstream: Downstream[O]): SyncSource = {
        object InternalSourceConnector extends Subscriber[O] with SyncSource {
          var subscription: Subscription = _
          var source: Upstream = _
          def onSubscribe(subscription: Subscription): Unit = {
            this.subscription = subscription
            this.source = BasicEffects.forSubscription(subscription)
          }
          // called from fanOut to this subscriber
          def onNext(element: O): Unit = Effect.run(downstream.next(element))
          def onComplete(): Unit = Effect.run(downstream.complete)
          def onError(cause: Throwable): Unit = Effect.run(downstream.error(cause))

          // called from internal subscriber
          // TODO: this may call moreRequested which will then be re-scheduled to run
          //       in the right context (the same we are already in internally). Then it
          //       will eventually call `requestFromUpstream` which calls the above
          //       `InternalProducerImpl.requestMore` and run further upstream processing.
          //       we could get rid of this extra scheduling round-trip if
          //       we can capture calls to moreRequested / unregisterSubscription and
          //       convert it into effects to be run instantly afterwards
          def handleRequestMore(n: Int): Effect = source.requestMore(n)
          // TODO: same as for handleRequestMore
          def handleCancel(): Effect = source.cancel
        }
        fanOut.subscribe(InternalSourceConnector)
        InternalSourceConnector
      }
    }
}

case class ConnectInternalSourceSink[O](sourceConstructor: Downstream[O] ⇒ SyncSource, sinkConstructor: Upstream ⇒ SyncSink[O]) extends SingleStep {
  override def runOne(): Effect = {
    object LazyUpstream extends Upstream {
      var source: SyncSource = _
      val requestMore: Int ⇒ Effect = n ⇒ Effect.step(source.handleRequestMore(n), s"RequestMoreFromInternalSource($source)")
      val cancel: Effect = Effect.step(source.handleCancel(), s"Cancel internal source")
    }
    val sink = sinkConstructor(LazyUpstream)
    val downstream = BasicEffects.forSink(sink)
    val source = sourceConstructor(downstream)
    LazyUpstream.source = source
    sink.start() ~ source.start()
  }
}
