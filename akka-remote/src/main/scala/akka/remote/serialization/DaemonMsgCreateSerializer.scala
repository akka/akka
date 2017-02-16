/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.remote.serialization

import akka.serialization.{ BaseSerializer, SerializationExtension, SerializerWithStringManifest }
import akka.protobuf.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import akka.actor.{ Deploy, ExtendedActorSystem, NoScopeGiven, Props, Scope }
import akka.remote.DaemonMsgCreate
import akka.remote.WireFormats.{ DaemonMsgCreateData, DeployData, PropsData, SerializedMessage }
import akka.routing.{ NoRouter, RouterConfig }

import scala.reflect.ClassTag
import util.{ Failure, Success }
import java.io.Serializable

/**
 * Serializes Akka's internal DaemonMsgCreate using protobuf
 * for the core structure of DaemonMsgCreate, Props and Deploy.
 * Serialization of contained RouterConfig, Config, and Scope
 * is done with configured serializer for those classes, by
 * default java.io.Serializable.
 *
 * INTERNAL API
 */
private[akka] class DaemonMsgCreateSerializer(val system: ExtendedActorSystem) extends BaseSerializer {
  import ProtobufSerializer.serializeActorRef
  import ProtobufSerializer.deserializeActorRef
  import Deploy.NoDispatcherGiven

  @deprecated("Use constructor with ExtendedActorSystem", "2.4")
  def this() = this(null)

  private val scala212OrLater = !scala.util.Properties.versionNumberString.startsWith("2.11")

  // TODO remove this when deprecated this() is removed
  override val identifier: Int =
    if (system eq null) 3
    else identifierFromConfig

  def includeManifest: Boolean = false

  lazy val serialization = SerializationExtension(system)

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case DaemonMsgCreate(props, deploy, path, supervisor) ⇒

      def deployProto(d: Deploy): DeployData = {
        val builder = DeployData.newBuilder.setPath(d.path)
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
        val builder = PropsData.newBuilder
          .setClazz(props.clazz.getName)
          .setDeploy(deployProto(props.deploy))
        props.args.map(serializeToSerializedMessage).foreach(builder.addPropsArgs)
        builder.build
      }

      DaemonMsgCreateData.newBuilder.
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
    val proto = DaemonMsgCreateData.parseFrom(bytes)

    def deploy(protoDeploy: DeployData): Deploy = {
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
      val actorClass = system.dynamicAccess.getClassFor[AnyRef](proto.getProps.getClazz).get
      val args: Vector[AnyRef] =
        if (proto.getProps.getPropsArgsCount > 0) {
          // message from a 2.5+ node, which includes string manifest and serializer id
          proto.getProps.getPropsArgsList.asScala.map(deSerializeFromSerializedMessage)(collection.breakOut)
        } else {
          // message from an old node, which only provides data and class name
          (proto.getProps.getArgsList.asScala zip proto.getProps.getClassesList.asScala)
            .map(deserialize)(collection.breakOut)
        }
      Props(deploy(proto.getProps.getDeploy), actorClass, args)
    }

    DaemonMsgCreate(
      props = props,
      deploy = deploy(proto.getDeploy),
      path = proto.getPath,
      supervisor = deserializeActorRef(system, proto.getSupervisor))
  }

  protected def serialize(any: Any): ByteString = ByteString.copyFrom(serialization.serialize(any.asInstanceOf[AnyRef]).get)

  private def serializeToSerializedMessage(any: Any): SerializedMessage = {
    val m = any.asInstanceOf[AnyRef]

    val serializer = serialization.findSerializerFor(m)
    val builder = SerializedMessage.newBuilder()

    serializer match {
      case ser: SerializerWithStringManifest ⇒
        val manifest = ser.manifest(m)
        if (manifest != "")
          builder.setMessageManifest(ByteString.copyFromUtf8(manifest))
      case _ ⇒
    }

    builder.setSerializerId(serializer.identifier)
      .setMessage(ByteString.copyFrom(serializer.toBinary(m)))
    builder.build()
  }

  protected def deserialize(p: (ByteString, String)): AnyRef =
    if (p._1.isEmpty && p._2 == "null") null
    else deserialize(p._1, system.dynamicAccess.getClassFor[AnyRef](p._2).get)

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

  private def deSerializeFromSerializedMessage(m: SerializedMessage): AnyRef = {
    serialization.deserialize(m.getMessage.toByteArray, m.getSerializerId, m.getMessageManifest.toStringUtf8).get
  }
}
