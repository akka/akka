/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import scala.concurrent.Promise

import akka.actor.Actor
import akka.actor.Props
import akka.annotation.InternalApi
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorMaterializer
import akka.stream.Materializer

import com.github.ghik.silencer.silent

/**
 * INTERNAL API
 *
 * The materializer guardian is parent to the default system wide materializer.
 * Eagerly started by the SystemMaterializer extension on system startup.
 */
@InternalApi
private[akka] object MaterializerGuardian {

  def props(systemMaterializer: Promise[Materializer], materializerSettings: ActorMaterializerSettings) =
    Props(new MaterializerGuardian(systemMaterializer, materializerSettings))
}

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class MaterializerGuardian(
    systemMaterializerPromise: Promise[Materializer],
    materializerSettings: ActorMaterializerSettings)
    extends Actor {

  @silent("deprecated")
  private val systemMaterializer = ActorMaterializer(materializerSettings, "flow")
  systemMaterializerPromise.success(systemMaterializer)

  override def receive: Receive = PartialFunction.empty
}