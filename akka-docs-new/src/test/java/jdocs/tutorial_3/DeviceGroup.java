/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package jdocs.tutorial_3;

import java.util.Set;
import java.util.Map;
import java.util.HashMap;

import akka.actor.AbstractActor;
import akka.actor.ActorRef;
import akka.actor.Props;
import akka.actor.Terminated;
import akka.event.Logging;
import akka.event.LoggingAdapter;

import jdocs.tutorial_3.Device;
import jdocs.tutorial_3.DeviceManager;

//#device-group-full
public class DeviceGroup extends AbstractActor {
  private final LoggingAdapter log = Logging.getLogger(getContext().getSystem(), this);

  final String groupId;

  public DeviceGroup(String groupId) {
    this.groupId = groupId;
  }

  //#device-group-register
  public static Props props(String groupId) {
    return Props.create(DeviceGroup.class, groupId);
  }
  //#device-group-register

  public static final class RequestDeviceList {
    final long requestId;

    public RequestDeviceList(long requestId) {
      this.requestId = requestId;
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
  //#device-group-register
//#device-group-register
//#device-group-register
//#device-group-remove

  Map<String, ActorRef> deviceIdToActor = new HashMap<>();
  //#device-group-register
  Map<ActorRef, String> actorToDeviceId = new HashMap<>();
  //#device-group-register

  @Override
  public void preStart() {
    log.info("DeviceGroup {} started", groupId);
  }

  @Override
  public void postStop() {
    log.info("DeviceGroup {} stopped", groupId);
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
            .match(DeviceManager.RequestTrackDevice.class, trackMsg -> {
              if (this.groupId.equals(trackMsg.groupId)) {
                ActorRef deviceActor = deviceIdToActor.get(trackMsg.deviceId);
                if (deviceActor != null) {
                  deviceActor.forward(trackMsg, getContext());
                } else {
                  log.info("Creating device actor for {}", trackMsg.deviceId);
                  deviceActor = getContext().actorOf(Device.props(groupId, trackMsg.deviceId), "device-" + trackMsg.deviceId);
                  //#device-group-register
                  getContext().watch(deviceActor);
                  actorToDeviceId.put(deviceActor, trackMsg.deviceId);
                  //#device-group-register
                  deviceIdToActor.put(trackMsg.deviceId, deviceActor);
                  deviceActor.forward(trackMsg, getContext());
                }
              } else {
                log.warning(
                        "Ignoring TrackDevice request for {}. This actor is responsible for {}.",
                        groupId, this.groupId
                );
              }
            })
            .match(RequestDeviceList.class, r -> {
              getSender().tell(new ReplyDeviceList(r.requestId, deviceIdToActor.keySet()), getSelf());
            })
            .match(Terminated.class, t -> {
              ActorRef deviceActor = t.getActor();
              String deviceId = actorToDeviceId.get(deviceActor);
              log.info("Device actor for {} has been terminated", deviceId);
              actorToDeviceId.remove(deviceActor);
              deviceIdToActor.remove(deviceId);
            })
            .build();
  }
}
//#device-group-remove
//#device-group-register
//#device-group-full
