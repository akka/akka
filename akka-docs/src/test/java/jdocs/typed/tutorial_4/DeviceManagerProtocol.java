/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_4;

import akka.actor.typed.ActorRef;

import java.util.Set;

//#device-registration-msgs
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
  //#device-registration-msgs

  //#device-list-msgs
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
  //#device-list-msgs

  //#device-registration-msgs
}
//#device-registration-msgs
