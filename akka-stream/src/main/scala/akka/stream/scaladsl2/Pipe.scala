/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl2

import akka.stream.impl2.Ast.AstNode
import org.reactivestreams._

import scala.annotation.unchecked.uncheckedVariance
import scala.language.higherKinds
import scala.language.existentials

private[scaladsl2] object Pipe {
  private val emptyInstance = Pipe[Any, Any](ops = Nil)
  def empty[T]: Pipe[T, T] = emptyInstance.asInstanceOf[Pipe[T, T]]

  val OnlyPipesErrorMessage = "Only pipes are supported currently!"
}

/**
 * Flow with one open input and one open output..
 */
private[scaladsl2] final case class Pipe[-In, +Out](ops: List[AstNode]) extends Flow[In, Out] {
  override type Repr[+O] = Pipe[In @uncheckedVariance, O]

  override protected def andThen[U](op: AstNode): Repr[U] = this.copy(ops = op :: ops)

  private[scaladsl2] def withDrain(out: Drain[Out]): SinkPipe[In] = SinkPipe(out, ops)

  private[scaladsl2] def withTap(in: Tap[In]): SourcePipe[Out] = SourcePipe(in, ops)

  override def connect[T](flow: Flow[Out, T]): Flow[In, T] = flow match {
    case p: Pipe[T, In] ⇒ Pipe(p.ops ++: ops)
    case _              ⇒ throw new IllegalArgumentException(Pipe.OnlyPipesErrorMessage)
  }

  override def connect(sink: Sink[Out]): Sink[In] = sink match {
    case sp: SinkPipe[Out] ⇒ sp.prependPipe(this)
    case d: Drain[Out]     ⇒ this.withDrain(d)
    case _                 ⇒ throw new IllegalArgumentException(Pipe.OnlyPipesErrorMessage)
  }

  private[scaladsl2] def appendPipe[T](pipe: Pipe[Out, T]): Pipe[In, T] = Pipe(pipe.ops ++: ops)
}

/**
 *  Pipe with open input and attached output. Can be used as a `Subscriber`.
 */
private[scaladsl2] final case class SinkPipe[-In](output: Drain[_], ops: List[AstNode]) extends Sink[In] {

  private[scaladsl2] def withTap(in: Tap[In]): RunnablePipe = RunnablePipe(in, output, ops)

  private[scaladsl2] def prependPipe[T](pipe: Pipe[T, In]): SinkPipe[T] = SinkPipe(output, ops ::: pipe.ops)

  override def toSubscriber()(implicit materializer: FlowMaterializer): Subscriber[In @uncheckedVariance] = {
    val subIn = SubscriberTap[In]()
    val mf = withTap(subIn).run()
    subIn.subscriber(mf)
  }
}

/**
 * Pipe with open output and attached input. Can be used as a `Publisher`.
 */
private[scaladsl2] final case class SourcePipe[+Out](input: Tap[_], ops: List[AstNode]) extends Source[Out] {
  override type Repr[+O] = SourcePipe[O]

  override protected def andThen[U](op: AstNode): Repr[U] = SourcePipe(input, op :: ops)

  private[scaladsl2] def withDrain(out: Drain[Out]): RunnablePipe = RunnablePipe(input, out, ops)

  private[scaladsl2] def appendPipe[T](pipe: Pipe[Out, T]): SourcePipe[T] = SourcePipe(input, pipe.ops ++: ops)

  override def connect[T](flow: Flow[Out, T]): Source[T] = flow match {
    case p: Pipe[Out, T] ⇒ appendPipe(p)
    case _               ⇒ throw new IllegalArgumentException(Pipe.OnlyPipesErrorMessage)
  }

  override def connect(sink: Sink[Out]): RunnableFlow = sink match {
    case sp: SinkPipe[Out] ⇒ RunnablePipe(input, sp.output, sp.ops ++: ops)
    case d: Drain[Out]     ⇒ this.withDrain(d)
    case _                 ⇒ throw new IllegalArgumentException(Pipe.OnlyPipesErrorMessage)
  }

  override def toPublisher()(implicit materializer: FlowMaterializer): Publisher[Out @uncheckedVariance] = {
    val pubOut = PublisherDrain[Out]
    val mf = withDrain(pubOut).run()
    pubOut.publisher(mf)
  }

  override def toFanoutPublisher(initialBufferSize: Int, maximumBufferSize: Int)(implicit materializer: FlowMaterializer): Publisher[Out @uncheckedVariance] = {
    val pubOut = PublisherDrain.withFanout[Out](initialBufferSize, maximumBufferSize)
    val mf = withDrain(pubOut).run()
    pubOut.publisher(mf)
  }

  override def publishTo(subscriber: Subscriber[Out @uncheckedVariance])(implicit materializer: FlowMaterializer): Unit =
    toPublisher().subscribe(subscriber)

  override def consume()(implicit materializer: FlowMaterializer): Unit =
    withDrain(BlackholeDrain).run()
}

/**
 * Pipe with attached input and output, can be executed.
 */
private[scaladsl2] final case class RunnablePipe(input: Tap[_], output: Drain[_], ops: List[AstNode]) extends RunnableFlow {
  def run()(implicit materializer: FlowMaterializer): MaterializedPipe =
    materializer.materialize(input, output, ops)
}

/**
 * Returned by [[RunnablePipe#run]] and can be used as parameter to the
 * accessor method to retrieve the materialized `Tap` or `Drain`, e.g.
 * [[SubscriberTap#subscriber]] or [[PublisherDrain#publisher]].
 */
private[stream] class MaterializedPipe(tapKey: AnyRef, matTap: Any, drainKey: AnyRef, matDrain: Any) extends MaterializedFlow {
  /**
   * Do not call directly. Use accessor method in the concrete `Tap`, e.g. [[SubscriberTap#subscriber]].
   */
  override def getTapFor[T](key: TapWithKey[_, T]): T =
    if (key == tapKey) matTap.asInstanceOf[T]
    else throw new IllegalArgumentException(s"Tap key [$key] doesn't match the tap [$tapKey] of this flow")

  /**
   * Do not call directly. Use accessor method in the concrete `Drain`, e.g. [[PublisherDrain#publisher]].
   */
  def getDrainFor[T](key: DrainWithKey[_, T]): T =
    if (key == drainKey) matDrain.asInstanceOf[T]
    else throw new IllegalArgumentException(s"Drain key [$key] doesn't match the drain [$drainKey] of this flow")
}
