/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model.headers

import java.util
import akka.http.javadsl.{ model ⇒ jm }
import akka.http.impl.util._
import akka.http.impl.util.JavaMapping.Implicits._

final case class HttpChallenge(scheme: String, realm: String,
                               params: Map[String, String] = Map.empty) extends jm.headers.HttpChallenge with ValueRenderable {

  def render[R <: Rendering](r: R): r.type = {
    r ~~ scheme
    if (realm != null) r ~~ " realm=" ~~#! realm
    if (params.nonEmpty) params.foreach { case (k, v) ⇒ r ~~ ',' ~~ k ~~ '=' ~~# v }
    r
  }

  /** Java API */
  def getParams: util.Map[String, String] = params.asJava
}

// FIXME: AbstractFunction3 required for bin compat. remove in Akka 10.0 and change realm in case class to option #20786
object HttpChallenge extends scala.runtime.AbstractFunction3[String, String, Map[String, String], HttpChallenge] {

  def apply(scheme: String, realm: Option[String]): HttpChallenge =
    HttpChallenge(scheme, realm.orNull, Map.empty[String, String])

  def apply(scheme: String, realm: Option[String], params: Map[String, String]): HttpChallenge =
    HttpChallenge(scheme, realm.orNull, params)
}

object HttpChallenges {

  def basic(realm: String): HttpChallenge = HttpChallenge("Basic", realm, Map("charset" → "UTF-8"))

  def oAuth2(realm: String): HttpChallenge = HttpChallenge("Bearer", realm)
}
