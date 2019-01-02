/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_3.inprogress3;

import akka.actor.typed.ActorRef;

import java.util.Optional;

abstract class DeviceProtocol {
  // no instances of DeviceProtocol class
  private DeviceProtocol() {
  }

  interface DeviceMessage {}

  //#write-protocol-1
  public static final class RecordTemperature implements DeviceMessage {
    final double value;

    public RecordTemperature(double value) {
      this.value = value;
    }
  }
  //#write-protocol-1
}

