/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package tutorial_2

//#iot-app
package com.example

import akka.actor.ActorSystem
import scala.io.StdIn

object IotApp {

  def main(args: Array[String]): Unit = {
    val system = ActorSystem("iot-system")

    try {
      // Create top level supervisor
      val supervisor = system.actorOf(IotSupervisor.props(), "iot-supervisor")
      // Exit the system after ENTER is pressed
      StdIn.readLine()
    } finally {
      system.terminate()
    }
  }

}
//#iot-app
