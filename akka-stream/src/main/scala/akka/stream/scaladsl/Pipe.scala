/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.stream.impl.Ast
import akka.stream.impl.Ast.AstNode
import org.reactivestreams.Processor
import scala.annotation.unchecked.uncheckedVariance
import scala.collection.immutable
import scala.language.{ existentials, higherKinds }
import akka.stream.FlowMaterializer

private[akka] object Pipe {
  private val emptyInstance = Pipe[Any, Any](ops = Nil, keys = Nil)
  def empty[T]: Pipe[T, T] = emptyInstance.asInstanceOf[Pipe[T, T]]

  // FIXME #16376 should probably be replaced with an ActorFlowProcessor similar to ActorFlowSource/Sink
  private[stream] def apply[In, Out](p: () ⇒ Processor[In, Out]): Pipe[In, Out] =
    Pipe(List(Ast.DirectProcessor(() ⇒ p().asInstanceOf[Processor[Any, Any]])), Nil)

  // FIXME #16376 should probably be replaced with an ActorFlowProcessor similar to ActorFlowSource/Sink
  private[stream] def apply[In, Out](key: Key)(p: () ⇒ (Processor[In, Out], Any)): Pipe[In, Out] =
    Pipe(List(Ast.DirectProcessorWithKey(() ⇒ p().asInstanceOf[(Processor[Any, Any], Any)], key)), Nil)

  private[stream] def apply[In, Out](source: SourcePipe[_]): Pipe[In, Out] =
    Pipe(source.ops, source.keys)

  private[stream] def apply[In, Out](sink: SinkPipe[_]): Pipe[In, Out] =
    Pipe(sink.ops, sink.keys)
}

/**
 * Flow with one open input and one open output.
 */
private[akka] final case class Pipe[-In, +Out](ops: List[AstNode], keys: List[Key], attributes: OperationAttributes = OperationAttributes.none) extends Flow[In, Out] {
  override type Repr[+O] = Pipe[In @uncheckedVariance, O]

  override private[scaladsl] def andThen[U](op: AstNode): Repr[U] = Pipe(ops = attributes.transform(op) :: ops, keys, attributes) // FIXME raw addition of AstNodes

  def withAttributes(attr: OperationAttributes): Repr[Out] = this.copy(attributes = attr)

  private[stream] def withSink(out: Sink[Out]): SinkPipe[In] = SinkPipe(out, ops, keys)

  private[stream] def withSource(in: Source[In]): SourcePipe[Out] = SourcePipe(in, ops, keys)

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

  override def join(flow: Flow[Out, In]): RunnableFlow = flow match {
    case p: Pipe[Out, In]             ⇒ GraphFlow(this).join(p)
    case gf: GraphFlow[Out, _, _, In] ⇒ gf.join(this)
    case x                            ⇒ FlowGraphInternal.throwUnsupportedValue(x)
  }

  override def withKey(key: Key): Pipe[In, Out] = Pipe(ops, keys :+ key)

  private[stream] def appendPipe[T](pipe: Pipe[Out, T]): Pipe[In, T] = Pipe(pipe.ops ::: ops, keys ::: pipe.keys) // FIXME raw addition of AstNodes
}

/**
 *  Pipe with open input and attached output. Can be used as a `Subscriber`.
 */
private[stream] final case class SinkPipe[-In](output: Sink[_], ops: List[AstNode], keys: List[Key]) extends Sink[In] {

  private[stream] def withSource(in: Source[In]): RunnablePipe = RunnablePipe(in, output, ops, keys)

  private[stream] def prependPipe[T](pipe: Pipe[T, In]): SinkPipe[T] = SinkPipe(output, ops ::: pipe.ops, keys ::: pipe.keys) // FIXME raw addition of AstNodes

}

/**
 * Pipe with open output and attached input. Can be used as a `Publisher`.
 */
private[stream] final case class SourcePipe[+Out](input: Source[_], ops: List[AstNode], keys: List[Key], attributes: OperationAttributes = OperationAttributes.none) extends Source[Out] {
  override type Repr[+O] = SourcePipe[O]

  override private[scaladsl] def andThen[U](op: AstNode): Repr[U] = SourcePipe(input, attributes.transform(op) :: ops, keys, attributes) // FIXME raw addition of AstNodes

  def withAttributes(attr: OperationAttributes): Repr[Out] = this.copy(attributes = attr)

  private[stream] def withSink(out: Sink[Out]): RunnablePipe = RunnablePipe(input, out, ops, keys)

  private[stream] def appendPipe[T](pipe: Pipe[Out, T]): SourcePipe[T] = SourcePipe(input, pipe.ops ::: ops, keys ::: pipe.keys) // FIXME raw addition of AstNodes

  override def via[T](flow: Flow[Out, T]): Source[T] = flow match {
    case p: Pipe[Out, T]            ⇒ this.appendPipe(p)
    case g: GraphFlow[Out, _, _, T] ⇒ g.prepend(this)
    case x                          ⇒ FlowGraphInternal.throwUnsupportedValue(x)
  }

  override def to(sink: Sink[Out]): RunnableFlow = sink match {
    case sp: SinkPipe[Out]    ⇒ RunnablePipe(input, sp.output, sp.ops ::: ops, keys ::: sp.keys) // FIXME raw addition of AstNodes
    case g: GraphSink[Out, _] ⇒ g.prepend(this)
    case d: Sink[Out]         ⇒ this.withSink(d)
  }

  override def withKey(key: Key): SourcePipe[Out] = SourcePipe(input, ops, keys :+ key)
}

/**
 * Pipe with attached input and output, can be executed.
 */
private[stream] final case class RunnablePipe(input: Source[_], output: Sink[_], ops: List[AstNode], keys: List[Key]) extends RunnableFlow {
  def run()(implicit materializer: FlowMaterializer): MaterializedMap =
    materializer.materialize(input, output, ops, keys)
}
