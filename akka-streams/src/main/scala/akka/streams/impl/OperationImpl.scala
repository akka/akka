package akka.streams
package impl

import Operation._
import ops._
import rx.async.api.Processor

object OperationImpl {
  def apply[A](ctx: ContextEffects, p: Pipeline[A]): SyncRunnable = {
    ComposeImpl.pipeline(apply[A](_: Downstream[A], ctx, p.source), apply[A](_: Upstream, ctx, p.sink))
  }
  def apply[I](upstream: Upstream, ctx: ContextEffects, sink: Sink[I]): SyncSink[I] =
    sink match {
      case Foreach(f)          ⇒ ForeachImpl(upstream, f)
      case FromConsumerSink(s) ⇒ FromConsumerSinkImpl(upstream, ctx, s)
    }
  def apply[O](downstream: Downstream[O], ctx: ContextEffects, source: Source[O]): SyncSource =
    source match {
      case m: MappedSource[i, O] ⇒
        ComposeImpl.source[i](apply(_: Downstream[i], ctx, m.source), up ⇒ apply(up, downstream, ctx, m.operation))
      case FromIterableSource(s)    ⇒ FromIterableSourceImpl(downstream, ctx, s)
      case f: FromProducerSource[_] ⇒ FromProducerSourceImpl(downstream, ctx, f)
    }

  def apply[I, O](upstream: Upstream, downstream: Downstream[O], ctx: ContextEffects, op: Operation[I, O]): SyncOperation[I] = op match {
    case a: Compose[I, i2, O] ⇒
      ComposeImpl.operation(
        apply(upstream, _: Downstream[i2], ctx, a.f),
        apply(_, downstream, ctx, a.g))
    case Map(f)                                     ⇒ MapImpl(upstream, downstream, f)
    case i: Identity[O]                             ⇒ IdentityImpl(upstream, downstream).asInstanceOf[SyncOperation[I]]
    case Flatten()                                  ⇒ FlattenImpl(upstream, downstream, ctx).asInstanceOf[SyncOperation[I]]
    case d: Fold[I, O]                              ⇒ FoldImpl(upstream, downstream, d)
    case u: UserOperation[I, O, _]                  ⇒ new UserOperationImpl(upstream, downstream, u)
    case FromProcessorOperation(p: Processor[I, O]) ⇒ new FromProcessorOperationImpl(upstream, downstream, ctx, p)
  }
}
