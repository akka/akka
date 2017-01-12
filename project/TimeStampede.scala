/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._

object TimeStampede extends AutoPlugin {

  override def trigger = noTrigger

  override lazy val projectSettings = Seq(
    commands += stampVersion
  )

  final val Snapshot = "-SNAPSHOT"

  def stampVersion = Command.command("stampVersion") { state =>
    val extracted = Project.extract(state)
    extracted.append(List(version in ThisBuild ~= stamp), state)
  }

  def stamp(version: String): String = {
    if (version endsWith Snapshot) (version stripSuffix Snapshot) + "-" + timestamp(System.currentTimeMillis)
    else version
  }

  def timestamp(time: Long): String = {
    val format = new java.text.SimpleDateFormat("yyyyMMdd-HHmmss")
    format.format(new java.util.Date(time))
  }
}
