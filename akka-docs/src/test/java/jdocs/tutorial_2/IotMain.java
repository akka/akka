/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

//#iot-app
package com.example;

import java.io.IOException;

import akka.actor.ActorSystem;
import akka.actor.ActorRef;

public class IotMain {

  public static void main(String[] args) throws IOException {
    ActorSystem system = ActorSystem.create("iot-system");

    try {
      // Create top level supervisor
      ActorRef supervisor = system.actorOf(IotSupervisor.props(), "iot-supervisor");

      System.out.println("Press ENTER to exit the system");
      System.in.read();
    } finally {
      system.terminate();
    }
  }

}
//#iot-app
