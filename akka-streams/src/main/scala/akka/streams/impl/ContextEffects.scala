package akka.streams.impl

import akka.streams.Operation.{ FromProducerSource, Sink, Source }
import asyncrx.api.Producer
import scala.concurrent.ExecutionContext
import asyncrx.spi
import spi.Subscriber
import akka.streams.AbstractProducer
import scala.annotation.tailrec
import java.util.concurrent.atomic.AtomicBoolean

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
  /** Directly runs an effect, must be executed in context */
  def runEffectHere(effect: Effect): Unit
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
      // TODO: what's with all the other cases? Maybe this (untested):
      // case _ ⇒ internalProducer(OperationImpl(_, this, source))
    }

  /**
   * This is the central implementation of fanOut for the implementation.
   */
  def internalProducer[O](sourceConstructor: Downstream[O] ⇒ SyncSource, shutdownEffect: Effect, initialFanOutBufferSize: Int, maxFanOutBufferSize: Int): Producer[O] =
    new AbstractProducer[O](initialFanOutBufferSize, maxFanOutBufferSize) with InternalProducer[O] {
      protected def requestFromUpstream(elements: Int): Unit = runEffect(sourceEffects.requestMore(elements))
      protected def cancelUpstream(): Unit = runEffect(sourceEffects.cancel)
      protected def shutdown(): Unit = runEffect(shutdownEffect)

      var effects: Effect = null
      def runEffect(effect: Effect): Unit = effects ~= effect

      type F[T1, T2] = (T1, T2) ⇒ Unit
      /**
       *  AbstractProducer logic is run on the thread of the calling party however any further effects
       *  going into the processing logic/upstream is going to be scheduled and run in the main context.
       *  Needless closure generation is prevented by allowing two parameters to be passed to the body to be run.
       */
      @tailrec def collectingEffects[T1, T2](body: F[T1, T2], t1: T1, t2: T2): Effect =
        if (locked.compareAndSet(false, true))
          try {
            assert(effects eq null)
            effects = Continue
            body(t1, t2)
            val result = effects
            effects = null
            result
          } finally locked.set(false)
        else collectingEffects(body, t1, t2)

      private[this] val locked = new AtomicBoolean

      // premature optimization? Try preventing to create closures all the time by preallocating the function stubs here.
      val subscribe: F[Subscriber[O], Null] = (s, _) ⇒ super.subscribe(s)
      val moreRequested: F[Subscription, Int] = super.moreRequested
      val unregisterSubscription: F[Subscription, Null] = (s, _) ⇒ super.unregisterSubscription(s)

      // These methods are called from the AbstractProducer in the context of subscribers / subscriptions
      // The fanOut logic is handled inside the calling context but any further processing is scheduled
      // to run on our own context.
      override def subscribe(subscriber: Subscriber[O]): Unit =
        runHereAndResultsInCtx(subscribe, subscriber, null)
      override protected def moreRequested(subscription: Subscription, elements: Int): Unit =
        runHereAndResultsInCtx(moreRequested, subscription, elements)
      override protected def unregisterSubscription(subscription: Subscription): Unit =
        runHereAndResultsInCtx(unregisterSubscription, subscription, null)

      // These methods are internal entry points for the above ones that avoid the additional scheduling.
      // They still need to synchronize running of any AbstractProducer logic.
      def subscribeInternal(subscriber: Subscriber[O]): Effect =
        collectingEffects(subscribe, subscriber, null)
      def requestMoreInternal(subscription: Subscription, elements: Int): Effect =
        collectingEffects(moreRequested, subscription, elements)
      def cancelInternal(subscription: Subscription): Effect =
        collectingEffects(unregisterSubscription, subscription, null)

      def runHereAndResultsInCtx[T1, T2](body: F[T1, T2], t1: T1, t2: T2): Unit = {
        val result = collectingEffects(body, t1, t2)
        runStrictInContext(result)
      }

      case class PushToFanOut(o: O) extends SingleStep with F[O, Null] {
        def apply(o: O, v2: Null): Unit = pushToDownstream(o)
        def runOne(): Effect = collectingEffects(this, o, null)
      }
      case object CompleteFanOut extends SingleStep with F[Null, Null] {
        def apply(v1: Null, v2: Null): Unit = completeDownstream()
        def runOne(): Effect = collectingEffects(this, null, null)
      }
      case class AbortFanOut(cause: Throwable) extends SingleStep with F[Throwable, Null] {
        def apply(cause: Throwable, v2: Null): Unit = abortDownstream(cause)
        def runOne(): Effect = collectingEffects(this, cause, null)
      }
      val downstream: Downstream[O] = new Downstream[O] {
        val next = PushToFanOut
        val complete: Effect = CompleteFanOut
        val error: Throwable ⇒ Effect = AbortFanOut
      }
      val source = sourceConstructor(downstream)
      val sourceEffects = BasicEffects.forSource(source)
      // this requires that this constructor is run in context
      runEffectHere(source.start())

      def createSource(downstream: Downstream[O]): SyncSource = {
        object InternalSourceConnector extends Subscriber[O] with SyncSource {
          var subscription: Subscription = _

          def onSubscribe(subscription: spi.Subscription): Unit = {
            this.subscription = subscription.asInstanceOf[Subscription]
          }
          // called from fanOut to this subscriber
          def onNext(element: O): Unit = runEffect(downstream.next(element))
          def onComplete(): Unit = runEffect(downstream.complete)
          def onError(cause: Throwable): Unit = runEffect(downstream.error(cause))

          def handleRequestMore(n: Int): Effect = requestMoreInternal(subscription, n)
          def handleCancel(): Effect = cancelInternal(subscription)

          override def start(): Effect = subscribeInternal(InternalSourceConnector)
        }
        InternalSourceConnector
      }
    }

}

case class ConnectInternalSourceSink[O](sourceConstructor: Downstream[O] ⇒ SyncSource, sinkConstructor: Upstream ⇒ SyncSink[O]) extends SingleStep {
  override def toString: String = s"ConnectInternalSourceSink(${sourceConstructor.getClass.getSimpleName}}, ${sinkConstructor.getClass.getSimpleName}})"

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
