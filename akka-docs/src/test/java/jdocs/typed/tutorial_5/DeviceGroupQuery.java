/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_5;

import akka.actor.typed.ActorRef;
import akka.actor.typed.Behavior;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;
import akka.actor.typed.javadsl.TimerScheduler;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static jdocs.typed.tutorial_5.DeviceManagerProtocol.*;

// #query-full
// #query-outline
public class DeviceGroupQuery extends AbstractBehavior<DeviceGroupQueryMessage> {

  public static Behavior<DeviceGroupQueryMessage> createBehavior(
      Map<String, ActorRef<DeviceProtocol.DeviceMessage>> deviceIdToActor,
      long requestId,
      ActorRef<RespondAllTemperatures> requester,
      Duration timeout) {
    return Behaviors.setup(
        context ->
            Behaviors.withTimers(
                timers ->
                    new DeviceGroupQuery(
                        deviceIdToActor, requestId, requester, timeout, context, timers)));
  }

  private static enum CollectionTimeout implements DeviceGroupQueryMessage {
    INSTANCE
  }

  static class WrappedRespondTemperature implements DeviceGroupQueryMessage {
    final DeviceProtocol.RespondTemperature response;

    WrappedRespondTemperature(DeviceProtocol.RespondTemperature response) {
      this.response = response;
    }
  }

  private static class DeviceTerminated implements DeviceGroupQueryMessage {
    final String deviceId;

    private DeviceTerminated(String deviceId) {
      this.deviceId = deviceId;
    }
  }

  private final long requestId;
  private final ActorRef<RespondAllTemperatures> requester;
  // #query-outline
  // #query-state
  private Map<String, TemperatureReading> repliesSoFar = new HashMap<>();
  private final Set<String> stillWaiting;

  // #query-state
  // #query-outline

  public DeviceGroupQuery(
      Map<String, ActorRef<DeviceProtocol.DeviceMessage>> deviceIdToActor,
      long requestId,
      ActorRef<RespondAllTemperatures> requester,
      Duration timeout,
      ActorContext<DeviceGroupQueryMessage> context,
      TimerScheduler<DeviceGroupQueryMessage> timers) {
    this.requestId = requestId;
    this.requester = requester;

    timers.startSingleTimer(CollectionTimeout.class, CollectionTimeout.INSTANCE, timeout);

    ActorRef<DeviceProtocol.RespondTemperature> respondTemperatureAdapter =
        context.messageAdapter(
            DeviceProtocol.RespondTemperature.class, WrappedRespondTemperature::new);

    for (Map.Entry<String, ActorRef<DeviceProtocol.DeviceMessage>> entry :
        deviceIdToActor.entrySet()) {
      context.watchWith(entry.getValue(), new DeviceTerminated(entry.getKey()));
      entry.getValue().tell(new DeviceProtocol.ReadTemperature(0L, respondTemperatureAdapter));
    }
    stillWaiting = new HashSet<>(deviceIdToActor.keySet());
  }

  // #query-outline
  // #query-state
  @Override
  public Receive<DeviceGroupQueryMessage> createReceive() {
    return receiveBuilder()
        .onMessage(WrappedRespondTemperature.class, this::onRespondTemperature)
        .onMessage(DeviceTerminated.class, this::onDeviceTerminated)
        .onMessage(CollectionTimeout.class, this::onCollectionTimeout)
        .build();
  }

  private Behavior<DeviceGroupQueryMessage> onRespondTemperature(WrappedRespondTemperature r) {
    TemperatureReading reading =
        r.response
            .value
            .map(v -> (TemperatureReading) new Temperature(v))
            .orElse(TemperatureNotAvailable.INSTANCE);

    String deviceId = r.response.deviceId;
    repliesSoFar.put(deviceId, reading);
    stillWaiting.remove(deviceId);

    return respondWhenAllCollected();
  }

  private Behavior<DeviceGroupQueryMessage> onDeviceTerminated(DeviceTerminated terminated) {
    if (stillWaiting.contains(terminated.deviceId)) {
      repliesSoFar.put(terminated.deviceId, DeviceNotAvailable.INSTANCE);
      stillWaiting.remove(terminated.deviceId);
    }
    return respondWhenAllCollected();
  }

  private Behavior<DeviceGroupQueryMessage> onCollectionTimeout(CollectionTimeout timeout) {
    for (String deviceId : stillWaiting) {
      repliesSoFar.put(deviceId, DeviceTimedOut.INSTANCE);
    }
    stillWaiting.clear();
    return respondWhenAllCollected();
  }
  // #query-state

  // #query-collect-reply
  private Behavior<DeviceGroupQueryMessage> respondWhenAllCollected() {
    if (stillWaiting.isEmpty()) {
      requester.tell(new RespondAllTemperatures(requestId, repliesSoFar));
      return Behaviors.stopped();
    } else {
      return this;
    }
  }
  // #query-collect-reply
  // #query-outline

}
// #query-outline
// #query-full
