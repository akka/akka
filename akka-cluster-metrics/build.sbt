import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNodeScalaTest, Unidoc, SigarLoader }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

SigarLoader.sigarSettings

OSGi.clusterMetrics

libraryDependencies ++= Dependencies.clusterMetrics

//MimaKeys.previousArtifact := akkaPreviousArtifact("akka-cluster-metrics").value

parallelExecution in Test := false

enablePlugins(MultiNodeScalaTest)
