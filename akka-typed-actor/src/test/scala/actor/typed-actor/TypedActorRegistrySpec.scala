package akka.actor

import org.scalatest.WordSpec
import org.scalatest.matchers.MustMatchers

import akka.config.Supervision._
import akka.config._

object TypedActorRegistrySpec {
  trait My
  class MyImpl extends TypedActor with My
}

class TypedActorRegistrySpec extends WordSpec with MustMatchers {
  import TypedActorRegistrySpec._

  "Typed Actor" should {

    "be able to be retreived from the registry by class" in {
      Actor.registry.shutdownAll()
      val my = TypedActor.newInstance[My](classOf[My], classOf[MyImpl], 3000)
      val actors = Actor.registry.typedActorsFor(classOf[My])
      actors.length must be (1)
      Actor.registry.shutdownAll()
    }

    "be able to be retreived from the registry by manifest" in {
      Actor.registry.shutdownAll()
      val my = TypedActor.newInstance[My](classOf[My], classOf[MyImpl], 3000)
      val option = Actor.registry.typedActorFor[My]
      option must not be (null)
      option.isDefined must be (true)
      Actor.registry.shutdownAll()
    }

    "be able to be retreived from the registry by class two times" in {
      Actor.registry.shutdownAll()
      val my = TypedActor.newInstance[My](classOf[My], classOf[MyImpl], 3000)
      val actors1 = Actor.registry.typedActorsFor(classOf[My])
      actors1.length must be (1)
      val actors2 = Actor.registry.typedActorsFor(classOf[My])
      actors2.length must be (1)
      Actor.registry.shutdownAll()
    }

    "be able to be retreived from the registry by manifest two times" in {
      Actor.registry.shutdownAll()
      val my = TypedActor.newInstance[My](classOf[My], classOf[MyImpl], 3000)
      val option1 = Actor.registry.typedActorFor[My]
      option1 must not be (null)
      option1.isDefined must be (true)
      val option2 = Actor.registry.typedActorFor[My]
      option2 must not be (null)
      option2.isDefined must be (true)
      Actor.registry.shutdownAll()
    }

    "be able to be retreived from the registry by manifest two times (even when created in supervisor)" in {
      Actor.registry.shutdownAll()
      val manager = new TypedActorConfigurator
      manager.configure(
        OneForOneStrategy(classOf[Exception] :: Nil, 3, 1000),
        Array(new SuperviseTypedActor(classOf[My], classOf[MyImpl], Permanent, 6000))
      ).supervise

      val option1 = Actor.registry.typedActorFor[My]
      option1 must not be (null)
      option1.isDefined must be (true)
      val option2 = Actor.registry.typedActorFor[My]
      option2 must not be (null)
      option2.isDefined must be (true)
      Actor.registry.shutdownAll()
    }
  }
}
