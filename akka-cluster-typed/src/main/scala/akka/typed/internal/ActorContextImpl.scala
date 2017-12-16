/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed
package internal

import akka.annotation.InternalApi
import java.util.Optional
import java.util.ArrayList
import scala.concurrent.ExecutionContextExecutor

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

  override def getChildren: java.util.List[akka.typed.ActorRef[Void]] = {
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

  override def getSelf: akka.typed.ActorRef[T] =
    self

  override def getSystem: akka.typed.ActorSystem[Void] =
    system.asInstanceOf[ActorSystem[Void]]

  override def spawn[U](behavior: akka.typed.Behavior[U], name: String): akka.typed.ActorRef[U] =
    spawn(behavior, name, Props.empty)

  override def spawnAnonymous[U](behavior: akka.typed.Behavior[U]): akka.typed.ActorRef[U] =
    spawnAnonymous(behavior, Props.empty)

  override def spawnAdapter[U](f: U ⇒ T, name: String): ActorRef[U] =
    internalSpawnAdapter(f, name)

  override def spawnAdapter[U](f: U ⇒ T): ActorRef[U] =
    internalSpawnAdapter(f, "")

  override def spawnAdapter[U](f: java.util.function.Function[U, T]): akka.typed.ActorRef[U] =
    internalSpawnAdapter(f.apply _, "")

  override def spawnAdapter[U](f: java.util.function.Function[U, T], name: String): akka.typed.ActorRef[U] =
    internalSpawnAdapter(f.apply _, name)

  /**
   * INTERNAL API: Needed to make Scala 2.12 compiler happy.
   * Otherwise "ambiguous reference to overloaded definition" because Function is lambda.
   */
  @InternalApi private[akka] def internalSpawnAdapter[U](f: U ⇒ T, _name: String): ActorRef[U]
}

