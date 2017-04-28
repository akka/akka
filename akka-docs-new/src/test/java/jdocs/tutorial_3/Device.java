/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.tutorial_3;

//#device-with-register

import akka.actor.AbstractActor;
import akka.actor.Props;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import jdocs.tutorial_3.DeviceManager.DeviceRegistered;
import jdocs.tutorial_3.DeviceManager.RequestTrackDevice;

import java.util.Optional;

public class Device extends AbstractActor {
  LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  final String groupId;

  final String deviceId;

  public Device(String groupId, String deviceId) {
    this.groupId = groupId;
    this.deviceId = deviceId;
  }

  public static Props props(String groupId, String deviceId) {
    return Props.create(Device.class, groupId, deviceId);
  }

  final static class RecordTemperature {
    final Long requestId;
    final Double value;

    public RecordTemperature(Long requestId, Double value) {
      this.requestId = requestId;
      this.value = value;
    }
  }

  final static class TemperatureRecorded {
    final Long requestId;

    public TemperatureRecorded(Long requestId) {
      this.requestId = requestId;
    }
  }

  final static class ReadTemperature {
    final Long requestId;

    public ReadTemperature(Long requestId) {
      this.requestId = requestId;
    }
  }

  final static class RespondTemperature {
    final Long requestId;
    final Optional<Double> value;

    public RespondTemperature(Long requestId, Optional<Double> value) {
      this.requestId = requestId;
      this.value = value;
    }
  }

  Optional<Double> lastTemperatureReading = Optional.empty();

  @Override
  public void preStart() {
    log.info("Device actor {}-{} started", groupId, deviceId);
  }

  @Override
  public void postStop() {
    log.info("Device actor {}-{} stopped", groupId, deviceId);
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
            .match(RequestTrackDevice.class, r -> {
              if (this.groupId.equals(r.groupId) && this.deviceId.equals(r.deviceId)) {
                sender().tell(new DeviceRegistered(), getSelf());
              } else {
                log.warning(
                        "Ignoring TrackDevice request for {}-{}.This actor is responsible for {}-{}.",
                        r.groupId, r.deviceId, this.groupId, this.deviceId
                );
              }
            })
            .match(RecordTemperature.class, r -> {
              log.info("Recorded temperature reading {} with {}", r.value, r.requestId);
              lastTemperatureReading = Optional.of(r.value);
              sender().tell(new TemperatureRecorded(r.requestId), getSelf());
            })
            .match(ReadTemperature.class, r -> {
              sender().tell(new RespondTemperature(r.requestId, lastTemperatureReading), getSelf());
            })
            .build();
  }
}
//#device-with-register
