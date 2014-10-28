import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNode, Unidoc }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

MultiNode.multiJvmSettings

OSGi.cluster

libraryDependencies ++= Dependencies.cluster

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-cluster").value

// disable parallel tests
parallelExecution in Test := false

extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
  (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
}

scalatestOptions in MultiJvm := MultiNode.defaultMultiJvmScalatestOptions.value
