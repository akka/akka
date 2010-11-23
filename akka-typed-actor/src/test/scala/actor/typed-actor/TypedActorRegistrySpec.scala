package akka.actor

import org.scalatest.junit.JUnitSuite
import org.junit.Test

object TypedActorRegistrySpec {
  trait My
  class MyImpl extends TypedActor with My
}

class TypedActorRegistrySpec extends JUnitSuite {
  import TypedActorRegistrySpec._

  @Test def shouldGetTypedActorByClassFromActorRegistry {
    ActorRegistry.shutdownAll
    val my = TypedActor.newInstance[My](classOf[My], classOf[MyImpl], 3000)

    val actors = ActorRegistry.typedActorsFor(classOf[My])
    assert(actors.length === 1)

    val option = ActorRegistry.typedActorFor[My]
    assert(option != null)
    assert(option.isDefined)
    ActorRegistry.shutdownAll
  }
}
