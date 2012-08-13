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
import akka.actor.{ Actor, Stash, SupervisorStrategy }
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

  /**
   * This trait provides a DSL for writing the inner workings of an actor, e.g.
   * for quickly trying things out in the REPL. It makes the following keywords
   * available:
   *
   *  - `become` mapped to `context.become(_, discardOld = false)`
   *
   *  - `unbecome` mapped to `context.unbecome`
   *
   *  - `setup` for implementing `preStart()`
   *
   *  - `whenFailing` for implementing `preRestart()`
   *
   *  - `whenRestarted` for implementing `postRestart()`
   *
   *  - `teardown` for implementing `postStop`
   *
   * Using the life-cycle keywords multiple times results in replacing the
   * content of the respective hook.
   */
  trait Act extends Actor {

    private[this] var preStartFun: () ⇒ Unit = null
    private[this] var postStopFun: () ⇒ Unit = null
    private[this] var preRestartFun: (Throwable, Option[Any]) ⇒ Unit = null
    private[this] var postRestartFun: Throwable ⇒ Unit = null
    private[this] var strategy: SupervisorStrategy = null

    /**
     * Add the given behavior on top of the behavior stack for this actor. This
     * stack is cleared upon restart. Use `unbecome()` to pop an element off
     * this stack.
     */
    def become(r: Receive) = context.become(r, discardOld = false)

    /**
     * Pop the active behavior from the behavior stack of this actor. This stack
     * is cleared upon restart.
     */
    def unbecome(): Unit = context.unbecome()

    /**
     * Set the supervisor strategy of this actor, i.e. how it supervises its children.
     */
    def superviseWith(s: SupervisorStrategy): Unit = strategy = s

    /**
     * Replace the `preStart` action with the supplied thunk. Default action
     * is to call `super.preStart()`
     */
    def whenStarting(body: ⇒ Unit): Unit = preStartFun = () ⇒ body

    /**
     * Replace the `preRestart` action with the supplied function. Default
     * action is to call `super.preRestart()`, which will kill all children
     * and invoke `postStop()`.
     */
    def whenFailing(body: (Throwable, Option[Any]) ⇒ Unit): Unit = preRestartFun = body

    /**
     * Replace the `postRestart` action with the supplied function. Default
     * action is to call `super.postRestart` which will call `preStart()`.
     */
    def whenRestarted(body: Throwable ⇒ Unit): Unit = postRestartFun = body

    /**
     * Replace the `postStop` action with the supplied thunk. Default action
     * is to call `super.postStop`.
     */
    def whenStopping(body: ⇒ Unit): Unit = postStopFun = () ⇒ body

    override def preStart(): Unit = if (preStartFun != null) preStartFun() else super.preStart()
    override def preRestart(cause: Throwable, msg: Option[Any]): Unit = if (preRestartFun != null) preRestartFun(cause, msg) else super.preRestart(cause, msg)
    override def postRestart(cause: Throwable): Unit = if (postRestartFun != null) postRestartFun(cause) else super.postRestart(cause)
    override def postStop(): Unit = if (postStopFun != null) postStopFun() else super.postStop()
    override def supervisorStrategy: SupervisorStrategy = if (strategy != null) strategy else super.supervisorStrategy

    /**
     * Default behavior of the actor is empty, use `become` to change this.
     */
    override def receive: Receive = Actor.emptyBehavior
  }

  /**
   * Use this trait when defining an [[akka.actor.Actor]] with [[akka.actor.Stash]],
   * since just using `actor()(new Act with Stash{})` will not be able to see the
   * Stash component due to type erasure.
   */
  trait ActWithStash extends Act with Stash

  private def mkProps(classOfActor: Class[_], ctor: () ⇒ Actor): Props =
    if (classOf[Stash].isAssignableFrom(classOfActor))
      Props(creator = ctor, dispatcher = "akka.actor.default-stash-dispatcher")
    else
      Props(creator = ctor)

  /**
   * Create an actor from the given thunk which must produce an [[akka.actor.Actor]].
   *
   * @param ctor is a by-name argument which captures an [[akka.actor.Actor]]
   *        factory; <b>do not make the generated object accessible to code
   *        outside and do not return the same object upon subsequent invocations.</b>
   * @param factory is an implicit [[akka.actor.ActorRefFactory]], which can
   *        either be an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]],
   *        where the latter is always implicitly available within an [[akka.actor.Actor]].
   */
  def actor[T <: Actor: ClassTag](ctor: ⇒ T)(implicit factory: ActorRefFactory): ActorRef = {
    // configure dispatcher/mailbox based on runtime class
    val classOfActor = implicitly[ClassTag[T]].runtimeClass
    val props = mkProps(classOfActor, () ⇒ ctor)
    factory.actorOf(props)
  }

  /**
   * Create an actor from the given thunk which must produce an [[akka.actor.Actor]].
   *
   * @param name is the name, which must be unique within the context of its
   *        parent.
   * @param ctor is a by-name argument which captures an [[akka.actor.Actor]]
   *        factory; <b>do not make the generated object accessible to code
   *        outside and do not return the same object upon subsequent invocations.</b>
   * @param factory is an implicit [[akka.actor.ActorRefFactory]], which can
   *        either be an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]],
   *        where the latter is always implicitly available within an [[akka.actor.Actor]].
   */
  def actor[T <: Actor: ClassTag](name: String)(ctor: ⇒ T)(implicit factory: ActorRefFactory): ActorRef = {
    // configure dispatcher/mailbox based on runtime class
    val classOfActor = implicitly[ClassTag[T]].runtimeClass
    val props = mkProps(classOfActor, () ⇒ ctor)

    if (name == null) factory.actorOf(props)
    else factory.actorOf(props, name)
  }

  /**
   * Create an actor from the given thunk which must produce an [[akka.actor.Actor]].
   *
   * @param name is the name, which must be unique within the context of its
   *        parent; defaults to `null` which will assign a name automatically.
   * @param ctor is a by-name argument which captures an [[akka.actor.Actor]]
   *        factory; <b>do not make the generated object accessible to code
   *        outside and do not return the same object upon subsequent invocations.</b>
   * @param factory is an implicit [[akka.actor.ActorRefFactory]], which can
   *        either be an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]],
   *        where the latter is always implicitly available within an [[akka.actor.Actor]].
   */
  def actor[T <: Actor: ClassTag](factory: ActorRefFactory, name: String)(ctor: ⇒ T): ActorRef =
    actor(name)(ctor)(implicitly[ClassTag[T]], factory)

  /**
   * Create an actor with an automatically generated name from the given thunk
   * which must produce an [[akka.actor.Actor]].
   *
   * @param ctor is a by-name argument which captures an [[akka.actor.Actor]]
   *        factory; <b>do not make the generated object accessible to code
   *        outside and do not return the same object upon subsequent invocations.</b>
   * @param factory is an implicit [[akka.actor.ActorRefFactory]], which can
   *        either be an [[akka.actor.ActorSystem]] or an [[akka.actor.ActorContext]],
   *        where the latter is always implicitly available within an [[akka.actor.Actor]].
   */
  def actor[T <: Actor: ClassTag](factory: ActorRefFactory)(ctor: ⇒ T): ActorRef =
    actor(null: String)(ctor)(implicitly[ClassTag[T]], factory)

}
