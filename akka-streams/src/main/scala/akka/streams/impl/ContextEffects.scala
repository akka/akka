package akka.streams.impl

import akka.streams.Operation.{ FromProducerSource, Sink, Source }
import rx.async.api.Producer

/**
 * Additional Effects supplied by the context to allow additional executing additional effects
 * of link internal sources and sinks.
 */
trait ContextEffects {
  /**
   * Subscribe to the given source and once subscribed call the `onSubscribe` callback with upstream
   * effects. onSubscribe
   */
  def subscribeTo[O](source: Source[O])(onSubscribe: Upstream ⇒ (SyncSink[O], Effect)): Effect
  def subscribeFrom[O](sink: Sink[O])(onSubscribe: Downstream[O] ⇒ (SyncSource, Effect)): Effect

  def expose[O](source: Source[O]): Producer[O]

  def runInContext(body: ⇒ Effect): Unit
}

/** Tries to implement ContextEffect methods generally */
abstract class AbstractContextEffects extends ContextEffects {
  def subscribeTo[O](source: Source[O])(onSubscribeCallback: Upstream ⇒ (SyncSink[O], Effect)): Effect = source match {
    case InternalSource(handler) ⇒ ContextEffects.subscribeToInternalSource(handler, onSubscribeCallback)
    // TODO: make sure only to match on the right types
    case x ⇒
      // TODO: this is very interesting and seems to be generally related to the compose implementation
      // can we get both together?
      def upstreamCons(downstream: Downstream[O]): (SyncSource, Effect) = {
        val source = OperationImpl.apply(downstream: Downstream[O], this, x)
        (source, source.start())
      }
      ContextEffects.subscribeToInternalSource(upstreamCons, onSubscribeCallback)
  }
}

object ContextEffects {
  def subscribeToInternalSource[O](handler: Downstream[O] ⇒ (SyncSource, Effect), onSubscribeCallback: Upstream ⇒ (SyncSink[O], Effect)): Effect =
    Effect.step({
      object LazyUpstream extends Upstream {
        var source: SyncSource = _
        val requestMore: Int ⇒ Effect = n ⇒ Effect.step(source.handleRequestMore(n), s"RequestMoreFromInternalSource($source)")
        val cancel: Effect = Effect.step(source.handleCancel(), s"Cancel internal source")
      }
      val (sink, sinkEffect) = onSubscribeCallback(LazyUpstream)
      val downstream = BasicEffects.forSink(sink)
      val (source, sourceEffect) = handler(downstream)
      LazyUpstream.source = source
      sinkEffect ~ sourceEffect
    }, s"<Connect internal source (${handler.getClass}) and sink (${onSubscribeCallback.getClass}})>")
}
