package akka.testkit.typed

import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior, Props }
import akka.annotation.{ ApiMayChange, DoNotInherit }
import akka.testkit.typed.TestKit._
import akka.util.Timeout
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.concurrent.{ Await, TimeoutException }
import scala.util.control.NoStackTrace

/**
 * Exception without stack trace to use for verifying exceptions in tests
 */
final case class TE(message: String) extends RuntimeException(message) with NoStackTrace

object TestKit {

  private[akka] sealed trait TestKitCommand
  private[akka] case class SpawnActor[T](name: String, behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]], props: Props) extends TestKitCommand
  private[akka] case class SpawnActorAnonymous[T](behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]], props: Props) extends TestKitCommand

  private val testKitGuardian = Behaviors.immutable[TestKitCommand] {
    case (ctx, SpawnActor(name, behavior, reply, props)) ⇒
      reply ! ctx.spawn(behavior, name, props)
      Behaviors.same
    case (ctx, SpawnActorAnonymous(behavior, reply, props)) ⇒
      reply ! ctx.spawnAnonymous(behavior, props)
      Behaviors.same
  }

  private def getCallerName(clazz: Class[_]): String = {
    val s = (Thread.currentThread.getStackTrace map (_.getClassName) drop 1)
      .dropWhile(_ matches "(java.lang.Thread|.*\\.Abstract.*)")
    val reduced = s.lastIndexWhere(_ == clazz.getName) match {
      case -1 ⇒ s
      case z  ⇒ s drop (z + 1)
    }
    reduced.head.replaceFirst(""".*\.""", "").replaceAll("[^a-zA-Z_0-9]", "_")
  }

  def shutdown(
    system:               ActorSystem[_],
    duration:             Duration,
    verifySystemShutdown: Boolean        = false): Unit = {
    system.terminate()
    try Await.ready(system.whenTerminated, duration) catch {
      case _: TimeoutException ⇒
        val msg = "Failed to stop [%s] within [%s] \n%s".format(system.name, duration,
          system.printTree)
        if (verifySystemShutdown) throw new RuntimeException(msg)
        else println(msg)
    }
  }

}

/**
 * Testkit for typed actors. Extending this removes some boiler plate when testing
 * typed actors.
 *
 * If a test can't extend then use the [[TestKitBase]] trait
 */
@ApiMayChange
class TestKit(name: String, config: Option[Config]) extends TestKitBase {
  def this() = this(TestKit.getCallerName(classOf[TestKit]), None)
  def this(name: String) = this(name, None)
  def this(config: Config) = this(TestKit.getCallerName(classOf[TestKit]), Some(config))
  def this(name: String, config: Config) = this(name, Some(config))

  import TestKit._
  implicit val system: ActorSystem[TestKitCommand] = ActorSystem(testKitGuardian, name, config = config)
}

@ApiMayChange
trait TestKitBase {
  def system: ActorSystem[TestKitCommand]
  implicit def testkitSettings = TestKitSettings(system)
  implicit def scheduler = system.scheduler
  private val childName: Iterator[String] = Iterator.from(0).map(_.toString)
  // FIXME testkit config
  private val timeoutDuration = 5.seconds
  implicit private val timeout = Timeout(timeoutDuration)

  def shutdown(): Unit = {
    TestKit.shutdown(system, timeoutDuration)
  }

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T]): ActorRef[T] =
    spawn(behavior, Props.empty)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T], props: Props): ActorRef[T] =
    Await.result(system ? (SpawnActorAnonymous(behavior, _, props)), timeoutDuration)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T], name: String): ActorRef[T] =
    spawn(behavior, name, Props.empty)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T], name: String, props: Props): ActorRef[T] =
    Await.result(system ? (SpawnActor(name, behavior, _, props)), timeoutDuration)

  def systemActor[T](behavior: Behavior[T], name: String): ActorRef[T] =
    Await.result(system.systemActorOf(behavior, name), timeoutDuration)

  def systemActor[T](behavior: Behavior[T]): ActorRef[T] =
    Await.result(system.systemActorOf(behavior, childName.next()), timeoutDuration)
}
