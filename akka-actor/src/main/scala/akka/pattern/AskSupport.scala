/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import java.util.concurrent.TimeoutException
import akka.util.Timeout
import annotation.tailrec
import akka.util.Unsafe
import akka.actor._
import akka.dispatch._

/**
 * This is what is used to complete a Future that is returned from an ask/? call,
 * when it times out.
 */
class AskTimeoutException(message: String, cause: Throwable) extends TimeoutException(message) {
  def this(message: String) = this(message, null: Throwable)
  override def getCause(): Throwable = cause
}

/**
 * This object contains implementation details of the “ask” pattern.
 */
trait AskSupport {

  /**
   * Import this implicit conversion to gain `?` and `ask` methods on
   * [[akka.actor.ActorRef]], which will defer to the
   * `ask(actorRef, message)(timeout)` method defined here.
   *
   * {{{
   * import akka.pattern.ask
   *
   * val future = actor ? message             // => ask(actor, message)
   * val future = actor ask message           // => ask(actor, message)
   * val future = actor.ask(message)(timeout) // => ask(actor, message)(timeout)
   * }}}
   *
   * All of the above use an implicit [[akka.actor.Timeout]].
   */
  implicit def ask(actorRef: ActorRef): AskableActorRef = new AskableActorRef(actorRef)

  /**
   * Sends a message asynchronously and returns a [[akka.dispatch.Future]]
   * holding the eventual reply message; this means that the target actor
   * needs to send the result to the `sender` reference provided. The Future
   * will be completed with an [[akka.pattern.AskTimeoutException]] after the
   * given timeout has expired; this is independent from any timeout applied
   * while awaiting a result for this future (i.e. in
   * `Await.result(..., timeout)`).
   *
   * <b>Warning:</b>
   * When using future callbacks, inside actors you need to carefully avoid closing over
   * the containing actor’s object, i.e. do not call methods or access mutable state
   * on the enclosing actor from within the callback. This would break the actor
   * encapsulation and may introduce synchronization bugs and race conditions because
   * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
   * there is not yet a way to detect these illegal accesses at compile time.
   *
   * <b>Recommended usage:</b>
   *
   * {{{
   *   val f = ask(worker, request)(timeout)
   *   flow {
   *     EnrichedRequest(request, f())
   *   } pipeTo nextActor
   * }}}
   *
   * [see [[akka.dispatch.Future]] for a description of `flow`]
   */
  def ask(actorRef: ActorRef, message: Any)(implicit timeout: Timeout): Future[Any] = actorRef match {
    case ref: InternalActorRef if ref.isTerminated ⇒
      actorRef.tell(message)
      Promise.failed(new AskTimeoutException("sending to terminated ref breaks promises"))(ref.provider.dispatcher)
    case ref: InternalActorRef ⇒
      val provider = ref.provider
      if (timeout.duration.length <= 0) {
        actorRef.tell(message)
        Promise.failed(new AskTimeoutException("not asking with negative timeout"))(provider.dispatcher)
      } else {
        val a = PromiseActorRef(provider, timeout)
        actorRef.tell(message, a)
        a.result
      }
    case _ ⇒ throw new IllegalArgumentException("incompatible ActorRef " + actorRef)
  }

  /**
   * Implementation detail of the “ask” pattern enrichment of ActorRef
   */
  private[akka] final class AskableActorRef(val actorRef: ActorRef) {

    /**
     * Sends a message asynchronously and returns a [[akka.dispatch.Future]]
     * holding the eventual reply message; this means that the target actor
     * needs to send the result to the `sender` reference provided. The Future
     * will be completed with an [[akka.pattern.AskTimeoutException]] after the
     * given timeout has expired; this is independent from any timeout applied
     * while awaiting a result for this future (i.e. in
     * `Await.result(..., timeout)`).
     *
     * <b>Warning:</b>
     * When using future callbacks, inside actors you need to carefully avoid closing over
     * the containing actor’s object, i.e. do not call methods or access mutable state
     * on the enclosing actor from within the callback. This would break the actor
     * encapsulation and may introduce synchronization bugs and race conditions because
     * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
     * there is not yet a way to detect these illegal accesses at compile time.
     *
     * <b>Recommended usage:</b>
     *
     * {{{
     *   flow {
     *     val f = worker.ask(request)(timeout)
     *     EnrichedRequest(request, f())
     *   } pipeTo nextActor
     * }}}
     *
     * [see the [[akka.dispatch.Future]] companion object for a description of `flow`]
     */
    def ask(message: Any)(implicit timeout: Timeout): Future[Any] = akka.pattern.ask(actorRef, message)(timeout)

    /**
     * Sends a message asynchronously and returns a [[akka.dispatch.Future]]
     * holding the eventual reply message; this means that the target actor
     * needs to send the result to the `sender` reference provided. The Future
     * will be completed with an [[akka.pattern.AskTimeoutException]] after the
     * given timeout has expired; this is independent from any timeout applied
     * while awaiting a result for this future (i.e. in
     * `Await.result(..., timeout)`).
     *
     * <b>Warning:</b>
     * When using future callbacks, inside actors you need to carefully avoid closing over
     * the containing actor’s object, i.e. do not call methods or access mutable state
     * on the enclosing actor from within the callback. This would break the actor
     * encapsulation and may introduce synchronization bugs and race conditions because
     * the callback will be scheduled concurrently to the enclosing actor. Unfortunately
     * there is not yet a way to detect these illegal accesses at compile time.
     *
     * <b>Recommended usage:</b>
     *
     * {{{
     *   flow {
     *     val f = worker ? request
     *     EnrichedRequest(request, f())
     *   } pipeTo nextActor
     * }}}
     *
     * [see the [[akka.dispatch.Future]] companion object for a description of `flow`]
     */
    def ?(message: Any)(implicit timeout: Timeout): Future[Any] = akka.pattern.ask(actorRef, message)(timeout)
  }
}

