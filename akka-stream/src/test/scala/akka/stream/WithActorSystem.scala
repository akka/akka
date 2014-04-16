/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.actor.ActorSystem
import org.testng.annotations.AfterClass

trait WithActorSystem {
  def system: ActorSystem

  @AfterClass
  def shutdownActorSystem(): Unit = system.shutdown()
}