import akka.{ AkkaBuild, Dependencies, Formatting, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.persistence

Dependencies.persistence

MimaKeys.previousArtifacts := akkaPreviousArtifacts("akka-persistence").value

fork in Test := true
