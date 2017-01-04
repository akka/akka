/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed

import akka.{ event ⇒ e }
import akka.event.Logging.{ LogEvent, LogLevel, StdOutLogger }
import akka.testkit.{ EventFilter, TestEvent ⇒ TE }
import scala.annotation.tailrec

/**
 * An EventStream allows local actors to register for certain message types, including
 * their subtypes automatically. Publishing events will broadcast them to all
 * currently subscribed actors with matching subscriptions for the event type.
 *
 * IMPORTANT NOTICE
 *
 * This EventStream is local to the ActorSystem, it does not span a cluster. For
 * disseminating messages across a cluster please refer to the DistributedPubSub
 * module.
 */
trait EventStream {
  /**
   * Attempts to register the subscriber to the specified Classifier
   * @return true if successful and false if not (because it was already
   *   subscribed to that Classifier, or otherwise)
   */
  def subscribe[T](subscriber: ActorRef[T], to: Class[T]): Boolean

  /**
   * Attempts to deregister the subscriber from the specified Classifier
   * @return true if successful and false if not (because it wasn't subscribed
   *   to that Classifier, or otherwise)
   */
  def unsubscribe[T](subscriber: ActorRef[T], from: Class[T]): Boolean

  /**
   * Attempts to deregister the subscriber from all Classifiers it may be subscribed to
   */
  def unsubscribe[T](subscriber: ActorRef[T]): Unit

  /**
   * Publishes the specified Event to this bus
   */
  def publish[T](event: T): Unit

  /**
   * Query the current minimum log level.
   */
  def logLevel: LogLevel

  /**
   * Change the current minimum log level.
   */
  def setLogLevel(loglevel: LogLevel): Unit
}

abstract class Logger {
  def initialBehavior: Behavior[Logger.Command]
}

object Logger {
  sealed trait Command
  case class Initialize(eventStream: EventStream, replyTo: ActorRef[ActorRef[LogEvent]]) extends Command
  // FIXME add Mute/Unmute (i.e. the TestEventListener functionality)
}

class DefaultLogger extends Logger with StdOutLogger {
  import ScalaDSL._
  import Logger._

  val initialBehavior =
    ContextAware[Command] { ctx ⇒
      Total {
        case Initialize(eventStream, replyTo) ⇒
          val log = ctx.spawn(Deferred[AnyRef] { () ⇒
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

            Static {
              case TE.Mute(filters)   ⇒ filters foreach addFilter
              case TE.UnMute(filters) ⇒ filters foreach removeFilter
              case event: LogEvent    ⇒ if (!filter(event)) print(event)
            }
          }, "logger")
          eventStream.subscribe(log, classOf[TE.Mute])
          eventStream.subscribe(log, classOf[TE.UnMute])
          ctx.watch(log) // sign death pact
          replyTo ! log
          Empty
      }
    }
}

class DefaultLoggingFilter(settings: Settings, eventStream: EventStream) extends e.DefaultLoggingFilter(() ⇒ eventStream.logLevel)

/**
 * [[akka.event.LoggingAdapter]] that publishes [[akka.event.Logging.LogEvent]] to event stream.
 */
class BusLogging(val bus: EventStream, val logSource: String, val logClass: Class[_], loggingFilter: e.LoggingFilter)
  extends e.LoggingAdapter {

  import e.Logging._

  def isErrorEnabled = loggingFilter.isErrorEnabled(logClass, logSource)
  def isWarningEnabled = loggingFilter.isWarningEnabled(logClass, logSource)
  def isInfoEnabled = loggingFilter.isInfoEnabled(logClass, logSource)
  def isDebugEnabled = loggingFilter.isDebugEnabled(logClass, logSource)

  protected def notifyError(message: String): Unit = bus.publish(Error(logSource, logClass, message, mdc))
  protected def notifyError(cause: Throwable, message: String): Unit = bus.publish(Error(cause, logSource, logClass, message, mdc))
  protected def notifyWarning(message: String): Unit = bus.publish(Warning(logSource, logClass, message, mdc))
  protected def notifyInfo(message: String): Unit = bus.publish(Info(logSource, logClass, message, mdc))
  protected def notifyDebug(message: String): Unit = bus.publish(Debug(logSource, logClass, message, mdc))
}
