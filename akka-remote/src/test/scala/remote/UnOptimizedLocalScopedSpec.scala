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
    "Fetch local actor ref when scope is local" in {
      val fooActor = ActorRegistry.actorOf[TestActor].start
      remote.register("foo", fooActor)

      remote.actorFor("foo", host, port) must not be (fooActor)
    }

    "Create local actor when client-managed is hosted locally" in {
      val localClientManaged = ActorRegistry.remote.actorOf[TestActor](host, port)
      localClientManaged.homeAddress must not be (None)
    }

  }
}