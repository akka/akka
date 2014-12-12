import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNode, Unidoc, SigarLoader }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

MultiNode.multiJvmSettings

SigarLoader.sigarSettings

OSGi.clusterMetrics

libraryDependencies ++= Dependencies.clusterMetrics

//MimaKeys.previousArtifact := akkaPreviousArtifact("akka-cluster-metrics").value

parallelExecution in Test := false

extraOptions in MultiJvm <<= (sourceDirectory in MultiJvm) { src =>
  (name: String) => (src ** (name + ".conf")).get.headOption.map("-Dakka.config=" + _.absolutePath).toSeq
}

scalatestOptions in MultiJvm := MultiNode.defaultMultiJvmScalatestOptions.value
