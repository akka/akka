package akka.actor.remote
import akka.actor. {ActorRegistry, Actor}

object UnOptimizedLocalScopedSpec {
  class TestActor extends Actor {
    def receive = { case _ => }
  }
}

class UnOptimizedLocalScopedSpec extends AkkaRemoteTest {
  import UnOptimizedLocalScopedSpec._
  override def OptimizeLocal = false

  "An enabled optimized local scoped remote" should {
    "Fetch remote actor ref when scope is local" in {
      val fooActor = Actor.actorOf[TestActor].start
      remote.register("foo", fooActor)

      remote.actorFor("foo", host, port) must not be (fooActor)
    }

    "Create remote actor when client-managed is hosted locally" in {
      val localClientManaged = Actor.remote.actorOf[TestActor](host, port)
      localClientManaged.homeAddress must not be (None)
    }

  }
}