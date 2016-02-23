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
    r ~~ scheme ~~ " realm=" ~~#! realm
    if (params.nonEmpty) params.foreach { case (k, v) ⇒ r ~~ ',' ~~ k ~~ '=' ~~# v }
    r
  }

  /** Java API */
  def getParams: util.Map[String, String] = params.asJava
}
