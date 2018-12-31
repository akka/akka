/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_5;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

import static jdocs.typed.tutorial_5.DeviceManagerProtocol.*;
import static jdocs.typed.tutorial_5.DeviceProtocol.DeviceMessage;

//#query-added
public class DeviceGroup extends AbstractBehavior<DeviceGroupMessage> {

  public static Behavior<DeviceGroupMessage> createBehavior(String groupId) {
    return Behaviors.setup(context -> new DeviceGroup(context, groupId));
  }

  private class DeviceTerminated implements DeviceGroupMessage{
    public final ActorRef<DeviceProtocol.DeviceMessage> device;
    public final String groupId;
    public final String deviceId;

    DeviceTerminated(ActorRef<DeviceProtocol.DeviceMessage> device, String groupId, String deviceId) {
      this.device = device;
      this.groupId = groupId;
      this.deviceId = deviceId;
    }
  }

  private final ActorContext<DeviceGroupMessage> context;
  private final String groupId;
  private final Map<String, ActorRef<DeviceMessage>> deviceIdToActor = new HashMap<>();

  public DeviceGroup(ActorContext<DeviceGroupMessage> context, String groupId) {
    this.context = context;
    this.groupId = groupId;
    context.getLog().info("DeviceGroup {} started", groupId);
  }

  //#query-added
  private DeviceGroup onTrackDevice(RequestTrackDevice trackMsg) {
    if (this.groupId.equals(trackMsg.groupId)) {
      ActorRef<DeviceMessage> deviceActor = deviceIdToActor.get(trackMsg.deviceId);
      if (deviceActor != null) {
        trackMsg.replyTo.tell(new DeviceRegistered(deviceActor));
      } else {
        context.getLog().info("Creating device actor for {}", trackMsg.deviceId);
        deviceActor = context.spawn(Device.createBehavior(groupId, trackMsg.deviceId), "device-" + trackMsg.deviceId);
        context.watchWith(deviceActor, new DeviceTerminated(deviceActor, groupId, trackMsg.deviceId));
        deviceIdToActor.put(trackMsg.deviceId, deviceActor);
        trackMsg.replyTo.tell(new DeviceRegistered(deviceActor));
      }
    } else {
      context.getLog().warning(
              "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
              groupId, this.groupId
      );
    }
    return this;
  }

  private DeviceGroup onDeviceList(RequestDeviceList r) {
    r.replyTo.tell(new ReplyDeviceList(r.requestId, deviceIdToActor.keySet()));
    return this;
  }

  private DeviceGroup onTerminated(DeviceTerminated t) {
    context.getLog().info("Device actor for {} has been terminated", t.deviceId);
    deviceIdToActor.remove(t.deviceId);
    return this;
  }

  private DeviceGroup postStop() {
    context.getLog().info("DeviceGroup {} stopped", groupId);
    return this;
  }

  //#query-added

  private DeviceGroup onAllTemperatures(RequestAllTemperatures r) {
    // since Java collections are mutable, we want to avoid sharing them between actors (since multiple Actors (threads)
    // modifying the same mutable data-structure is not safe), and perform a defensive copy of the mutable map:
    //
    // Feel free to use your favourite immutable data-structures library with Akka in Java applications!
    Map<String, ActorRef<DeviceMessage>> deviceIdToActorCopy = new HashMap<>(this.deviceIdToActor);

    context.spawnAnonymous(DeviceGroupQuery.createBehavior(
      deviceIdToActorCopy, r.requestId, r.replyTo, Duration.ofSeconds(3)));

    return this;
  }

  @Override
  public Receive<DeviceGroupMessage> createReceive() {
    return receiveBuilder()
      //#query-added
      .onMessage(RequestTrackDevice.class, this::onTrackDevice)
      .onMessage(RequestDeviceList.class, r -> r.groupId.equals(groupId), this::onDeviceList)
      .onMessage(DeviceTerminated.class, this::onTerminated)
      .onSignal(PostStop.class, signal -> postStop())
      //#query-added
      // ... other cases omitted
      .onMessage(RequestAllTemperatures.class, r -> r.groupId.equals(groupId), this::onAllTemperatures)
      .build();
  }
}
//#query-added
