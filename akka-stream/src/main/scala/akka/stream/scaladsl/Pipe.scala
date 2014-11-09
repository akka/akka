/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Ast.AstNode
import scala.annotation.unchecked.uncheckedVariance
import scala.language.{ existentials, higherKinds }
import akka.stream.FlowMaterializer

private[stream] object Pipe {
  private val emptyInstance = Pipe[Any, Any](ops = Nil)
  def empty[T]: Pipe[T, T] = emptyInstance.asInstanceOf[Pipe[T, T]]
}

/**
 * Flow with one open input and one open output.
 */
private[stream] final case class Pipe[-In, +Out](ops: List[AstNode]) extends Flow[In, Out] {
  override type Repr[+O] = Pipe[In @uncheckedVariance, O]

  override private[scaladsl] def andThen[U](op: AstNode): Repr[U] = this.copy(ops = op :: ops) // FIXME raw addition of AstNodes

  private[stream] def withSink(out: Sink[Out]): SinkPipe[In] = SinkPipe(out, ops)

  private[stream] def withSource(in: Source[In]): SourcePipe[Out] = SourcePipe(in, ops)

  override def via[T](flow: Flow[Out, T]): Flow[In, T] = flow match {
    case p: Pipe[Out, T]             ⇒ this.appendPipe(p)
    case gf: GraphFlow[Out, _, _, T] ⇒ gf.prepend(this)
    case x                           ⇒ FlowGraphInternal.throwUnsupportedValue(x)
  }

  override def to(sink: Sink[Out]): Sink[In] = sink match {
    case sp: SinkPipe[Out]     ⇒ sp.prependPipe(this)
    case gs: GraphSink[Out, _] ⇒ gs.prepend(this)
    case d: Sink[Out]          ⇒ this.withSink(d)
  }

  private[stream] def appendPipe[T](pipe: Pipe[Out, T]): Pipe[In, T] = Pipe(pipe.ops ++: ops) // FIXME raw addition of AstNodes
}

/**
 *  Pipe with open input and attached output. Can be used as a `Subscriber`.
 */
private[stream] final case class SinkPipe[-In](output: Sink[_], ops: List[AstNode]) extends Sink[In] {

  private[stream] def withSource(in: Source[In]): RunnablePipe = RunnablePipe(in, output, ops)

  private[stream] def prependPipe[T](pipe: Pipe[T, In]): SinkPipe[T] = SinkPipe(output, ops ::: pipe.ops) // FIXME raw addition of AstNodes

}

/**
 * Pipe with open output and attached input. Can be used as a `Publisher`.
 */
private[stream] final case class SourcePipe[+Out](input: Source[_], ops: List[AstNode]) extends Source[Out] {
  override type Repr[+O] = SourcePipe[O]

  override private[scaladsl] def andThen[U](op: AstNode): Repr[U] = SourcePipe(input, op :: ops) // FIXME raw addition of AstNodes

  private[stream] def withSink(out: Sink[Out]): RunnablePipe = RunnablePipe(input, out, ops)

  private[stream] def appendPipe[T](pipe: Pipe[Out, T]): SourcePipe[T] = SourcePipe(input, pipe.ops ++: ops) // FIXME raw addition of AstNodes

  override def via[T](flow: Flow[Out, T]): Source[T] = flow match {
    case p: Pipe[Out, T]            ⇒ appendPipe(p)
    case g: GraphFlow[Out, _, _, T] ⇒ g.prepend(this)
    case x                          ⇒ FlowGraphInternal.throwUnsupportedValue(x)
  }

  override def to(sink: Sink[Out]): RunnableFlow = sink match {
    case sp: SinkPipe[Out]    ⇒ RunnablePipe(input, sp.output, sp.ops ++: ops) // FIXME raw addition of AstNodes
    case g: GraphSink[Out, _] ⇒ g.prepend(this)
    case d: Sink[Out]         ⇒ this.withSink(d)
  }
}

/**
 * Pipe with attached input and output, can be executed.
 */
private[stream] final case class RunnablePipe(input: Source[_], output: Sink[_], ops: List[AstNode]) extends RunnableFlow {
  def run()(implicit materializer: FlowMaterializer): MaterializedMap =
    materializer.materialize(input, output, ops)
}

/**
 * Returned by [[RunnablePipe#run]] and can be used as parameter to retrieve the materialized
 * `Source` input or `Sink` output.
 */
private[stream] class MaterializedPipe(sourceKey: AnyRef, matSource: Any, sinkKey: AnyRef, matSink: Any) extends MaterializedMap {
  override def get(key: Source[_]): key.MaterializedType =
    key match {
      case _: KeyedSource[_] ⇒
        if (key == sourceKey) matSource.asInstanceOf[key.MaterializedType]
        else throw new IllegalArgumentException(s"Source key [$key] doesn't match the source [$sourceKey] of this flow")
      case _ ⇒ ().asInstanceOf[key.MaterializedType]
    }

  override def get(key: Sink[_]): key.MaterializedType =
    key match {
      case _: KeyedSink[_] ⇒
        if (key == sinkKey) matSink.asInstanceOf[key.MaterializedType]
        else throw new IllegalArgumentException(s"Sink key [$key] doesn't match the sink [$sinkKey] of this flow")
      case _ ⇒ ().asInstanceOf[key.MaterializedType]
    }
}
