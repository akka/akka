/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import akka.{ actor ⇒ a, event ⇒ e }
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.concurrent.{ Await, Promise }
import akka.util.{ ReentrantGuard, Subclassification, SubclassifiedIndex }
import scala.collection.immutable
import java.util.concurrent.TimeoutException
import akka.util.Timeout

/**
 * INTERNAL API
 *
 * An Akka EventStream is a pub-sub stream of events both system and user generated,
 * where subscribers are ActorRefs and the channels are Classes and Events are any java.lang.Object.
 * EventStreams employ SubchannelClassification, which means that if you listen to a Class,
 * you'll receive any message that is of that type or a subtype.
 *
 * The debug flag in the constructor toggles if operations on this EventStream should also be published
 * as Debug-Events
 */
private[typed] class EventStreamImpl(private val debug: Boolean)(implicit private val timeout: Timeout) extends EventStream {
  import e.Logging._
  import ScalaDSL._
  import EventStreamImpl._

  private val unsubscriberPromise = Promise[ActorRef[Command]]
  private val unsubscriber = ActorRef(unsubscriberPromise.future)

  /**
   * ''Must'' be called after actor system is "ready".
   * Starts system actor that takes care of unsubscribing subscribers that have terminated.
   */
  def startUnsubscriber(sys: ActorSystem[Nothing]): Unit =
    unsubscriberPromise.completeWith(sys.systemActorOf(unsubscriberBehavior, "eventStreamUnsubscriber"))

  private val unsubscriberBehavior = Deferred { () ⇒
    if (debug) publish(e.Logging.Debug(simpleName(getClass), getClass, s"registering unsubscriber with $this"))
    Full[Command] {
      case Msg(ctx, Register(actor)) ⇒
        if (debug) publish(e.Logging.Debug(simpleName(getClass), getClass, s"watching $actor in order to unsubscribe from EventStream when it terminates"))
        ctx.watch[Nothing](actor)
        Same

      case Msg(ctx, UnregisterIfNoMoreSubscribedChannels(actor)) if hasSubscriptions(actor) ⇒ Same
      // hasSubscriptions can be slow, but it's better for this actor to take the hit than the EventStream

      case Msg(ctx, UnregisterIfNoMoreSubscribedChannels(actor)) ⇒
        if (debug) publish(e.Logging.Debug(simpleName(getClass), getClass, s"unwatching $actor, since has no subscriptions"))
        ctx.unwatch[Nothing](actor)
        Same

      case Sig(ctx, Terminated(actor)) ⇒
        if (debug) publish(e.Logging.Debug(simpleName(getClass), getClass, s"unsubscribe $actor from $this, because it was terminated"))
        unsubscribe(actor)
        Same
    }
  }

  private val guard = new ReentrantGuard
  private var loggers = Seq.empty[(ActorRef[Logger.Command], ActorRef[LogEvent])]
  @volatile private var _logLevel: LogLevel = _

  /**
   * Query currently set log level. See object Logging for more information.
   */
  def logLevel = _logLevel

  /**
   * Change log level: default loggers (i.e. from configuration file) are
   * subscribed/unsubscribed as necessary so that they listen to all levels
   * which are at least as severe as the given one. See object Logging for
   * more information.
   *
   * NOTE: if the StandardOutLogger is configured also as normal logger, it
   * will not participate in the automatic management of log level
   * subscriptions!
   */
  def setLogLevel(level: LogLevel): Unit = guard.withGuard {
    val logLvl = _logLevel // saves (2 * AllLogLevel.size - 1) volatile reads (because of the loops below)
    for {
      l ← AllLogLevels
      // subscribe if previously ignored and now requested
      if l > logLvl && l <= level
      (logger, channel) ← loggers
    } subscribe(channel, classFor(l))
    for {
      l ← AllLogLevels
      // unsubscribe if previously registered and now ignored
      if l <= logLvl && l > level
      (logger, channel) ← loggers
    } unsubscribe(channel, classFor(l))
    _logLevel = level
  }

  private def setUpStdoutLogger(settings: Settings) {
    val level = levelFor(settings.untyped.StdoutLogLevel) getOrElse {
      // only log initialization errors directly with StandardOutLogger.print
      StandardOutLogger.print(Error(new LoggerException, simpleName(this), this.getClass, "unknown akka.stdout-loglevel " + settings.untyped.StdoutLogLevel))
      ErrorLevel
    }
    AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(StandardOutLogger, classFor(l)))
    guard.withGuard {
      loggers :+= internal.BlackholeActorRef → StandardOutLogger
      _logLevel = level
    }
  }

  /**
   * Actor-less logging implementation for synchronous logging to standard
   * output. This logger is always attached first in order to be able to log
   * failures during application start-up, even before normal logging is
   * started. Its log level can be defined by configuration setting
   * <code>akka.stdout-loglevel</code>.
   */
  private[typed] class StandardOutLogger extends ActorRef[LogEvent](StandardOutLoggerPath) with ActorRefImpl[LogEvent] with StdOutLogger {
    override def tell(message: LogEvent): Unit =
      if (message == null) throw new a.InvalidMessageException("Message must not be null")
      else print(message)

    def isLocal: Boolean = true

    def sendSystem(signal: SystemMessage): Unit = ()

    @throws(classOf[java.io.ObjectStreamException])
    protected def writeReplace(): AnyRef = serializedStandardOutLogger
  }

  private val serializedStandardOutLogger = new SerializedStandardOutLogger

  @SerialVersionUID(1L)
  private[typed] class SerializedStandardOutLogger extends Serializable {
    @throws(classOf[java.io.ObjectStreamException])
    private def readResolve(): AnyRef = StandardOutLogger
  }

  private val StandardOutLogger = new StandardOutLogger

  private val UnhandledMessageForwarder = Static[a.UnhandledMessage] {
    case a.UnhandledMessage(msg, sender, rcp) ⇒
      publish(Debug(rcp.path.toString, rcp.getClass, "unhandled message from " + sender + ": " + msg))
  }

  def startStdoutLogger(settings: Settings) {
    setUpStdoutLogger(settings)
    publish(Debug(simpleName(this), this.getClass, "StandardOutLogger started"))
  }

  def startDefaultLoggers(system: ActorSystemImpl[Nothing]) {
    val logName = simpleName(this) + "(" + system + ")"
    val level = levelFor(system.settings.untyped.LogLevel) getOrElse {
      // only log initialization errors directly with StandardOutLogger.print
      StandardOutLogger.print(Error(new LoggerException, logName, this.getClass, "unknown akka.loglevel " + system.settings.untyped.LogLevel))
      ErrorLevel
    }
    try {
      val defaultLoggers = system.settings.Loggers match {
        case Nil     ⇒ classOf[DefaultLogger].getName :: Nil
        case loggers ⇒ loggers
      }
      val myloggers =
        for {
          loggerName ← defaultLoggers
          if loggerName != StandardOutLogger.getClass.getName
        } yield {
          system.dynamicAccess.getClassFor[Logger](loggerName).map(addLogger(system, _, level, logName))
            .recover({
              case e ⇒ throw new akka.ConfigurationException(
                "Logger specified in config can't be loaded [" + loggerName +
                  "] due to [" + e.toString + "]", e)
            }).get
        }
      guard.withGuard {
        loggers = myloggers
        _logLevel = level
      }
      if (system.settings.untyped.DebugUnhandledMessage)
        subscribe(
          ActorRef(
            system.systemActorOf(UnhandledMessageForwarder, "UnhandledMessageForwarder")),
          classOf[a.UnhandledMessage])
      publish(Debug(logName, this.getClass, "Default Loggers started"))
      if (!(defaultLoggers contains StandardOutLogger.getClass.getName)) {
        unsubscribe(StandardOutLogger)
      }
    } catch {
      case e: Exception ⇒
        System.err.println("error while starting up loggers")
        e.printStackTrace()
        throw new akka.ConfigurationException("Could not start logger due to [" + e.toString + "]")
    }
  }

  def stopDefaultLoggers(system: ActorSystem[Nothing]) {
    val level = _logLevel // volatile access before reading loggers
    if (!(loggers contains StandardOutLogger)) {
      setUpStdoutLogger(system.settings)
      publish(Debug(simpleName(this), this.getClass, "shutting down: StandardOutLogger started"))
    }
    for {
      (logger, channel) ← loggers
    } {
      // this is very necessary, else you get infinite loop with DeadLetter
      unsubscribe(channel)
      import internal._
      logger.sorry.sendSystem(Terminate())
    }
    publish(Debug(simpleName(this), this.getClass, "all default loggers stopped"))
  }

  private def addLogger(
    system:  ActorSystemImpl[Nothing],
    clazz:   Class[_ <: Logger],
    level:   LogLevel,
    logName: String): (ActorRef[Logger.Command], ActorRef[LogEvent]) = {
    val name = "log" + system.loggerId() + "-" + simpleName(clazz)
    val logger = clazz.newInstance()
    val actor = ActorRef(system.systemActorOf(logger.initialBehavior, name, DispatcherFromConfig(system.settings.untyped.LoggersDispatcher)))
    import AskPattern._
    implicit val scheduler = system.scheduler
    val logChannel = try Await.result(actor ? (Logger.Initialize(this, _: ActorRef[ActorRef[LogEvent]])), timeout.duration) catch {
      case ex: TimeoutException ⇒
        publish(Warning(logName, this.getClass, "Logger " + name + " did not respond within " + timeout + " to InitializeLogger(bus)"))
        throw ex
    }
    AllLogLevels filter (level >= _) foreach (l ⇒ subscribe(logChannel, classFor(l)))
    publish(Debug(logName, this.getClass, "logger " + name + " started"))
    (actor, logChannel)
  }

  private val subscriptions = new SubclassifiedIndex[Class[_], ActorRef[Any]]()(subclassification)

  @volatile
  private var cache = Map.empty[Class[_], Set[ActorRef[Any]]]

  override def subscribe[T](subscriber: ActorRef[T], channel: Class[T]): Boolean = {
    if (subscriber eq null) throw new IllegalArgumentException("subscriber is null")
    if (debug) publish(e.Logging.Debug(simpleName(this), this.getClass, "subscribing " + subscriber + " to channel " + channel))
    unsubscriber ! Register(subscriber)
    subscriptions.synchronized {
      val diff = subscriptions.addValue(channel, subscriber.upcast[Any])
      addToCache(diff)
      diff.nonEmpty
    }
  }

  override def unsubscribe[T](subscriber: ActorRef[T], channel: Class[T]): Boolean = {
    if (subscriber eq null) throw new IllegalArgumentException("subscriber is null")
    val ret = subscriptions.synchronized {
      val diff = subscriptions.removeValue(channel, subscriber.upcast[Any])
      // removeValue(K, V) does not return the diff to remove from or add to the cache
      // but instead the whole set of keys and values that should be updated in the cache
      cache ++= diff
      diff.nonEmpty
    }
    if (debug) publish(e.Logging.Debug(simpleName(this), this.getClass, "unsubscribing " + subscriber + " from channel " + channel))
    unsubscriber ! UnregisterIfNoMoreSubscribedChannels(subscriber.upcast[Any])
    ret
  }

  override def unsubscribe[T](subscriber: ActorRef[T]) {
    if (subscriber eq null) throw new IllegalArgumentException("subscriber is null")
    subscriptions.synchronized {
      removeFromCache(subscriptions.removeValue(subscriber.upcast[Any]))
    }
    if (debug) publish(e.Logging.Debug(simpleName(this), this.getClass, "unsubscribing " + subscriber + " from all channels"))
    unsubscriber ! UnregisterIfNoMoreSubscribedChannels(subscriber.upcast[Any])
  }

  override def publish[T](event: T): Unit = {
    val c = event.asInstanceOf[AnyRef].getClass
    val recv =
      if (cache contains c) cache(c) // c will never be removed from cache
      else subscriptions.synchronized {
        if (cache contains c) cache(c)
        else {
          addToCache(subscriptions.addKey(c))
          cache(c)
        }
      }
    recv foreach (_ ! event)
  }

  /**
   * Expensive call! Avoid calling directly from event bus subscribe / unsubscribe.
   */
  private def hasSubscriptions(subscriber: ActorRef[Any]): Boolean =
    cache.values exists { _ contains subscriber }

  private def removeFromCache(changes: immutable.Seq[(Class[_], Set[ActorRef[Any]])]): Unit =
    cache = (cache /: changes) {
      case (m, (c, cs)) ⇒ m.updated(c, m.getOrElse(c, Set.empty[ActorRef[Any]]) diff cs)
    }

  private def addToCache(changes: immutable.Seq[(Class[_], Set[ActorRef[Any]])]): Unit =
    cache = (cache /: changes) {
      case (m, (c, cs)) ⇒ m.updated(c, m.getOrElse(c, Set.empty[ActorRef[Any]]) union cs)
    }
}

private[typed] object EventStreamImpl {

  sealed trait Command
  final case class Register(actor: ActorRef[Nothing]) extends Command
  final case class UnregisterIfNoMoreSubscribedChannels(actor: ActorRef[Any]) extends Command

  private val subclassification = new Subclassification[Class[_]] {
    def isEqual(x: Class[_], y: Class[_]) = x == y
    def isSubclass(x: Class[_], y: Class[_]) = y isAssignableFrom x
  }

  val StandardOutLoggerPath = a.RootActorPath(a.Address("akka.typed.internal", "StandardOutLogger"))

}
