import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, Unidoc }
import com.typesafe.tools.mima.plugin.MimaKeys
import akka.MultiNode

AkkaBuild.defaultSettings

AkkaBuild.experimentalSettings

Formatting.formatSettings

Unidoc.scaladocSettings

Unidoc.javadocSettings

OSGi.persistence

Dependencies.persistence

MimaKeys.previousArtifact := akkaPreviousArtifact("akka-persistence-experimental").value

fork in Test := true

javaOptions in Test := MultiNode.defaultMultiJvmOptions
