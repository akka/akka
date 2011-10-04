/**
 * Copyright (C) 2009-2010 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.spring

import org.scalatest.Spec
import org.scalatest.matchers.ShouldMatchers
import org.scalatest.junit.JUnitRunner
import org.junit.runner.RunWith
import akka.config.TypedActorConfigurator

private[akka] class Foo

@RunWith(classOf[JUnitRunner])
class SupervisionFactoryBeanTest extends Spec with ShouldMatchers {

  val faultHandlingStrategy = new AllForOneStrategy(List(classOf[Exception]), 3, 1000)
  val typedActors = List(createTypedActorProperties("akka.spring.Foo", "1000"))

  private def createTypedActorProperties(target: String, timeout: String): ActorProperties = {
    val properties = new ActorProperties()
    properties.target = target
    properties.timeoutStr = timeout
    properties
  }

  describe("A SupervisionFactoryBean") {
    val bean = new SupervisionFactoryBean
    it("should have java getters and setters for all properties") {
      bean.setRestartStrategy(faultHandlingStrategy)
      assert(bean.getRestartStrategy == faultHandlingStrategy)
      bean.setSupervised(typedActors)
      assert(bean.getSupervised == typedActors)
    }

    it("should return the object type AnyRef") {
      assert(bean.getObjectType == classOf[AnyRef])
    }
  }
}
