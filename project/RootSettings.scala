/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka

import sbt._
import sbt.Keys._
import akka.ValidatePullRequest.validatePullRequest
import sbtunidoc.Plugin.UnidocKeys.unidoc
import com.typesafe.sbt.site.SphinxSupport
import com.typesafe.sbt.site.SphinxSupport.Sphinx

object RootSettings extends AutoPlugin {

  lazy val docs = ProjectRef(file("."), "akka-docs")

  // settings for root project goes here
  override val projectSettings = Seq(
    validatePullRequest <<= validatePullRequest.dependsOn(unidoc in Compile),
    validatePullRequest <<= validatePullRequest.dependsOn(SphinxSupport.generate in Sphinx in docs)
  )
}
