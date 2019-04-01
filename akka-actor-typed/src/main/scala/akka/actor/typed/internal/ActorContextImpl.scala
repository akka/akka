/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal

import java.time.Duration
import java.util.function.{ Function => JFunction }
import java.util.ArrayList
import java.util.Optional
import java.util.concurrent.CompletionStage
import java.util.function.BiConsumer
import java.util.function.BiFunction

import scala.concurrent.{ ExecutionContextExecutor, Future }
import scala.reflect.ClassTag
import scala.util.Try
import akka.annotation.InternalApi
import akka.util.OptionVal
import akka.util.Timeout
import akka.util.JavaDurationConverters._

/**
 * INTERNAL API
 */
@InternalApi private[akka] trait ActorContextImpl[T]
    extends TypedActorContext[T]
    with javadsl.ActorContext[T]
    with scaladsl.ActorContext[T] {

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

  override def getLog: Logger = log

  override def setReceiveTimeout(d: java.time.Duration, msg: T): Unit =
    setReceiveTimeout(d.asScala, msg)

  override def scheduleOnce[U](delay: java.time.Duration, target: ActorRef[U], msg: U): akka.actor.Cancellable =
    scheduleOnce(delay.asScala, target, msg)

  override def spawn[U](behavior: akka.actor.typed.Behavior[U], name: String): akka.actor.typed.ActorRef[U] =
    spawn(behavior, name, Props.empty)

  override def spawnAnonymous[U](behavior: akka.actor.typed.Behavior[U]): akka.actor.typed.ActorRef[U] =
    spawnAnonymous(behavior, Props.empty)

  // Scala API impl
  override def ask[Req, Res](target: RecipientRef[Req])(createRequest: ActorRef[Res] => Req)(
      mapResponse: Try[Res] => T)(implicit responseTimeout: Timeout, classTag: ClassTag[Res]): Unit = {
    import akka.actor.typed.scaladsl.AskPattern._
    pipeToSelf((target.ask(createRequest))(responseTimeout, system.scheduler))(mapResponse)
  }

  // Java API impl
  def ask[Req, Res](
      resClass: Class[Res],
      target: RecipientRef[Req],
      responseTimeout: Duration,
      createRequest: JFunction[ActorRef[Res], Req],
      applyToResponse: BiFunction[Res, Throwable, T]): Unit = {
    import akka.actor.typed.javadsl.AskPattern
    val message = new akka.japi.function.Function[ActorRef[Res], Req] {
      def apply(ref: ActorRef[Res]): Req = createRequest(ref)
    }
    pipeToSelf(AskPattern.ask(target, message, responseTimeout, system.scheduler), applyToResponse)
  }

  // Scala API impl
  def pipeToSelf[Value](future: Future[Value])(mapResult: Try[Value] => T): Unit = {
    future.onComplete(value => self.unsafeUpcast ! AdaptMessage(value, mapResult))
  }

  // Java API impl
  def pipeToSelf[Value](future: CompletionStage[Value], applyToResult: BiFunction[Value, Throwable, T]): Unit = {
    future.whenComplete(new BiConsumer[Value, Throwable] {
      def accept(value: Value, ex: Throwable): Unit = {
        if (value != null) self.unsafeUpcast ! AdaptMessage(value, applyToResult.apply(_: Value, null))
        if (ex != null)
          self.unsafeUpcast ! AdaptMessage(ex, applyToResult.apply(null.asInstanceOf[Value], _: Throwable))
      }
    })
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

  override def messageAdapter[U](messageClass: Class[U], f: JFunction[U, T]): ActorRef[U] =
    internalMessageAdapter(messageClass, f.apply)

  private def internalMessageAdapter[U](messageClass: Class[U], f: U => T): ActorRef[U] = {
    // replace existing adapter for same class, only one per class is supported to avoid unbounded growth
    // in case "same" adapter is added repeatedly
    _messageAdapters = (messageClass, f.asInstanceOf[Any => T]) ::
      _messageAdapters.filterNot { case (cls, _) => cls == messageClass }
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
