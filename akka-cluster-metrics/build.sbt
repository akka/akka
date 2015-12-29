import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNodeScalaTest, SigarLoader }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

SigarLoader.sigarSettings

OSGi.clusterMetrics

Dependencies.clusterMetrics

//MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-cluster-metrics").value

parallelExecution in Test := false

enablePlugins(MultiNodeScalaTest)
