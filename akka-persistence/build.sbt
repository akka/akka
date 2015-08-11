import akka.{ AkkaBuild, Dependencies, Formatting, OSGi }
import com.typesafe.tools.mima.plugin.MimaKeys

AkkaBuild.defaultSettings

Formatting.formatSettings

OSGi.persistence

Dependencies.persistence

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-persistence-experimental").value

fork in Test := true
