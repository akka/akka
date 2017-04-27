/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.settings

import akka.annotation.ApiMayChange

/**
 * Options that are in "preview" or "early access" mode.
 * These options may change and/or be removed within patch releases
 * without early notice (e.g. by moving them into a stable supported place).
 */
@ApiMayChange
final case class PreviewServerSettings(
  /**
   * Configures the Http extension to bind using HTTP/2 if given an
   * [[akka.http.scaladsl.HttpsConnectionContext]]. Otherwise binds as plain HTTP.
   *
   * Please note that when using this mode of binding you MUST include
   * `"com.typesafe.akka" %% "akka-http2-support" % AkkaHttpVersion` in your
   * dependencies / classpath.
   */
  useHttp2: Boolean
)
