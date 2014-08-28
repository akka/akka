package akka.stream.dsl

import akka.stream.FlowMaterializer
import org.reactivestreams.{ Subscription, Subscriber, Publisher }

import scala.concurrent.{ Future, Promise }
import scala.reflect.ClassTag

trait FlowGraph {
  def run(mat: FlowMaterializer) = MaterializedGraph()
}

case class MaterializedGraph()

/**
 * A FlowGraph builder which allows user to specify vertices.
 */
case class VertexBuilder() extends FlowGraph {
  def merge[T, U, V >: T with U](from1: HasOpenOutput[_, T], from2: HasOpenOutput[_, U], to: HasOpenInput[V, _]) = this
  def broadcast[T](from: HasOpenOutput[_, T], to: Seq[HasOpenInput[T, _]]) = this
  def zip[T, U](from1: HasOpenOutput[_, T], from2: HasOpenOutput[_, U], to: HasOpenInput[(T, U), _]) = this

  /**
   * Logically connect `InputFactory` to the input side of the specified `Flow`.
   * If the specified `Flow` has an `InputFactory` already, it will be replaced with the specified one.
   * When materialized, the specified `Flow` will get an input from the `InputFactory`.
   */
  def from[T](in: InputFactory[T], flow: Flow[T, _]) = this

  /**
   * Logically connect `OutputFactory` to the output side of the specified `Flow`.
   * If the specified `Flow` has an `OutputFactory` already, it will be replaced with the specified one.
   * When materialized, the specified `Flow` will get an output from the `OutputFactory`.
   */
  def to[T](out: OutputFactory[T], flow: Flow[_, T]) = this
}

/**
 * A FlowGraph builder which allows user to specify edges.
 */
case class EdgeBuilder[T]() extends FlowGraph {
  import akka.stream.dsl.EdgeBuilder._

  def merge[U, V, W >: U with V] = Merge[U, V, W]()
  def broadcast[U] = Broadcast[U]()
  def zip[U, V] = Zip[U, V]()

  /**
   * A connect operation sequence must start with an apply method,
   * which sets the next expected type parameter.
   */
  def apply[U](in: InputFactory[U]) = EdgeBuilder[U]()
  def apply[U](in: OutputFactory[U]) = EdgeBuilder[U]()
  def apply[U](in: Flow[_, U]) = EdgeBuilder[U]()
  def apply[U](in: FanOperation[U]) = EdgeBuilder[U]()

  def ~>(in: InputFactory[T]) = this
  def ~>(out: OutputFactory[T]) = this
  def ~>[U](flow: Flow[T, U]) = EdgeBuilder[U]()

  /**
   * Connector for any Fan-In/Out operation with only one type parameter.
   */
  def ~>(fan: FanOperation[T]) = this

  /**
   * Two methods for zip is needed, because both inputs need to support connect operation.
   */
  def ~>[U](zip: Zip[T, U]) = EdgeBuilder[(T, U)]
  def ~~>[U](zip: Zip[U, T]) = EdgeBuilder[(T, U)] // FIXME: double definition after erasure

  /**
   * Two methods for merge is needed, because both inputs need to support connect operation.
   */
  def ~>[U](merge: Merge[_, T, U]) = EdgeBuilder[U]
  def ~~>[U](merge: Merge[T, _, U]) = EdgeBuilder[U]
}

object EdgeBuilder {
  case class Merge[T, U, V >: T with U]()
  case class Broadcast[T]() extends FanOperation[T]
  case class Zip[T, U]()

  trait FanOperation[T]
}

trait InputFactory[T] {
  type Input[T]
  def materialize(mat: FlowMaterializer)
  def getInputFrom(p: MaterializedGraph): Input[T]
}

case class PromiseInput[T]() extends InputFactory[T] {
  type Input[T] = Promise[T]
  def materialize(mat: FlowMaterializer) = ???
  def getInputFrom(p: MaterializedGraph): Promise[T] = Promise[T]()
}

case class SubscriberInput[T]() extends InputFactory[T] {
  type Input[T] = Subscriber[T]
  def materialize(mat: FlowMaterializer) = ???
  def getInputFrom(p: MaterializedGraph): Subscriber[T] = new Subscriber[T] {
    override def onSubscribe(s: Subscription): Unit = ???
    override def onError(t: Throwable): Unit = ???
    override def onComplete(): Unit = ???
    override def onNext(t: T): Unit = ???
  }
}

trait OutputFactory[T] {
  type Output[T]
  def materialize(mat: FlowMaterializer)
  def getOutputFrom(p: MaterializedGraph): Output[T]
}

case class FutureOutput[T]() extends OutputFactory[T] {
  type Output[T] = Future[T]
  def materialize(mat: FlowMaterializer) = ???
  def getOutputFrom(p: MaterializedGraph) = Future.failed[T](new Exception())
}

case class PublisherOutput[T]() extends OutputFactory[T] {
  type Output[T] = Publisher[T]
  def materialize(mat: FlowMaterializer) = ???
  def getOutputFrom(p: MaterializedGraph) = new Publisher[T] {
    override def subscribe(s: Subscriber[T]): Unit = ???
  }
}
