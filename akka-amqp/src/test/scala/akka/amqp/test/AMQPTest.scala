/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp.test

import akka.actor.ActorSystem
import com.typesafe.config.ConfigFactory

object AMQPTest {

  def withCleanEndState(action: ⇒ (ActorSystem) ⇒ Unit) {
    try {
      val system = ActorSystem("Testing", ConfigFactory.load.getConfig("testing"))
      try {
        action(system)
      } finally {
        system.shutdown()
      }
    } catch {
      case e ⇒ {
        println(e.toString)
        println(e.getStackTraceString)
      }
    }
  }
}
