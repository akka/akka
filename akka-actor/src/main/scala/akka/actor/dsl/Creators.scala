/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.actor.dsl

import scala.concurrent.Await
import akka.actor.ActorLogging
import scala.concurrent.util.Deadline
import scala.collection.immutable.TreeSet
import scala.concurrent.util.{ Duration, FiniteDuration }
import scala.concurrent.util.duration._
import akka.actor.Cancellable
import akka.actor.{ Actor, Stash }
import scala.collection.mutable.Queue
import akka.actor.{ ActorSystem, ActorRefFactory }
import akka.actor.ActorRef
import akka.util.Timeout
import akka.actor.Status
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import akka.pattern.ask
import akka.actor.ActorDSL
import akka.actor.Props
import scala.reflect.ClassTag

trait Creators { this: ActorDSL.type ⇒

  trait Act extends Actor {
    /*
    whenFailing { (cause, optMsg) => ... } // preRestart
    whenRestarted { cause => ... }         // postRestart
    */
    private[this] var preStartFun: () ⇒ Unit = null
    private[this] var postStopFun: () ⇒ Unit = null
    private[this] var preRestartFun: (Throwable, Option[Any]) ⇒ Unit = null
    private[this] var postRestartFun: Throwable ⇒ Unit = null

    def become(r: Receive) =
      context.become(r, false)

    def unbecome(): Unit =
      context.unbecome()

    def setup(body: ⇒ Unit): Unit =
      preStartFun = () ⇒ body

    def teardown(body: ⇒ Unit): Unit =
      postStopFun = () ⇒ body

    override def preStart(): Unit =
      if (preStartFun != null) preStartFun()

    override def postStop(): Unit =
      if (postStopFun != null) postStopFun()

    override def receive: Receive = {
      case _ ⇒ /* do nothing */
    }
  }

  private def mkProps(classOfActor: Class[_], ctor: () ⇒ Actor): Props =
    if (classOf[Stash].isAssignableFrom(classOfActor))
      Props(creator = ctor, dispatcher = "akka.actor.default-stash-dispatcher")
    else
      Props(creator = ctor)

  def actor[T <: Actor: ClassTag](name: String = null)(ctor: ⇒ T)(implicit factory: ActorRefFactory): ActorRef = {
    // configure dispatcher/mailbox based on runtime class
    val classOfActor = implicitly[ClassTag[T]].runtimeClass
    val props = mkProps(classOfActor, () ⇒ ctor)
    factory.actorOf(props, if (name == null) "anonymous-actor" else name) //TODO: attach ID
  }

  def actor[T <: Actor: ClassTag](factory: ActorRefFactory, name: String)(ctor: ⇒ T): ActorRef = null

}
