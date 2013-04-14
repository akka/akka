/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.serialization

import akka.serialization.{ Serializer, SerializationExtension }
import java.io.Serializable
import com.google.protobuf.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.{ Actor, ActorRef, Deploy, ExtendedActorSystem, NoScopeGiven, Props, Scope }
import akka.remote.DaemonMsgCreate
import akka.remote.RemoteProtocol.{ DaemonMsgCreateProtocol, DeployProtocol, PropsProtocol }
import akka.routing.{ NoRouter, RouterConfig }
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
  import Deploy.NoDispatcherGiven

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
        if (d.dispatcher != NoDispatcherGiven)
          builder.setDispatcher(d.dispatcher)
        builder.build
      }

      def propsProto = {
        val builder = PropsProtocol.newBuilder
          .setClazz(props.clazz.getName)
          .setDeploy(deployProto(props.deploy))
        props.args map serialize foreach builder.addArgs
        props.args map (_.getClass.getName) foreach builder.addClasses
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
      val dispatcher =
        if (protoDeploy.hasDispatcher) protoDeploy.getDispatcher
        else NoDispatcherGiven
      Deploy(protoDeploy.getPath, config, routerConfig, scope, dispatcher)
    }

    def props = {
      import scala.collection.JavaConverters._
      val clazz = system.dynamicAccess.getClassFor[AnyRef](proto.getProps.getClazz).get
      val args: Vector[AnyRef] = (proto.getProps.getArgsList.asScala zip proto.getProps.getClassesList.asScala)
        .map(p ⇒ deserialize(p._1, system.dynamicAccess.getClassFor[AnyRef](p._2).get))(collection.breakOut)
      Props(deploy(proto.getProps.getDeploy), clazz, args)
    }

    DaemonMsgCreate(
      props = props,
      deploy = deploy(proto.getDeploy),
      path = proto.getPath,
      supervisor = deserializeActorRef(system, proto.getSupervisor))
  }

  protected def serialize(any: Any): ByteString = ByteString.copyFrom(serialization.serialize(any.asInstanceOf[AnyRef]).get)

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
