package akka.testkit.typed

import akka.actor.typed.scaladsl.Actor
import akka.actor.typed.scaladsl.AskPattern._
import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.annotation.ApiMayChange
import akka.testkit.typed.TestKit._
import akka.util.Timeout
import com.typesafe.config.Config

import scala.concurrent.duration._
import scala.concurrent.{ Await, TimeoutException }

object TestKit {

  private[akka] sealed trait TestKitCommand
  private[akka] case class SpawnActor[T](name: String, behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]]) extends TestKitCommand
  private[akka] case class SpawnActorAnonymous[T](behavior: Behavior[T], replyTo: ActorRef[ActorRef[T]]) extends TestKitCommand

  private val testKitGuardian = Actor.immutable[TestKitCommand] {
    case (ctx, SpawnActor(name, behavior, reply)) ⇒
      reply ! ctx.spawn(behavior, name)
      Actor.same
    case (ctx, SpawnActorAnonymous(behavior, reply)) ⇒
      reply ! ctx.spawnAnonymous(behavior)
      Actor.same
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
  implicit val system = ActorSystem(testKitGuardian, name, config = config)
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
    shutdown(system, 5.seconds)
  }

  def shutdown(
    actorSystem:          ActorSystem[_],
    duration:             Duration,
    verifySystemShutdown: Boolean        = false): Unit = {
    system.terminate()
    try Await.ready(actorSystem.whenTerminated, duration) catch {
      case _: TimeoutException ⇒
        val msg = "Failed to stop [%s] within [%s] \n%s".format(actorSystem.name, duration,
          actorSystem.printTree)
        if (verifySystemShutdown) throw new RuntimeException(msg)
        else println(msg)
    }
  }

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T]): ActorRef[T] =
    Await.result(system ? (SpawnActorAnonymous(behavior, _)), timeoutDuration)

  /**
   * Spawn the given behavior. This is created as a child of the test kit
   * guardian
   */
  def spawn[T](behavior: Behavior[T], name: String): ActorRef[T] =
    Await.result(system ? (SpawnActor(name, behavior, _)), timeoutDuration)

  // The only current impl of a typed actor system returns a Future.successful currently
  // hence the hardcoded timeouts
  def systemActor[T](behaviour: Behavior[T], name: String): ActorRef[T] =
    Await.result(system.systemActorOf(behaviour, name), timeoutDuration)

  def systemActor[T](behaviour: Behavior[T]): ActorRef[T] =
    Await.result(system.systemActorOf(behaviour, childName.next()), timeoutDuration)
}
