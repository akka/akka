/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed
package internal

import java.util.concurrent.TimeoutException

import akka.annotation.InternalApi
import java.util.{ ArrayList, Optional }
import java.util.function.{ Function ⇒ JFunction }

import akka.util.Timeout
import scala.concurrent.ExecutionContextExecutor
import scala.util.{ Failure, Success, Try }

import akka.Done

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

  /**
   * INTERNAL API: Needed to make Scala 2.12 compiler happy.
   * Otherwise "ambiguous reference to overloaded definition" because Function is lambda.
   */
  @InternalApi private[akka] def internalSpawnAdapter[U](f: U ⇒ T, _name: String): ActorRef[U]

  // Scala impl
  override def ask[Req, Res](otherActor: ActorRef[Req], createMessage: ActorRef[Res] ⇒ Req)(responseToOwnProtocol: Try[Res] ⇒ T)(implicit timeout: Timeout): Unit = {

    // note that the adapters are running _in_ this actor so there is no concurrency

    var replyTo: ActorRef[Res] = null
    var reqMsgClassName: String = null

    val timeoutDone: ActorRef[Done] = spawnAdapter { _ ⇒
      stop(replyTo)
      val exc = new TimeoutException(s"Ask timed out on [$otherActor] after [${timeout.duration.toMillis} ms]. " +
        s"Sender[$self] sent message of type [$reqMsgClassName].")
      responseToOwnProtocol(Failure(exc))
    }

    import akka.actor.typed.scaladsl.adapter._
    val timeoutTask = system.scheduler.scheduleOnce(timeout.duration, timeoutDone.toUntyped, Done)(executionContext)

    replyTo = spawnAdapter[Res] { rsp: Res ⇒
      timeoutTask.cancel()
      stop(timeoutDone)
      responseToOwnProtocol(Success(rsp))
    }

    val req = createMessage(replyTo)
    reqMsgClassName = req.getClass.getName // TODO performance overhead? only used for the TimeoutExc
    otherActor ! req
  }

  // Java impl
  override def ask[Req, Res](
    otherActor:            ActorRef[Req],
    createMessage:         JFunction[ActorRef[Res], Req],
    responseToOwnProtocol: JFunction[Res, T],
    failureToOwnProtocol:  JFunction[Throwable, T],
    responseTimeout:       Timeout
  ): Unit = {

    val f: Try[Res] ⇒ T = (rsp ⇒ rsp match {
      case Success(r) ⇒ responseToOwnProtocol.apply(r)
      case Failure(e) ⇒ failureToOwnProtocol.apply(e)
    })
    ask[Req, Res](otherActor, (replyTo: ActorRef[Res]) ⇒ createMessage.apply(replyTo))(f)(responseTimeout)
  }
}

