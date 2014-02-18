package akka.streams.impl

import akka.streams.Operation
import Operation._
import ops._
import rx.async.api.Producer

object OperationImpl {
  def apply[A](ctx: ContextEffects, p: Pipeline[A]): SyncRunnable = {
    ComposeImpl.pipeline(apply[A](_: Downstream[A], ctx, p.source), apply[A](_: Upstream, ctx, p.sink))
  }
  def apply[I](upstream: Upstream, ctx: ContextEffects, sink: Sink[I]): SyncSink[I] =
    sink match {
      case Foreach(f)          ⇒ new ForeachImpl(upstream, f)
      case FromConsumerSink(s) ⇒ new FromConsumerSinkImpl(upstream, ctx, s)
    }
  def apply[O](downstream: Downstream[O], ctx: ContextEffects, source: Source[O]): SyncSource = {
    def delegate(): SyncSource = apply(downstream, ctx, delegateSourceImpl(source))

    source match {
      case m: MappedSource[i, O] ⇒
        ComposeImpl.source[i](apply(_: Downstream[i], ctx, m.source), up ⇒ apply(up, downstream, ctx, m.operation))
      case FromIterableSource(s)    ⇒ new FromIterableSourceImpl(downstream, ctx, s)
      case FromProducerSource(p)    ⇒ new FromProducerSourceImpl(downstream, ctx, p)
      case SingletonSource(element) ⇒ new SingletonSourceImpl(downstream, element)
      case EmptySource              ⇒ new EmptySourceImpl(downstream)
      case c: ConcatSources[_]      ⇒ delegate()
    }
  }

  /** source implementations that can be implemented in terms of another implementation */
  def delegateSourceImpl[O](source: Source[O]): Source[O] = source match {
    case ConcatSources(s1, s2) ⇒ Seq(s1, s2).toSource.flatten
    case x                     ⇒ x
  }

  def apply[I, O](upstream: Upstream, downstream: Downstream[O], ctx: ContextEffects, op: Operation[I, O]): SyncOperation[I] = {
    def delegate(): SyncOperation[I] = apply(upstream, downstream, ctx, delegateOperationImpl(op))
    op match {
      case a: Compose[I, i2, O] ⇒
        ComposeImpl.operation(
          apply(upstream, _: Downstream[i2], ctx, a.f),
          apply(_, downstream, ctx, a.g))
      case Map(f)              ⇒ new MapImpl(upstream, downstream, f)
      case Flatten()           ⇒ new FlattenImpl(upstream, downstream, ctx).asInstanceOf[SyncOperation[I]]
      case d: Fold[I, O]       ⇒ new FoldImpl(upstream, downstream, d)
      case u: Process[I, O, _] ⇒ new ProcessImpl(upstream, downstream, u)
      case s: Span[I]          ⇒ new SpanImpl(upstream, downstream.asInstanceOf[Downstream[Source[I]]], s)
      case ExposeProducer()    ⇒ new ExposeProducerImpl(upstream, downstream.asInstanceOf[Downstream[Producer[I]]], ctx).asInstanceOf[SyncOperation[I]]
      case i: Identity[O]      ⇒ delegate()
      case f: FlatMap[_, _]    ⇒ delegate()
    }
  }

  /** operation implementations that can be implemented in terms of another implementation */
  def delegateOperationImpl[I, O](op: Operation[I, O]): Operation[I, O] = op match {
    case FlatMap(f) ⇒ Map(f).flatten
    case Identity() ⇒ Map(i ⇒ i.asInstanceOf[O])
    case x          ⇒ x
  }
}
