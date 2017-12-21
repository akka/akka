/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.testkit.typed.javadsl

import akka.actor.typed.ActorSystem
import akka.testkit.typed.TestKitSettings

/**
 * Java API:
 */
class TestProbe[M](name: String, system: ActorSystem[_], settings: TestKitSettings) extends akka.testkit.typed.scaladsl.TestProbe[M](name)(system, settings) {

  def this(system: ActorSystem[_], settings: TestKitSettings) = this("testProbe", system, settings)

  /**
   * Same as `expectMsgType[T](remainingOrDefault)`, but correctly treating the timeFactor.
   */
  def expectMsgType[T <: M](t: Class[T]): T =
    expectMsgClass_internal(remainingOrDefault, t)

}
