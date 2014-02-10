package akka.streams.ops2

import scala.annotation.tailrec

sealed trait Effect {
  def ~(next: Effect): Effect =
    if (next == Continue) this
    else Effects(Vector(this, next))
}
object Continue extends Effect {
  override def ~(next: Effect): Effect = next
}
case class Effects(results: Vector[Effect]) extends Effect {
  override def ~(next: Effect): Effect =
    if (next == Continue) this
    else Effects(results :+ next)
}
trait SingleStep extends Effect {
  def runOne(): Effect
}
trait SideEffect extends Effect {
  def run(): Unit
}
object Effect {
  def step[O](body: ⇒ Effect): Effect = new SingleStep {
    def runOne(): Effect = body
  }
  def sideEffect[O](body: ⇒ Unit): Effect = new SideEffect {
    def run(): Unit = body
  }

  /** Runs a possibly tail-recursive chain of effects */
  def run(effect: Effect): Unit =
    effect match {
      // shortcut for simple results
      case s: SideEffect    ⇒ s.run()
      case Continue         ⇒
      case r: SingleStep    ⇒ iterate(Vector(r.runOne()))
      case Effects(results) ⇒ iterate(results)
    }

  @tailrec private[this] def iterate(elements: Vector[Effect]): Unit =
    if (elements.isEmpty) ()
    else elements.head match {
      case s: SideEffect ⇒
        s.run(); iterate(elements.tail)
      case Continue         ⇒ iterate(elements.tail)
      case r: SingleStep    ⇒ iterate(r.runOne() +: elements.tail)
      case Effects(results) ⇒ iterate(results ++ elements.tail)
    }
}
