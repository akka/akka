package akka.streams.impl

import akka.streams.Operation.{ FromProducerSource, Sink, Source }
import rx.async.api.Producer
import scala.concurrent.ExecutionContext
import rx.async.spi
import spi.{ Subscriber, Publisher }
import akka.streams.AbstractProducer
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicBoolean

trait FanOut[I] extends Publisher[I] {
  def downstream: Downstream[I]
  def runEffect(effect: Effect): Unit
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

  def internalProducer[O](sourceConstructor: Downstream[O] ⇒ SyncSource,
                          shutdown: Effect = Continue,
                          initialFanOutBufferSize: Int = defaultInitialBufferSize,
                          maxFanOutBufferSize: Int = defaultMaxBufferSize): Producer[O]

  def defaultInitialBufferSize: Int
  def defaultMaxBufferSize: Int
  implicit def executionContext: ExecutionContext
  def runInContext(body: ⇒ Effect): Unit = runStrictInContext(Effect.step(body, s"deferred ${(body _).getClass.getSimpleName}"))
  def runStrictInContext(effect: Effect): Unit
}

/** General implementations of most ContextEffect methods */
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

  def internalProducer[O](sourceConstructor: Downstream[O] ⇒ SyncSource, shutdownEffect: Effect, initialFanOutBufferSize: Int, maxFanOutBufferSize: Int): Producer[O] =
    new AbstractProducer[O](initialFanOutBufferSize, maxFanOutBufferSize) with InternalProducer[O] {
      protected def requestFromUpstream(elements: Int): Unit = runEffect(source.handleRequestMore(elements))
      protected def cancelUpstream(): Unit = runEffect(source.handleCancel())
      protected def shutdown(): Unit = runEffect(shutdownEffect)

      var effects: Effect = null
      def runEffect(effect: Effect): Unit = effects ~= effect
      @tailrec def collectingEffects(body: ⇒ Unit): Effect =
        if (locked.compareAndSet(false, true)) {
          try {
            assert(effects eq null)
            effects = Continue
            body
            val result = effects
            effects = null
            result
          } finally locked.set(false)
        } else collectingEffects(body)

      private[this] val locked = new AtomicBoolean // TODO: replace with AtomicFieldUpdater / sun.misc.Unsafe

      override def subscribe(subscriber: Subscriber[O]): Unit =
        collectAndRun(super.subscribe(subscriber))
      override protected def moreRequested(subscription: Subscription, elements: Int): Unit =
        collectAndRun(super.moreRequested(subscription, elements))
      override protected def unregisterSubscription(subscription: Subscription): Unit =
        collectAndRun(super.unregisterSubscription(subscription))

      def subscribeInternal(subscriber: Subscriber[O]): Effect =
        collectingEffects(super.subscribe(subscriber))

      def collectAndRun(body: ⇒ Unit): Unit = runStrictInContext(collectingEffects(body))
      case class Collecting(name: String)(body: ⇒ Unit) extends SingleStep {
        def runOne(): Effect = collectingEffects(body)
      }
      val downstream: Downstream[O] = new Downstream[O] {
        val next = (o: O) ⇒ Collecting("nextFanOut")(pushToDownstream(o))
        val complete: Effect = Collecting("completeFanOut")(completeDownstream())
        val error: Throwable ⇒ Effect = cause ⇒ Collecting("errorFanOut")(abortDownstream(cause))
      }
      val source = sourceConstructor(downstream)
      Effect.run(source.start())

      def createSource(downstream: Downstream[O]): SyncSource = {
        object InternalSourceConnector extends Subscriber[O] with SyncSource {
          var subscription: spi.Subscription = _
          var source: Upstream = _

          def onSubscribe(subscription: spi.Subscription): Unit = {
            this.subscription = subscription
            this.source = BasicEffects.forSubscription(subscription)
          }
          // called from fanOut to this subscriber
          def onNext(element: O): Unit = runEffect(downstream.next(element))
          def onComplete(): Unit = runEffect(downstream.complete)
          def onError(cause: Throwable): Unit = runEffect(downstream.error(cause))

          // called from internal subscriber
          // TODO: this may call moreRequested which will then be re-scheduled to run
          //       in the right context (the same we are already in internally). Then it
          //       will eventually call `requestFromUpstream` which calls the above
          //       `InternalProducerImpl.requestMore` and run further upstream processing.
          //       we could get rid of this extra scheduling round-trip if
          //       we can capture calls to moreRequested / unregisterSubscription and
          //       convert it into effects to be run instantly afterwards
          // TODO: check! this may already have been fixed
          def handleRequestMore(n: Int): Effect = source.requestMore(n)
          // TODO: same as for handleRequestMore
          override def handleCancel(): Effect = source.cancel

          override def start(): Effect = subscribeInternal(InternalSourceConnector)
        }
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
