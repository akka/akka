/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */
package se.scalablesolutions.akka.spring

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import se.scalablesolutions.akka.config.JavaConfig._
import se.scalablesolutions.akka.config.TypedActorConfigurator

private[akka] class Foo

@RunWith(classOf[JUnitRunner])
class SupervisionFactoryBeanTest extends Spec with ShouldMatchers {

  val restartStrategy = new RestartStrategy(new AllForOne(), 3, 1000, Array(classOf[Throwable]))
  val typedActors = List(createTypedActorProperties("se.scalablesolutions.akka.spring.Foo", "1000"))

  private def createTypedActorProperties(target: String, timeout: String) : ActorProperties = {
    val properties = new ActorProperties()
    properties.target = target
    properties.timeoutStr = timeout
    properties
  }

  describe("A SupervisionFactoryBean") {
    val bean = new SupervisionFactoryBean
    it("should have java getters and setters for all properties") {
      bean.setRestartStrategy(restartStrategy)
      assert(bean.getRestartStrategy == restartStrategy)
      bean.setSupervised(typedActors)
      assert(bean.getSupervised == typedActors)
    }

    it("should return the object type AnyRef") {
      assert(bean.getObjectType == classOf[AnyRef])
    }
  }
}
