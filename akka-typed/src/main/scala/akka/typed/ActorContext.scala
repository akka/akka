/**
 * Copyright (C) 2014-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import scala.concurrent.ExecutionContextExecutor
import java.util.Optional
import java.util.ArrayList

import akka.annotation.DoNotInherit
import akka.annotation.ApiMayChange

/**
 * This trait is not meant to be extended by user code. If you do so, you may
 * lose binary compatibility.
 */
@DoNotInherit
@ApiMayChange
trait ActorContext[T] extends javadsl.ActorContext[T] with scaladsl.ActorContext[T] {

  // FIXME can we simplify this weird hierarchy of contexts, e.g. problem with createAdapter

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
    spawn(behavior, name, EmptyDeploymentConfig)

  override def spawnAnonymous[U](behavior: akka.typed.Behavior[U]): akka.typed.ActorRef[U] =
    spawnAnonymous(behavior, EmptyDeploymentConfig)

  override def createAdapter[U](f: java.util.function.Function[U, T]): akka.typed.ActorRef[U] =
    spawnAdapter(f.apply _)

  override def createAdapter[U](f: java.util.function.Function[U, T], name: String): akka.typed.ActorRef[U] =
    spawnAdapter(f.apply _, name)
}
