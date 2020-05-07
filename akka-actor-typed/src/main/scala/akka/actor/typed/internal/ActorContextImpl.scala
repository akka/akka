/*
 * Copyright (C) 2017-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import java.time.Duration
import java.util.ArrayList
import java.util.Optional
import java.util.concurrent.CompletionStage

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.reflect.ClassTag
import scala.util.Try

import com.github.ghik.silencer.silent
import org.slf4j.Logger
import org.slf4j.LoggerFactory

import akka.actor.Address
import akka.actor.typed.internal.adapter.ActorSystemAdapter
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.util.{ BoxedType, Timeout }
import akka.util.JavaDurationConverters._
import akka.util.OptionVal
import akka.util.Timeout

/**
 * INTERNAL API
 */
@InternalApi private[akka] object ActorContextImpl {

  // single context for logging as there are a few things that are initialized
  // together that we can cache as long as the actor is alive
  object LoggingContext {
    def apply(logger: Logger, tags: Set[String], ctx: ActorContextImpl[_]): LoggingContext = {
      val tagsString =
        // "" means no tags
        if (tags.isEmpty) ""
        else
          // mdc can only contain string values, and we don't want to render that string
          // on each log entry or message, so do that up front here
          tags.mkString(",")

      val akkaSource = ctx.self.path.toString

      val akkaAddress =
        ctx.system match {
          case adapter: ActorSystemAdapter[_] => adapter.provider.addressString
          case _                              => Address("akka", ctx.system.name).toString
        }

      val sourceActorSystem = ctx.system.name

      new LoggingContext(logger, tagsString, akkaSource, sourceActorSystem, akkaAddress, hasCustomName = false)
    }
  }

  final case class LoggingContext(
      logger: Logger,
      tagsString: String,
      akkaSource: String,
      sourceActorSystem: String,
      akkaAddress: String,
      hasCustomName: Boolean) {
    // toggled once per message if logging is used to avoid having to
    // touch the mdc thread local for cleanup in the end
    var mdcUsed = false

    def withLogger(logger: Logger): LoggingContext = {
      val l = copy(logger = logger, hasCustomName = true)
      l.mdcUsed = mdcUsed
      l
    }
  }

}

/**
 * INTERNAL API
 */
