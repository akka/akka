/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package typed.tutorial_4

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

//#device-manager-full
object DeviceManager {
  def apply(): Behavior[DeviceManagerMessage] =
    Behaviors.setup(context => new DeviceManager(context))

  //#device-manager-msgs
  import DeviceGroup.DeviceGroupMessage

  sealed trait DeviceManagerMessage

  //#device-registration-msgs
  final case class RequestTrackDevice(groupId: String, deviceId: String, replyTo: ActorRef[DeviceRegistered])
      extends DeviceManagerMessage
      with DeviceGroupMessage

  final case class DeviceRegistered(device: ActorRef[Device.DeviceMessage])
  //#device-registration-msgs

  //#device-list-msgs
  final case class RequestDeviceList(requestId: Long, groupId: String, replyTo: ActorRef[ReplyDeviceList])
      extends DeviceManagerMessage
      with DeviceGroupMessage

  final case class ReplyDeviceList(requestId: Long, ids: Set[String])
  //#device-list-msgs

  private final case class DeviceGroupTerminated(groupId: String) extends DeviceManagerMessage
  //#device-manager-msgs
}

class DeviceManager(context: ActorContext[DeviceManager.DeviceManagerMessage])
    extends AbstractBehavior[DeviceManager.DeviceManagerMessage] {
  import DeviceManager._
  import DeviceGroup.DeviceGroupMessage

  var groupIdToActor = Map.empty[String, ActorRef[DeviceGroupMessage]]

  context.log.info("DeviceManager started")

  override def onMessage(msg: DeviceManagerMessage): Behavior[DeviceManagerMessage] =
    msg match {
      case trackMsg @ RequestTrackDevice(groupId, _, replyTo) =>
        groupIdToActor.get(groupId) match {
          case Some(ref) =>
            ref ! trackMsg
          case None =>
            context.log.info("Creating device group actor for {}", groupId)
            val groupActor = context.spawn(DeviceGroup(groupId), "group-" + groupId)
            context.watchWith(groupActor, DeviceGroupTerminated(groupId))
            groupActor ! trackMsg
            groupIdToActor += groupId -> groupActor
        }
        this

      case req @ RequestDeviceList(requestId, groupId, replyTo) =>
        groupIdToActor.get(groupId) match {
          case Some(ref) =>
            ref ! req
          case None =>
            replyTo ! ReplyDeviceList(requestId, Set.empty)
        }
        this

      case DeviceGroupTerminated(groupId) =>
        context.log.info("Device group actor for {} has been terminated", groupId)
        groupIdToActor -= groupId
        this
    }

  override def onSignal: PartialFunction[Signal, Behavior[DeviceManagerMessage]] = {
    case PostStop =>
      context.log.info("DeviceManager stopped")
      this
  }

}
//#device-manager-full
