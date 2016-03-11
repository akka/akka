import akka.{ AkkaBuild, Dependencies, Formatting, OSGi, MultiNode, ScaladocNoVerificationOfDiagrams }

AkkaBuild.defaultSettings
Formatting.formatSettings
OSGi.contrib
Dependencies.contrib

description := """|
                  |This subproject provides a home to modules contributed by external
                  |developers which may or may not move into the officially supported code
                  |base over time. A module in this subproject doesn't have to obey the rule
                  |of staying binary compatible between minor releases. Breaking API changes
                  |may be introduced in minor releases without notice as we refine and
                  |simplify based on your feedback. A module may be dropped in any release
                  |without prior deprecation. The Lightbend subscription does not cover
                  |support for these modules.
                  |""".stripMargin

enablePlugins(MultiNode, ScaladocNoVerificationOfDiagrams)
disablePlugins(MimaPlugin)