/**
 * Akka private optimized representation of the temporary actor spawned to
 * receive the reply to an "ask" operation.
 *
 * INTERNAL API
 */
private[akka] final class PromiseActorRef private (val provider: ActorRefProvider, val result: Promise[Any])
  extends MinimalActorRef {
  import PromiseActorRef._
  import AbstractPromiseActorRef.stateOffset

  /**
   * As an optimization for the common (local) case we only register this PromiseActorRef
   * with the provider when the `path` member is actually queried, which happens during
   * serialization (but also during a simple call to `toString`, `equals` or `hashCode`!).
   *
   * Defined states:
   * null                  => started, path not yet created
   * Registering           => currently creating temp path and registering it
   * path: ActorPath       => path is available and was registered
   * StoppedWithPath(path) => stopped, path available
   * Stopped               => stopped, path not yet created
   */
  @volatile
  private[this] var _stateDoNotCallMeDirectly: AnyRef = _

  @inline
  private def state: AnyRef = Unsafe.instance.getObjectVolatile(this, stateOffset)

  @inline
  private def updateState(oldState: AnyRef, newState: AnyRef): Boolean = Unsafe.instance.compareAndSwapObject(this, stateOffset, oldState, newState)

  @inline
  private def setState(newState: AnyRef): Unit = Unsafe.instance.putObjectVolatile(this, stateOffset, newState)

  override def getParent: InternalActorRef = provider.tempContainer

  /**
   * Contract of this method:
   * Must always return the same ActorPath, which must have
   * been registered if we haven't been stopped yet.
   */
  @tailrec
  def path: ActorPath = state match {
    case null ⇒
      if (updateState(null, Registering)) {
        var p: ActorPath = null
        try {
          p = provider.tempPath()
          provider.registerTempActor(this, p)
          p
        } finally { setState(p) }
      } else path
    case p: ActorPath       ⇒ p
    case StoppedWithPath(p) ⇒ p
    case Stopped ⇒
      // even if we are already stopped we still need to produce a proper path
      updateState(Stopped, StoppedWithPath(provider.tempPath()))
      path
    case Registering ⇒ path // spin until registration is completed
  }

  override def !(message: Any)(implicit sender: ActorRef = null): Unit = state match {
    case Stopped | _: StoppedWithPath ⇒ provider.deadLetters ! message
    case _ ⇒
      val completedJustNow = result.tryComplete {
        message match {
          case Status.Success(r) ⇒ Right(r)
          case Status.Failure(f) ⇒ Left(f)
          case other             ⇒ Right(other)
        }
      }
      if (!completedJustNow) provider.deadLetters ! message
  }

  override def sendSystemMessage(message: SystemMessage): Unit = {
    val self = this
    message match {
      case _: Terminate             ⇒ stop()
      case Watch(`self`, watcher)   ⇒ //FIXME IMPLEMENT
      case Unwatch(`self`, watcher) ⇒ //FIXME IMPLEMENT
      case _                        ⇒
    }
  }

  override def isTerminated: Boolean = state match {
    case Stopped | _: StoppedWithPath ⇒ true
    case _                            ⇒ false
  }

  @tailrec
  override def stop(): Unit = {
    def ensureCompleted(): Unit = if (!result.isCompleted) result.tryComplete(Left(new ActorKilledException("Stopped")))
    state match {
      case null ⇒ // if path was never queried nobody can possibly be watching us, so we don't have to publish termination either
        if (updateState(null, Stopped)) ensureCompleted() else stop()
      case p: ActorPath ⇒
        if (updateState(p, StoppedWithPath(p))) {
          try {
            ensureCompleted()
            val termination = Terminated(this)(stopped = true)
            // watchedBy foreach { w => w.tell(termination) }
            // watching foreach { w.sendSystemMessage(Unwatch(w, self)) }
          } finally {
            provider.unregisterTempActor(p)
          }
        } else stop()
      case Stopped | _: StoppedWithPath ⇒ // already stopped
      case Registering                  ⇒ stop() // spin until registration is completed before stopping
    }
  }
}

/**
 * INTERNAL API
 */
private[akka] object PromiseActorRef {
  private case object Registering
  private case object Stopped
  private case class StoppedWithPath(path: ActorPath)

  def apply(provider: ActorRefProvider, timeout: Timeout): PromiseActorRef = {
    val result = Promise[Any]()(provider.dispatcher)
    val a = new PromiseActorRef(provider, result)
    val f = provider.scheduler.scheduleOnce(timeout.duration) { result.tryComplete(Left(new AskTimeoutException("Timed out"))) }
    result onComplete { _ ⇒ try a.stop() finally f.cancel() }
    a
  }
}