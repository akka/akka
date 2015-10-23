import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, ScaladocNoVerificationOfDiagrams }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.agent

Dependencies.agent

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-agent").value

enablePlugins(ScaladocNoVerificationOfDiagrams)
