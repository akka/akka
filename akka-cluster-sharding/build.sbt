import akka.{ AkkaBuild, Dependencies, Formatting, MultiNode, ScaladocNoVerificationOfDiagrams, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.clusterSharding

Dependencies.clusterSharding

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-cluster-sharding").value

enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams)
