package akka.streams
package impl

import Operation._
import ops._

object OperationImpl {
  def apply[A](subscribable: ContextEffects, p: Pipeline[A]): SyncRunnable = {
    ComposeImpl.pipeline(apply[A](_: Downstream[A], subscribable, p.source), apply[A](_: Upstream, subscribable, p.sink))
  }
  def apply[I](upstream: Upstream, subscribable: ContextEffects, sink: Sink[I]): SyncSink[I] =
    sink match {
      case Foreach(f)          ⇒ ForeachImpl(upstream, f)
      case FromConsumerSink(s) ⇒ FromConsumerSinkImpl(upstream, subscribable, s)
    }
  def apply[O](downstream: Downstream[O], subscribable: ContextEffects, source: Source[O]): SyncSource =
    source match {
      case m: MappedSource[i, O] ⇒
        ComposeImpl.source[i, O](apply(_: Downstream[i], subscribable, m.source), up ⇒ apply(up, downstream, subscribable, m.operation))
      case FromIterableSource(s)    ⇒ FromIterableSourceImpl(downstream, subscribable, s)
      case f: FromProducerSource[_] ⇒ FromProducerSourceImpl(downstream, subscribable, f)
    }

  def apply[I, O](upstream: Upstream, downstream: Downstream[O], subscribable: ContextEffects, op: Operation[I, O]): SyncOperation[I] = op match {
    case a: Compose[I, i2, O] ⇒
      ComposeImpl.operation(apply(upstream, _: Downstream[i2], subscribable, a.f), apply(_, downstream, subscribable, a.g))
    case Map(f)         ⇒ MapImpl(upstream, downstream, f)
    case i: Identity[O] ⇒ IdentityImpl(upstream, downstream).asInstanceOf[SyncOperation[I]]
    case Flatten()      ⇒ FlattenImpl(upstream, downstream, subscribable).asInstanceOf[SyncOperation[I]]
    case d: Fold[I, O]  ⇒ FoldImpl(upstream, downstream, d)
  }
}
