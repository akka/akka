package akka.actor.remote

import akka.actor.{Actor}

object OptimizedLocalScopedSpec {
  class TestActor extends Actor {
    def receive = { case _ => }
  }
}

class OptimizedLocalScopedSpec extends AkkaRemoteTest {
  import OptimizedLocalScopedSpec._
  override def OptimizeLocal = true

  "An enabled optimized local scoped remote" should {
    "Fetch local actor ref when scope is local" in {
      val fooActor = Actor.actorOf[TestActor].start
      remote.register("foo", fooActor)

      remote.actorFor("foo", host, port) must be (fooActor)
    }

    "Create local actor when client-managed is hosted locally" in {
      val localClientManaged = Actor.remote.actorOf[TestActor](host, port)
      localClientManaged.homeAddress must be (None)
    }

  }
}