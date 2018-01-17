package akka.testkit.typed

import akka.event.Logging.{ LogEvent, StdOutLogger }
import akka.testkit.{ EventFilter, TestEvent ⇒ TE }
import akka.event.typed.Logger.{ Command, Initialize }

import scala.annotation.tailrec
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.Behavior
import akka.event.typed.Logger

/**
 * EventListener for running tests, which allows selectively filtering out
 * expected messages. To use it, include something like this into
 * your config
 *
 * <pre><code>
 * akka.typed {
 *   loggers = ["akka.testkit.typed.TestEventListener"]
 * }
 * </code></pre>
 */
class TestEventListener extends Logger with StdOutLogger {

  override val initialBehavior: Behavior[Command] = {
    Behaviors.deferred[Command] { _ ⇒
      Behaviors.immutable[Command] {
        case (ctx, Initialize(eventStream, replyTo)) ⇒
          val log = ctx.spawn(Behaviors.deferred[AnyRef] { childCtx ⇒
            var filters: List[EventFilter] = Nil

            def filter(event: LogEvent): Boolean = filters exists (f ⇒ try { f(event) } catch { case e: Exception ⇒ false })

            def addFilter(filter: EventFilter): Unit = filters ::= filter

            def removeFilter(filter: EventFilter) {
              @tailrec def removeFirst(list: List[EventFilter], zipped: List[EventFilter] = Nil): List[EventFilter] = list match {
                case head :: tail if head == filter ⇒ tail.reverse_:::(zipped)
                case head :: tail                   ⇒ removeFirst(tail, head :: zipped)
                case Nil                            ⇒ filters // filter not found, just return original list
              }
              filters = removeFirst(filters)
            }

            Behaviors.immutable[AnyRef] {
              case (_, TE.Mute(filters)) ⇒
                filters foreach addFilter
                Behaviors.same
              case (_, TE.UnMute(filters)) ⇒
                filters foreach removeFilter
                Behaviors.same
              case (_, event: LogEvent) ⇒
                if (!filter(event)) print(event)
                Behaviors.same
              case _ ⇒ Behaviors.unhandled
            }
          }, "logger")

          eventStream.subscribe(log, classOf[TE.Mute])
          eventStream.subscribe(log, classOf[TE.UnMute])
          ctx.watch(log) // sign death pact
          replyTo ! log

          Behaviors.empty
      }
    }
  }
}
