/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.typed.tutorial_3.inprogress2;

// #device-with-read

import akka.actor.typed.Behavior;
import akka.actor.typed.PostStop;
import akka.actor.typed.javadsl.AbstractBehavior;
import akka.actor.typed.javadsl.ActorContext;
import akka.actor.typed.javadsl.Behaviors;
import akka.actor.typed.javadsl.Receive;

import java.util.Optional;

// #device-with-read
import static jdocs.typed.tutorial_3.inprogress2.DeviceProtocol.*;
/*
//#device-with-read
import static com.lightbend.akka.sample.DeviceProtocol.*;
//#device-with-read
*/
// #device-with-read

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
    return receiveBuilder()
        .onMessage(ReadTemperature.class, this::readTemperature)
        .onSignal(PostStop.class, signal -> postStop())
        .build();
  }

  private Behavior<DeviceMessage> readTemperature(ReadTemperature r) {
    r.replyTo.tell(new RespondTemperature(r.requestId, lastTemperatureReading));
    return this;
  }

  private Device postStop() {
    context.getLog().info("Device actor {}-{} stopped", groupId, deviceId);
    return this;
  }
}

// #device-with-read
