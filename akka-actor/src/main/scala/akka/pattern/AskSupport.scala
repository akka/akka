/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.pattern

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeoutException
import akka.actor.{ Terminated, Status, MinimalActorRef, InternalActorRef, ActorRef, ActorPath }
import akka.dispatch.{ Promise, Terminate, SystemMessage, Future }
import akka.event.DeathWatch
import akka.actor.ActorRefProvider
import akka.util.Timeout

/**
 * This is what is used to complete a Future that is returned from an ask/? call,
 * when it times out.
 */
class AskTimeoutException(message: String, cause: Throwable) extends TimeoutException {
  def this(message: String) = this(message, null: Throwable)
}

/**
 * This object contains implementation details of the “ask” pattern.
 */
object AskSupport {

  /**
   * Implementation detail of the “ask” pattern enrichment of ActorRef
   */
  private[akka] final class AskableActorRef(val actorRef: ActorRef) {

    /**
     * Sends a message asynchronously and returns a [[akka.dispatch.Future]]
     * holding the eventual reply message; this means that the target actor
     * needs to send the result to the `sender` reference provided. The Future
     * will be completed with an [[akka.actor.AskTimeoutException]] after the
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
     * will be completed with an [[akka.actor.AskTimeoutException]] after the
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

  /**
   * Akka private optimized representation of the temporary actor spawned to
   * receive the reply to an "ask" operation.
   */
  private[akka] final class PromiseActorRef(
    val provider: ActorRefProvider,
    val path: ActorPath,
    override val getParent: InternalActorRef,
    val result: Promise[Any],
    val deathWatch: DeathWatch) extends MinimalActorRef {

    final val running = new AtomicBoolean(true)

    override def !(message: Any)(implicit sender: ActorRef = null): Unit = if (running.get) message match {
      case Status.Success(r) ⇒ result.success(r)
      case Status.Failure(f) ⇒ result.failure(f)
      case other             ⇒ result.success(other)
    }

    override def sendSystemMessage(message: SystemMessage): Unit = message match {
      case _: Terminate ⇒ stop()
      case _            ⇒
    }

    override def isTerminated = result.isCompleted

    override def stop(): Unit = if (running.getAndSet(false)) {
      deathWatch.publish(Terminated(this))
    }
  }

  def createAsker(provider: ActorRefProvider, timeout: Timeout): PromiseActorRef = {
    val path = provider.tempPath()
    val result = Promise[Any]()(provider.dispatcher)
    val a = new PromiseActorRef(provider, path, provider.tempContainer, result, provider.deathWatch)
    provider.registerTempActor(a, path)
    val f = provider.scheduler.scheduleOnce(timeout.duration) { result.failure(new AskTimeoutException("Timed out")) }
    result onComplete { _ ⇒
      try { a.stop(); f.cancel() }
      finally { provider.unregisterTempActor(path) }
    }
    a
  }
}
