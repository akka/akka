package akka.streams
package ops

object Implementation {
  def apply[I, O](operation: Operation[I, O]): OpInstance[I, O] = operation match {
    case andThen: AndThen[_, _, _] ⇒ AndThenImpl(andThen)
    case id: Identity[_]           ⇒ IdentityImpl(id)
    case f: Map[_, _]              ⇒ MapImpl(f)
    case f: Fold[_, _]             ⇒ FoldImpl(f)
    case span: Span[_]             ⇒ SpanImpl(span)
    case f: Flatten[_]             ⇒ FlattenImpl(f)
    case f: Filter[_]              ⇒ FilterImpl(f)
    case f: FoldUntil[_, _, _]     ⇒ FoldUntilImpl(f)
    case p: Produce[_]             ⇒ ProduceImpl(p)
  }
}
