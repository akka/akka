package akka.streams

import akka.actor.ActorSystem
import org.testng.annotations.AfterClass

trait WithActorSystem {
  val system: ActorSystem = ActorSystem(getClass.getSimpleName)

  @AfterClass
  def shutdownActorSystem(): Unit = system.shutdown()
}