package akka.streams
package ops

import akka.streams.Operation.ExposeProducer

object Implementation {
  def apply[I, O](operation: Operation[I, O]): OpInstance[I, O] = operation match {
    case Operation.AndThen(first, second)   ⇒ AndThenImpl(apply(first), apply(second))
    case id: Operation.Identity[_]          ⇒ IdentityImpl
    case f: Operation.Map[_, _]             ⇒ MapImpl(f)
    //case f: Operation.Fold[_, _]          ⇒ FoldImpl(f)
    case span: Operation.Span[_]            ⇒ SpanImpl(span).asInstanceOf[OpInstance[I, O]]
    case f: Operation.Flatten[_]            ⇒ FlattenImpl(f).asInstanceOf[OpInstance[I, O]]
    //case f: Operation.Filter[_]           ⇒ FilterImpl(f)
    case f: Operation.FoldUntil[_, _, _]    ⇒ FoldUntilImpl(f)
    case p: Operation.FromIterableSource[_] ⇒ ProduceImpl(p).asInstanceOf[OpInstance[I, O]]
    case e: ExposeProducer[_]               ⇒ ExposeProducerImpl().asInstanceOf[OpInstance[I, O]]
  }
}
