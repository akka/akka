/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package internal

import scala.annotation.{ tailrec, switch }
import scala.util.control.NonFatal
import scala.util.control.Exception.Catcher
import akka.event.Logging

/**
 * INTERNAL API
 */
private[typed] trait SupervisionMechanics[T] {
  import ActorCell._

  /*
   * INTERFACE WITH ACTOR CELL
   */
  protected def system: ActorSystem[Nothing]
  protected def initialBehavior: Behavior[T]
  protected def self: ActorRefImpl[T]
  protected def parent: ActorRefImpl[Nothing]
  protected def behavior: Behavior[T]
  protected def behavior_=(b: Behavior[T]): Unit
  protected def next(b: Behavior[T], msg: Any): Unit
  protected def terminatingMap: Map[String, ActorRefImpl[Nothing]]
  protected def stopAll(): Unit
  protected def setTerminating(): Unit
  protected def setClosed(): Unit
  protected def maySend: Boolean
  protected def ctx: ActorContext[T]
  protected def publish(e: Logging.LogEvent): Unit
  protected def clazz(obj: AnyRef): Class[_]

  // INTERFACE WITH DEATHWATCH
  protected def addWatcher(watchee: ActorRefImpl[Nothing], watcher: ActorRefImpl[Nothing]): Unit
  protected def remWatcher(watchee: ActorRefImpl[Nothing], watcher: ActorRefImpl[Nothing]): Unit
  protected def watchedActorTerminated(actor: ActorRefImpl[Nothing], failure: Throwable): Boolean
  protected def tellWatchersWeDied(): Unit
  protected def unwatchWatchedActors(): Unit

  /**
   * Process one system message and return whether further messages shall be processed.
   */
  protected def processSignal(message: SystemMessage): Boolean = {
    if (ActorCell.Debug) println(s"[${Thread.currentThread.getName}] $self processing system message $message")
    message match {
      case Watch(watchee, watcher)      ⇒ { addWatcher(watchee.sorryForNothing, watcher.sorryForNothing); true }
      case Unwatch(watchee, watcher)    ⇒ { remWatcher(watchee.sorryForNothing, watcher.sorryForNothing); true }
      case DeathWatchNotification(a, f) ⇒ watchedActorTerminated(a.sorryForNothing, f)
      case Create()                     ⇒ create()
      case Terminate()                  ⇒ terminate()
      case NoMessage                    ⇒ false // only here to suppress warning
    }
  }

  private[this] var _failed: Throwable = null
  protected def failed: Throwable = _failed

  protected def fail(thr: Throwable): Unit = {
    if (_failed eq null) _failed = thr
    publish(Logging.Error(thr, self.path.toString, getClass, thr.getMessage))
    if (maySend) self.sendSystem(Terminate())
  }

  private def create(): Boolean = {
    behavior = initialBehavior
    if (system.settings.untyped.DebugLifecycle)
      publish(Logging.Debug(self.path.toString, clazz(behavior), "started"))
    if (Behavior.isAlive(behavior)) next(behavior.management(ctx, PreStart), PreStart)
    else self.sendSystem(Terminate())
    true
  }

  private def terminate(): Boolean = {
    setTerminating()
    unwatchWatchedActors()
    stopAll()
    if (terminatingMap.isEmpty) {
      finishTerminate()
      false
    } else true
  }

  protected def finishTerminate(): Unit = {
    val a = behavior
    /*
     * The following order is crucial for things to work properly. Only change this if you're very confident and lucky.
     */
    try if (a ne null) a.management(ctx, PostStop)
    catch { case NonFatal(ex) ⇒ publish(Logging.Error(ex, self.path.toString, clazz(a), "failure during PostStop")) }
    finally try tellWatchersWeDied()
    finally try parent.sendSystem(DeathWatchNotification(self, failed))
    finally {
      behavior = null
      _failed = null
      setClosed()
      if (system.settings.untyped.DebugLifecycle)
        publish(Logging.Debug(self.path.toString, clazz(a), "stopped"))
    }
  }
}
