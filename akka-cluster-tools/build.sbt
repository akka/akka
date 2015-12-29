import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, ScaladocNoVerificationOfDiagrams, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.clusterTools

Dependencies.clusterTools

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-cluster-tools").value

enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams)
