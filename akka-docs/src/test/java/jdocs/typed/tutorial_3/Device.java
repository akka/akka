/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_3;

// #full-device

import java.util.Optional;

import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

// #full-device
import static jdocs.typed.tutorial_3.DeviceProtocol.*;
/*
//#full-device
import static com.lightbend.akka.sample.DeviceProtocol.*;
//#full-device
*/
// #full-device

public class Device extends AbstractBehavior<DeviceMessage> {

  public static Behavior<DeviceMessage> createBehavior(String groupId, String deviceId) {
    return Behaviors.setup(context -> new Device(context, groupId, deviceId));
  }

  private final ActorContext<DeviceMessage> context;
  private final String groupId;
  private final String deviceId;

  private Optional<Double> lastTemperatureReading = Optional.empty();

  public Device(ActorContext<DeviceMessage> context, String groupId, String deviceId) {
    this.context = context;
    this.groupId = groupId;
    this.deviceId = deviceId;

    context.getLog().info("Device actor {}-{} started", groupId, deviceId);
  }

  @Override
  public Receive<DeviceMessage> createReceive() {
    return newReceiveBuilder()
        .onMessage(RecordTemperature.class, this::recordTemperature)
        .onMessage(ReadTemperature.class, this::readTemperature)
        .onSignal(PostStop.class, signal -> postStop())
        .build();
  }

  private Behavior<DeviceMessage> recordTemperature(RecordTemperature r) {
    context.getLog().info("Recorded temperature reading {} with {}", r.value, r.requestId);
    lastTemperatureReading = Optional.of(r.value);
    r.replyTo.tell(new TemperatureRecorded(r.requestId));
    return this;
  }

  private Behavior<DeviceMessage> readTemperature(ReadTemperature r) {
    r.replyTo.tell(new RespondTemperature(r.requestId, lastTemperatureReading));
    return this;
  }

  private Behavior<DeviceMessage> postStop() {
    context.getLog().info("Device actor {}-{} stopped", groupId, deviceId);
    return Behaviors.stopped();
  }
}
// #full-device
