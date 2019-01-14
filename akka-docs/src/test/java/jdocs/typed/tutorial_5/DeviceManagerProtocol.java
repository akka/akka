/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_5;

import akka.actor.typed.ActorRef;

import java.util.Map;
import java.util.Set;

abstract class DeviceManagerProtocol {
  // no instances of DeviceManagerProtocol class
  private DeviceManagerProtocol() {}

  interface DeviceManagerMessage {}

  interface DeviceGroupMessage {}

  public static final class RequestTrackDevice implements DeviceManagerMessage, DeviceGroupMessage {
    public final String groupId;
    public final String deviceId;
    public final ActorRef<DeviceRegistered> replyTo;

    public RequestTrackDevice(String groupId, String deviceId, ActorRef<DeviceRegistered> replyTo) {
      this.groupId = groupId;
      this.deviceId = deviceId;
      this.replyTo = replyTo;
    }
  }

  public static final class DeviceRegistered {
    public final ActorRef<DeviceProtocol.DeviceMessage> device;

    public DeviceRegistered(ActorRef<DeviceProtocol.DeviceMessage> device) {
      this.device = device;
    }
  }

  public static final class RequestDeviceList implements DeviceManagerMessage, DeviceGroupMessage {
    final long requestId;
    final String groupId;
    final ActorRef<ReplyDeviceList> replyTo;

    public RequestDeviceList(long requestId, String groupId, ActorRef<ReplyDeviceList> replyTo) {
      this.requestId = requestId;
      this.groupId = groupId;
      this.replyTo = replyTo;
    }
  }

  public static final class ReplyDeviceList {
    final long requestId;
    final Set<String> ids;

    public ReplyDeviceList(long requestId, Set<String> ids) {
      this.requestId = requestId;
      this.ids = ids;
    }
  }

  // #query-protocol
  interface DeviceGroupQueryMessage {}

  public static final class RequestAllTemperatures
      implements DeviceGroupQueryMessage, DeviceGroupMessage, DeviceManagerMessage {

    final long requestId;
    final String groupId;
    final ActorRef<RespondAllTemperatures> replyTo;

    public RequestAllTemperatures(
        long requestId, String groupId, ActorRef<RespondAllTemperatures> replyTo) {
      this.requestId = requestId;
      this.groupId = groupId;
      this.replyTo = replyTo;
    }
  }

  public static final class RespondAllTemperatures {
    final long requestId;
    final Map<String, TemperatureReading> temperatures;

    public RespondAllTemperatures(long requestId, Map<String, TemperatureReading> temperatures) {
      this.requestId = requestId;
      this.temperatures = temperatures;
    }
  }

  public static interface TemperatureReading {}

  public static final class Temperature implements TemperatureReading {
    public final double value;

    public Temperature(double value) {
      this.value = value;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Temperature that = (Temperature) o;

      return Double.compare(that.value, value) == 0;
    }

    @Override
    public int hashCode() {
      long temp = Double.doubleToLongBits(value);
      return (int) (temp ^ (temp >>> 32));
    }

    @Override
    public String toString() {
      return "Temperature{" + "value=" + value + '}';
    }
  }

  public enum TemperatureNotAvailable implements TemperatureReading {
    INSTANCE
  }

  public enum DeviceNotAvailable implements TemperatureReading {
    INSTANCE
  }

  public enum DeviceTimedOut implements TemperatureReading {
    INSTANCE
  }
  // #query-protocol

}
