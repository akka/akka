/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com/>
 */
package akka.typed
package adapter

import akka.{ actor ⇒ a, dispatch ⇒ d }
import akka.dispatch.sysmsg
import scala.concurrent.ExecutionContextExecutor
import akka.util.Timeout
import scala.concurrent.Future

/**
 * Lightweight wrapper for presenting an untyped ActorSystem to a Behavior (via the context).
 * Therefore it does not have a lot of vals, only the whenTerminated Future is cached after
 * its transformation because redoing that every time will add extra objects that persist for
 * a longer time; in all other cases the wrapper will just be spawned for a single call in
 * most circumstances.
 */
private[typed] class ActorSystemAdapter[-T](val untyped: a.ActorSystemImpl)
  extends ActorRef[T](a.RootActorPath(a.Address("akka", untyped.name)) / "user")
  with ActorSystem[T] with internal.ActorRefImpl[T] {

  // Members declared in akka.typed.ActorRef
  override def tell(msg: T): Unit = untyped.guardian ! msg
  override def isLocal: Boolean = true
  override def sendSystem(signal: internal.SystemMessage): Unit = sendSystemMessage(untyped.guardian, signal)

  // Members declared in akka.typed.ActorSystem
  override def deadLetters[U]: ActorRef[U] = ActorRefAdapter(untyped.deadLetters)
  override def dispatchers: Dispatchers = new Dispatchers {
    override def lookup(selector: DispatcherSelector): ExecutionContextExecutor =
      selector match {
        case DispatcherDefault(_)                 ⇒ untyped.dispatcher
        case DispatcherFromConfig(str, _)         ⇒ untyped.dispatchers.lookup(str)
        case DispatcherFromExecutionContext(_, _) ⇒ throw new UnsupportedOperationException("cannot use DispatcherFromExecutionContext with ActorSystemAdapter")
        case DispatcherFromExecutor(_, _)         ⇒ throw new UnsupportedOperationException("cannot use DispatcherFromExecutor with ActorSystemAdapter")
      }
    override def shutdown(): Unit = () // there was no shutdown in untyped Akka
  }
  override def dynamicAccess: a.DynamicAccess = untyped.dynamicAccess
  override def eventStream: EventStream = new EventStreamAdapter(untyped.eventStream)
  implicit override def executionContext: scala.concurrent.ExecutionContextExecutor = untyped.dispatcher
  override def log: akka.event.LoggingAdapter = untyped.log
  override def logConfiguration(): Unit = untyped.logConfiguration()
  override def logFilter: akka.event.LoggingFilter = untyped.logFilter
  override def name: String = untyped.name
  override def scheduler: akka.actor.Scheduler = untyped.scheduler
  override def settings: Settings = new Settings(untyped.settings)
  override def startTime: Long = untyped.startTime
  override def threadFactory: java.util.concurrent.ThreadFactory = untyped.threadFactory
  override def uptime: Long = untyped.uptime
  override def printTree: String = untyped.printTree

  import akka.dispatch.ExecutionContexts.sameThreadExecutionContext

  override def terminate(): scala.concurrent.Future[akka.typed.Terminated] =
    untyped.terminate().map(t ⇒ Terminated(ActorRefAdapter(t.actor))(null))(sameThreadExecutionContext)
  override lazy val whenTerminated: scala.concurrent.Future[akka.typed.Terminated] =
    untyped.whenTerminated.map(t ⇒ Terminated(ActorRefAdapter(t.actor))(null))(sameThreadExecutionContext)

  def systemActorOf[U](behavior: Behavior[U], name: String, deployment: DeploymentConfig)(implicit timeout: Timeout): Future[ActorRef[U]] = {
    val ref = untyped.systemActorOf(PropsAdapter(behavior, deployment), name)
    Future.successful(ref)
  }

}

private[typed] object ActorSystemAdapter {
  def apply(untyped: a.ActorSystem): ActorSystem[Nothing] = new ActorSystemAdapter(untyped.asInstanceOf[a.ActorSystemImpl])
}
