package akka.stream.dsl

/**
 * Dummy implementation needed for runtime tests.
 */
trait Graph {
  def broadcast[T](source: HasOpenOutput[_, T], destinations: Seq[HasOpenInput[T, _]]) = ()
  def zip[T, U](source1: HasOpenOutput[_, T], source2: HasOpenOutput[_, U], destination: HasOpenInput[(T, U), _]) = ()

  /**
   * Whenever a new K value is returned from `f`, a new `dest` flow will be materialized.
   *
   * The discriminating value `K` of the output flow can be known by applying `f` on any
   * element in the output flows.
   */
  def groupBy[T, K](source: HasOpenOutput[_, T], f: T ⇒ K, dest: HasOpenInput[T, _]) = ()
}

object Graph {
  def apply(): Graph = new Graph {}
}
