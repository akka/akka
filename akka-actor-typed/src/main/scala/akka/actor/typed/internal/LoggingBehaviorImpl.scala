/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.internal

import akka.actor.Cancellable
import akka.actor.typed.internal.adapter.ActorContextAdapter
import akka.actor.typed.{ ActorContext, ActorRef, ActorSystem, Behavior, ExtensibleBehavior, Props, Signal }
import akka.event._
import akka.util.OptionVal
import akka.actor.typed.{ ActorContext ⇒ AC }
import akka.actor.typed.scaladsl.{ ActorContext ⇒ SAC }
import akka.annotation.InternalApi
import akka.event.Logging.MDC

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration

/**
 * INTERNAL API
 *
 * Regular logging is supported directly on the ActorContext, but more advanced logging needs are covered here
 */
@InternalApi object LoggingBehaviorImpl {

  /**
   * MDC logging support
   *
   * @param setupMdc Is invoked before each message is handled, allowing to setup MDC, MDC is cleared after
   *                 each message processing by the inner behavior is done.
   * @param behavior The actual behavior handling the messages, the MDC is used for the log entries logged through
   *                 `ActorContext.log`
   */
  def withMdc[T](
    setupMdc: T ⇒ MDC,
    behavior: Behavior[T]): Behavior[T] =
    Behavior.DeferredBehavior(ctx ⇒
      new WithMDC[T](new MDCActorContext[T](ctx.asInstanceOf[ActorContextAdapter[T]]), setupMdc, behavior).init()
    )

  /**
   * Decorates the actual actor context with the [[DiagnosticLoggingAdapter]] as log
   */
  private final class MDCActorContext[T](actual: ActorContextAdapter[T]) extends ActorContextImpl[T] {

    private[akka] def internalSpawnAdapter[U](f: U ⇒ T, _name: String) = actual.internalSpawnAdapter(f, _name)
    def self: ActorRef[T] = actual.self
    def mailboxCapacity: Int = actual.mailboxCapacity
    def system: ActorSystem[Nothing] = actual.system

    // lazily created since inner logging adapter is
    private var _diagnosticLoggingAdapter: OptionVal[DiagnosticLoggingAdapter] = OptionVal.None
    def diagnosticLoggingAdapter: DiagnosticLoggingAdapter = {
      _diagnosticLoggingAdapter match {
        case OptionVal.Some(a) ⇒ a
        case OptionVal.None ⇒
          import scala.language.existentials
          // FIXME the clazz here probably does not make much sense
          val untypedSystem = actual.untyped.system
          val (str, clazz) = LogSource(actual.untyped.self, untypedSystem)
          val b = new DiagnosticMarkerBusLoggingAdapter(untypedSystem.eventStream, str, clazz, system.logFilter)
          _diagnosticLoggingAdapter = OptionVal.Some(b)
          b
      }
    }

    def log: DiagnosticLoggingAdapter = diagnosticLoggingAdapter
    def children: Iterable[ActorRef[Nothing]] = actual.children
    def child(name: String): Option[ActorRef[Nothing]] = actual.child(name)
    def spawnAnonymous[U](behavior: Behavior[U], props: Props): ActorRef[U] = actual.spawnAnonymous(behavior, props)
    def spawn[U](behavior: Behavior[U], name: String, props: Props): ActorRef[U] = actual.spawn(behavior, name, props)
    def stop[U](child: ActorRef[U]): Boolean = actual.stop(child)
    def watch[U](other: ActorRef[U]): Unit = actual.watch(other)
    def watchWith[U](other: ActorRef[U], msg: T): Unit = actual.watchWith(other, msg)
    def unwatch[U](other: ActorRef[U]): Unit = actual.unwatch(other)
    def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = actual.setReceiveTimeout(d, msg)
    def cancelReceiveTimeout(): Unit = actual.cancelReceiveTimeout()
    def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): Cancellable = actual.schedule(delay, target, msg)
    implicit def executionContext: ExecutionContextExecutor = actual.executionContext
  }

  /**
   * Provides an DiagnosticLoggingAdapter in place of the
   */
  // FIXME should we have one setupMdc callback for signals as well??
  private final case class WithMDC[T](
    mdcCtx:   MDCActorContext[T],
    setupMdc: T ⇒ MDC,
    behavior: Behavior[T]) extends ExtensibleBehavior[T] {

    def init(): Behavior[T] = {
      // initial defer also uses the MDC context
      withMdc(Behavior.validateAsInitial(Behavior.undefer(behavior, mdcCtx)))
    }

    // FIXME allocating a new wrapper for each message, should/can we avoid it?
    private def withMdc(behavior: Behavior[T]) = copy(behavior = behavior)

    def receiveSignal(ctx: AC[T], signal: Signal): Behavior[T] = {
      Behavior.wrap(behavior, Behavior.interpretSignal(behavior, mdcCtx, signal), mdcCtx)(withMdc)
    }

    def receiveMessage(ctx: AC[T], msg: T): Behavior[T] = {
      try {
        mdcCtx.diagnosticLoggingAdapter.mdc(setupMdc(msg))
        Behavior.wrap(behavior, Behavior.interpretMessage(behavior, mdcCtx, msg), mdcCtx)(withMdc)
      } finally {
        mdcCtx.diagnosticLoggingAdapter.clearMDC()
      }
    }

  }
}
