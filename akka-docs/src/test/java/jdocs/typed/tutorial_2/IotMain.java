/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_2;

// #iot-app
import akka.actor.typed.ActorSystem;

public class IotMain {

  public static void main(String[] args) {
    // Create ActorSystem and top level supervisor
    ActorSystem.create(IotSupervisor.create(), "iot-system");
  }
}
// #iot-app
