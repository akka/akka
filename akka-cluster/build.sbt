import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNodeScalaTest }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.cluster

Dependencies.cluster

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-cluster").value

// disable parallel tests
parallelExecution in Test := false

enablePlugins(MultiNodeScalaTest)
