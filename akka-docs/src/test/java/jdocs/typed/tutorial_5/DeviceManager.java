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

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static jdocs.typed.tutorial_5.DeviceManagerProtocol.*;

public class DeviceManager extends AbstractBehavior<DeviceManagerMessage> {

  public static Behavior<DeviceManagerMessage> createBehavior() {
    return Behaviors.setup(DeviceManager::new);
  }

  private static class DeviceGroupTerminated implements DeviceManagerMessage {
    public final String groupId;

    DeviceGroupTerminated(String groupId) {
      this.groupId = groupId;
    }
  }

  private final ActorContext<DeviceManagerMessage> context;
  private final Map<String, ActorRef<DeviceGroupMessage>> groupIdToActor = new HashMap<>();

  public DeviceManager(ActorContext<DeviceManagerMessage> context) {
    this.context = context;
    context.getLog().info("DeviceManager started");
  }

  private DeviceManager onTrackDevice(RequestTrackDevice trackMsg) {
    String groupId = trackMsg.groupId;
    ActorRef<DeviceGroupMessage> ref = groupIdToActor.get(groupId);
    if (ref != null) {
      ref.tell(trackMsg);
    } else {
      context.getLog().info("Creating device group actor for {}", groupId);
      ActorRef<DeviceGroupMessage> groupActor =
          context.spawn(DeviceGroup.createBehavior(groupId), "group-" + groupId);
      context.watchWith(groupActor, new DeviceGroupTerminated(groupId));
      groupActor.tell(trackMsg);
      groupIdToActor.put(groupId, groupActor);
    }
    return this;
  }

  private DeviceManager onRequestDeviceList(RequestDeviceList request) {
    ActorRef<DeviceGroupMessage> ref = groupIdToActor.get(request.groupId);
    if (ref != null) {
      ref.tell(request);
    } else {
      request.replyTo.tell(new ReplyDeviceList(request.requestId, Collections.emptySet()));
    }
    return this;
  }

  private DeviceManager onRequestAllTemperatures(RequestAllTemperatures request) {
    ActorRef<DeviceGroupMessage> ref = groupIdToActor.get(request.groupId);
    if (ref != null) {
      ref.tell(request);
    } else {
      request.replyTo.tell(new RespondAllTemperatures(request.requestId, Collections.emptyMap()));
    }
    return this;
  }

  private DeviceManager onTerminated(DeviceGroupTerminated t) {
    context.getLog().info("Device group actor for {} has been terminated", t.groupId);
    groupIdToActor.remove(t.groupId);
    return this;
  }

  public Receive<DeviceManagerMessage> createReceive() {
    return newReceiveBuilder()
        .onMessage(RequestTrackDevice.class, this::onTrackDevice)
        .onMessage(RequestDeviceList.class, this::onRequestDeviceList)
        .onMessage(RequestAllTemperatures.class, this::onRequestAllTemperatures)
        .onMessage(DeviceGroupTerminated.class, this::onTerminated)
        .onSignal(PostStop.class, signal -> postStop())
        .build();
  }

  private DeviceManager postStop() {
    context.getLog().info("DeviceManager stopped");
    return this;
  }
}
