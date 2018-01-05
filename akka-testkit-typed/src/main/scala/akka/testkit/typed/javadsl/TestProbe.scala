/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.testkit.typed.javadsl

import akka.actor.typed.ActorSystem

/**
 * Java API:
 */
class TestProbe[M](name: String, system: ActorSystem[_]) extends akka.testkit.typed.scaladsl.TestProbe[M](name)(system) {

  def this(system: ActorSystem[_]) = this("testProbe", system)

  /**
   * Same as `expectMsgType[T](remainingOrDefault)`, but correctly treating the timeFactor.
   */
  def expectMsgType[T <: M](t: Class[T]): T =
    expectMsgClass_internal(remainingOrDefault, t)

}
