import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, Unidoc, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Unidoc.scaladocSettingsNoVerificationOfDiagrams

Unidoc.javadocSettings

OSGi.clusterTools

libraryDependencies ++= Dependencies.clusterTools

//MimaKeys.previousArtifact := akkaPreviousArtifact("akka-cluster-tools").value

enablePlugins(MultiNode)
