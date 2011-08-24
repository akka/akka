/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.event

import akka.actor._
import akka.dispatch.Dispatchers
import akka.config.Config._
import akka.config.ConfigurationException
import akka.util.{ ListenerManagement, ReflectiveAccess }
import akka.serialization._
import akka.AkkaException

/**
 * Event handler.
 * <p/>
 * Create, add and remove a listener:
 * <pre>
 * val eventHandlerListener = Actor.actorOf(new Actor {
 *   self.dispatcher = EventHandler.EventHandlerDispatcher
 *
 *   def receive = {
 *     case EventHandler.Error(cause, instance, message) ⇒ ...
 *     case EventHandler.Warning(instance, message)      ⇒ ...
 *     case EventHandler.Info(instance, message)         ⇒ ...
 *     case EventHandler.Debug(instance, message)        ⇒ ...
 *     case genericEvent                                 ⇒ ...
 * }
 * })
 *
 * EventHandler.addListener(eventHandlerListener)
 * ...
 * EventHandler.removeListener(eventHandlerListener)
 * </pre>
 * <p/>
 * However best is probably to register the listener in the 'akka.conf'
 * configuration file.
 * <p/>
 * Log an error event:
 * <pre>
 * EventHandler.notify(EventHandler.Error(exception, this, message))
 * </pre>
 * Or use the direct methods (better performance):
 * <pre>
 * EventHandler.error(exception, this, message)
 * </pre>
 *
 * Shut down the EventHandler:
 * <pre>
 * EventHandler.shutdown()
 * </pre>
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object EventHandler extends ListenerManagement {
  val synchronousLogging: Boolean = System.getProperty("akka.event.force-sync") match {
    case null | "" ⇒ false
    case _         ⇒ true
  }

  val ErrorLevel = 1
  val WarningLevel = 2
  val InfoLevel = 3
  val DebugLevel = 4

  sealed trait Event {
    @transient
    val thread: Thread = Thread.currentThread
    val level: Int
  }

  case class Error(cause: Throwable, instance: AnyRef, message: Any = "") extends Event {
    override val level = ErrorLevel
  }

  case class Warning(instance: AnyRef, message: Any = "") extends Event {
    override val level = WarningLevel
  }

  case class Info(instance: AnyRef, message: Any = "") extends Event {
    override val level = InfoLevel
  }

  case class Debug(instance: AnyRef, message: Any = "") extends Event {
    override val level = DebugLevel
  }

  val errorFormat = "[ERROR] [%s] [%s] [%s] %s\n%s".intern
  val warningFormat = "[WARN] [%s] [%s] [%s] %s".intern
  val infoFormat = "[INFO] [%s] [%s] [%s] %s".intern
  val debugFormat = "[DEBUG] [%s] [%s] [%s] %s".intern
  val genericFormat = "[GENERIC] [%s] [%s]".intern

  class EventHandlerException extends AkkaException

  lazy val StandardOutLogger = new StandardOutLogger {}

  lazy val EventHandlerDispatcher = Dispatchers.newDispatcher("akka:event:handler").build

  implicit object defaultListenerFormat extends StatelessActorFormat[DefaultListener]

  @volatile
  var level: Int = config.getString("akka.event-handler-level", "INFO") match {
    case "ERROR" | "error"     ⇒ ErrorLevel
    case "WARNING" | "warning" ⇒ WarningLevel
    case "INFO" | "info"       ⇒ InfoLevel
    case "DEBUG" | "debug"     ⇒ DebugLevel
    case unknown ⇒ throw new ConfigurationException(
      "Configuration option 'akka.event-handler-level' is invalid [" + unknown + "]")
  }

  def start() {
    try {
      val defaultListeners = config.getList("akka.event-handlers") match {
        case Nil       ⇒ "akka.event.EventHandler$DefaultListener" :: Nil
        case listeners ⇒ listeners
      }
      defaultListeners foreach { listenerName ⇒
        try {
          ReflectiveAccess.getClassFor[Actor](listenerName) match {
            case Right(actorClass) ⇒ addListener(Actor.localActorOf(actorClass).start())
            case Left(exception)   ⇒ throw exception
          }
        } catch {
          case e: Exception ⇒
            throw new ConfigurationException(
              "Event Handler specified in config can't be loaded [" + listenerName +
                "] due to [" + e.toString + "]", e)
        }
      }
      info(this, "Starting up EventHandler")
    } catch {
      case e: Exception ⇒
        e.printStackTrace()
        throw new ConfigurationException("Could not start Event Handler due to [" + e.toString + "]")
    }
  }

  /**
   * Shuts down all event handler listeners including the event handle dispatcher.
   */
  def shutdown() {
    foreachListener(_.stop())
    EventHandlerDispatcher.shutdown()
  }

  def notify(event: Any) {
    if (event.isInstanceOf[Event]) {
      if (level >= event.asInstanceOf[Event].level) log(event)
    } else log(event)
  }

  def notify[T <: Event: ClassManifest](event: ⇒ T) {
    if (level >= levelFor(classManifest[T].erasure.asInstanceOf[Class[_ <: Event]])) log(event)
  }

  def error(cause: Throwable, instance: AnyRef, message: ⇒ String) {
    if (level >= ErrorLevel) log(Error(cause, instance, message))
  }

  def error(cause: Throwable, instance: AnyRef, message: Any) {
    if (level >= ErrorLevel) log(Error(cause, instance, message))
  }

  def error(instance: AnyRef, message: ⇒ String) {
    if (level >= ErrorLevel) log(Error(new EventHandlerException, instance, message))
  }

  def error(instance: AnyRef, message: Any) {
    if (level >= ErrorLevel) log(Error(new EventHandlerException, instance, message))
  }

  def warning(instance: AnyRef, message: ⇒ String) {
    if (level >= WarningLevel) log(Warning(instance, message))
  }

  def warning(instance: AnyRef, message: Any) {
    if (level >= WarningLevel) log(Warning(instance, message))
  }

  def info(instance: AnyRef, message: ⇒ String) {
    if (level >= InfoLevel) log(Info(instance, message))
  }

  def info(instance: AnyRef, message: Any) {
    if (level >= InfoLevel) log(Info(instance, message))
  }

  def debug(instance: AnyRef, message: ⇒ String) {
    if (level >= DebugLevel) log(Debug(instance, message))
  }

  def debug(instance: AnyRef, message: Any) {
    if (level >= DebugLevel) log(Debug(instance, message))
  }

  def isInfoEnabled = level >= InfoLevel

  def isDebugEnabled = level >= DebugLevel

  def stackTraceFor(e: Throwable) = {
    import java.io.{ StringWriter, PrintWriter }
    val sw = new StringWriter
    val pw = new PrintWriter(sw)
    e.printStackTrace(pw)
    sw.toString
  }

  private def levelFor(eventClass: Class[_ <: Event]) = {
    if (eventClass.isInstanceOf[Error]) ErrorLevel
    else if (eventClass.isInstanceOf[Warning]) WarningLevel
    else if (eventClass.isInstanceOf[Info]) InfoLevel
    else if (eventClass.isInstanceOf[Debug]) DebugLevel
    else DebugLevel
  }

  private def log(event: Any) {
    if (synchronousLogging) StandardOutLogger.print(event)
    else notifyListeners(event)
  }

  trait StandardOutLogger {
    import java.text.SimpleDateFormat
    import java.util.Date

    val dateFormat = new SimpleDateFormat("MM/dd/yyyy HH:mm:ss.S")

    def timestamp = dateFormat.format(new Date)

    def print(event: Any) {
      event match {
        case e: Error   ⇒ error(e)
        case e: Warning ⇒ warning(e)
        case e: Info    ⇒ info(e)
        case e: Debug   ⇒ debug(e)
        case e          ⇒ generic(e)
      }
    }

    def error(event: Error) =
      println(errorFormat.format(
        timestamp,
        event.thread.getName,
        event.instance.getClass.getSimpleName,
        event.message,
        stackTraceFor(event.cause)))

    def warning(event: Warning) =
      println(warningFormat.format(
        timestamp,
        event.thread.getName,
        event.instance.getClass.getSimpleName,
        event.message))

    def info(event: Info) =
      println(infoFormat.format(
        timestamp,
        event.thread.getName,
        event.instance.getClass.getSimpleName,
        event.message))

    def debug(event: Debug) =
      println(debugFormat.format(
        timestamp,
        event.thread.getName,
        event.instance.getClass.getSimpleName,
        event.message))

    def generic(event: Any) =
      println(genericFormat.format(timestamp, event.toString))
  }

  class DefaultListener extends Actor with StandardOutLogger {
    self.dispatcher = EventHandlerDispatcher

    def receive = {
      case event ⇒ print(event)
    }
  }

  start()
}
