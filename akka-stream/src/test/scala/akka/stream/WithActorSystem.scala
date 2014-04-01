/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.actor.ActorSystem
import org.testng.annotations.AfterClass
import akka.testkit.AkkaSpec

trait WithActorSystem extends Timeouts {
  implicit val system: ActorSystem = ActorSystem(getClass.getSimpleName, AkkaSpec.testConf)

  @AfterClass
  def shutdownActorSystem(): Unit = system.shutdown()
}