package se.scalablesolutions.akka.actor

/**
 * Actor which can be used as the basis for unit testing actors. I automatically start and stops all involved handlers before and after
 * the test.
 */
abstract class TestActor extends Actor with ActorTestUtil {
  def test: Unit
  def receive = {case _ =>}
}

trait ActorTestUtil {
  def handle[T](actors: Actor*)(test: => T): T = {
    for (a <- actors) a.start
    try {
      test
    }
    finally {
      for (a <- actors) a.stop
    }
  }

  def verify(actor: TestActor): Unit = handle(actor) {
    actor.test
  }
}
