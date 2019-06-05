/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_4;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

import java.util.HashMap;
import java.util.Map;

import static jdocs.typed.tutorial_4.DeviceManagerProtocol.*;
import static jdocs.typed.tutorial_4.DeviceProtocol.DeviceMessage;

// #device-group-full
// #device-group-remove
// #device-group-register
public class DeviceGroup extends AbstractBehavior<DeviceGroupMessage> {

  public static Behavior<DeviceGroupMessage> createBehavior(String groupId) {
    return Behaviors.setup(context -> new DeviceGroup(context, groupId));
  }

  // #device-terminated
  private class DeviceTerminated implements DeviceGroupMessage {
    public final ActorRef<DeviceProtocol.DeviceMessage> device;
    public final String groupId;
    public final String deviceId;

    DeviceTerminated(
        ActorRef<DeviceProtocol.DeviceMessage> device, String groupId, String deviceId) {
      this.device = device;
      this.groupId = groupId;
      this.deviceId = deviceId;
    }
  }
  // #device-terminated

  private final ActorContext<DeviceGroupMessage> context;
  private final String groupId;
  private final Map<String, ActorRef<DeviceMessage>> deviceIdToActor = new HashMap<>();

  public DeviceGroup(ActorContext<DeviceGroupMessage> context, String groupId) {
    this.context = context;
    this.groupId = groupId;
    context.getLog().info("DeviceGroup {} started", groupId);
  }

  private DeviceGroup onTrackDevice(RequestTrackDevice trackMsg) {
    if (this.groupId.equals(trackMsg.groupId)) {
      ActorRef<DeviceMessage> deviceActor = deviceIdToActor.get(trackMsg.deviceId);
      if (deviceActor != null) {
        trackMsg.replyTo.tell(new DeviceRegistered(deviceActor));
      } else {
        context.getLog().info("Creating device actor for {}", trackMsg.deviceId);
        deviceActor =
            context.spawn(
                Device.createBehavior(groupId, trackMsg.deviceId), "device-" + trackMsg.deviceId);
        // #device-group-register
        context.watchWith(
            deviceActor, new DeviceTerminated(deviceActor, groupId, trackMsg.deviceId));
        // #device-group-register
        deviceIdToActor.put(trackMsg.deviceId, deviceActor);
        trackMsg.replyTo.tell(new DeviceRegistered(deviceActor));
      }
    } else {
      context
          .getLog()
          .warning(
              "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
              groupId,
              this.groupId);
    }
    return this;
  }

  // #device-group-register
  // #device-group-remove

  private DeviceGroup onDeviceList(RequestDeviceList r) {
    r.replyTo.tell(new ReplyDeviceList(r.requestId, deviceIdToActor.keySet()));
    return this;
  }
  // #device-group-remove

  private DeviceGroup onTerminated(DeviceTerminated t) {
    context.getLog().info("Device actor for {} has been terminated", t.deviceId);
    deviceIdToActor.remove(t.deviceId);
    return this;
  }
  // #device-group-register

  @Override
  public Receive<DeviceGroupMessage> createReceive() {
    return newReceiveBuilder()
        .onMessage(RequestTrackDevice.class, this::onTrackDevice)
        // #device-group-register
        // #device-group-remove
        .onMessage(RequestDeviceList.class, r -> r.groupId.equals(groupId), this::onDeviceList)
        // #device-group-remove
        .onMessage(DeviceTerminated.class, this::onTerminated)
        .onSignal(PostStop.class, signal -> postStop())
        // #device-group-register
        .build();
  }

  private DeviceGroup postStop() {
    context.getLog().info("DeviceGroup {} stopped", groupId);
    return this;
  }
}
// #device-group-register
// #device-group-remove
// #device-group-full
