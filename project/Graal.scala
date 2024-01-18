/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt.AutoPlugin
import sbt.Keys.*
import sbt.*
import sbt.plugins.JvmPlugin

object Graal extends AutoPlugin {

  private val isGraal = sys.props("java.vendor.version").contains("GraalVM")
  private val collectGraalMeta = sys.props.get("akka.build.collect.graal.meta").isDefined
  if (collectGraalMeta)
    if (isGraal) println("[info] Setting up tests to collect Graal Metadata using agent")
    else throw new IllegalArgumentException("To collect Graal metadata you must be running on a Graal JVM")

  override def trigger = AllRequirements

  override def requires = JvmPlugin

  override def projectSettings: Seq[Def.Setting[_]] =
    if (isGraal && collectGraalMeta) {
      Seq(Test / javaOptions := {
        val moduleToCollectTo = name.value.replace("-tests", "")
        val akkaRepoRoot = baseDirectory.value.getParentFile
        val callerFilterFile = s"$akkaRepoRoot/.graal-caller-filter.json"
        val metadataDirPath =
          s"$akkaRepoRoot/$moduleToCollectTo/src/main/resources/META-INF/native-image/${organization.value}/$moduleToCollectTo"
        (s"-agentlib:native-image-agent=caller-filter-file=$callerFilterFile,config-merge-dir=$metadataDirPath" +: (Test / javaOptions).value)
      })
    } else Seq.empty

}
