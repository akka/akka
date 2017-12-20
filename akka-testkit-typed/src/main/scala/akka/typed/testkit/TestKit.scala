package akka.typed.testkit

import akka.actor.typed.{ ActorRef, ActorSystem, Behavior }
import akka.annotation.ApiMayChange
import akka.util.Timeout

import scala.concurrent.duration._
import scala.concurrent.{ Await, TimeoutException }

/**
 * Testkit for typed actors. Extending this removes some boiler plate when testing
 * typed actors.
 *
 * If a test can't extend then use the [[TestKitBase]] trait
 *
 * @param _system The [ActorSystem] for the test
 */
@ApiMayChange
class TestKit(_system: ActorSystem[_]) extends TestKitBase {
  implicit val system = _system
}

@ApiMayChange
trait TestKitBase {
  def system: ActorSystem[_]
  implicit def testkitSettings = TestKitSettings(system)

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

  // The only current impl of a typed actor system returns a Future.successful currently
  // hence the hardcoded timeouts
  def actorOf[T](behaviour: Behavior[T], name: String): ActorRef[T] =
    Await.result(system.systemActorOf(behaviour, name)(Timeout(20.seconds)), 21.seconds)
}

