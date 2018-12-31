/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_5;

import akka.actor.typed.ActorRef;

import java.util.Optional;

abstract class DeviceProtocol {
  // no instances of DeviceProtocol class
  private DeviceProtocol() {}

  interface DeviceMessage {}

  public static final class RecordTemperature implements DeviceMessage {
    final long requestId;
    final double value;
    final ActorRef<TemperatureRecorded> replyTo;

    public RecordTemperature(long requestId, double value, ActorRef<TemperatureRecorded> replyTo){
      this.requestId = requestId;
      this.value = value;
      this.replyTo = replyTo;
    }
  }

  public static final class TemperatureRecorded {
    final long requestId;

    public TemperatureRecorded(long requestId) {
      this.requestId = requestId;
    }
  }

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
    final String deviceId;
    final Optional<Double> value;

    public RespondTemperature(long requestId, String deviceId, Optional<Double> value) {
      this.requestId = requestId;
      this.deviceId = deviceId;
      this.value = value;
    }
  }

  static enum Passivate implements DeviceMessage {
    INSTANCE
  }

}
