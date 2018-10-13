/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import java.util

import akka.actor.ActorPath

import akka.actor.typed.{ Behavior, PostStop, Signal, ActorRef }
import akka.annotation.InternalApi
import akka.actor.testkit.typed.Effect
import akka.actor.testkit.typed.Effect._

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.immutable
import scala.reflect.ClassTag
import scala.util.control.Exception.Catcher
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class BehaviorTestKitImpl[T](_path: ActorPath, _initialBehavior: Behavior[T])
  extends akka.actor.testkit.typed.javadsl.BehaviorTestKit[T]
  with akka.actor.testkit.typed.scaladsl.BehaviorTestKit[T] {

  // really this should be private, make so when we port out tests that need it
  private[akka] val ctx = new EffectfulActorContext[T](_path)

  private[akka] def as[U]: BehaviorTestKitImpl[U] = this.asInstanceOf[BehaviorTestKitImpl[U]]

  private var currentUncanonical = _initialBehavior
  private var current = Behavior.validateAsInitial(Behavior.start(_initialBehavior, ctx))

  // execute any future tasks scheduled in Actor's constructor
  runAllTasks()

  override def retrieveEffect(): Effect = ctx.effectQueue.poll() match {
    case null ⇒ NoEffects
    case x    ⇒ x
  }

  override def childInbox[U](name: String): TestInboxImpl[U] = {
    val inbox = ctx.childInbox[U](name)
    assert(inbox.isDefined, s"Child not created: $name. Children created: [${ctx.childrenNames.mkString(",")}]")
    inbox.get
  }

  override def childTestKit[U](child: ActorRef[U]): BehaviorTestKitImpl[U] = ctx.childTestKit(child)

  override def selfInbox(): TestInboxImpl[T] = ctx.selfInbox

  override def retrieveAllEffects(): immutable.Seq[Effect] = {
    @tailrec def rec(acc: List[Effect]): List[Effect] = ctx.effectQueue.poll() match {
      case null ⇒ acc.reverse
      case x    ⇒ rec(x :: acc)
    }

    rec(Nil)
  }

  def getEffect(): Effect = retrieveEffect()

  def getAllEffects(): util.List[Effect] = retrieveAllEffects().asJava

  override def expectEffect(expectedEffect: Effect): Unit = {
    ctx.effectQueue.poll() match {
      case null   ⇒ assert(expectedEffect == NoEffects, s"expected: $expectedEffect but no effects were recorded")
      case effect ⇒ assert(expectedEffect == effect, s"expected: $expectedEffect but found $effect")
    }
  }

  def expectEffectClass[E <: Effect](effectClass: Class[E]): E = {
    ctx.effectQueue.poll() match {
      case null if effectClass.isAssignableFrom(NoEffects.getClass) ⇒ effectClass.cast(NoEffects)
      case null ⇒ throw new AssertionError(s"expected: effect type ${effectClass.getName} but no effects were recorded")
      case effect if effectClass.isAssignableFrom(effect.getClass) ⇒ effect.asInstanceOf[E]
      case other ⇒ throw new AssertionError(s"expected: effect class ${effectClass.getName} but found $other")
    }
  }

  def expectEffectPF[R](f: PartialFunction[Effect, R]): R = {
    ctx.effectQueue.poll() match {
      case null if f.isDefinedAt(NoEffects) ⇒
        f.apply(NoEffects)
      case eff if f.isDefinedAt(eff) ⇒
        f.apply(eff)
      case other ⇒
        throw new AssertionError(s"expected matching effect but got: $other")
    }
  }

  def expectEffectType[E <: Effect](implicit classTag: ClassTag[E]): E =
    expectEffectClass(classTag.runtimeClass.asInstanceOf[Class[E]])

  def returnedBehavior: Behavior[T] = currentUncanonical
  def currentBehavior: Behavior[T] = current
  def isAlive: Boolean = Behavior.isAlive(current)

  private def handleException: Catcher[Unit] = {
    case NonFatal(e) ⇒
      try Behavior.canonicalize(Behavior.interpretSignal(current, ctx, PostStop), current, ctx) // TODO why canonicalize here?
      catch {
        case NonFatal(_) ⇒ /* ignore, real is logging */
      }
      throw e
  }

  private def runAllTasks(): Unit = {
    ctx.executionContext match {
      case controlled: ControlledExecutor ⇒ controlled.runAll()
      case _                              ⇒
    }
  }

  override def run(msg: T): Unit = {
    try {
      currentUncanonical = Behavior.interpretMessage(current, ctx, msg)
      current = Behavior.canonicalize(currentUncanonical, current, ctx)
      runAllTasks()
    } catch handleException
  }

  override def runOne(): Unit = run(selfInbox.receiveMessage())

  override def signal(signal: Signal): Unit = {
    try {
      currentUncanonical = Behavior.interpretSignal(current, ctx, signal)
      current = Behavior.canonicalize(currentUncanonical, current, ctx)
    } catch handleException
  }

  override def hasEffects(): Boolean = !ctx.effectQueue.isEmpty
}
