/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

//#oo-style
//#fun-style
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
//#fun-style
import akka.actor.typed.scaladsl.AbstractBehavior
//#oo-style

import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.TimerScheduler
import scala.concurrent.duration.FiniteDuration
import akka.Done

object StyleGuideDocExamples {

  object FunctionalStyle {

    //#fun-style

    //#messages
    object Counter {
      sealed trait Command
      case object Increment extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)
      //#messages

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
      //#messages
    }
    //#messages
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

  object FunctionalStyleSetupParams1 {
    // #fun-style-setup-params1

    // this is an anti-example, better solutions exists
    object Counter {
      sealed trait Command
      case object Increment extends Command
      final case class IncrementRepeatedly(interval: FiniteDuration) extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)

      def apply(name: String): Behavior[Command] =
        Behaviors.withTimers { timers =>
          counter(name, timers, 0)
        }

      private def counter(name: String, timers: TimerScheduler[Command], n: Int): Behavior[Command] =
        Behaviors.receive { (context, message) =>
          message match {
            case IncrementRepeatedly(interval) =>
              context.log.debug(
                "[{}] Starting repeated increments with interval [{}], current count is [{}]",
                name,
                interval,
                n)
              timers.startTimerWithFixedDelay("repeat", Increment, interval)
              Behaviors.same
            case Increment =>
              val newValue = n + 1
              context.log.debug("[{}] Incremented counter to [{}]", name, newValue)
              counter(name, timers, newValue)
            case GetValue(replyTo) =>
              replyTo ! Value(n)
              Behaviors.same
          }
        }
    }
    // #fun-style-setup-params1
  }

  object FunctionalStyleSetupParams2 {
    // #fun-style-setup-params2

    // this is better than previous example, but even better solution exists
    object Counter {
      sealed trait Command
      case object Increment extends Command
      final case class IncrementRepeatedly(interval: FiniteDuration) extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)

      private case class Setup(name: String, context: ActorContext[Command], timers: TimerScheduler[Command])

      def apply(name: String): Behavior[Command] =
        Behaviors.setup { context =>
          Behaviors.withTimers { timers =>
            counter(Setup(name, context, timers), 0)
          }
        }

      private def counter(setup: Setup, n: Int): Behavior[Command] =
        Behaviors.receiveMessage {
          case IncrementRepeatedly(interval) =>
            setup.context.log.debug(
              "[{}] Starting repeated increments with interval [{}], current count is [{}]",
              setup.name,
              interval,
              n)
            setup.timers.startTimerWithFixedDelay("repeat", Increment, interval)
            Behaviors.same
          case Increment =>
            val newValue = n + 1
            setup.context.log.debug("[{}] Incremented counter to [{}]", setup.name, newValue)
            counter(setup, newValue)
          case GetValue(replyTo) =>
            replyTo ! Value(n)
            Behaviors.same
        }
    }
    // #fun-style-setup-params2
  }

  object FunctionalStyleSetupParams3 {
    // #fun-style-setup-params3

    // this is better than previous examples
    object Counter {
      sealed trait Command
      case object Increment extends Command
      final case class IncrementRepeatedly(interval: FiniteDuration) extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)

      def apply(name: String): Behavior[Command] =
        Behaviors.setup { context =>
          Behaviors.withTimers { timers =>
            new Counter(name, context, timers).counter(0)
          }
        }
    }

    class Counter private (
        name: String,
        context: ActorContext[Counter.Command],
        timers: TimerScheduler[Counter.Command]) {
      import Counter._

      private def counter(n: Int): Behavior[Command] =
        Behaviors.receiveMessage {
          case IncrementRepeatedly(interval) =>
            context.log.debug(
              "[{}] Starting repeated increments with interval [{}], current count is [{}]",
              name,
              interval,
              n)
            timers.startTimerWithFixedDelay("repeat", Increment, interval)
            Behaviors.same
          case Increment =>
            val newValue = n + 1
            context.log.debug("[{}] Incremented counter to [{}]", name, newValue)
            counter(newValue)
          case GetValue(replyTo) =>
            replyTo ! Value(n)
            Behaviors.same
        }
    }
    // #fun-style-setup-params3
  }

  object FunctionalStyleSetupParams4 {
    // #fun-style-setup-params4

    // this works, but previous example is better for structuring more complex behaviors
    object Counter {
      sealed trait Command
      case object Increment extends Command
      final case class IncrementRepeatedly(interval: FiniteDuration) extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)

      def apply(name: String): Behavior[Command] =
        Behaviors.setup { context =>
          Behaviors.withTimers { timers =>
            def counter(n: Int): Behavior[Command] =
              Behaviors.receiveMessage {
                case IncrementRepeatedly(interval) =>
                  context.log.debug(
                    "[{}] Starting repeated increments with interval [{}], current count is [{}]",
                    name,
                    interval,
                    n)
                  timers.startTimerWithFixedDelay("repeat", Increment, interval)
                  Behaviors.same
                case Increment =>
                  val newValue = n + 1
                  context.log.debug("[{}] Incremented counter to [{}]", name, newValue)
                  counter(newValue)
                case GetValue(replyTo) =>
                  replyTo ! Value(n)
                  Behaviors.same
              }

            counter(0)
          }
        }
    }

    // #fun-style-setup-params4
  }

  object FactoryMethod {
    //#behavior-factory-method
    object CountDown {
      sealed trait Command
      case object Down extends Command

      // factory for the initial `Behavior`
      def apply(countDownFrom: Int, notifyWhenZero: ActorRef[Done]): Behavior[Command] =
        new CountDown(notifyWhenZero).counter(countDownFrom)
    }

    private class CountDown(notifyWhenZero: ActorRef[Done]) {
      import CountDown._

      private def counter(remaining: Int): Behavior[Command] = {
        Behaviors.receiveMessage {
          case Down =>
            if (remaining == 1) {
              notifyWhenZero.tell(Done)
              Behaviors.stopped
            } else
              counter(remaining - 1)
        }
      }

    }
    //#behavior-factory-method

    object Usage {
      val context: ActorContext[_] = ???
      val doneRef: ActorRef[Done] = ???

      //#behavior-factory-method-spawn
      val countDown = context.spawn(CountDown(100, doneRef), "countDown")
      //#behavior-factory-method-spawn

      //#message-prefix-in-tell
      countDown ! CountDown.Down
      //#message-prefix-in-tell
    }
  }

  object Messages {
    //#message-protocol
    object CounterProtocol {
      sealed trait Command

      final case class Increment(delta: Int, replyTo: ActorRef[OperationResult]) extends Command
      final case class Decrement(delta: Int, replyTo: ActorRef[OperationResult]) extends Command

      sealed trait OperationResult
      case object Confirmed extends OperationResult
      final case class Rejected(reason: String)
    }
    //#message-protocol
  }

  object PublicVsPrivateMessages1 {
    //#public-private-messages-1
    object Counter {
      sealed trait Command
      case object Increment extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)

      // Tick is private so can't be sent from the outside
      private case object Tick extends Command

      def apply(name: String, tickInterval: FiniteDuration): Behavior[Command] =
        Behaviors.setup { context =>
          Behaviors.withTimers { timers =>
            timers.startTimerWithFixedDelay("tick", Tick, tickInterval)
            new Counter(name, context).counter(0)
          }
        }
    }

    class Counter private (name: String, context: ActorContext[Counter.Command]) {
      import Counter._

      private def counter(n: Int): Behavior[Command] =
        Behaviors.receiveMessage {
          case Increment =>
            val newValue = n + 1
            context.log.debug("[{}] Incremented counter to [{}]", name, newValue)
            counter(newValue)
          case Tick =>
            val newValue = n + 1
            context.log.debug("[{}] Incremented counter by background tick to [{}]", name, newValue)
            counter(newValue)
          case GetValue(replyTo) =>
            replyTo ! Value(n)
            Behaviors.same
        }
    }
  }
  //#public-private-messages-1

  object PublicVsPrivateMessages2 {
    //#public-private-messages-2
    // above example is preferred, but this is possible and not wrong
    object Counter {
      sealed trait PrivateCommand
      sealed trait Command extends PrivateCommand
      case object Increment extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)

      // Tick is a PrivateCommand so can't be sent to an ActorRef[Command]
      case object Tick extends PrivateCommand

      def apply(name: String, tickInterval: FiniteDuration): Behavior[Command] = {
        Behaviors
          .setup[Counter.PrivateCommand] { context =>
            Behaviors.withTimers { timers =>
              timers.startTimerWithFixedDelay("tick", Tick, tickInterval)
              new Counter(name, context).counter(0)
            }
          }
          .narrow // note narrow here
      }
    }

    class Counter private (name: String, context: ActorContext[Counter.PrivateCommand]) {
      import Counter._

      private def counter(n: Int): Behavior[PrivateCommand] =
        Behaviors.receiveMessage {
          case Increment =>
            val newValue = n + 1
            context.log.debug("[{}] Incremented counter to [{}]", name, newValue)
            counter(newValue)
          case Tick =>
            val newValue = n + 1
            context.log.debug("[{}] Incremented counter by background tick to [{}]", name, newValue)
            counter(newValue)
          case GetValue(replyTo) =>
            replyTo ! Value(n)
            Behaviors.same
        }
    }
  }
  //#public-private-messages-2

}
