/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_3.inprogress2;

import akka.actor.typed.ActorRef;

import java.util.Optional;

//#read-protocol-2
abstract class DeviceProtocol {
  // no instances of DeviceProtocol class
  private DeviceProtocol() {
  }

  interface DeviceMessage {}

  public static final class ReadTemperature implements DeviceMessage {
    final long requestId;
    final ActorRef<RespondTemperature> replyTo;

    public ReadTemperature(long requestId, ActorRef<RespondTemperature> replyTo) {
      this.requestId = requestId;
      this.replyTo = replyTo;
    }
  }

  public static final class RespondTemperature {
    final long requestId;
    final Optional<Double> value;

    public RespondTemperature(long requestId, Optional<Double> value) {
      this.requestId = requestId;
      this.value = value;
    }
  }

}
//#read-protocol-2

