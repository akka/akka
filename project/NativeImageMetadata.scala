/*
 * Copyright (C) 2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt.AutoPlugin
import sbt.Keys.*
import sbt.*
import sbt.plugins.JvmPlugin

object NativeImageMetadata extends AutoPlugin {

  private val isGraal = sys.props("java.vendor.version").contains("GraalVM")
  private val collectNativeImageMetadata = sys.props.contains("akka.collect-native-image-metadata")

  if (!isGraal && collectNativeImageMetadata) {
    throw new IllegalArgumentException("Can only collect native image metadata in a GraalVM JDK")
  }

  override def trigger = AllRequirements

  override def requires = JvmPlugin

  import Keys._

  override def projectSettings: Seq[Def.Setting[_]] =
    if (isGraal && collectNativeImageMetadata) {
      Seq(
        Test / mainClass := Some("akka.NativeImageMetadataMain"),
        Test / javaOptions := {
          val moduleToCollectTo = name.value.replace("-tests", "")
          val akkaRepoRoot = baseDirectory.value.getParentFile
          val callerFilterFile = s"$akkaRepoRoot/.native-image-caller-filter.json"
          val metadataDirPath =
            s"$akkaRepoRoot/$moduleToCollectTo/src/main/resources/META-INF/native-image/${organization.value}/$moduleToCollectTo"
          (s"-agentlib:native-image-agent=caller-filter-file=$callerFilterFile,config-merge-dir=$metadataDirPath" +: (Test / javaOptions).value)
        },
        Test / run / fork := true)

    } else Seq()

}
