package akka.stream.dsl

/**
 * Dummy implementation needed for runtime tests.
 */
trait Graph {

  def merge[T](source1: HasOpenOutput[_, T], source2: HasOpenOutput[_, T], destination: HasOpenInput[T, _]) = ()
  def zip[T, U](source1: HasOpenOutput[_, T], source2: HasOpenOutput[_, U], destination: HasOpenInput[(T, U), _]) = ()
  def concat[T](source1: HasOpenOutput[_, T], source2: HasOpenOutput[_, T], destination: HasOpenInput[T, _]) = ()
  def broadcast[T](source: HasOpenOutput[_, T], destinations: Seq[HasOpenInput[T, _]]) = ()
}

object Graph {
  def apply(): Graph = new Graph {}
}

final case class Broadcast[T]() extends Input[T] with Output[T]
final case class Zip[T]() extends Input[T] with Output[T]
