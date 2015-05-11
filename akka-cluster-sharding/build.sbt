import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, Unidoc, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettingsNoVerificationOfDiagrams

Unidoc.javadocSettings

OSGi.clusterSharding

MultiNode.multiJvmSettings

libraryDependencies ++= Dependencies.clusterSharding

//MimaKeys.previousArtifact := akkaPreviousArtifact("akka-cluster-sharding").value
