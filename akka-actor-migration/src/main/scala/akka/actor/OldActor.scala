/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.japi.Creator
import scala.util.Timeout
import akka.dispatch.Future
import akka.dispatch.OldFuture
import scala.util.Duration
import java.util.concurrent.TimeUnit
import akka.migration.AskableActorRef

/**
 * Migration replacement for `object akka.actor.Actor`.
 */
@deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
object OldActor {

  /**
   *  Creates an ActorRef out of the Actor with type T.
   *  It will be automatically started, i.e. remove old call to `start()`.
   *
   */
  @deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
  def actorOf[T <: Actor: Manifest]: ActorRef = actorOf(manifest[T].erasure.asInstanceOf[Class[_ <: Actor]])

  /**
   * Creates an ActorRef out of the Actor of the specified Class.
   * It will be automatically started, i.e. remove old call to `start()`.
   */
  @deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
  def actorOf(clazz: Class[_ <: Actor]): ActorRef = GlobalActorSystem.actorOf(Props(clazz))

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory function
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   *
   * It will be automatically started, i.e. remove old call to `start()`.
   */
  @deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
  def actorOf(factory: ⇒ Actor): ActorRef = GlobalActorSystem.actorOf(Props(factory))

  /**
   * Creates an ActorRef out of the Actor. Allows you to pass in a factory (Creator<Actor>)
   * that creates the Actor. Please note that this function can be invoked multiple
   * times if for example the Actor is supervised and needs to be restarted.
   * <p/>
   * JAVA API
   */
  @deprecated("use ActorRefFactory (ActorSystem or ActorContext) to create actors", "2.0")
  def actorOf(creator: Creator[Actor]): ActorRef = GlobalActorSystem.actorOf(Props(creator))

  @deprecated("OldActor.remote should not be used", "2.0")
  lazy val remote: OldRemoteSupport = new OldRemoteSupport
}

@deprecated("use Actor", "2.0")
abstract class OldActor extends Actor {

  implicit def askTimeout: Timeout = akka.migration.askTimeout

  implicit def future2OldFuture[T](future: Future[T]): OldFuture[T] = akka.migration.future2OldFuture(future)

  implicit def actorRef2OldActorRef(actorRef: ActorRef) = new OldActorRef(actorRef)

  implicit def askableActorRef(actorRef: ActorRef): AskableActorRef = new AskableActorRef(actorRef)

  @deprecated("Use context.become instead", "2.0")
  def become(behavior: Receive, discardOld: Boolean = true) = context.become(behavior, discardOld)

  @deprecated("Use context.unbecome instead", "2.0")
  def unbecome() = context.unbecome()

  class OldActorRef(actorRef: ActorRef) {
    @deprecated("Actors are automatically started when creatd, i.e. remove old call to start()", "2.0")
    def start(): ActorRef = actorRef

    @deprecated("Stop with ActorSystem or ActorContext instead", "2.0")
    def exit() = stop()

    @deprecated("Stop with ActorSystem or ActorContext instead", "2.0")
    def stop(): Unit = context.stop(actorRef)

    @deprecated("Use context.getReceiveTimeout instead", "2.0")
    def getReceiveTimeout(): Option[Long] = context.receiveTimeout.map(_.toMillis)

    @deprecated("Use context.setReceiveTimeout instead", "2.0")
    def setReceiveTimeout(timeout: Long) = context.setReceiveTimeout(Duration(timeout, TimeUnit.MILLISECONDS))

    @deprecated("Use context.getReceiveTimeout instead", "2.0")
    def receiveTimeout: Option[Long] = getReceiveTimeout()

    @deprecated("Use context.setReceiveTimeout instead", "2.0")
    def receiveTimeout_=(timeout: Option[Long]) = setReceiveTimeout(timeout.getOrElse(0L))

    @deprecated("Use self.isTerminated instead", "2.0")
    def isShutdown: Boolean = self.isTerminated

    @deprecated("Use sender instead", "2.0")
    def channel() = context.sender

    @deprecated("Use sender instead", "2.0")
    def sender() = Some(context.sender)

    @deprecated("Use sender ! instead", "2.0")
    def reply(message: Any) = context.sender.!(message, context.self)

    @deprecated("Use sender ! instead", "2.0")
    def tryReply(message: Any): Boolean = {
      reply(message)
      true
    }

    @deprecated("Use sender ! instead", "2.0")
    def tryTell(message: Any)(implicit sender: ActorRef = context.self): Boolean = {
      actorRef.!(message)(sender)
      true
    }

    @deprecated("Use sender ! akka.actor.Status.Failure(e) instead", "2.0")
    def sendException(ex: Throwable): Boolean = {
      context.sender.!(akka.actor.Status.Failure(ex), context.self)
      true
    }
  }
}

class OldRemoteSupport {

  @deprecated("remote.start is not needed", "2.0")
  def start() {}

  @deprecated("remote.start is not needed, use configuration to specify RemoteActorRefProvider, host and port", "2.0")
  def start(host: String, port: Int) {}

  @deprecated("remote.start is not needed, use configuration to specify RemoteActorRefProvider, host and port", "2.0")
  def start(host: String, port: Int, loader: ClassLoader) {}

  @deprecated("remote.shutdown is not needed", "2.0")
  def shutdown() {}

  @deprecated("use actorFor in ActorRefProvider (ActorSystem or ActorContext) instead", "2.0")
  def actorFor(classNameOrServiceId: String, hostname: String, port: Int): ActorRef =
    GlobalActorSystem.actorFor("akka://%s@%s:%s/user/%s".format(GlobalActorSystem.name, hostname, port, classNameOrServiceId))

  @deprecated("use actorFor in ActorRefProvider (ActorSystem or ActorContext) instead", "2.0")
  def actorFor(classNameOrServiceId: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(classNameOrServiceId, hostname, port)

  @deprecated("use actorFor in ActorRefProvider (ActorSystem or ActorContext) instead", "2.0")
  def actorFor(serviceId: String, className: String, hostname: String, port: Int): ActorRef =
    actorFor(serviceId, hostname, port)

  @deprecated("use actorFor in ActorRefProvider (ActorSystem or ActorContext) instead", "2.0")
  def actorFor(serviceId: String, className: String, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(serviceId, hostname, port)

  @deprecated("use actorFor in ActorRefProvider (ActorSystem or ActorContext) instead", "2.0")
  def actorFor(classNameOrServiceId: String, timeout: Long, hostname: String, port: Int): ActorRef =
    actorFor(classNameOrServiceId, hostname, port)

  @deprecated("use actorFor in ActorRefProvider (ActorSystem or ActorContext) instead", "2.0")
  def actorFor(classNameOrServiceId: String, timeout: Long, hostname: String, port: Int, loader: ClassLoader): ActorRef =
    actorFor(classNameOrServiceId, hostname, port)

  @deprecated("use actorFor in ActorRefProvider (ActorSystem or ActorContext) instead", "2.0")
  def actorFor(serviceId: String, className: String, timeout: Long, hostname: String, port: Int): ActorRef =
    actorFor(serviceId, hostname, port)

}