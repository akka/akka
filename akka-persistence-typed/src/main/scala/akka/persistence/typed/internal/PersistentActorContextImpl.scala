/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed
package internal

import java.time.Duration
import java.util
import java.util.{ Optional, function }
import java.util.function.BiFunction

import akka.actor.Cancellable
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Logger, Props }
import akka.actor.typed.scaladsl.ActorContext
import akka.annotation.InternalApi
import akka.util.Timeout

import scala.concurrent.ExecutionContextExecutor
import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag
import scala.util.Try

/**
 * INTERNAL API
 */
@InternalApi private[akka] case class PersistentActorContextImpl[T](ac: ActorContext[T], lastSequenceNr: Long) extends PersistentActorContext[T] with javadsl.PersistentActorContext[T] with scaladsl.PersistentActorContext[T] with ActorContext[T] with akka.actor.typed.javadsl.ActorContext[T] {

  def getLastSequenceNr: Long = lastSequenceNr

  def asJava: javadsl.PersistentActorContext[T] = this

  def asScala: scaladsl.PersistentActorContext[T] = this

  def self: ActorRef[T] = ac.self

  def system: ActorSystem[Nothing] = ac.system

  def log: Logger = ac.log

  def children: Iterable[ActorRef[Nothing]] = ac.children

  def child(name: String): Option[ActorRef[Nothing]] = ac.child(name)

  def spawnAnonymous[U](behavior: Behavior[U], props: Props): ActorRef[U] = ac.spawnAnonymous(behavior)

  def spawn[U](behavior: Behavior[U], name: String, props: Props): ActorRef[U] = ac.spawn(behavior, name, props)

  def stop[U](child: ActorRef[U]): Unit = ac.stop(child)

  def watch[U](other: ActorRef[U]): Unit = ac.watch(other)

  def watchWith[U](other: ActorRef[U], msg: T): Unit = ac.watchWith(other, msg)

  def unwatch[U](other: ActorRef[U]): Unit = ac.unwatch(other)

  def setReceiveTimeout(d: FiniteDuration, msg: T): Unit = ac.setReceiveTimeout(d, msg)

  def cancelReceiveTimeout(): Unit = ac.cancelReceiveTimeout()

  def schedule[U](delay: FiniteDuration, target: ActorRef[U], msg: U): Cancellable = ac.schedule(delay, target, msg)

  implicit def executionContext: ExecutionContextExecutor = ac.executionContext

  private[akka] def spawnMessageAdapter[U](f: U ⇒ T, name: String) = ac.spawnMessageAdapter(f, name)

  private[akka] def spawnMessageAdapter[U](f: U ⇒ T) = ac.spawnMessageAdapter(f)

  def messageAdapter[U: ClassTag](f: U ⇒ T): ActorRef[U] = ac.messageAdapter(f)

  def ask[Req, Res](otherActor: ActorRef[Req])(createRequest: ActorRef[Res] ⇒ Req)(mapResponse: Try[Res] ⇒ T)(implicit responseTimeout: Timeout, classTag: ClassTag[Res]): Unit = ac.ask(otherActor)(createRequest)(mapResponse)

  private def acAsJava = {
    ac.asJava
  }

  def getSelf: ActorRef[T] = acAsJava.getSelf

  def getSystem: ActorSystem[Void] = acAsJava.getSystem

  def getLog: Logger = acAsJava.getLog

  def getChildren: util.List[ActorRef[Void]] = acAsJava.getChildren

  def getChild(name: String): Optional[ActorRef[Void]] = acAsJava.getChild(name)

  def spawnAnonymous[U](behavior: Behavior[U]): ActorRef[U] = acAsJava.spawnAnonymous(behavior)

  def spawn[U](behavior: Behavior[U], name: String): ActorRef[U] = acAsJava.spawn(behavior, name)

  def setReceiveTimeout(d: Duration, msg: T): Unit = acAsJava.setReceiveTimeout(d, msg)

  def schedule[U](delay: Duration, target: ActorRef[U], msg: U): Cancellable = acAsJava.schedule(delay, target, msg)

  def getExecutionContext: ExecutionContextExecutor = acAsJava.getExecutionContext

  def messageAdapter[U](messageClass: Class[U], f: function.Function[U, T]): ActorRef[U] = acAsJava.messageAdapter(messageClass, f)

  def ask[Req, Res](resClass: Class[Res], otherActor: ActorRef[Req], responseTimeout: Timeout, createRequest: function.Function[ActorRef[Res], Req], applyToResponse: BiFunction[Res, Throwable, T]): Unit = acAsJava.ask(resClass, otherActor, responseTimeout, createRequest, applyToResponse)
}

