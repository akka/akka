/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model
package headers

import akka.http.util._
import java.util

import akka.http.model.japi.JavaMapping.Implicits._

final case class HttpChallenge(scheme: String, realm: String,
                               params: Map[String, String] = Map.empty) extends japi.headers.HttpChallenge with ValueRenderable {

  def render[R <: Rendering](r: R): r.type = {
    r ~~ scheme ~~ " realm=" ~~# realm
    if (params.nonEmpty) params.foreach { case (k, v) ⇒ r ~~ ',' ~~ k ~~ '=' ~~# v }
    r
  }

  /** Java API */
  def getParams: util.Map[String, String] = params.asJava
}
