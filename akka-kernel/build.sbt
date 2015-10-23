import akka.{ AkkaBuild, Dependencies, Formatting, ScaladocNoVerificationOfDiagrams }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

Dependencies.kernel

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-kernel").value

enablePlugins(ScaladocNoVerificationOfDiagrams)
