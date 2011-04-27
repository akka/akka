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
  }
}
