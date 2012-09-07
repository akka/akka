/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.serialization

import java.io.Serializable
import com.google.protobuf.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.{ Actor, ActorRef, Deploy, ExtendedActorSystem, NoScopeGiven, Props, Scope }
import akka.remote.DaemonMsgCreate
import akka.remote.RemoteProtocol.{ DaemonMsgCreateProtocol, DeployProtocol, PropsProtocol }
import akka.routing.{ NoRouter, RouterConfig }
import akka.actor.FromClassCreator
import scala.reflect.ClassTag
import util.{ Failure, Success }

/**
 * Serializes akka's internal DaemonMsgCreate using protobuf
 * for the core structure of DaemonMsgCreate, Props and Deploy.
 * Serialization of contained RouterConfig, Config, and Scope
 * is done with configured serializer for those classes, by
 * default java.io.Serializable.
 *
 * INTERNAL API
 */
private[akka] class DaemonMsgCreateSerializer(val system: ExtendedActorSystem) extends Serializer {
  import ProtobufSerializer.serializeActorRef
  import ProtobufSerializer.deserializeActorRef

  def includeManifest: Boolean = false
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
          setDispatcher(props.dispatcher).
          setDeploy(deployProto(props.deploy))
        props.creator match {
          case FromClassCreator(clazz) ⇒ builder.setFromClassCreator(clazz.getName)
          case creator                 ⇒ builder.setCreator(serialize(creator))
        }
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

    def deploy(protoDeploy: DeployProtocol): Deploy = {
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
      val creator =
        if (proto.getProps.hasFromClassCreator)
          FromClassCreator(system.dynamicAccess.getClassFor[Actor](proto.getProps.getFromClassCreator).get)
        else
          deserialize(proto.getProps.getCreator, classOf[() ⇒ Actor])

      val routerConfig =
        if (proto.getProps.hasRouterConfig) deserialize(proto.getProps.getRouterConfig, classOf[RouterConfig])
        else NoRouter

      Props(
        creator = creator,
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

  protected def serialize(any: AnyRef): ByteString = ByteString.copyFrom(serialization.serialize(any).get)

  protected def deserialize[T: ClassTag](data: ByteString, clazz: Class[T]): T = {
    val bytes = data.toByteArray
    serialization.deserialize(bytes, clazz) match {
      case Success(x: T)  ⇒ x
      case Success(other) ⇒ throw new IllegalArgumentException("Can't deserialize to [%s], got [%s]".format(clazz.getName, other))
      case Failure(e) ⇒
        // Fallback to the java serializer, because some interfaces don't implement java.io.Serializable,
        // but the impl instance does. This could be optimized by adding java serializers in reference.conf:
        // com.typesafe.config.Config
        // akka.routing.RouterConfig
        // akka.actor.Scope
        serialization.deserialize(bytes, classOf[java.io.Serializable]) match {
          case Success(x: T) ⇒ x
          case _             ⇒ throw e // the first exception
        }
    }
  }
}