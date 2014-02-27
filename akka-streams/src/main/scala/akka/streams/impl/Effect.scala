package akka.streams.impl

import scala.annotation.tailrec

/**
 * The result of a synchronous handling step is zero, one, or several external effects.
 * This allows easy testing of synchronous operation implementations that never *execute*
 * their external side-effect but only return them. Their wrapper is then responsible for
 * running all the effects. In tests, effects don't have to be actually run but can be
 * matched on.
 *
 * The special Effect, `SingleStep`, allows for possibly mutually-recursive effects to be
 * run in a trampolining fashion to avoid stack overflows.
 *
 * The complete hierarchy of effects is this:
 *
 * Effect
 *  |-- Continue   => no effect
 *  |-- Effects    => a combination of several effects
 *  |-- SideEffect => the representation of an external effect
 *  |-- SingleStep => a partial effect that returns another effect to be run afterwards
 *
 *  Effect.run implements the trampolining logic which executes a possibly long chain of
 *  recursive effects without running into stack overflows.
 */
sealed trait Effect {
  def ~(next: Effect): Effect =
    if (next == Continue) this
    else Effects(Vector(this, next))
}
case object Continue extends Effect {
  override def ~(next: Effect): Effect = next
}
case class Effects(effects: Vector[Effect]) extends Effect {
  override def ~(next: Effect): Effect =
    if (next == Continue) this
    else Effects(effects :+ next)

  override def toString: String = effects.mkString("(", " ~ ", ")")
}

/** A single step that will result in a new effect. */
trait SingleStep extends Effect {
  def runOne(): Effect
}

/** A side-effect that executes some external effect. */
trait ExternalEffect extends Effect {
  def run(): Unit
}
object Effect {
  /** Creates an anonymous step */
  def step[O](body: ⇒ Effect, name: String): Effect = new SingleStep {
    override def toString: String = name

    def runOne(): Effect = body
  }
  /** Creates an anonymous external side-effect */
  def externalEffect[O](body: ⇒ Unit, name: String): Effect = new ExternalEffect {
    override def toString: String = name
    def run(): Unit = body
  }
}

/** An interface for implementations that can run effects */
trait EffectExecutor {
  def run(effect: Effect): Unit
}

object PlainEffectExecutor extends EffectExecutor {
  /** Runs a possibly tail-recursive chain of effects */
  def run(effect: Effect): Unit = {
    @tailrec def iterate(elements: Vector[Effect]): Unit = {
      if (elements.isEmpty) ()
      else elements.head match {
        case s: ExternalEffect ⇒
          s.run(); iterate(elements.tail)
        case Continue         ⇒ iterate(elements.tail)
        case r: SingleStep    ⇒ iterate(elements.tail :+ r.runOne())
        case Effects(effects) ⇒ iterate(effects ++ elements.tail)
      }
    }

    effect match {
      // shortcut for simple results
      case s: ExternalEffect ⇒ s.run()
      case Continue          ⇒
      case r: SingleStep     ⇒ iterate(Vector(r.runOne()))
      case Effects(effects)  ⇒ iterate(effects)
    }
  }
}

class TracingEffectExecutor(log: String ⇒ Unit) extends EffectExecutor {
  def run(effect: Effect): Unit = if (effect ne Continue) {
    import Console._
    log(s"${WHITE}Executing these effects: $effect$RESET")
    @tailrec def iterate(elements: Vector[Effect]): Unit = {
      if (elements.isEmpty) ()
      else {
        val first = elements.head
        val result = runOne(first)
        first match {
          case e: Effects ⇒ iterate(result ++ elements.tail)
          case _          ⇒ iterate(elements.tail ++ result)
        }
      }
    }

    def runOne(effect: Effect): Vector[Effect] =
      effect match {
        // shortcut for simple results
        case s: ExternalEffect ⇒
          log(s"${YELLOW}EE$RESET $s")
          s.run(); Vector.empty
        case Continue ⇒ Vector.empty
        case r: SingleStep ⇒
          val result = r.runOne()
          log(s"${BLUE}STEP$RESET $effect $WHITE⇒$RESET $result")
          Vector(result)
        case Effects(effects) ⇒ effects
      }

    val effects = runOne(effect)
    if (effects.nonEmpty) iterate(effects)
  }
}