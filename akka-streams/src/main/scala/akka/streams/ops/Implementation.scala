package akka.streams
package ops

import akka.streams.Operation.{ FromIterableSource, Source, MappedSource, ExposeProducer }

object Implementation {
  case class ImplementationSettings(traceTrampolining: Boolean = false)
  object ImplementationSettings {
    implicit val default = ImplementationSettings()
  }

  def apply[I, O](operation: Operation[I, O])(implicit settings: ImplementationSettings): OpInstance[I, O] = operation match {
    case Operation.AndThen(first, second) ⇒ AndThenImpl(apply(first), apply(second))
    case id: Operation.Identity[_]        ⇒ IdentityImpl
    case f: Operation.Map[_, _]           ⇒ MapImpl(f)
    //case f: Operation.Fold[_, _]          ⇒ FoldImpl(f)
    case span: Operation.Span[_]          ⇒ SpanImpl(span).asInstanceOf[OpInstance[I, O]]
    case f: Operation.Flatten[_]          ⇒ FlattenImpl(f).asInstanceOf[OpInstance[I, O]]
    //case f: Operation.Filter[_]           ⇒ FilterImpl(f)
    case f: Operation.FoldUntil[_, _, _]  ⇒ FoldUntilImpl(f)
    case e: ExposeProducer[_]             ⇒ ExposeProducerImpl().asInstanceOf[OpInstance[I, O]]
  }
  def apply[O](mappedSource: Source[O])(implicit settings: ImplementationSettings): OpInstance[Nothing, O] =
    mappedSource match {
      case m: MappedSource[i, O]  ⇒ AndThenImpl[Nothing, i, O](apply(m.source), apply(m.operation))
      case FromIterableSource(it) ⇒ IterableSourceImpl(it)
    }
}
