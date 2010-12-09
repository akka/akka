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
      ActorRegistry.shutdownAll
      val my = TypedActor.newInstance[My](classOf[My], classOf[MyImpl], 3000)
      val actors = ActorRegistry.typedActorsFor(classOf[My])
      actors.length must be (1)
      ActorRegistry.shutdownAll
    }

    "be able to be retreived from the registry by manifest" in {
      ActorRegistry.shutdownAll
      val my = TypedActor.newInstance[My](classOf[My], classOf[MyImpl], 3000)
      val option = ActorRegistry.typedActorFor[My]
      option must not be (null)
      option.isDefined must be (true)
      ActorRegistry.shutdownAll
    }

    "be able to be retreived from the registry by class two times" in {
      ActorRegistry.shutdownAll
      val my = TypedActor.newInstance[My](classOf[My], classOf[MyImpl], 3000)
      val actors1 = ActorRegistry.typedActorsFor(classOf[My])
      actors1.length must be (1)
      val actors2 = ActorRegistry.typedActorsFor(classOf[My])
      actors2.length must be (1)
      ActorRegistry.shutdownAll
    }

    "be able to be retreived from the registry by manifest two times" in {
      ActorRegistry.shutdownAll
      val my = TypedActor.newInstance[My](classOf[My], classOf[MyImpl], 3000)
      val option1 = ActorRegistry.typedActorFor[My]
      option1 must not be (null)
      option1.isDefined must be (true)
      val option2 = ActorRegistry.typedActorFor[My]
      option2 must not be (null)
      option2.isDefined must be (true)
      ActorRegistry.shutdownAll
    }

    "be able to be retreived from the registry by manifest two times (even when created in supervisor)" in {
      ActorRegistry.shutdownAll
      val manager = new TypedActorConfigurator
      manager.configure(
        OneForOneStrategy(classOf[Exception] :: Nil, 3, 1000),
        Array(new SuperviseTypedActor(classOf[My], classOf[MyImpl], Permanent, 6000))
      ).supervise

      val option1 = ActorRegistry.typedActorFor[My]
      option1 must not be (null)
      option1.isDefined must be (true)
      val option2 = ActorRegistry.typedActorFor[My]
      option2 must not be (null)
      option2.isDefined must be (true)
      ActorRegistry.shutdownAll
    }
  }
}
