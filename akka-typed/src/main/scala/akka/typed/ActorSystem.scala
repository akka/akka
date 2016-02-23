/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import akka.event.EventStream
import scala.concurrent.ExecutionContext
import akka.actor.ActorRefProvider
import java.util.concurrent.ThreadFactory
import akka.actor.DynamicAccess
import akka.actor.ActorSystemImpl
import com.typesafe.config.Config
import akka.actor.ExtendedActorSystem
import com.typesafe.config.ConfigFactory
import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.Future
import akka.dispatch.Dispatchers

/**
 * An ActorSystem is home to a hierarchy of Actors. It is created using
 * [[ActorSystem$]] `apply` from a [[Props]] object that describes the root
 * Actor of this hierarchy and which will create all other Actors beneath it.
 * A system also implements the [[ActorRef]] type, and sending a message to
 * the system directs that message to the root Actor.
 */
abstract class ActorSystem[-T](_name: String) extends ActorRef[T] { this: ScalaActorRef[T] ⇒

  /**
   * INTERNAL API.
   *
   * Access to the underlying (untyped) ActorSystem.
   */
  private[akka] val untyped: ExtendedActorSystem

  /**
   * The name of this actor system, used to distinguish multiple ones within
   * the same JVM & class loader.
   */
  def name: String = _name

  /**
   * The core settings extracted from the supplied configuration.
   */
  def settings: akka.actor.ActorSystem.Settings = untyped.settings

  /**
   * Log the configuration.
   */
  def logConfiguration(): Unit = untyped.logConfiguration()

  /**
   * Start-up time in milliseconds since the epoch.
   */
  def startTime: Long = untyped.startTime

  /**
   * Up-time of this actor system in seconds.
   */
  def uptime: Long = untyped.uptime

  /**
   * Helper object for looking up configured dispatchers.
   */
  def dispatchers: Dispatchers = untyped.dispatchers

  /**
   * A ThreadFactory that can be used if the transport needs to create any Threads
   */
  def threadFactory: ThreadFactory = untyped.threadFactory

  /**
   * ClassLoader wrapper which is used for reflective accesses internally. This is set
   * to use the context class loader, if one is set, or the class loader which
   * loaded the ActorSystem implementation. The context class loader is also
   * set on all threads created by the ActorSystem, if one was set during
   * creation.
   */
  def dynamicAccess: DynamicAccess = untyped.dynamicAccess

  /**
   * The ActorRefProvider is the only entity which creates all actor references within this actor system.
   */
  def provider: ActorRefProvider = untyped.provider

  /**
   * The user guardian’s untyped [[akka.actor.ActorRef]].
   */
  private[akka] override def untypedRef: akka.actor.ActorRef = untyped.provider.guardian

  /**
   * Main event bus of this actor system, used for example for logging.
   */
  def eventStream: EventStream = untyped.eventStream

  /**
   * The default thread pool of this ActorSystem, configured with settings in `akka.actor.default-dispatcher`.
   */
  implicit def executionContext: ExecutionContextExecutor = untyped.dispatcher

  /**
   * Terminates this actor system. This will stop the guardian actor, which in turn
   * will recursively stop all its child actors, then the system guardian
   * (below which the logging actors reside).
   */
  def terminate(): Future[Terminated] = untyped.terminate().map(t ⇒ Terminated(ActorRef(t.actor)))

  /**
   * Returns a Future which will be completed after the ActorSystem has been terminated
   * and termination hooks have been executed.
   */
  def whenTerminated: Future[Terminated] = untyped.whenTerminated.map(t ⇒ Terminated(ActorRef(t.actor)))

  /**
   * The deadLetter address is a destination that will accept (and discard)
   * every message sent to it.
   */
  def deadLetters[U]: ActorRef[U] = deadLetterRef
  lazy private val deadLetterRef = ActorRef[Any](untyped.deadLetters)
}

object ActorSystem {
  private class Impl[T](_name: String, _config: Config, _cl: ClassLoader, _ec: Option[ExecutionContext], _p: Props[T])
    extends ActorSystem[T](_name) with ScalaActorRef[T] {
    override private[akka] val untyped: ExtendedActorSystem = new ActorSystemImpl(_name, _config, _cl, _ec, Some(Props.untyped(_p))).start()
  }

  private class Wrapper(val untyped: ExtendedActorSystem) extends ActorSystem[Nothing](untyped.name) with ScalaActorRef[Nothing]

  def apply[T](name: String, guardianProps: Props[T],
               config: Option[Config] = None,
               classLoader: Option[ClassLoader] = None,
               executionContext: Option[ExecutionContext] = None): ActorSystem[T] = {
    val cl = classLoader.getOrElse(akka.actor.ActorSystem.findClassLoader())
    val appConfig = config.getOrElse(ConfigFactory.load(cl))
    new Impl(name, appConfig, cl, executionContext, guardianProps)
  }

  def apply(untyped: akka.actor.ActorSystem): ActorSystem[Nothing] = new Wrapper(untyped.asInstanceOf[ExtendedActorSystem])
}
