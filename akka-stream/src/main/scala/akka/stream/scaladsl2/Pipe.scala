/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.impl2.Ast.AstNode

import scala.annotation.unchecked.uncheckedVariance
import scala.language.{ existentials, higherKinds }

private[stream] object Pipe {
  private val emptyInstance = Pipe[Any, Any](ops = Nil)
  def empty[T]: Pipe[T, T] = emptyInstance.asInstanceOf[Pipe[T, T]]
}

/**
 * Flow with one open input and one open output.
 */
private[stream] final case class Pipe[-In, +Out](ops: List[AstNode]) extends Flow[In, Out] {
  override type Repr[+O] = Pipe[In @uncheckedVariance, O]

  override private[scaladsl2] def andThen[U](op: AstNode): Repr[U] = this.copy(ops = op :: ops)

  private[stream] def withDrain(out: Drain[Out]): SinkPipe[In] = SinkPipe(out, ops)

  private[stream] def withTap(in: Tap[In]): SourcePipe[Out] = SourcePipe(in, ops)

  override def connect[T](flow: Flow[Out, T]): Flow[In, T] = flow match {
    case p: Pipe[T, In]              ⇒ Pipe(p.ops ++: ops)
    case gf: GraphFlow[Out, _, _, T] ⇒ gf.prepend(this)
    case x                           ⇒ FlowGraphInternal.throwUnsupportedValue(x)
  }

  override def connect(sink: Sink[Out]): Sink[In] = sink match {
    case d: Drain[Out]         ⇒ this.withDrain(d)
    case sp: SinkPipe[Out]     ⇒ sp.prependPipe(this)
    case gs: GraphSink[Out, _] ⇒ gs.prepend(this)
    case x                     ⇒ FlowGraphInternal.throwUnsupportedValue(x)
  }

  private[stream] def appendPipe[T](pipe: Pipe[Out, T]): Pipe[In, T] = Pipe(pipe.ops ++: ops)
}

/**
 *  Pipe with open input and attached output. Can be used as a `Subscriber`.
 */
private[stream] final case class SinkPipe[-In](output: Drain[_], ops: List[AstNode]) extends Sink[In] {

  private[stream] def withTap(in: Tap[In]): RunnablePipe = RunnablePipe(in, output, ops)

  private[stream] def prependPipe[T](pipe: Pipe[T, In]): SinkPipe[T] = SinkPipe(output, ops ::: pipe.ops)
  override def runWith(tap: SimpleTap[In])(implicit materializer: FlowMaterializer): Unit =
    tap.connect(this).run()

}

/**
 * Pipe with open output and attached input. Can be used as a `Publisher`.
 */
private[stream] final case class SourcePipe[+Out](input: Tap[_], ops: List[AstNode]) extends Source[Out] {
  override type Repr[+O] = SourcePipe[O]

  override private[scaladsl2] def andThen[U](op: AstNode): Repr[U] = SourcePipe(input, op :: ops)

  private[stream] def withDrain(out: Drain[Out]): RunnablePipe = RunnablePipe(input, out, ops)

  private[stream] def appendPipe[T](pipe: Pipe[Out, T]): SourcePipe[T] = SourcePipe(input, pipe.ops ++: ops)

  override def connect[T](flow: Flow[Out, T]): Source[T] = flow match {
    case p: Pipe[Out, T]            ⇒ appendPipe(p)
    case g: GraphFlow[Out, _, _, T] ⇒ g.prepend(this)
    case x                          ⇒ FlowGraphInternal.throwUnsupportedValue(x)
  }

  override def connect(sink: Sink[Out]): RunnableFlow = sink match {
    case sp: SinkPipe[Out]    ⇒ RunnablePipe(input, sp.output, sp.ops ++: ops)
    case d: Drain[Out]        ⇒ this.withDrain(d)
    case g: GraphSink[Out, _] ⇒ g.prepend(this)
    case x                    ⇒ FlowGraphInternal.throwUnsupportedValue(x)
  }
}

/**
 * Pipe with attached input and output, can be executed.
 */
private[scaladsl2] final case class RunnablePipe(input: Tap[_], output: Drain[_], ops: List[AstNode]) extends RunnableFlow {
  def run()(implicit materializer: FlowMaterializer): MaterializedMap =
    materializer.materialize(input, output, ops)
}

/**
 * Returned by [[RunnablePipe#run]] and can be used as parameter to retrieve the materialized
 * `Tap` input or `Drain` output.
 */
private[stream] class MaterializedPipe(tapKey: AnyRef, matTap: Any, drainKey: AnyRef, matDrain: Any) extends MaterializedMap {
  override def materializedTap(key: TapWithKey[_]): key.MaterializedType =
    if (key == tapKey) matTap.asInstanceOf[key.MaterializedType]
    else throw new IllegalArgumentException(s"Tap key [$key] doesn't match the tap [$tapKey] of this flow")

  override def materializedDrain(key: DrainWithKey[_]): key.MaterializedType =
    if (key == drainKey) matDrain.asInstanceOf[key.MaterializedType]
    else throw new IllegalArgumentException(s"Drain key [$key] doesn't match the drain [$drainKey] of this flow")
}
