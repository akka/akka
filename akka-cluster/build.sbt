import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNodeScalaTest, Unidoc }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.cluster

libraryDependencies ++= Dependencies.cluster

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-cluster").value

// disable parallel tests
parallelExecution in Test := false

enablePlugins(MultiNodeScalaTest)
