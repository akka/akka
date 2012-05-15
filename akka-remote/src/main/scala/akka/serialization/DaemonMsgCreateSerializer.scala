/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import java.io.Serializable

import com.google.protobuf.ByteString
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import akka.actor.Actor
import akka.actor.ActorRef
import akka.actor.Deploy
import akka.actor.ExtendedActorSystem
import akka.actor.NoScopeGiven
import akka.actor.Props
import akka.actor.Scope
import akka.remote.DaemonMsgCreate
import akka.remote.RemoteProtocol.ActorRefProtocol
import akka.remote.RemoteProtocol.DaemonMsgCreateProtocol
import akka.remote.RemoteProtocol.DeployProtocol
import akka.remote.RemoteProtocol.PropsProtocol
import akka.routing.NoRouter
import akka.routing.RouterConfig

/**
 * Serializes akka's internal DaemonMsgCreate using protobuf
 * for the core structure of DaemonMsgCreate, Props and Deploy.
 * Serialization of contained RouterConfig, Config, Scope, and creator (scala.Function0)
 * is done with configured serializer for those classes, by default java.io.Serializable.
 */
class DaemonMsgCreateSerializer(val system: ExtendedActorSystem) extends Serializer {
  import ProtobufSerializer.serializeActorRef
  import ProtobufSerializer.deserializeActorRef

  def includeManifest: Boolean = true
  def identifier = 3
  lazy val serialization = SerializationExtension(system)

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case DaemonMsgCreate(props, deploy, path, supervisor) ⇒

      def deployProto(d: Deploy): DeployProtocol = {
        val builder = DeployProtocol.newBuilder.setPath(d.path)
        if (d.config != ConfigFactory.empty)
          builder.setConfig(serialize(d.config))
        if (d.routerConfig != NoRouter)
          builder.setRouterConfig(serialize(d.routerConfig))
        if (d.scope != NoScopeGiven)
          builder.setScope(serialize(d.scope))
        builder.build
      }

      def propsProto = {
        val builder = PropsProtocol.newBuilder.
          setCreator(serialize(props.creator)).
          setDispatcher(props.dispatcher).
          setDeploy(deployProto(props.deploy))
        if (props.routerConfig != NoRouter)
          builder.setRouterConfig(serialize(props.routerConfig))
        builder.build
      }

      DaemonMsgCreateProtocol.newBuilder.
        setProps(propsProto).
        setDeploy(deployProto(deploy)).
        setPath(path).
        setSupervisor(serializeActorRef(supervisor)).
        build.toByteArray

    case _ ⇒
      throw new IllegalArgumentException(
        "Can't serialize a non-DaemonMsgCreate message using DaemonMsgCreateSerializer [%s]".format(obj))
  }

  def fromBinary(bytes: Array[Byte], clazz: Option[Class[_]]): AnyRef = {
    val proto = DaemonMsgCreateProtocol.parseFrom(bytes)

    def deploy(protoDeploy: DeployProtocol) = {
      val config =
        if (protoDeploy.hasConfig) deserialize(protoDeploy.getConfig, classOf[Config])
        else ConfigFactory.empty
      val routerConfig =
        if (protoDeploy.hasRouterConfig) deserialize(protoDeploy.getRouterConfig, classOf[RouterConfig])
        else NoRouter
      val scope =
        if (protoDeploy.hasScope) deserialize(protoDeploy.getScope, classOf[Scope])
        else NoScopeGiven
      Deploy(protoDeploy.getPath, config, routerConfig, scope)
    }

    def props = {
      val routerConfig =
        if (proto.getProps.hasRouterConfig) deserialize(proto.getProps.getRouterConfig, classOf[RouterConfig])
        else NoRouter
      Props(
        creator = deserialize(proto.getProps.getCreator, classOf[() ⇒ Actor]),
        dispatcher = proto.getProps.getDispatcher,
        routerConfig = routerConfig,
        deploy = deploy(proto.getProps.getDeploy))
    }

    DaemonMsgCreate(
      props = props,
      deploy = deploy(proto.getDeploy),
      path = proto.getPath,
      supervisor = deserializeActorRef(system, proto.getSupervisor))
  }

  protected def serialize(any: AnyRef): ByteString =
    serialization.serialize(any) match {
      case Right(bytes) ⇒ ByteString.copyFrom(bytes)
      case Left(e)      ⇒ throw e
    }

  protected def deserialize[T: ClassManifest](data: ByteString, clazz: Class[T]): T = {
    val bytes = data.toByteArray
    serialization.deserialize(bytes, clazz) match {
      case Right(x) if classManifest[T].erasure.isInstance(x) ⇒ x.asInstanceOf[T]
      case Right(other) ⇒ throw new IllegalArgumentException("Can't deserialize to [%s], got [%s]".
        format(clazz.getName, other))
      case Left(e) ⇒
        // Fallback to the java serializer, because some interfaces don't implement java.io.Serializable,
        // but the impl instance does. This could be optimized by adding java serializers in reference.conf:
        // scala.Function0 (the creator)
        // com.typesafe.config.Config
        // akka.routing.RouterConfig
        // akka.actor.Scope
        serialization.deserialize(bytes, classOf[java.io.Serializable]) match {
          case Right(x) if classManifest[T].erasure.isInstance(x) ⇒ x.asInstanceOf[T]
          case _ ⇒ throw e // the first exception
        }
    }

  }

}