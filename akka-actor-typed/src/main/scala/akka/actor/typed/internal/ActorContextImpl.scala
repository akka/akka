/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed
package internal

import java.util.function.BiFunction
import java.util.{ ArrayList, Optional, function }

import akka.annotation.InternalApi
import akka.util.Timeout

import scala.concurrent.ExecutionContextExecutor
import scala.reflect.ClassTag
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 */
@InternalApi private[akka] trait ActorContextImpl[T] extends ActorContext[T] with javadsl.ActorContext[T] with scaladsl.ActorContext[T] {

  override def asJava: javadsl.ActorContext[T] = this

  override def asScala: scaladsl.ActorContext[T] = this

  override def getChild(name: String): Optional[ActorRef[Void]] =
    child(name) match {
      case Some(c) ⇒ Optional.of(c.upcast[Void])
      case None    ⇒ Optional.empty()
    }

  override def getChildren: java.util.List[ActorRef[Void]] = {
    val c = children
    val a = new ArrayList[ActorRef[Void]](c.size)
    val i = c.iterator
    while (i.hasNext) a.add(i.next().upcast[Void])
    a
  }

  override def getExecutionContext: ExecutionContextExecutor =
    executionContext

  override def getMailboxCapacity: Int =
    mailboxCapacity

  override def getSelf: akka.actor.typed.ActorRef[T] =
    self

  override def getSystem: akka.actor.typed.ActorSystem[Void] =
    system.asInstanceOf[ActorSystem[Void]]

  override def spawn[U](behavior: akka.actor.typed.Behavior[U], name: String): akka.actor.typed.ActorRef[U] =
    spawn(behavior, name, Props.empty)

  override def spawnAnonymous[U](behavior: akka.actor.typed.Behavior[U]): akka.actor.typed.ActorRef[U] =
    spawnAnonymous(behavior, Props.empty)

  override def spawnAdapter[U](f: U ⇒ T, name: String): ActorRef[U] =
    internalSpawnAdapter(f, name)

  override def spawnAdapter[U](f: U ⇒ T): ActorRef[U] =
    internalSpawnAdapter(f, "")

  override def spawnAdapter[U](f: java.util.function.Function[U, T]): akka.actor.typed.ActorRef[U] =
    internalSpawnAdapter(f.apply, "")

  override def spawnAdapter[U](f: java.util.function.Function[U, T], name: String): akka.actor.typed.ActorRef[U] =
    internalSpawnAdapter(f.apply, name)

  // Scala API impl
  override def ask[Req, Res](otherActor: ActorRef[Req], createMessage: ActorRef[Res] ⇒ Req)(responseToOwnProtocol: Try[Res] ⇒ T)(implicit responseTimeout: Timeout, classTag: ClassTag[Res]): Unit = {
    import akka.actor.typed.scaladsl.AskPattern._
    (otherActor ? createMessage)(responseTimeout, system.scheduler).onComplete(res ⇒
      self.asInstanceOf[ActorRef[AnyRef]] ! new AskResponse(res, responseToOwnProtocol)
    )
  }

  // Java API impl
  def ask[Req, Res](resClass: Class[Res], otherActor: ActorRef[Req], responseTimeout: Timeout, createMessage: function.Function[ActorRef[Res], Req], responseToOwnProtocol: BiFunction[Res, Throwable, T]): Unit = {
    this.ask(otherActor, createMessage.apply) {
      case Success(message) ⇒ responseToOwnProtocol.apply(message, null)
      case Failure(ex)      ⇒ responseToOwnProtocol.apply(null.asInstanceOf[Res], ex)
    }(responseTimeout, ClassTag[Res](resClass))
  }

  /**
   * INTERNAL API: Needed to make Scala 2.12 compiler happy.
   * Otherwise "ambiguous reference to overloaded definition" because Function is lambda.
   */
  @InternalApi private[akka] def internalSpawnAdapter[U](f: U ⇒ T, _name: String): ActorRef[U]
}

