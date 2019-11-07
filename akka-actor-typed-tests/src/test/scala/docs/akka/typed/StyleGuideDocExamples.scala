/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.akka.typed

import scala.concurrent.duration._
import scala.concurrent.Future

import akka.actor.typed.ActorSystem
import akka.actor.typed.ActorRef
import akka.actor.typed.scaladsl.LoggerOps
import akka.actor.typed.scaladsl.TimerScheduler
import scala.concurrent.duration.FiniteDuration

import akka.Done
import com.github.ghik.silencer.silent

//#oo-style
//#fun-style

import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
//#fun-style
import akka.actor.typed.scaladsl.AbstractBehavior
//#oo-style

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

    class Counter(context: ActorContext[Counter.Command]) extends AbstractBehavior[Counter.Command](context) {
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
                interval.toString,
                n.toString)
              timers.startTimerWithFixedDelay("repeat", Increment, interval)
              Behaviors.same
            case Increment =>
              val newValue = n + 1
              context.log.debug2("[{}] Incremented counter to [{}]", name, newValue)
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
            setup.context.log.debugN(
              "[{}] Starting repeated increments with interval [{}], current count is [{}]",
              setup.name,
              interval,
              n)
            setup.timers.startTimerWithFixedDelay("repeat", Increment, interval)
            Behaviors.same
          case Increment =>
            val newValue = n + 1
            setup.context.log.debug2("[{}] Incremented counter to [{}]", setup.name, newValue)
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
            context.log.debugN(
              "[{}] Starting repeated increments with interval [{}], current count is [{}]",
              name,
              interval,
              n)
            timers.startTimerWithFixedDelay("repeat", Increment, interval)
            Behaviors.same
          case Increment =>
            val newValue = n + 1
            context.log.debug2("[{}] Incremented counter to [{}]", name, newValue)
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
                  context.log.debugN(
                    "[{}] Starting repeated increments with interval [{}], current count is [{}]",
                    name,
                    interval,
                    n)
                  timers.startTimerWithFixedDelay("repeat", Increment, interval)
                  Behaviors.same
                case Increment =>
                  val newValue = n + 1
                  context.log.debug2("[{}] Incremented counter to [{}]", name, newValue)
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
        //#exhastivness-check
        Behaviors.receiveMessage {
          case Down =>
            if (remaining == 1) {
              notifyWhenZero.tell(Done)
              Behaviors.stopped
            } else
              counter(remaining - 1)
        }
        //#exhastivness-check
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
            context.log.debug2("[{}] Incremented counter to [{}]", name, newValue)
            counter(newValue)
          case Tick =>
            val newValue = n + 1
            context.log.debug2("[{}] Incremented counter by background tick to [{}]", name, newValue)
            counter(newValue)
          case GetValue(replyTo) =>
            replyTo ! Value(n)
            Behaviors.same
        }
    }
    //#public-private-messages-1
  }

  object PublicVsPrivateMessages2 {
    //#public-private-messages-2
    // above example is preferred, but this is possible and not wrong
    object Counter {
      // The type of all public and private messages the Counter actor handles
      sealed trait Message

      /** Counter's public message protocol type. */
      sealed trait Command extends Message
      case object Increment extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)

      // The type of the Counter actor's internal messages.
      sealed trait PrivateCommand extends Message
      // Tick is a private command so can't be sent to an ActorRef[Command]
      case object Tick extends PrivateCommand

      def apply(name: String, tickInterval: FiniteDuration): Behavior[Command] = {
        Behaviors
          .setup[Counter.Message] { context =>
            Behaviors.withTimers { timers =>
              timers.startTimerWithFixedDelay("tick", Tick, tickInterval)
              new Counter(name, context).counter(0)
            }
          }
          .narrow // note narrow here
      }
    }

    class Counter private (name: String, context: ActorContext[Counter.Message]) {
      import Counter._

      private def counter(n: Int): Behavior[Message] =
        Behaviors.receiveMessage {
          case Increment =>
            val newValue = n + 1
            context.log.debug2("[{}] Incremented counter to [{}]", name, newValue)
            counter(newValue)
          case Tick =>
            val newValue = n + 1
            context.log.debug2("[{}] Incremented counter by background tick to [{}]", name, newValue)
            counter(newValue)
          case GetValue(replyTo) =>
            replyTo ! Value(n)
            Behaviors.same
        }
    }
    //#public-private-messages-2
  }

  object Ask {
    import Messages.CounterProtocol._

    implicit val system: ActorSystem[Nothing] = ???

    //#ask-1
    import akka.actor.typed.scaladsl.AskPattern._
    import akka.util.Timeout

    implicit val timeout = Timeout(3.seconds)
    val counter: ActorRef[Command] = ???

    val result: Future[OperationResult] = counter.ask(replyTo => Increment(delta = 2, replyTo))
    //#ask-1

    //#ask-2
    val result2: Future[OperationResult] = counter.ask(Increment(delta = 2, _))
    //#ask-2

    /*
    //#ask-3
    // doesn't compile
    val result3: Future[OperationResult] = counter ? Increment(delta = 2, _)
    //#ask-3
     */

    //#ask-4
    val result3: Future[OperationResult] = counter ? (Increment(delta = 2, _))
    //#ask-4
  }

  object ExhaustivenessCheck {

    object CountDown {
      //#messages-sealed
      sealed trait Command
      case object Down extends Command
      final case class GetValue(replyTo: ActorRef[Value]) extends Command
      final case class Value(n: Int)
      //#messages-sealed

      def apply(countDownFrom: Int, notifyWhenZero: ActorRef[Done]): Behavior[Command] =
        new CountDown(notifyWhenZero).counterWithGuard(countDownFrom)
    }

    private class CountDown(notifyWhenZero: ActorRef[Done]) {
      import CountDown._

      private def counterWithGuard(remaining: Int): Behavior[Command] = {
        //#pattern-match-guard
        // no exhaustiveness check because of guard condition
        Behaviors.receiveMessage {
          case Down if remaining == 1 =>
            notifyWhenZero.tell(Done)
            zero
          case Down =>
            counter(remaining - 1)
        }
        //#pattern-match-guard
      }

      @silent
      private def counter(remaining: Int): Behavior[Command] = {
        //#pattern-match-without-guard
        Behaviors.receiveMessage {
          case Down =>
            if (remaining == 1) {
              notifyWhenZero.tell(Done)
              zero
            } else
              counter(remaining - 1)
        }
        //#pattern-match-without-guard
      }

      //#pattern-match-unhandled
      private val zero: Behavior[Command] = {
        Behaviors.receiveMessage {
          case GetValue(replyTo) =>
            replyTo ! Value(0)
            Behaviors.same
          case Down =>
            Behaviors.unhandled
        }
      }
      //#pattern-match-unhandled

      @silent
      object partial {
        //#pattern-match-partial
        private val zero: Behavior[Command] = {
          Behaviors.receiveMessagePartial {
            case GetValue(replyTo) =>
              replyTo ! Value(0)
              Behaviors.same
          }
        }
        //#pattern-match-partial
      }

    }
  }
}
