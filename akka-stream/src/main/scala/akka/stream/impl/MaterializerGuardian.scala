/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import scala.annotation.nowarn
import scala.concurrent.Promise

import akka.actor.Actor
import akka.actor.Props
import akka.annotation.{ DoNotInherit, InternalApi }
import akka.stream.ActorMaterializerSettings
import akka.stream.Attributes
import akka.stream.Materializer

/**
 * INTERNAL API
 *
 * The materializer guardian is parent to all materializers created on the `system` level including the default
 * system wide materializer. Eagerly started by the SystemMaterializer extension on system startup.
 */
@InternalApi
private[akka] object MaterializerGuardian {

  /** Not for user extension */
  @DoNotInherit
  sealed trait RequestMaterializerStart {
    def attributes: Option[Attributes]
  }

  object StartMaterializer
      extends RequestMaterializerStart
      with Function1[Option[Attributes], RequestMaterializerStart] {
    val attributes: Option[Attributes] = None

    def apply(attributes: Option[Attributes]): RequestMaterializerStart =
      attributes match {
        case None => this
        case Some(_) =>
          val attrs = attributes
          new RequestMaterializerStart {
            val attributes: Option[Attributes] = attrs
          }
      }

    def unapply(rms: RequestMaterializerStart): Option[Option[Attributes]] = Some(rms.attributes)
  }

  final case class MaterializerStarted(materializer: Materializer)

  // this is available to keep backwards compatibility with ActorMaterializer and should
  // be removed together with ActorMaterializer in a future version
  final case class LegacyStartMaterializer(namePrefix: String, settings: ActorMaterializerSettings)

  def props(systemMaterializer: Promise[Materializer], materializerSettings: ActorMaterializerSettings) =
    Props(new MaterializerGuardian(systemMaterializer, materializerSettings))
}

/**
 * INTERNAL API
 */
@nowarn("msg=deprecated")
@InternalApi
private[akka] final class MaterializerGuardian(
    systemMaterializerPromise: Promise[Materializer],
    materializerSettings: ActorMaterializerSettings)
    extends Actor {
  import MaterializerGuardian._

  private val defaultAttributes = materializerSettings.toAttributes
  private val defaultNamePrefix = "flow"

  private val systemMaterializer = startMaterializer(None)
  systemMaterializerPromise.success(systemMaterializer)

  override def receive: Receive = {
    case StartMaterializer(attributesOpt) =>
      sender() ! MaterializerStarted(startMaterializer(attributesOpt))

    case LegacyStartMaterializer(namePrefix, settings) =>
      val startedMaterializer = PhasedFusingActorMaterializer(context, namePrefix, settings, settings.toAttributes)
      sender() ! MaterializerStarted(startedMaterializer)
  }

  private def startMaterializer(attributesOpt: Option[Attributes]) = {
    PhasedFusingActorMaterializer(
      context,
      defaultNamePrefix,
      materializerSettings,
      attributesOpt.getOrElse(defaultAttributes))
  }
}
