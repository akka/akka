/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.testkit.typed.internal

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

  val testKitGuardian: Behavior[TestKitCommand] = Behaviors.receive[TestKitCommand] {
    case (ctx, SpawnActor(name, behavior, reply, props)) ⇒
      reply ! ctx.spawn(behavior, name, props)
      Behaviors.same
    case (ctx, SpawnActorAnonymous(behavior, reply, props)) ⇒
      reply ! ctx.spawnAnonymous(behavior, props)
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
    val startFrom = classToStartFrom.getName
    val filteredStack = Thread.currentThread.getStackTrace.toIterator
      .map(_.getClassName)
      // drop until we find the first occurence of classToStartFrom
      .dropWhile(!_.startsWith(startFrom))
      // then continue to the next entry after classToStartFrom that makes sense
      .dropWhile {
        case `startFrom`                            ⇒ true
        case str if str.startsWith(startFrom + "$") ⇒ true // lambdas inside startFrom etc
        case TestKitRegex()                         ⇒ true // testkit internals
        case _                                      ⇒ false
      }

    // sanitize for actor system name
    filteredStack.next()
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
        val msg = "Failed to stop [%s] within [%s] \n%s".format(system.name, timeout, system.printTree)
        if (throwIfShutdownTimesOut) throw new RuntimeException(msg)
        else println(msg)
    }
  }
}
