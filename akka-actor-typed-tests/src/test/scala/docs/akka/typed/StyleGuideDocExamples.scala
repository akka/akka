/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

//#oo-style
//#fun-style
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
//#fun-style
import akka.actor.typed.scaladsl.AbstractBehavior
//#oo-style

object StyleGuideDocExamples {

  object FunctionalStyle {

    //#fun-style

    object Counter {
      sealed trait Command
      case object Increment extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)

      def apply(): Behavior[Command] =
        counter(0)

      private def counter(n: Int): Behavior[Command] =
        Behaviors.receive { (context, message) =>
          message match {
            case Increment =>
              val newValue = n + 1
              context.log.debug("Incremented counter to [{}]", newValue)
              counter(newValue)
            case GetValue(replyTo) =>
              replyTo ! Value(n)
              Behaviors.same
          }
        }
    }
    //#fun-style

  }

  object OOStyle {

    //#oo-style
    object Counter {
      sealed trait Command
      case object Increment extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)

      def apply(): Behavior[Command] = {
        Behaviors.setup(context => new Counter(context))
      }
    }

    class Counter(context: ActorContext[Counter.Command]) extends AbstractBehavior[Counter.Command] {
      import Counter._

      private var n = 0

      override def onMessage(msg: Command): Behavior[Counter.Command] = {
        msg match {
          case Increment =>
            n += 1
            context.log.debug("Incremented counter to [{}]", n)
            this
          case GetValue(replyTo) =>
            replyTo ! Value(n)
            this
        }
      }
    }
    //#oo-style

  }

}