@InternalApi private[akka] trait ActorContextImpl[T]
    extends TypedActorContext[T]
    with javadsl.ActorContext[T]
    with scaladsl.ActorContext[T] {

  import ActorContextImpl.LoggingContext

  // lazily initialized
  private var _logging: OptionVal[LoggingContext] = OptionVal.None

  private var messageAdapterRef: OptionVal[ActorRef[Any]] = OptionVal.None
  private var _messageAdapters: List[(Class[_], Any => T)] = Nil
  private var _timer: OptionVal[TimerSchedulerImpl[T]] = OptionVal.None

  // context-shared timer needed to allow for nested timer usage
  def timer: TimerSchedulerImpl[T] = _timer match {
    case OptionVal.Some(timer) => timer
    case OptionVal.None =>
      val timer = new TimerSchedulerImpl[T](this)
      _timer = OptionVal.Some(timer)
      timer
  }

  override private[akka] def hasTimer: Boolean = _timer.isDefined

  override private[akka] def cancelAllTimers(): Unit = {
    if (hasTimer)
      timer.cancelAll()
  }

  override def asJava: javadsl.ActorContext[T] = this

  override def asScala: scaladsl.ActorContext[T] = this

  override def getChild(name: String): Optional[ActorRef[Void]] =
    child(name) match {
      case Some(c) => Optional.of(c.unsafeUpcast[Void])
      case None    => Optional.empty()
    }

  override def getChildren: java.util.List[ActorRef[Void]] = {
    val c = children
    val a = new ArrayList[ActorRef[Void]](c.size)
    val i = c.iterator
    while (i.hasNext) a.add(i.next().unsafeUpcast[Void])
    a
  }

  override def getExecutionContext: ExecutionContextExecutor =
    executionContext

  override def getSelf: akka.actor.typed.ActorRef[T] =
    self

  override def getSystem: akka.actor.typed.ActorSystem[Void] =
    system.asInstanceOf[ActorSystem[Void]]

  private def loggingContext(): LoggingContext = {
    // lazy init of logging setup
    _logging match {
      case OptionVal.Some(l) => l
      case OptionVal.None =>
        val logClass = LoggerClass.detectLoggerClassFromStack(classOf[Behavior[_]])
        val logger = LoggerFactory.getLogger(logClass.getName)
        val l = LoggingContext(logger, classicActorContext.props.deploy.tags, this)
        _logging = OptionVal.Some(l)
        l
    }
  }

  override def log: Logger = {
    val logging = loggingContext()
    ActorMdc.setMdc(logging)
    logging.logger
  }

  override def getLog: Logger = log

  override def setLoggerName(name: String): Unit = {
    _logging = OptionVal.Some(loggingContext().withLogger(LoggerFactory.getLogger(name)))
  }

  override def setLoggerName(clazz: Class[_]): Unit =
    setLoggerName(clazz.getName)

  def hasCustomLoggerName: Boolean = loggingContext().hasCustomName

  // MDC is cleared (if used) from aroundReceive in ActorAdapter after processing each message
  override private[akka] def clearMdc(): Unit = {
    // avoid access to MDC ThreadLocal if not needed, see details in LoggingContext
    _logging match {
      case OptionVal.Some(ctx) if ctx.mdcUsed =>
        ActorMdc.clearMdc()
        ctx.mdcUsed = false
      case _ =>
    }
  }

  override def setReceiveTimeout(d: java.time.Duration, msg: T): Unit =
    setReceiveTimeout(d.asScala, msg)

  override def scheduleOnce[U](delay: java.time.Duration, target: ActorRef[U], msg: U): akka.actor.Cancellable =
    scheduleOnce(delay.asScala, target, msg)

  override def spawn[U](behavior: akka.actor.typed.Behavior[U], name: String): akka.actor.typed.ActorRef[U] =
    spawn(behavior, name, Props.empty)

  override def spawnAnonymous[U](behavior: akka.actor.typed.Behavior[U]): akka.actor.typed.ActorRef[U] =
    spawnAnonymous(behavior, Props.empty)

  // Scala API impl
  override def ask[Req, Res](target: RecipientRef[Req], createRequest: ActorRef[Res] => Req)(
      mapResponse: Try[Res] => T)(implicit responseTimeout: Timeout, classTag: ClassTag[Res]): Unit = {
    import akka.actor.typed.scaladsl.AskPattern._
    pipeToSelf((target.ask(createRequest))(responseTimeout, system.scheduler))(mapResponse)
  }

  // Java API impl
  @silent("never used") // resClass is just a pretend param
  override def ask[Req, Res](
      resClass: Class[Res],
      target: RecipientRef[Req],
      responseTimeout: Duration,
      createRequest: akka.japi.function.Function[ActorRef[Res], Req],
      applyToResponse: akka.japi.function.Function2[Res, Throwable, T]): Unit = {
    import akka.actor.typed.javadsl.AskPattern
    pipeToSelf(AskPattern.ask(target, (ref) => createRequest(ref), responseTimeout, system.scheduler), applyToResponse)
  }

  // Scala API impl
  def pipeToSelf[Value](future: Future[Value])(mapResult: Try[Value] => T): Unit = {
    future.onComplete(value => self.unsafeUpcast ! AdaptMessage(value, mapResult))(ExecutionContexts.parasitic)
  }

  // Java API impl
  def pipeToSelf[Value](
      future: CompletionStage[Value],
      applyToResult: akka.japi.function.Function2[Value, Throwable, T]): Unit = {
    future.whenComplete { (value, ex) =>
      if (ex != null)
        self.unsafeUpcast ! AdaptMessage(ex, applyToResult.apply(null.asInstanceOf[Value], _: Throwable))
      else self.unsafeUpcast ! AdaptMessage(value, applyToResult.apply(_: Value, null))
    }
  }

  private[akka] override def spawnMessageAdapter[U](f: U => T, name: String): ActorRef[U] =
    internalSpawnMessageAdapter(f, name)

  private[akka] override def spawnMessageAdapter[U](f: U => T): ActorRef[U] =
    internalSpawnMessageAdapter(f, name = "")

  /**
   * INTERNAL API: Needed to make Scala 2.12 compiler happy if spawnMessageAdapter is overloaded for scaladsl/javadsl.
   * Otherwise "ambiguous reference to overloaded definition" because Function is lambda.
   */
  @InternalApi private[akka] def internalSpawnMessageAdapter[U](f: U => T, name: String): ActorRef[U]

  override def messageAdapter[U: ClassTag](f: U => T): ActorRef[U] = {
    val messageClass = implicitly[ClassTag[U]].runtimeClass.asInstanceOf[Class[U]]
    internalMessageAdapter(messageClass, f)
  }

  override def messageAdapter[U](messageClass: Class[U], f: akka.japi.function.Function[U, T]): ActorRef[U] =
    internalMessageAdapter(messageClass, f.apply)

  private def internalMessageAdapter[U](messageClass: Class[U], f: U => T): ActorRef[U] = {
    // replace existing adapter for same class, only one per class is supported to avoid unbounded growth
    // in case "same" adapter is added repeatedly
    val boxedMessageClass = BoxedType(messageClass).asInstanceOf[Class[U]]
    _messageAdapters = (boxedMessageClass, f.asInstanceOf[Any => T]) ::
      _messageAdapters.filterNot { case (cls, _) => cls == boxedMessageClass }
    val ref = messageAdapterRef match {
      case OptionVal.Some(ref) => ref.asInstanceOf[ActorRef[U]]
      case OptionVal.None      =>
        // AdaptMessage is not really a T, but that is erased
        val ref =
          internalSpawnMessageAdapter[Any](msg => AdaptWithRegisteredMessageAdapter(msg).asInstanceOf[T], "adapter")
        messageAdapterRef = OptionVal.Some(ref)
        ref
    }
    ref.asInstanceOf[ActorRef[U]]
  }

  /**
   * INTERNAL API
   */
  @InternalApi private[akka] def messageAdapters: List[(Class[_], Any => T)] = _messageAdapters
}
