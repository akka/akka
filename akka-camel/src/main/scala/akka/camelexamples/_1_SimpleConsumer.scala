/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camelexamples

import akka.actor.{Props, ActorSystem}
import RichString._


object _1_SimpleConsumer extends App{
  val system = ActorSystem("test")

  system.actorOf(Props[SysOutConsumer])

  "data/input/CamelConsumer/file1.txt" << "test data "+math.random

  Thread.sleep(2000)

  system.shutdown()

}