/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal
package adapter

import java.lang.reflect.InvocationTargetException

import akka.actor.ActorInitializationException
import akka.actor.typed.internal.adapter.ActorAdapter.TypedActorFailedException

import scala.annotation.tailrec
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import akka.{ actor ⇒ untyped }
import akka.annotation.InternalApi
import akka.util.OptionVal

/**
 * INTERNAL API
 */
@InternalApi private[typed] object ActorAdapter {

  /**
   * Thrown to indicate that a Behavior has failed so that the parent gets
   * the cause and can fill in the cause in the `ChildFailed` signal
   * Wrapped to avoid it being logged as the typed supervision will already
   * have logged it.
   */
  final case class TypedActorFailedException(cause: Throwable) extends RuntimeException
}

/**
 * INTERNAL API
 */
@InternalApi private[typed] class ActorAdapter[T](_initialBehavior: Behavior[T]) extends untyped.Actor with untyped.ActorLogging {
  import Behavior._

  protected var behavior: Behavior[T] = _initialBehavior

  private var _ctx: ActorContextAdapter[T] = _
  def ctx: ActorContextAdapter[T] =
    if (_ctx ne null) _ctx
    else throw new IllegalStateException("Context was accessed before typed actor was started.")

  /**
   * Failures from failed children, that were stopped through untyped supervision, this is what allows us to pass
   * child exception in Terminated for direct children.
   */
  private var failures: Map[untyped.ActorRef, Throwable] = Map.empty

  def receive: Receive = running

  def running: Receive = {
    case untyped.Terminated(ref) ⇒
      val msg =
        if (failures contains ref) {
          val ex = failures(ref)
          failures -= ref
          ChildFailed(ActorRefAdapter(ref), ex)
        } else Terminated(ActorRefAdapter(ref))
      next(Behavior.interpretSignal(behavior, ctx, msg), msg)
    case untyped.ReceiveTimeout ⇒
      next(Behavior.interpretMessage(behavior, ctx, ctx.receiveTimeoutMsg), ctx.receiveTimeoutMsg)
    case wrapped: AdaptMessage[Any, T] @unchecked ⇒
      withSafelyAdapted(() ⇒ wrapped.adapt()) {
        case AdaptWithRegisteredMessageAdapter(msg) ⇒
          adaptAndHandle(msg)
        case msg: T @unchecked ⇒
          handleMessage(msg)
      }
    case AdaptWithRegisteredMessageAdapter(msg) ⇒
      adaptAndHandle(msg)
    case msg: T @unchecked ⇒
      handleMessage(msg)
  }

  private def handleMessage(msg: T): Unit = {
    next(Behavior.interpretMessage(behavior, ctx, msg), msg)
  }

  private def next(b: Behavior[T], msg: Any): Unit = {
    if (Behavior.isUnhandled(b)) unhandled(msg)
    else {
      b match {
        case s: StoppedBehavior[T] ⇒
          // use StoppedBehavior with previous behavior or an explicitly given `postStop` behavior
          // until Terminate is received, i.e until postStop is invoked, and there PostStop
          // will be signaled to the previous/postStop behavior
          s.postStop match {
            case OptionVal.None ⇒
              // use previous as the postStop behavior
              behavior = new Behavior.StoppedBehavior(OptionVal.Some(behavior))
            case OptionVal.Some(postStop) ⇒
              // use the given postStop behavior, but canonicalize it
              behavior = new Behavior.StoppedBehavior(OptionVal.Some(Behavior.canonicalize(postStop, behavior, ctx)))
          }
          context.stop(self)
        case f: FailedBehavior ⇒
          // For the parent untyped supervisor to pick up the exception
          throw new TypedActorFailedException(f.cause)
        case _ ⇒
          behavior = Behavior.canonicalize(b, behavior, ctx)
      }
    }
  }

  private def adaptAndHandle(msg: Any): Unit = {
    @tailrec def handle(adapters: List[(Class[_], Any ⇒ T)]): Unit = {
      adapters match {
        case Nil ⇒
          // no adapter function registered for message class
          unhandled(msg)
        case (clazz, f) :: tail ⇒
          if (clazz.isAssignableFrom(msg.getClass)) {
            withSafelyAdapted(() ⇒ f(msg))(handleMessage)
          } else
            handle(tail) // recursive
      }
    }
    handle(ctx.messageAdapters)
  }

  private def withSafelyAdapted[U, V](adapt: () ⇒ U)(body: U ⇒ V): Unit = {
    Try(adapt()) match {
      case Success(a) ⇒
        body(a)
      case Failure(ex) ⇒
        log.error(ex, "Exception thrown out of adapter. Stopping myself.")
        context.stop(self)
    }
  }

  override def unhandled(msg: Any): Unit = msg match {
    case Terminated(ref) ⇒ throw DeathPactException(ref)
    case _: Signal       ⇒ // that's ok
    case other           ⇒ super.unhandled(other)
  }

  override val supervisorStrategy = untyped.OneForOneStrategy(loggingEnabled = false) {
    case TypedActorFailedException(cause) ⇒
      // These have already been optionally logged by typed supervision
      recordChildFailure(cause)
      untyped.SupervisorStrategy.Stop
    case ex ⇒
      recordChildFailure(ex)
      val logMessage = ex match {
        case e: ActorInitializationException if e.getCause ne null ⇒ e.getCause match {
          case ex: InvocationTargetException if ex.getCause ne null ⇒ ex.getCause.getMessage
          case ex ⇒ ex.getMessage
        }
        case e ⇒ e.getMessage
      }
      // log at Error as that is what the supervision strategy would have done.
      log.error(ex, logMessage)
      untyped.SupervisorStrategy.Stop
  }

  private def recordChildFailure(ex: Throwable): Unit = {
    val ref = sender()
    if (context.asInstanceOf[untyped.ActorCell].isWatching(ref)) {
      failures = failures.updated(ref, ex)
    }
  }

  override def preStart(): Unit =
    if (!isAlive(behavior)) {
      if (behavior == Behavior.stopped) context.stop(self)
      else {
        // post stop hook may touch context
        initializeContext()
        context.stop(self)
      }
    } else
      start()

  protected def start(): Unit = {
    context.become(running)
    initializeContext()
    behavior = validateAsInitial(Behavior.start(behavior, ctx))
    if (!isAlive(behavior)) context.stop(self)
  }

  override def preRestart(reason: Throwable, message: Option[Any]): Unit = {
    Behavior.interpretSignal(behavior, ctx, PreRestart)
    behavior = Behavior.stopped
  }

  override def postRestart(reason: Throwable): Unit = {
    initializeContext()
    behavior = validateAsInitial(Behavior.start(behavior, ctx))
    if (!isAlive(behavior)) context.stop(self)
  }

  override def postStop(): Unit = {
    behavior match {
      case null                   ⇒ // skip PostStop
      case _: DeferredBehavior[_] ⇒
      // Do not undefer a DeferredBehavior as that may cause creation side-effects, which we do not want on termination.
      case s: StoppedBehavior[_] ⇒ s.postStop match {
        case OptionVal.Some(postStop) ⇒ Behavior.interpretSignal(postStop, ctx, PostStop)
        case OptionVal.None           ⇒ // no postStop behavior defined
      }
      case b ⇒ Behavior.interpretSignal(b, ctx, PostStop)
    }

    behavior = Behavior.stopped
  }

  protected def initializeContext(): Unit = {
    _ctx = new ActorContextAdapter[T](context)
  }
}

/**
 * INTERNAL API
 *
 * A special adapter for the guardian which will defer processing until a special `Start` signal has been received.
 * That will allow to defer typed processing until the untyped ActorSystem has completely started up.
 */
@InternalApi
private[typed] class GuardianActorAdapter[T](_initialBehavior: Behavior[T]) extends ActorAdapter[T](_initialBehavior) {
  import Behavior._

  override def preStart(): Unit =
    if (!isAlive(behavior))
      context.stop(self)
    else
      context.become(waitingForStart(Nil))

  def waitingForStart(stashed: List[Any]): Receive = {
    case GuardianActorAdapter.Start ⇒
      start()

      stashed.reverse.foreach(receive)
    case other ⇒
      // unlikely to happen but not impossible
      context.become(waitingForStart(other :: stashed))
  }

  override def postRestart(reason: Throwable): Unit = {
    initializeContext()

    super.postRestart(reason)
  }

  override def postStop(): Unit = {
    initializeContext()

    super.postStop()
  }
}
/**
 * INTERNAL API
 */
@InternalApi private[typed] object GuardianActorAdapter {
  case object Start

}
