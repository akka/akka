import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNodeScalaTest, SigarLoader }
import com.typesafe.sbt.SbtMultiJvm.MultiJvmKeys._

AkkaBuild.defaultSettings
Formatting.formatSettings
SigarLoader.sigarSettings
OSGi.clusterMetrics
Dependencies.clusterMetrics

parallelExecution in Test := false

enablePlugins(MultiNodeScalaTest)
