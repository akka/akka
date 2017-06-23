/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.tutorial_6;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;

import java.io.IOException;

public class IotMain {

  public static void main(String[] args) throws IOException {
    ActorSystem system = ActorSystem.create("iot-system");

    try {
      // Create top level supervisor
      ActorRef supervisor = system.actorOf(DeviceManager.props(), "iot-supervisor");

      supervisor.tell(new DeviceManager.RequestTrackDevice("mygroup", "device1"), ActorRef.noSender());

      System.out.println("Press ENTER to exit the system");
      System.in.read();
    } finally {
      system.terminate();
    }
  }

}
