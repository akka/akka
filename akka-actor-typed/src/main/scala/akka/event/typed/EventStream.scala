package akka.event.typed

import akka.actor.typed.ActorRef
import akka.event.Logging.LogLevel

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

import akka.actor.typed.{ ActorRef, Behavior, Settings }
import akka.event.Logging.LogEvent
import akka.{ event ⇒ e }

abstract class Logger {
  def initialBehavior: Behavior[Logger.Command]
}

object Logger {
  sealed trait Command
  case class Initialize(eventStream: EventStream, replyTo: ActorRef[ActorRef[LogEvent]]) extends Command
  // FIXME add Mute/Unmute (i.e. the TestEventListener functionality)
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
