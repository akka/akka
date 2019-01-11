/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.remote.serialization

import scala.collection.immutable

import akka.serialization.{ BaseSerializer, SerializationExtension, SerializerWithStringManifest }
import akka.protobuf.ByteString
import akka.actor.{ Deploy, ExtendedActorSystem, NoScopeGiven, Props, Scope }
import akka.remote.DaemonMsgCreate
import akka.remote.WireFormats.{ DaemonMsgCreateData, DeployData, PropsData }
import akka.routing.{ NoRouter, RouterConfig }
import com.typesafe.config.{ Config, ConfigFactory }
import akka.util.ccompat._

import scala.reflect.ClassTag
import util.{ Failure, Success }

/**
 * Serializes Akka's internal DaemonMsgCreate using protobuf
 * for the core structure of DaemonMsgCreate, Props and Deploy.
 * Serialization of contained RouterConfig, Config, and Scope
 * is done with configured serializer for those classes, by
 * default java.io.Serializable.
 *
 * INTERNAL API
 */
private[akka] final class DaemonMsgCreateSerializer(val system: ExtendedActorSystem) extends BaseSerializer {
  import ProtobufSerializer.serializeActorRef
  import ProtobufSerializer.deserializeActorRef
  import Deploy.NoDispatcherGiven

  private val scala212OrLater = !scala.util.Properties.versionNumberString.startsWith("2.11")

  private lazy val serialization = SerializationExtension(system)

  override val includeManifest: Boolean = false

  def toBinary(obj: AnyRef): Array[Byte] = obj match {
    case DaemonMsgCreate(props, deploy, path, supervisor) ⇒

      def deployProto(d: Deploy): DeployData = {
        val builder = DeployData.newBuilder.setPath(d.path)

        {
          val (serId, _, manifest, bytes) = serialize(d.config)
          builder.setConfigSerializerId(serId)
          builder.setConfigManifest(manifest)
          builder.setConfig(ByteString.copyFrom(bytes))
        }

        if (d.routerConfig != NoRouter) {
          val (serId, _, manifest, bytes) = serialize(d.routerConfig)
          builder.setRouterConfigSerializerId(serId)
          builder.setRouterConfigManifest(manifest)
          builder.setRouterConfig(ByteString.copyFrom(bytes))
        }

        if (d.scope != NoScopeGiven) {
          val (serId, _, manifest, bytes) = serialize(d.scope)
          builder.setScopeSerializerId(serId)
          builder.setScopeManifest(manifest)
          builder.setScope(ByteString.copyFrom(bytes))
        }

        if (d.dispatcher != NoDispatcherGiven) {
          builder.setDispatcher(d.dispatcher)
        }
        builder.build
      }

      def propsProto = {
        val builder = PropsData.newBuilder
          .setClazz(props.clazz.getName)
          .setDeploy(deployProto(props.deploy))
        props.args.foreach { arg ⇒
          val (serializerId, hasManifest, manifest, bytes) = serialize(arg)
          builder.addArgs(ByteString.copyFrom(bytes))
          builder.addManifests(manifest)
          builder.addSerializerIds(serializerId)
          builder.addHasManifest(hasManifest)
        }
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
        if (protoDeploy.hasConfig) {
          if (protoDeploy.hasConfigSerializerId) {
            serialization.deserialize(
              protoDeploy.getConfig.toByteArray,
              protoDeploy.getConfigSerializerId,
              protoDeploy.getConfigManifest).get.asInstanceOf[Config]
          } else {
            // old wire format
            oldDeserialize(protoDeploy.getConfig, classOf[Config])
          }
        } else ConfigFactory.empty

      val routerConfig =
        if (protoDeploy.hasRouterConfig) {
          if (protoDeploy.hasRouterConfigSerializerId) {
            serialization.deserialize(
              protoDeploy.getRouterConfig.toByteArray,
              protoDeploy.getRouterConfigSerializerId,
              protoDeploy.getRouterConfigManifest).get.asInstanceOf[RouterConfig]
          } else {
            // old wire format
            oldDeserialize(protoDeploy.getRouterConfig, classOf[RouterConfig])
          }
        } else NoRouter

      val scope =
        if (protoDeploy.hasScope) {
          if (protoDeploy.hasScopeSerializerId) {
            serialization.deserialize(
              protoDeploy.getScope.toByteArray,
              protoDeploy.getScopeSerializerId,
              protoDeploy.getScopeManifest).get.asInstanceOf[Scope]
          } else {
            // old wire format
            oldDeserialize(protoDeploy.getScope, classOf[Scope])
          }
        } else NoScopeGiven
      val dispatcher =
        if (protoDeploy.hasDispatcher) protoDeploy.getDispatcher
        else NoDispatcherGiven
      Deploy(protoDeploy.getPath, config, routerConfig, scope, dispatcher)
    }

    def props = {
      import scala.collection.JavaConverters._
      val protoProps = proto.getProps
      val actorClass = system.dynamicAccess.getClassFor[AnyRef](protoProps.getClazz).get
      val args: Vector[AnyRef] =
        // message from a newer node always contains serializer ids and possibly a string manifest for each position
        if (protoProps.getSerializerIdsCount > 0) {
          for {
            idx ← (0 until protoProps.getSerializerIdsCount).toVector
          } yield {
            val manifest =
              if (protoProps.getHasManifest(idx)) protoProps.getManifests(idx)
              else ""
            serialization.deserialize(
              protoProps.getArgs(idx).toByteArray(),
              protoProps.getSerializerIds(idx),
              manifest).get
          }
        } else {
          // message from an older node, which only provides data and class name
          // and never any serializer ids
          (proto.getProps.getArgsList.asScala zip proto.getProps.getManifestsList.asScala).iterator
            .map(oldDeserialize).to(immutable.Vector)
        }
      Props(deploy(proto.getProps.getDeploy), actorClass, args)
    }

    DaemonMsgCreate(
      props = props,
      deploy = deploy(proto.getDeploy),
      path = proto.getPath,
      supervisor = deserializeActorRef(system, proto.getSupervisor))
  }

  private def serialize(any: Any): (Int, Boolean, String, Array[Byte]) = {
    val m = any.asInstanceOf[AnyRef]
    val serializer = serialization.findSerializerFor(m)

    // this trixery is to retain backwards wire compatibility while at the same time
    // allowing for usage of serializers with string manifests
    val hasManifest = serializer.includeManifest
    val manifest = serializer match {
      case ser: SerializerWithStringManifest ⇒
        ser.manifest(m)
      case _ ⇒
        // we do include class name regardless to retain wire compatibility
        // with older nodes who expect manifest to be the class name
        if (m eq null) {
          "null"
        } else {
          val className = m.getClass.getName
          if (scala212OrLater && m.isInstanceOf[java.io.Serializable] && m.getClass.isSynthetic && className.contains("$Lambda$")) {
            // When the additional-protobuf serializers are not enabled
            // the serialization of the parameters is based on passing class name instead of
            // serializerId and manifest as we usually do. With Scala 2.12 the functions are generated as
            // lambdas and we can't use that load class from that name when deserializing
            classOf[java.io.Serializable].getName
          } else {
            className
          }
        }
    }

    (serializer.identifier, hasManifest, manifest, serializer.toBinary(m))
  }

  private def oldDeserialize(p: (ByteString, String)): AnyRef =
    oldDeserialize(p._1, p._2)

  private def oldDeserialize(data: ByteString, className: String): AnyRef =
    if (data.isEmpty && className == "null") null
    else oldDeserialize(data, system.dynamicAccess.getClassFor[AnyRef](className).get)

  private def oldDeserialize[T: ClassTag](data: ByteString, clazz: Class[T]): T = {
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
