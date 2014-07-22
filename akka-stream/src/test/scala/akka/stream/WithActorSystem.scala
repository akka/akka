/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.actor.ActorSystem
// FIXME: TestNG dependency comes from TCK. Needs new TCK version
//import org.testng.annotations.AfterClass

trait WithActorSystem {
  def system: ActorSystem

  // FIXME: TestNG dependency comes from TCK. Needs new TCK version
  //  @AfterClass
  //  def shutdownActorSystem(): Unit = system.shutdown()
}