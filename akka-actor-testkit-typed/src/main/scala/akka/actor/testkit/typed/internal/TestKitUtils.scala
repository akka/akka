/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.testkit.typed.internal

import java.lang.reflect.Modifier

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Props }
import akka.annotation.InternalApi
import scala.concurrent.{ Await, TimeoutException }
import scala.concurrent.duration.Duration

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object ActorTestKitGuardian {
  sealed trait TestKitCommand
  final case class SpawnActor[T](name: String, behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]], props: Props) extends TestKitCommand
  final case class SpawnActorAnonymous[T](behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]], props: Props) extends TestKitCommand
  final case class StopActor[T](ref: ActorRef[T], replyTo: ActorRef[Ack.type]) extends TestKitCommand
  final case class ActorStopped[T](replyTo: ActorRef[Ack.type]) extends TestKitCommand

  final case object Ack

  val testKitGuardian: Behavior[TestKitCommand] = Behaviors.receive[TestKitCommand] {
    case (context, SpawnActor(name, behavior, reply, props)) ⇒
      reply ! context.spawn(behavior, name, props)
      Behaviors.same
    case (context, SpawnActorAnonymous(behavior, reply, props)) ⇒
      reply ! context.spawnAnonymous(behavior, props)
      Behaviors.same
    case (context, StopActor(ref, reply)) ⇒
      context.watchWith(ref, ActorStopped(reply))
      context.stop(ref)
      Behaviors.same
    case (_, ActorStopped(reply)) ⇒
      reply ! Ack
      Behaviors.same
  }
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] object TestKitUtils {

  // common internal utility impls for Java and Scala
  private val TestKitRegex = """akka\.testkit\.typed\.(?:javadsl|scaladsl)\.ActorTestKit(?:\$.*)?""".r

  def testNameFromCallStack(classToStartFrom: Class[_]): String = {

    def isAbstractClass(className: String): Boolean = {
      try {
        Modifier.isAbstract(Class.forName(className).getModifiers)
      } catch {
        case _: Throwable ⇒ false // yes catch everything, best effort check
      }
    }

    val startFrom = classToStartFrom.getName
    val filteredStack = Thread.currentThread.getStackTrace.toIterator
      .map(_.getClassName)
      // drop until we find the first occurrence of classToStartFrom
      .dropWhile(!_.startsWith(startFrom))
      // then continue to the next entry after classToStartFrom that makes sense
      .dropWhile {
        case `startFrom`                            ⇒ true
        case str if str.startsWith(startFrom + "$") ⇒ true // lambdas inside startFrom etc
        case TestKitRegex()                         ⇒ true // testkit internals
        case str if isAbstractClass(str)            ⇒ true
        case _                                      ⇒ false
      }

    if (filteredStack.isEmpty)
      throw new IllegalArgumentException(s"Couldn't find [${classToStartFrom.getName}] in call stack")

    // sanitize for actor system name
    scrubActorSystemName(filteredStack.next())
  }

  /**
   * Sanitize the `name` to be used as valid actor system name by
   * replacing invalid characters. `name` may for example be a fully qualified
   * class name and then the short class name will be used.
   */
  def scrubActorSystemName(name: String): String = {
    name
      .replaceFirst("""^.*\.""", "") // drop package name
      .replaceAll("""\$\$?\w+""", "") // drop scala anonymous functions/classes
      .replaceAll("[^a-zA-Z_0-9]", "_")
  }

  def shutdown(
    system:                  ActorSystem[_],
    timeout:                 Duration,
    throwIfShutdownTimesOut: Boolean): Unit = {
    system.terminate()
    try Await.ready(system.whenTerminated, timeout) catch {
      case _: TimeoutException ⇒
        val message = "Failed to stop [%s] within [%s] \n%s".format(system.name, timeout, system.printTree)
        if (throwIfShutdownTimesOut) throw new RuntimeException(message)
        else println(message)
    }
  }
}
