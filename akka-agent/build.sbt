import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, ScaladocNoVerificationOfDiagrams }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.agent

Dependencies.agent

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-agent").value

enablePlugins(ScaladocNoVerificationOfDiagrams)
