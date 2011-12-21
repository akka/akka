* f772b01 2011-12-20 | Initial commit of dispatcher key refactoring, for review. See #1458 (HEAD, origin/wip-1458-dispatcher-id-patriknw, wip-1458-dispatcher-id-patriknw) [Patrik Nordwall]
*   92bb4c5 2011-12-21 | Merge pull request #180 from jboner/1529-hardcoded-value-he (origin/master, origin/HEAD, master) [Henrik Engstrom]
|\  
| * 1a8e755 2011-12-21 | Minor updates after further feedback. See #1529 (origin/1529-hardcoded-value-he) [Henrik Engstrom]
| * dac0beb 2011-12-21 | Updates based on feedback - use of abstract member variables specific to the router type. See #1529 [Henrik Engstrom]
| * 0dc161c 2011-12-20 | Initial take on removing hardcoded value from SGFCR. See #1529 [Henrik Engstrom]
* | a9cce25 2011-12-21 | Fixing racy FutureSpec test [Viktor Klang]
* | a624c74 2011-12-21 | Remove .tags_sorted_by_file [Peter Vlugter]
* |   1b3974c 2011-12-20 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| |/  
| *   f6f52c4 2011-12-20 | Merge pull request #178 from jboner/wip-1512-dispatcher-shutdown-timeout-patriknw [patriknw]
| |\  
| | * f591a31 2011-12-20 | Fixed typo (origin/wip-1512-dispatcher-shutdown-timeout-patriknw, wip-1512-dispatcher-shutdown-timeout-patriknw) [Patrik Nordwall]
| | * 60f45c7 2011-12-20 | Move dispatcher-shutdown-timeout to dispatcher config. See #1512. [Patrik Nordwall]
| |/  
| *   f3406ac 2011-12-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | *   1a5d2a4 2011-12-20 | Merge pull request #174 from jboner/1495-routees-message-he [Henrik Engstrom]
| | |\  
| | | *   c92f3c5 2011-12-20 | Merge branch 'master' into 1495-routees-message-he [Henrik Engstrom]
| | | |\  
| | | |/  
| | |/|   
| | | * f67a500 2011-12-19 | Removed racy test. See #1495 [Henrik Engstrom]
| | | * 5aa4784 2011-12-19 | Updated code after feedback; the actual ActorRef's are returned instead of the name of them. See #1495 [Henrik Engstrom]
| | | * 3ff779c 2011-12-19 | Added functionality for a router client to retrieve the current routees of that router. See #1495 [Henrik Engstrom]
| * | | 49b3bac 2011-12-20 | Removing UnhandledMessageException and fixing tests [Viktor Klang]
| * | |   9a9e800 2011-12-20 | Merge branch 'master' into wip-1539-publish-unhandled-eventstream-√ [Viktor Klang]
| |\ \ \  
| | |/ /  
| | * |   7fc19ec 2011-12-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \  
| | | * \   73f2ae4 2011-12-20 | Merge pull request #173 from jboner/wip-improve-remote-logging-√ [viktorklang]
| | | |\ \  
| | | | * | b7b1ea5 2011-12-19 | Tweaking general badassery [Viktor Klang]
| | | | * | 0f3a720 2011-12-19 | Adding general badassery [Viktor Klang]
| | | | |/  
| | | * |   8997376 2011-12-20 | Merge pull request #176 from jboner/wip-1484-config-mailboxtype-patriknw [patriknw]
| | | |\ \  
| | | | * | d87d9e2 2011-12-20 | Additional feedback, thanks. (wip-1484-config-mailboxtype-patriknw) [Patrik Nordwall]
| | | | * | fb510d5 2011-12-20 | Unwrap InvocationTargetException in ReflectiveAccess.createInstance. See #1555 [Patrik Nordwall]
| | | | * | 5fd40e5 2011-12-20 | Updates after feedback [Patrik Nordwall]
| | | | * | 83b08b2 2011-12-19 | Added CustomMailbox for user defined mailbox implementations with ActorContext instead of ActorCell. [Patrik Nordwall]
| | | | * | 61813c6 2011-12-19 | Make MailboxType implementation configurable. See #1484 [Patrik Nordwall]
| | * | | |   a4568d6 2011-12-20 | Merging in wip-1510-make-testlatch-awaitable-√ [Viktor Klang]
| | |\ \ \ \  
| | | |/ / /  
| | |/| | |   
| | | * | | c904fd3 2011-12-19 | Making TestLatch Awaitable and fixing tons of tests [Viktor Klang]
| * | | | | 634147d 2011-12-20 | Cleaning up some of the Java samples and adding sender to the UnhandledMessage [Viktor Klang]
| * | | | | 2adb042 2011-12-20 | Publish UnhandledMessage to EventStream [Viktor Klang]
| |/ / / /  
| * | | | e82ea3c 2011-12-20 | DOC: Extracted sample for explicit and implicit timeout with ask. Correction of akka.util.Timeout (wip-doc) [Patrik Nordwall]
| * | | | 8da41aa 2011-12-20 | Add copyright header to agent examples [Peter Vlugter]
| * | | |   00c7fe5 2011-12-19 | Merge pull request #172 from jboner/migrate-agent [Peter Vlugter]
| |\ \ \ \  
| | |_|/ /  
| |/| | |   
| | * | | a144c35 2011-12-19 | Add docs and tests for java api for agents. Fixes #1545 [Peter Vlugter]
| | * | | 70d8cd3 2011-12-19 | Migrate agent to scala-stm. See #1281 [Peter Vlugter]
| * | | | 6e3c2cb 2011-12-19 | Enable parallel execution of tests. See #1548 (wip-1548) [Patrik Nordwall]
* | | | | 9801ec6 2011-12-20 | Removed old unused config files in multi-jvm tests. [Jonas Bonér]
|/ / / /  
* | | | 84090ef 2011-12-19 | Bootstrapping the ContextClassLoader of the calling thread to init Remote to be the classloader for the remoting [Viktor Klang]
| |/ /  
|/| |   
* | | 47c1be6 2011-12-19 | Adding FIXME comments to unprotected casts to ActorSystemImpl (4 places) [Viktor Klang]
| |/  
|/|   
* | 419b694 2011-12-19 | Added copyright header to all samples in docs. Fixes #1531 [Henrik Engstrom]
|/  
* c5ef5ad 2011-12-17 | #1475 - implement mapTo for Java [Viktor Klang]
* 42e8a45 2011-12-17 | #1496 - Rename 'targets' to 'routees' [Viktor Klang]
* c2597ed 2011-12-17 | Adding debug messages for all remote exceptions, to ease debugging of remoting [Viktor Klang]
* e66d466 2011-12-16 | Some more minor changes to documentation [Peter Vlugter]
*   789b145 2011-12-16 | Merge branch 'master' of github.com:jboner/akka [Patrik Nordwall]
|\  
| * f6511db 2011-12-16 | Formatting after compile [Peter Vlugter]
| * 1f62b8d 2011-12-16 | Disable amqp module as there currently isn't anything there [Peter Vlugter]
| * 2e988c8 2011-12-16 | polish TestKit (add new assertions) [Roland]
| * d665297 2011-12-16 | Fix remaining warnings in docs generation [Peter Vlugter]
* | 164f92a 2011-12-16 | DOC: Added recommendation about naming actors and added name to some samples [Patrik Nordwall]
|/  
* 6225b75 2011-12-16 | DOC: Fixed invalid include [Patrik Nordwall]
* 4a027b9 2011-12-16 | Added brief documentation for Java specific routing. [Henrik Engstrom]
*   e491b3b 2011-12-15 | Merge pull request #168 from jboner/1175-docs-remoting-he [Henrik Engstrom]
|\  
| * 215c776 2011-12-15 | Fixed even more comments on the remoting. See #1175 [Henrik Engstrom]
| * 9b39b94 2011-12-15 | Fixed all comments related to remoting. Added new serialization section in documentation. See #1175. See #1536. [Henrik Engstrom]
| * 94017d8 2011-12-15 | Initial stab at remoting documentation. See #1175 [Henrik Engstrom]
* | afe8203 2011-12-15 | DOC: Minor cleanup by using Futures.successful, which didn't exist a while ago (wip-promise) [Patrik Nordwall]
* | 38ff479 2011-12-15 | redo section Identifying Actors for Java&Scala [Roland]
* |   5178196 2011-12-15 | Merge pull request #167 from jboner/wip-1487-future-doc-patriknw [patriknw]
|\ \  
| * | ff35ae9 2011-12-15 | Minor corrections from review comments [Patrik Nordwall]
| * | da24cb0 2011-12-15 | DOC: Update Future (Java) Chapter. See #1487 [Patrik Nordwall]
| * | 3b6c3e2 2011-12-15 | DOC: Update Future (Scala) Chapter. See #1487 [Patrik Nordwall]
* | | 473dc7c 2011-12-15 | Fixed broken link in "What is an Actor?" [Jonas Bonér]
* | |   84e1300 2011-12-15 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| * \ \   7dad0a3 2011-12-15 | Merge pull request #169 from jboner/rk-doc-fault-handling [Roland Kuhn]
| |\ \ \  
| | * | | 6f31e83 2011-12-15 | add Java part for fault handling docs [Roland]
| | * | | 5298d80 2011-12-15 | document FaultHandlingStrategy for Scala [Roland]
* | | | | ab53169 2011-12-15 | Clarified the contract for isTerminated [Viktor Klang]
|/ / / /  
* | | | 0ba28a7 2011-12-15 | Moving todo out from docs ;) [Viktor Klang]
* | | | a95dea4 2011-12-15 | Minor touchups of FSM docs [Viktor Klang]
* | | | c0031a3 2011-12-15 | change "direct" to "from-code" in router config [Roland]
* | | | ca15cca 2011-12-15 | Minor edits to documentation in reference.conf in akka-actor. [Jonas Bonér]
* | | | 95574da 2011-12-15 | Fixed failing test class. [Henrik Engstrom]
* | | |   d51351f 2011-12-15 | Merge pull request #165 from jboner/1063-docs-routing-he [Henrik Engstrom]
|\ \ \ \  
| * | | | 536659d 2011-12-15 | Fixed last(?) comments on the pull request. Fixes #1063 [Henrik Engstrom]
| * | | |   26d49fe 2011-12-15 | Merge branch 'master' into 1063-docs-routing-he [Henrik Engstrom]
| |\ \ \ \  
| |/ / / /  
|/| | | |   
* | | | | fd0443d 2011-12-15 | Clarifying how Awaitable should be used [Viktor Klang]
* | | | |   6c96397 2011-12-15 | Merge branch 'wip-1456-document-typed-actors-√' [Viktor Klang]
|\ \ \ \ \  
| |_|/ / /  
|/| | | |   
| * | | | 9d2ab2e 2011-12-15 | Minor edits [Viktor Klang]
| * | | | 0668708 2011-12-15 | Correcting minor things [Viktor Klang]
| * | | |   40acab7 2011-12-15 | Merge branch 'wip-1456-document-typed-actors-√' of github.com:jboner/akka into wip-1456-document-typed-actors-√ [Viktor Klang]
| |\ \ \ \  
| | * | | | 5f3e0c0 2011-12-15 | Adding Typed Actor docs for Java, as well as some minor tweaks on some Java APIs [Viktor Klang]
| * | | | | 5e03cda 2011-12-15 | Adding Typed Actor docs for Java, as well as some minor tweaks on some Java APIs [Viktor Klang]
| |/ / / /  
| * | | | a561372 2011-12-15 | Removing Guice docs [Viktor Klang]
| * | | |   009853f 2011-12-15 | Merge with master [Viktor Klang]
| |\ \ \ \  
| * | | | | c47d3ef 2011-12-15 | Adding a note describing the serialization of Typed Actor method calls [Viktor Klang]
| * | | | | 66c89fe 2011-12-15 | Minor touchups after review [Viktor Klang]
| * | | | | 77e5596 2011-12-14 | Removing conflicting versions of typedActorOf and added Scala docs for TypedActor [Viktor Klang]
| * | | | | 0c44258 2011-12-14 | Reducing the number of typedActorOf-methods [Viktor Klang]
| * | | | |   b126a72 2011-12-14 | Merge branch 'master' into wip-1456-document-typed-actors-√ [Viktor Klang]
| |\ \ \ \ \  
| * | | | | | 562646f 2011-12-13 | Removing the typed-actor docs for java, will redo later [Viktor Klang]
| * | | | | | ead9c12 2011-12-13 | Adding daemonicity to the dispatcher configurator [Viktor Klang]
| * | | | | | 973d5ab 2011-12-13 | Making it easier to specify daemon-ness for the ThreadPoolConfig [Viktor Klang]
* | | | | | | fd4b4e3 2011-12-15 | transparent remoting -> location transparency [Roland]
| |_|_|_|/ /  
|/| | | | |   
* | | | | |   987a8cc 2011-12-15 | Merge pull request #166 from jboner/wip-1522-touchup-dataflow-√ [viktorklang]
|\ \ \ \ \ \  
| * | | | | | ed829ac 2011-12-15 | Quick and dirty touch-up of Dataflow [Viktor Klang]
| | |_|_|_|/  
| |/| | | |   
* | | | | | 1053dcb 2011-12-15 | correct points Henrik raised [Roland]
|/ / / / /  
* | | | |   06a13d3 2011-12-15 | Merge pull request #164 from jboner/wip-1169-actor-system-doc-rk [Roland Kuhn]
|\ \ \ \ \  
| * | | | | 6d72c99 2011-12-15 | document deadLetter in actors concept [Roland]
| * | | | | c6afb5b 2011-12-15 | polish some more and add remoting.rst [Roland]
| * | | | | 31591d4 2011-12-15 | polish and add general/actors (wip-1169-actor-system-doc-rk) [Roland]
| * | | | | 0fa4f35 2011-12-14 | first stab at actor-systems.rst [Roland]
* | | | | |   0b2a5ee 2011-12-15 | Merge pull request #163 from jboner/wip-1486-testing-doc-patriknw [patriknw]
|\ \ \ \ \ \  
| * | | | | | 30416af 2011-12-15 | DOC: Update Testing Actor Systems (TestKit) Chapter. See #1486 (wip-1486-testing-doc-patriknw) [Patrik Nordwall]
| | |_|_|/ /  
| |/| | | |   
* | | | | |   65efba2 2011-12-15 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \  
| |/ / / / /  
* | | | | | d9217e4 2011-12-15 | Added 'notes' section formatting and some more content to the cluster, spring and camel pages. [Jonas Bonér]
* | | | | | ce296b0 2011-12-15 | Misc additions to, and rewrites and formatting of, the documentation. [Jonas Bonér]
| | | | | * 0aee902 2011-12-15 | Fixed typo. See #1063 [Henrik Engstrom]
| | | | | * d68777e 2011-12-15 | Updated after feedback. See #1063 [Henrik Engstrom]
| | | | | * 41ce42c 2011-12-15 | Upgraded routing documentation to Akka 2.0. See #1063 [Henrik Engstrom]
| | |_|_|/  
| |/| | |   
| * | | | 73b79d6 2011-12-15 | Adding a Scala and a Java guide to Akka Extensions [Viktor Klang]
| * | | | 866e47c 2011-12-15 | Adding Scala documentation for Akka Extensions [Viktor Klang]
|/ / / /  
* | | |   b0e630a 2011-12-15 | Merge remote-tracking branch 'origin/simplified-multi-jvm-test' [Jonas Bonér]
|\ \ \ \  
| * | | | 991a4a3 2011-12-09 | Removed multi-jvm test for gossip. Will reintroduce later, but first write in-process tests for the gossip using the new remoting. [Jonas Bonér]
| * | | | 553d1da 2011-12-05 | Cleaned up inconsistent logging messages (simplified-multi-jvm-test) [Jonas Bonér]
| * | | | 064a8a7 2011-12-05 | Added fallback to testConfig in AkkaRemoteSpec [Jonas Bonér]
| * | | | 392c060 2011-12-05 | Simplified multi-jvm test by adding all settings and config into the test source itself [Jonas Bonér]
* | | | |   b4f1978 2011-12-15 | Merge remote-tracking branch 'origin/wip-simplify-configuring-new-router-in-props-jboner' [Jonas Bonér]
|\ \ \ \ \  
| * | | | | 2fd43bc 2011-12-14 | Removed withRouter[TYPE] method and cleaned up some docs. [Jonas Bonér]
| * | | | | 7f93f56 2011-12-14 | Rearranged ordering of sections in untyped actor docs [Jonas Bonér]
| * | | | | f2e36f0 2011-12-14 | Fix minor issue in the untyped actor docs [Jonas Bonér]
| * | | | | 8289ac2 2011-12-14 | Minor doc changes to Props docs [Jonas Bonér]
| * | | | | 80600ab 2011-12-14 | Added 'withRouter[TYPE]' to 'Props'. Added docs (Scala and Java) and (code for the docs) for 'Props'. Renamed UntypedActorTestBase to UntypedActorDocTestBase. [Jonas Bonér]
* | | | | |   f59b4c6 2011-12-15 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \  
| * | | | | | 44a82be 2011-12-15 | DOC: Disabled agents chapter, since it's in the akka-stm module. See #1488 [Patrik Nordwall]
| * | | | | | 1d8bd1b 2011-12-15 | Update akka sbt plugin [Peter Vlugter]
| * | | | | | 37efb72 2011-12-15 | Some documentation fixes [Peter Vlugter]
| * | | | | |   a3af362 2011-12-14 | Merge pull request #153 from jboner/docs-intro-he [Peter Vlugter]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   cf27ca0 2011-12-15 | Merge with master [Peter Vlugter]
| | |\ \ \ \ \ \  
| | |/ / / / / /  
| |/| | | | | |   
| * | | | | | | 1ef5145 2011-12-15 | fix hideous and well-hidden oversight [Roland]
| * | | | | | | 05461cd 2011-12-14 | fix log statement in ActorModelSpec [Roland]
| * | | | | | |   14e6ee5 2011-12-14 | Merge pull request #152 from jboner/enable-akka-kernel [Peter Vlugter]
| |\ \ \ \ \ \ \  
| | * | | | | | | ad8a050 2011-12-15 | Updated microkernel [Peter Vlugter]
| | * | | | | | |   0772d01 2011-12-15 | Merge branch 'master' into enable-akka-kernel [Peter Vlugter]
| | |\ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \   55594a2 2011-12-15 | Merge with master [Peter Vlugter]
| | |\ \ \ \ \ \ \ \  
| | * | | | | | | | | b058e6a 2011-12-14 | Add config spec for akka kernel [Peter Vlugter]
| | * | | | | | | | | ba9ed98 2011-12-14 | Re-enable akka-kernel and add small sample [Peter Vlugter]
| | | |_|_|/ / / / /  
| | |/| | | | | | |   
| * | | | | | | | |   8cb682c 2011-12-14 | Merge pull request #159 from jboner/wip-1516-ActorContext-cleanup-rk [Roland Kuhn]
| |\ \ \ \ \ \ \ \ \  
| | |_|_|/ / / / / /  
| |/| | | | | | | |   
| | * | | | | | | | cdff927 2011-12-14 | remove non-user API from ActorContext, see #1516 [Roland]
| | | | | * | | | | 49e350a 2011-12-14 | Updated introduction documents to Akka 2.0. Fixes #1480 [Henrik Engstrom]
| | | | |/ / / / /  
| | | |/| | | | |   
* | | | | | | | |   9c18b8c 2011-12-15 | Merge branch 'wip-remove-timeout-jboner' [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| |/ / / / / / / /  
|/| | | | | | | |   
| * | | | | | | |   a18206b 2011-12-14 | Merge branch 'wip-remove-timeout-jboner' into master [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | 04cd2ad 2011-12-14 | Moved Timeout classes from akka.actor._ to akka.util._. [Jonas Bonér]
* | | | | | | | | | fabe475 2011-12-14 | DOC: Improved scheduler doc. Split into Java/Scala samples (wip-scheduler-doc) [Patrik Nordwall]
| |_|_|_|/ / / / /  
|/| | | | | | | |   
* | | | | | | | | c57b273 2011-12-14 | DOC: Another correction of stop description [Patrik Nordwall]
* | | | | | | | | 7b2349c 2011-12-14 | DOC: Correction of stop description [Patrik Nordwall]
|/ / / / / / / /  
* | | | | | | | ab1c4c6 2011-12-14 | DOC: Updated stop description [Patrik Nordwall]
* | | | | | | | 6bbbcea 2011-12-14 | DOC: Updated preRestart [Patrik Nordwall]
| |_|_|_|/ / /  
|/| | | | | |   
* | | | | | |   9ad2580 2011-12-14 | Merge pull request #154 from jboner/wip-1503-remove-stm-patriknw [patriknw]
|\ \ \ \ \ \ \  
| |_|/ / / / /  
|/| | | | | |   
| * | | | | | 34252c5 2011-12-14 | A few more 2000 milliseconds (wip-1503-remove-stm-patriknw) [Patrik Nordwall]
| * | | | | |   e456213 2011-12-14 | Merge branch 'master' into wip-1503-remove-stm-patriknw [Patrik Nordwall]
| |\ \ \ \ \ \  
| * | | | | | | 328d62d 2011-12-14 | Minor review comment fix [Patrik Nordwall]
| * | | | | | | 06a08c5 2011-12-14 | Removed STM module. See #1503 [Patrik Nordwall]
* | | | | | | |   85602fd 2011-12-14 | Merge branch 'wip-1514-duration-inf-rk' [Roland]
|\ \ \ \ \ \ \ \  
| |_|/ / / / / /  
|/| | | | | | |   
| * | | | | | | e96db77 2011-12-14 | make infinite durations compare true to themselves, see #1514 [Roland]
* | | | | | | |   d9e9efe 2011-12-14 | Merge pull request #156 from jboner/wip-1504-config-comments-patriknw [patriknw]
|\ \ \ \ \ \ \ \  
| |_|_|_|_|_|_|/  
|/| | | | | | |   
| * | | | | | | b243374 2011-12-14 | Review comments. Config lib v0.2.0. (wip-1504-config-comments-patriknw) [Patrik Nordwall]
| * | | | | | |   c1826ab 2011-12-14 | Merge branch 'master' into wip-1504-config-comments-patriknw [Patrik Nordwall]
| |\ \ \ \ \ \ \  
| * | | | | | | | 8ffa85c 2011-12-14 | DOC: Rewrite config comments. See #1505 [Patrik Nordwall]
| * | | | | | | | 6045af5 2011-12-14 | Updated to config lib 5302c1e [Patrik Nordwall]
| | |_|/ / / / /  
| |/| | | | | |   
* | | | | | | |   353aa88 2011-12-14 | Merge branch 'master' into integration [Viktor Klang]
|\ \ \ \ \ \ \ \  
| | |_|/ / / / /  
| |/| | | | | |   
| * | | | | | | 7ede606 2011-12-14 | always start Davy Jones [Roland]
| | |/ / / / /  
| |/| | | | |   
* | | | | | |   e959493 2011-12-14 | Enormous merge with master which probably led to the indirect unfortunate deaths of several kittens [Viktor Klang]
|\ \ \ \ \ \ \  
| |/ / / / / /  
|/| | / / / /   
| | |/ / / /    
| |/| | | |     
| * | | | | 0af92f2 2011-12-14 | Fixing some ScalaDoc inaccuracies [Viktor Klang]
| * | | | | 97811a7 2011-12-14 | Replacing old Future.fold impl with sequence, avoiding to close over this on dispatchTask, changing UnsupportedOperationException to NoSuchElementException [Viktor Klang]
| * | | | | b3e5da2 2011-12-14 | Changing Akka Futures to better conform to spec [Viktor Klang]
| * | | | | 48adb3c 2011-12-14 | Adding Promise.future and the failed-projection to Future [Viktor Klang]
| * | | | |   bf01045 2011-12-13 | Merged with current master [Viktor Klang]
| |\ \ \ \ \  
| * | | | | | b32cbbc 2011-12-12 | Renaming Block to Await, renaming sync to result, renaming on to ready, Await.ready and Await.result looks and reads well [Viktor Klang]
| * | | | | | d8fe6a5 2011-12-12 | Removing Future.get [Viktor Klang]
| * | | | | | ddcbe23 2011-12-12 | Renaming Promise.fulfilled => Promise.successful [Viktor Klang]
| * | | | | | 4ddf581 2011-12-12 | Implementing most of the 'pending' Future-tests [Viktor Klang]
| * | | | | | 67c782f 2011-12-12 | Renaming onResult to onSuccess and onException to onFailure [Viktor Klang]
| * | | | | | 2d418c1 2011-12-12 | Renaming completeWithResult to success, completeWithException to failure, adding tryComplete to signal whether the completion was made or not [Viktor Klang]
| * | | | | | 7026ded 2011-12-12 | Removing Future.result [Viktor Klang]
| * | | | | | 7eced71 2011-12-12 | Removing FutureFactory and reintroducing Futures (for Java API) [Viktor Klang]
| * | | | | | 0b6a1a0 2011-12-12 | Removing Future.exception plus starting to remove Future.result [Viktor Klang]
| * | | | | | 53e8373 2011-12-12 | Changing AskActorRef so that it cannot be completed when it times out, and that it does not complete the future when it times out [Viktor Klang]
| * | | | | | 2673a9c 2011-12-11 | Removing Future.as[] and commenting out 2 Java Specs because the compiler can't find them? [Viktor Klang]
| * | | | | | 4f92500 2011-12-11 | Converting away the usage of as[..] [Viktor Klang]
| * | | | | | 1efed78 2011-12-11 | Removing resultOrException [Viktor Klang]
| * | | | | | de758c0 2011-12-11 | Adding Blockable.sync to reduce usage of resultOrException.get [Viktor Klang]
| * | | | | | 3b1330c 2011-12-11 | Tests are green with new Futures, consider this a half-way-there marker [Viktor Klang]
* | | | | | | ba4e2cb 2011-12-14 | fix stupid compile error [Roland]
* | | | | | |   1ab2cec 2011-12-14 | Merge branch 'wip-1466-remove-stop-rk' [Roland]
|\ \ \ \ \ \ \  
| |_|_|/ / / /  
|/| | | | | |   
| * | | | | | 49837e4 2011-12-14 | incorporate review comments [Roland]
| * | | | | | 7d6c74d 2011-12-14 | UntypedActor hooks default to super.<whatever> now, plus updated ScalaDoc [Roland]
| * | | | | | 488576c 2011-12-14 | make Davy Jones configurable [Roland]
| * | | | | | 5eedbdd 2011-12-14 | rename ActorSystem.stop() to .shutdown() [Roland]
| * | | | | | 9af5836 2011-12-14 | change default behavior to kill all children during preRestart [Roland]
| * | | | | | cb85778 2011-12-14 | remove ActorRef.stop() [Roland]
* | | | | | | 5e2dff2 2011-12-13 | DOC: Updated dispatcher chapter (Java). See #1471 (wip-1471-doc-dispatchers-java-patriknw) [Patrik Nordwall]
| |_|_|/ / /  
|/| | | | |   
* | | | | | 66e7155 2011-12-14 | Fix compilation error in typed actor [Peter Vlugter]
* | | | | |   a9fe796 2011-12-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| * | | | | | facd5be 2011-12-14 | Remove bin and config dirs from distribution zip [Peter Vlugter]
| * | | | | | 4c6c316 2011-12-13 | Updated the Pi tutorial to reflect the changes in Akka 2.0. Fixes #1354 [Henrik Engstrom]
* | | | | | | 544bbf7 2011-12-14 | Adding resource cleanup for TypedActors as to avoid memory leaks [Viktor Klang]
|/ / / / / /  
* | | | | |   c64086f 2011-12-13 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | | 7da61b6 2011-12-13 | rename /null to /deadLetters, fixes #1492 [Roland]
| | |_|_|/  
| |/| | |   
* | | | | 89e29b0 2011-12-13 | Adding daemonicity to the dispatcher configurator [Viktor Klang]
* | | | | 7b7402c 2011-12-13 | Making it easier to specify daemon-ness for the ThreadPoolConfig [Viktor Klang]
|/ / / /  
* | | | c16fceb 2011-12-13 | fix docs generation [Roland]
* | | |   dde6769 2011-12-13 | Merge branch 'wip-remote-supervision-rk' [Roland]
|\ \ \ \  
| * \ \ \   92e7693 2011-12-13 | Merge remote-tracking branch 'origin/master' into wip-remote-supervision-rk [Roland]
| |\ \ \ \  
| * | | | | 134fac4 2011-12-13 | make routers monitor their children [Roland]
| * | | | | 040f307 2011-12-13 | add watch/unwatch for testActor to TestKit [Roland]
| * | | | | 8617f92 2011-12-13 | make writing custom routers even easier [Roland]
| * | | | | 4bd9f6a 2011-12-13 | rename Props.withRouting to .withRouter [Roland]
| * | | | | 4b2b41e 2011-12-13 | fix two comments from Patrik [Roland]
| * | | | | db7dd94 2011-12-13 | re-enable the missing three multi-jvm tests [Roland]
| * | | | | d1a26a9 2011-12-13 | implement remote routers [Roland]
| * | | | | 0a7e5fe 2011-12-12 | wrap up local routing [Roland]
| * | | | |   d8bc57d 2011-12-12 | Merge remote-tracking branch 'origin/1428-RoutedActorRef-henrikengstrom' into wip-remote-supervision-rk [Roland]
| |\ \ \ \ \  
| | * | | | | 192f84d 2011-12-12 | Misc improvements of ActorRoutedRef. Implemented a scatterer gatherer router. Enabled router related tests. See #1440. [Henrik Engstrom]
| | * | | | | a7886ab 2011-12-11 | Implemented a couple of router types. Updated some tests. See #1440 [Henrik Engstrom]
| | * | | | |   fd7a041 2011-12-10 | merged [Henrik Engstrom]
| | |\ \ \ \ \  
| | * | | | | | 11450ca 2011-12-10 | tmp [Henrik Engstrom]
| * | | | | | | 7f0275b 2011-12-11 | that was one hell of a FIXME [Roland]
| * | | | | | | 4065422 2011-12-11 | fix review comment .size>0 => .nonEmpty [Roland]
| * | | | | | | 11601c2 2011-12-10 | fix some FIXMEs [Roland]
| * | | | | | | 09aadcb 2011-12-10 | incorporate review comments [Roland]
| * | | | | | | d4a764c 2011-12-10 | remove LocalActorRef.underlyingActorInstance [Roland]
| * | | | | | | 57d8859 2011-12-10 | tweak authors.pl convenience [Roland]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | f4fd207 2011-12-09 | re-enable multi-jvm tests [Roland]
| * | | | | | 4f643ea 2011-12-09 | simplify structure of Deployer [Roland]
| * | | | | | 8540c70 2011-12-09 | require deployment actor paths to be relative to /user [Roland]
| * | | | | | e773279 2011-12-09 | fix remote-deployed zig-zag look-up [Roland]
| * | | | | |   b84a354 2011-12-09 | Merge remote-tracking branch 'origin/1428-RoutedActorRef-henrikengstrom' into wip-remote-supervision-rk [Roland]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 90b6833 2011-12-08 | Initial take on new routing implementation. Please note that this is work in progress! [Henrik Engstrom]
| * | | | | | a20aad4 2011-12-09 | fix routing of remote messages bouncing nodes (there may be pathological cases ...) [Roland]
| * | | | | | e5bd8b5 2011-12-09 | make remote supervision and path continuation work [Roland]
| * | | | | | fac840a 2011-12-08 | make remote lookup work [Roland]
| * | | | | | 25e23a3 2011-12-07 | remove references to Remote* from akka-actor [Roland]
| * | | | | | 9a74bca 2011-12-07 | remove residue in RemoteActorRefProvider [Roland]
* | | | | | | c8c4f7a 2011-12-13 | Added ScalaDoc to Props. [Jonas Bonér]
| |_|/ / / /  
|/| | | | |   
* | | | | |   e18c924 2011-12-13 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \  
| * | | | | | 31e2cb3 2011-12-13 | Updated to latest config release from typesafehub, v0.1.8 (wip-config-update) [Patrik Nordwall]
* | | | | | |   b5d1785 2011-12-13 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| |/ / / / / /  
| * | | | | |   18601fb 2011-12-13 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   2f8706e 2011-12-13 | Merge pull request #149 from jboner/wip-doc-dispatchers-scala-patriknw [patriknw]
| | |\ \ \ \ \ \  
| | | * | | | | | 7a17eb0 2011-12-13 | DOC: Corrections of dispatcher docs from review. See #1471 (wip-doc-dispatchers-scala-patriknw) [Patrik Nordwall]
| | | * | | | | | eede488 2011-12-13 | Added lookup method in Dispatchers to provide a registry of configured dispatchers to be shared between actors. See #1458 [Patrik Nordwall]
| | | * | | | | | 03e731e 2011-12-12 | DOC: Update Dispatchers (Scala) Chapter. See #1471 [Patrik Nordwall]
| | | * | | | | | 69ea6db 2011-12-12 | gitignore _mb, which is created by file based durable mailbox tests [Patrik Nordwall]
| * | | | | | | |   afd8b89 2011-12-13 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| * | | | | | | | f722641 2011-12-13 | Making owner in PinnedDispatcher private [Viktor Klang]
* | | | | | | | |   8c86804 2011-12-13 | Merge branch 'wip-clean-up-actor-cell-jboner' [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| |_|/ / / / / / /  
|/| | | | | | | |   
| * | | | | | | | d725c9c 2011-12-13 | Updated docs with changes to 'actorOf(Props(..))' [Jonas Bonér]
| * | | | | | | | c9b787f 2011-12-13 | Removed all 'actorOf' methods that does not take a 'Props', and changed all callers to use 'actorOf(Props(..))' [Jonas Bonér]
| * | | | | | | | 86a5114 2011-12-13 | Cleaned up ActorCell, removed all Java-unfriendly methods [Jonas Bonér]
* | | | | | | | |   237f6c3 2011-12-13 | Merge pull request #150 from jboner/wip-1467-logging-docs-patriknw [patriknw]
|\ \ \ \ \ \ \ \ \  
| |_|/ / / / / / /  
|/| | | | | | | |   
| * | | | | | | | 0f41cee 2011-12-13 | Split logging doc into scala and java. See #1467 (wip-1467-logging-docs-patriknw) [Patrik Nordwall]
| * | | | | | | | 4cf3a11 2011-12-13 | Added with ActorLogging [Patrik Nordwall]
| * | | | | | | | 0239106 2011-12-13 | DOC: Updated logging documentation. See #1467 [Patrik Nordwall]
|/ / / / / / / /  
* | | | | | | |   d7840fe 2011-12-13 | Merge pull request #148 from jboner/wip-1470-document-scheduler-√ [viktorklang]
|\ \ \ \ \ \ \ \  
| * | | | | | | | ec1c108 2011-12-13 | More docs [Viktor Klang]
| * | | | | | | | 34ddca0 2011-12-13 | #1470 - Document Scheduler [Viktor Klang]
| | |_|_|_|_|/ /  
| |/| | | | | |   
* | | | | | | | b500f4a 2011-12-13 | DOC: Removed stability-matrix [Patrik Nordwall]
| |_|/ / / / /  
|/| | | | | |   
* | | | | | | 531397e 2011-12-13 | Add dist task for building download zip. Fixes #1001 [Peter Vlugter]
|/ / / / / /  
* | | | | | f4b8e9c 2011-12-12 | DOC: added Henrik to team list [Patrik Nordwall]
* | | | | | 9098f30 2011-12-12 | DOC: Disabled stm and transactors documentation [Patrik Nordwall]
* | | | | | 57b03c5 2011-12-12 | DOC: minor corr [Patrik Nordwall]
* | | | | | 4e9c7de 2011-12-12 | DOC: fixed links [Patrik Nordwall]
* | | | | | fb4faab 2011-12-12 | DOC: fixed other-doc [Patrik Nordwall]
* | | | | | d92c52b 2011-12-12 | DOC: Removed old migration guides and release notes. See #1455 [Patrik Nordwall]
* | | | | | 4df0ec5 2011-12-12 | DOC: Removed most of http docs. See #1455 [Patrik Nordwall]
* | | | | | ad0a67c 2011-12-12 | DOC: Removed actor registry. See #1455 [Patrik Nordwall]
* | | | | | 92a0fa7 2011-12-12 | DOC: Removed tutorial chat server. See #1455 [Patrik Nordwall]
* | | | | | f07768d 2011-12-12 | DOC: Disabled spring, camel and microkernel. See #1455 [Patrik Nordwall]
* | | | | | eaafed6 2011-12-12 | DOC: Update Durable Mailboxes Chapter. See #1472 [Patrik Nordwall]
* | | | | | 08af768 2011-12-12 | Include copy xsd in release script [Peter Vlugter]
|/ / / / /  
* | | | |   5a79a91 2011-12-11 | Merge pull request #145 from jboner/wip-1479-Duration-rk [Roland Kuhn]
|\ \ \ \ \  
| * | | | | baf2a17 2011-12-11 | add docs for Deadline [Roland]
| * | | | | 27e93f6 2011-12-11 | polish Deadline class [Roland]
| * | | | | f4cc4c1 2011-12-11 | add Deadline class [Roland]
|/ / / / /  
* | | | | ceb888b 2011-12-09 | Add scripted release [Peter Vlugter]
* | | | | 7db3f62 2011-12-09 | Converted tabs to spaces. [Jonas Bonér]
* | | | | 4d649c3 2011-12-09 | Removed all @author tags for Jonas Bonér since it has lost its meaning. [Jonas Bonér]
* | | | | 15c0462 2011-12-09 | Added sbteclipse plugin to the build (version 1.5.0) [Jonas Bonér]
* | | | |   0288940 2011-12-09 | Merge pull request #143 from jboner/wip-1435-doc-java-actors-patriknw [patriknw]
|\ \ \ \ \  
| * | | | | 09719af 2011-12-09 | From review comments (wip-1435-doc-java-actors-patriknw) [Patrik Nordwall]
| * | | | | 1979b14 2011-12-08 | UnhandledMessageException extends RuntimeException. See #1453 [Patrik Nordwall]
| * | | | | ce12874 2011-12-08 | Updated documentation of Actors (Java). See #1435 [Patrik Nordwall]
| | |_|/ /  
| |/| | |   
* | | | | 884dc43 2011-12-09 | DOC: Replace all akka.conf references. Fixes #1469 [Patrik Nordwall]
* | | | | 9fdf9a9 2011-12-09 | Removed mist from docs. See #1455 [Patrik Nordwall]
* | | | | f28a1f3 2011-12-09 | Fixed another shutdown of dispatcher issue. See #1454 (pi) [Patrik Nordwall]
* | | | | 9a677e5 2011-12-09 | whitespace format [Patrik Nordwall]
* | | | | b22679e 2011-12-09 | Reuse the deployment and default deployment configs when looping through all deployments. [Patrik Nordwall]
|/ / / /  
* | | |   b4f4866 2011-12-08 | Merge pull request #142 from jboner/wip-1447-actor-context-not-serializable-√ [viktorklang]
|\ \ \ \  
| * | | | 3b5d45f 2011-12-08 | Minor corrections after review [Viktor Klang]
| * | | | 712805b 2011-12-08 | Making sure that ActorCell isn't serializable [Viktor Klang]
* | | | |   2b17415 2011-12-08 | Merge pull request #141 from jboner/wip-1290-clarify-pitfalls [viktorklang]
|\ \ \ \ \  
| * | | | | c2d9e70 2011-12-08 | Fixing indentation and adding another common pitfall [Viktor Klang]
| * | | | | 8870c58 2011-12-08 | Clarifying some do's and dont's on Actors in the jmm docs [Viktor Klang]
| |/ / / /  
* | | | |   9cc8b67 2011-12-08 | Merging in the hotswap docs into master [Viktor Klang]
|\ \ \ \ \  
| * \ \ \ \   dd35b18 2011-12-08 | Merge pull request #139 from jboner/wip-768-message-send-semantics [viktorklang]
| |\ \ \ \ \  
| | * | | | | 210fd09 2011-12-08 | Corrections based on review [Viktor Klang]
| | * | | | | d7771dc 2011-12-08 | Elaborating on the message send semantics as per the ticket [Viktor Klang]
| | |/ / / /  
| * | | | | 9848fdc 2011-12-08 | Update to sbt 0.11.2 [Peter Vlugter]
| * | | | |   738857c 2011-12-07 | Merge pull request #137 from jboner/wip-1435-doc-scala-actors-patriknw [patriknw]
| |\ \ \ \ \  
| | |/ / / /  
| |/| | | |   
| | * | | | c847282 2011-12-07 | Fixed review comments (wip-1435-doc-scala-actors-patriknw) [Patrik Nordwall]
| | * | | | 5cee768 2011-12-06 | Updated documentation of Actors Scala. See #1435 [Patrik Nordwall]
* | | | | | 519aa39 2011-12-08 | Removing 1-entry lists [Viktor Klang]
* | | | | | 6cdb012 2011-12-08 | Removing HotSwap and revertHotSwap [Viktor Klang]
|/ / / / /  
* | | | | 4803ba5 2011-12-07 | Making sure that it doesn't break for the dlq itself [Viktor Klang]
* | | | |   b6f89e3 2011-12-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| * | | | | 5fc9eb2 2011-12-07 | fix failing ActorLookupSpec: implied synchronicity in AskActorRef.whenDone which does not exist [Roland]
| | |_|/ /  
| |/| | |   
* | | | | 72d69cb 2011-12-07 | Moving all the logic for cleaning up mailboxes into the mailbox implementation itself [Viktor Klang]
|/ / / /  
* | | |   bf3ce9b 2011-12-07 | Merge pull request #138 from jboner/wip-reset_behaviors_on_restart [viktorklang]
|\ \ \ \  
| * | | | 4084c01 2011-12-07 | Adding docs to clarify that restarting resets the actor to the original behavior [Viktor Klang]
| * | | | cde4576 2011-12-07 | #1429 - reverting hotswap on restart and termination [Viktor Klang]
* | | | | 81492ce 2011-12-07 | Changed to Debug log level on some shutdown logging (wip-loglevels-patriknw) [Patrik Nordwall]
* | | | | 2721a87 2011-12-07 | Use Config in benchmarks [Patrik Nordwall]
* | | | | 119ceb0 2011-12-07 | Since there is no user API for remoting anymore, remove the old docs [Viktor Klang]
* | | | |   5c3c24b 2011-12-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| | |_|/ /  
| |/| | |   
| * | | | 75c8ac3 2011-11-29 | make next state’s data available in onTransition blocks, fixes #1422 [Roland]
| |/ / /  
* | | | 2872d8b 2011-12-07 | #1339 - adding docs to testing.rst describing how to get testActor to be the implicit sender in TestKit tests [Viktor Klang]
|/ / /  
* | |   7351523 2011-12-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| * | | a0a44ab 2011-12-07 | add and verify Java API for actorFor/ActorPath, fixes #1343 [Roland]
| |/ /  
* | |   50febc5 2011-12-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| |/ /  
| * | 56dc181 2011-12-07 | ActorContext instead of ActorRef in HotSwap code parameter. See #1441 (wip-1441-hotswap-patriknw) [Patrik Nordwall]
| * | f7d6393 2011-12-07 | Removed actorOf methods from AkkaSpec. See #1439 (wip-1439-cleanup-akkaspec-patriknw) [Patrik Nordwall]
* | | aa9b077 2011-12-07 | Adding support for range-based setting of pool sizes [Viktor Klang]
|/ /  
* | eb61173 2011-12-07 | API doc clarification of context and getContext [Patrik Nordwall]
* |   87fc7ea 2011-12-07 | Merge pull request #136 from jboner/wip-1377-context-patriknw [patriknw]
|\ \  
| * \   9c73c8e 2011-12-07 | Merge branch 'master' into wip-1377-context-patriknw (wip-1377-context-patriknw) [Patrik Nordwall]
| |\ \  
| |/ /  
|/| |   
* | | 0f922a3 2011-12-07 | fix ordering issue in ActorLookupSpec [Roland]
| * | 1402c76 2011-12-07 | Minor fixes from review comments. See #1377 [Patrik Nordwall]
| * |   1a93ddb 2011-12-07 | Merge branch 'master' into wip-1377-context-patriknw [Patrik Nordwall]
| |\ \  
| |/ /  
|/| |   
* | | d8ede28 2011-12-06 | improve ScalaDoc of actorOf methods (esp. their blocking on ActorSystem) [Roland]
* | | c4ed571 2011-12-06 | make Jenkins wait for Davy Jones [Roland]
* | | a1d8f30 2011-12-06 | fix bug in creating anonymous actors [Roland]
* | | 831b327 2011-12-06 | add min/max bounds on absolute number of threads for dispatcher [Roland]
* | |   f7f36ac 2011-12-06 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
* | | | 05da3f3 2011-12-06 | Minor formatting, docs and logging edits. [Jonas Bonér]
* | | |   29dd02b 2011-12-06 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
* | | | | 37f21c7 2011-12-05 | Fixed string concatenation error [Jonas Bonér]
| | | * |   bfa14a6 2011-12-07 | Merge branch 'master' into wip-1377-context-patriknw [Patrik Nordwall]
| | | |\ \  
| | | |/ /  
| | |/| |   
| | * | | d6fc97c 2011-12-06 | introduce akka.actor.creation-timeout to make Jenkins happy [Roland]
| | * | |   66c1d62 2011-12-06 | Merge branch 'wip-ActorPath-rk' [Roland]
| | |\ \ \  
| |/ / / /  
| | * | | b2a8e4c 2011-12-06 | document requirements of our Scheduler service [Roland]
| | * | |   9d7597c 2011-12-05 | Merge branch master into wip-ActorPath-rk [Roland]
| | |\ \ \  
| | * | | | 82dc4d6 2011-12-05 | fix remaining review comments [Roland]
| | * | | | cdc5492 2011-12-05 | correct spelling of Davy Jones [Roland]
| | * | | | c0c9487 2011-12-05 | make testActor spew out uncollected messages after test end [Roland]
| | * | | | d2cffe7 2011-12-05 | do not use the only two special characters in Helpers.base64 [Roland]
| | * | | | 3c06992 2011-12-05 | incorporate review comments [Roland]
| | * | | | 13eb1b6 2011-12-05 | replace @volatile for childrenRefs with mailbox status read for memory consistency [Roland]
| | * | | | 0b5f8b0 2011-12-05 | rename ActorPath.{pathElemens => elements} [Roland]
| | * | | | eeca88d 2011-12-05 | add test for “unorderly” shutdown of ActorSystem by PoisonPill [Roland]
| | * | | | 829c67f 2011-12-03 | fix one leak in RoutedActorRef (didn’t properly say good-bye) [Roland]
| | * | | | ea4d30e 2011-12-03 | annotate my new FIXMEs with RK [Roland]
| | * | | | 236ce15 2011-12-03 | fix visibility of top-level actors after creation [Roland]
| | * | | | 1755aed 2011-12-03 | make scheduler shutdown more stringent [Roland]
| | * | | | ed4e302 2011-12-03 | make HashedWheelTimer reliably shutdown [Roland]
| | * | | | 4c1d722 2011-12-03 | fix bug in ActorRef.stop() implementation [Roland]
| | * | | | 3d0bb8b 2011-12-03 | implement ActorSeletion and document ActorRefFactory [Roland]
| | * | | | 79e5c5d 2011-12-03 | implement coherent actorFor look-up [Roland]
| | * | | | a3e6fca 2011-12-02 | rename RefInternals to InternalActorRef and restructure [Roland]
| | * | | |   e38cd19 2011-12-02 | Merge branch 'master' into wip-ActorPath-rk [Roland]
| | |\ \ \ \  
| | * | | | | cf020d7 2011-12-02 | rename top-level paths as per Jonas recommendation [Roland]
| | * | | | | 6b9cdc5 2011-12-01 | fix ActorRef serialization [Roland]
| | * | | | | b65799c 2011-11-30 | remove ActorRef.address & ActorRef.name [Roland]
| | * | | | | 7e4333a 2011-11-30 | fix actor creation with duplicate name within same message invocation [Roland]
| | * | | | | 97789dd 2011-11-30 | fix one typo and one bad omission: [Roland]
| | * | | | | 073c3c0 2011-11-29 | fix EventStreamSpec by adding Logging extension [Roland]
| | * | | | |   afda539 2011-11-29 | merge master into wip-ActorPath-rk [Roland]
| | |\ \ \ \ \  
| | * | | | | | 3182fa3 2011-11-29 | second step: remove LocalActorRefProvider.actors [Roland]
| | * | | | | | dad1c98 2011-11-24 | first step: sanitize ActorPath interface [Roland]
| | * | | | | |   9659870 2011-11-24 | Merge remote-tracking branch 'origin/master' into wip-ActorPath-rk [Roland]
| | |\ \ \ \ \ \  
| | * | | | | | | d40235f 2011-11-22 | version 2 of the addressing spec [Roland]
| | * | | | | | | a2a09ec 2011-11-20 | write addressing & path spec [Roland]
| * | | | | | | | 9618288 2011-12-06 | #1437 - Replacing self with the DeadLetterActorRef when the ActorCell is shut down, this to direct captured self references to the DLQ [Viktor Klang]
| | |_|_|_|/ / /  
| |/| | | | | |   
| | | | | | * | 7595e52 2011-12-06 | Renamed startsWatching to watch, and stopsWatching to unwatch [Patrik Nordwall]
| | | | | | * | 3204269 2011-12-05 | Cleanup of methods in Actor and ActorContext trait. See #1377 [Patrik Nordwall]
| | |_|_|_|/ /  
| |/| | | | |   
| * | | | | | 5530c4c 2011-12-05 | Unborking master [Viktor Klang]
|/ / / / / /  
* | | | | | 5f91bf5 2011-12-05 | Added disabled GossipMembershipMultiJVMSpec. [Jonas Bonér]
* | | | | | d12a332 2011-12-05 | Fixed wrong help text in exception. Fixes #1431. [Jonas Bonér]
| |_|_|_|/  
|/| | | |   
* | | | |   d6d9ced 2011-12-05 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| * | | | | c8a1a96 2011-12-05 | Updated config documentation [Patrik Nordwall]
* | | | | | ddf3a36 2011-12-05 | Added JSON file for ls.implicit.ly [Jonas Bonér]
|/ / / / /  
* | | | | 95791ce 2011-12-02 | #1424 - RemoteSupport is now instantiated from the config, so now anyone can write their own Akka transport layer for remote actors [Viktor Klang]
* | | | |   b2ecad1 2011-12-02 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| * | | | | 1f665ab 2011-12-02 | Changed signatures of Scheduler for better api of by-name blocks (wip-by-name) [Patrik Nordwall]
| * | | | | af1ee4f 2011-12-02 | Utilized the optimized withFallback to simplify config checkValid stuff [Patrik Nordwall]
| | |_|_|/  
| |/| | |   
* | | | | 93d093e 2011-12-02 | Commenting out the ForkJoin stuff until I've cleared some bits with Doug Lea [Viktor Klang]
|/ / / /  
* | | | db075d0 2011-12-02 | Updated to latest config lib 38fb8d6 [Patrik Nordwall]
* | | | eebe068 2011-12-02 | Nice looking toString of config settings. See #1373 [Patrik Nordwall]
* | | |   d85f8d7 2011-12-02 | Merge pull request #134 from jboner/wip-1404-memory-leak-patriknw [patriknw]
|\ \ \ \  
| * | | | 79866e5 2011-12-02 | Made DefaultScheduler Closeable (wip-1404-memory-leak-patriknw) [Patrik Nordwall]
| * | | | b488d70 2011-11-30 | Fixed several memory and thread leaks. See #1404 [Patrik Nordwall]
* | | | | 639c5d6 2011-12-02 | Revert the removal of akka.remote.transport. Will be used in ticket 1424 (wip-transport) [Patrik Nordwall]
|/ / / /  
* | | |   035f514 2011-12-02 | Merge pull request #131 from jboner/wip-1378-fixme-patriknw [patriknw]
|\ \ \ \  
| * | | | fd82251 2011-12-02 | Minor fixes from review comments. (wip-1378-fixme-patriknw) [Patrik Nordwall]
| * | | |   82bbca4 2011-12-02 | Merge branch 'master' into wip-1378-fixme-patriknw [Patrik Nordwall]
| |\ \ \ \  
| |/ / / /  
|/| | | |   
* | | | |   b70faa4 2011-12-01 | Merge pull request #129 from jboner/wip-config-patriknw [patriknw]
|\ \ \ \ \  
| * | | | | 66bf116 2011-12-02 | Changed config.toValue -> config.root (wip-config-patriknw) [Patrik Nordwall]
| * | | | |   c5a367a 2011-12-02 | Merge branch 'master' into wip-config-patriknw [Patrik Nordwall]
| |\ \ \ \ \  
| |/ / / / /  
|/| | | | |   
* | | | | | d626cc2 2011-12-02 | Removing suspend and resume from user-facing API [Viktor Klang]
* | | | | | 879ea7c 2011-12-02 | Removing startsWatching and stopsWatching from docs and removing cruft [Viktor Klang]
* | | | | | fcc6169 2011-12-02 | Removing the final usages of startsWatching/stopsWatching [Viktor Klang]
* | | | | | 54e2e9a 2011-12-02 | Switching more test code to use watch instead of startsWatching [Viktor Klang]
* | | | | | 571d856 2011-12-01 | Removing one use-site of startsWatching [Viktor Klang]
* | | | | | bf7befc 2011-12-01 | Sprinkling some final magic sauce [Viktor Klang]
* | | | | | ef27f86 2011-12-01 | Adding support for ForkJoinPoolConfig so you can use ForkJoin [Viktor Klang]
* | | | | | e3e694d 2011-12-01 | Tweaking the consistency spec for using more cores [Viktor Klang]
* | | | | | e590a48 2011-12-01 | Making the ConsistencySpec a tad more awesomized [Viktor Klang]
* | | | | | b42c6b6 2011-11-30 | Adding the origin address to the SHUTDOWN CommandType when sent and also removed a wasteful FIXME [Viktor Klang]
* | | | | |   e3fe09f 2011-11-30 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| * \ \ \ \ \   67cf9b5 2011-11-30 | Merge pull request #127 from jboner/wip-1380-scheduler-dispatcher-patriknw [patriknw]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   b3107ae 2011-11-30 | Merge branch 'master' into wip-1380-scheduler-dispatcher-patriknw (wip-1380-scheduler-dispatcher-patriknw) [Patrik Nordwall]
| | |\ \ \ \ \ \  
| | |/ / / / / /  
| |/| | | | | |   
| * | | | | | | 99e5d88 2011-11-30 | Removed obsolete samples, see #1278 [Henrik Engstrom]
| * | | | | | |   4e49ee6 2011-11-30 | Merge pull request #130 from jboner/samples-henrikengstrom [Henrik Engstrom]
| |\ \ \ \ \ \ \  
| | * \ \ \ \ \ \   e4ea7ac 2011-11-30 | Merge branch 'master' into samples-henrikengstrom [Henrik Engstrom]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | 9e5c2f1 2011-11-30 | Changed LinkedList to Iterable in constructor, see #1278 [Henrik Engstrom]
| | * | | | | | | | 5cc36fa 2011-11-29 | Added todos for 2.0 release, see #1278 [Henrik Engstrom]
| | * | | | | | | | 1b3ee08 2011-11-29 | Updated after comments, see #1278 [Henrik Engstrom]
| | * | | | | | | | c1f9e76 2011-11-29 | Replaced removed visibility, see #1278 [Henrik Engstrom]
| | * | | | | | | | 823a68a 2011-11-25 | Updated samples and tutorial to Akka 2.0. Added projects to SBT project file. Fixes #1278 [Henrik Engstrom]
| | | |_|_|_|_|/ /  
| | |/| | | | | |   
| | | | * | | | |   fb468d7 2011-11-29 | Merge branch 'master' into wip-1380-scheduler-dispatcher-patriknw [Patrik Nordwall]
| | | | |\ \ \ \ \  
| | | | * | | | | | 4d92091 2011-11-29 | Added dispatcher to constructor of DefaultDispatcher. See #1380 [Patrik Nordwall]
| | | | * | | | | | 15748e5 2011-11-28 | Execute scheduled tasks in system default dispatcher. See #1380 [Patrik Nordwall]
* | | | | | | | | | 16dee0e 2011-11-30 | #1409 - offsetting the raciness and also refrain from having a separate Timer for each Active connection handler [Viktor Klang]
|/ / / / / / / / /  
* | | | | | | | | 070d446 2011-11-30 | #1417 - Added a test to attempt to statistically verify memory consistency for actors [Viktor Klang]
| |/ / / / / / /  
|/| | | | | | |   
| | | | * | | | b56201a 2011-11-29 | Updated to latest config lib and changed how reference config files are loaded. [Patrik Nordwall]
| | | | | * | | 80ac173 2011-11-30 | First walk throught of FIXME. See #1378 [Patrik Nordwall]
| |_|_|_|/ / /  
|/| | | | | |   
* | | | | | |   af3a710 2011-11-29 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \  
| | |_|_|_|_|/  
| |/| | | | |   
| * | | | | | 8f5ddff 2011-11-29 | Added initial support for ls.implicit.ly to the build, still need more work though [Jonas Bonér]
| * | | | | |   bcaadb9 2011-11-29 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | | |_|_|/ /  
| | |/| | | |   
| * | | | | |   b4c09c6 2011-11-28 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| * | | | | | | abf4b52 2011-11-28 | Added *.vim to .gitignore [Jonas Bonér]
| | |_|_|/ / /  
| |/| | | | |   
* | | | | | | 9afc9dc 2011-11-29 | Making sure that all access to status and systemMessage is through Unsafe [Viktor Klang]
| |_|/ / / /  
|/| | | | |   
* | | | | |   8ab25a2 2011-11-29 | Merge pull request #128 from jboner/wip-1371 [viktorklang]
|\ \ \ \ \ \  
| |_|_|_|/ /  
|/| | | | |   
| * | | | | 539e12a 2011-11-28 | Making TypedActors an Akka Extension and adding LifeCycle overrides to TypedActors, see #1371 and #1397 [Viktor Klang]
* | | | | |   7706eea 2011-11-28 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| * \ \ \ \ \   6f18192 2011-11-28 | Merge pull request #126 from jboner/wip-1402-error-without-stacktrace-patriknw [patriknw]
| |\ \ \ \ \ \  
| | |_|_|/ / /  
| |/| | | | |   
| | * | | | | 01e43c3 2011-11-28 | Use scala.util.control.NoStackTrace (wip-1402-error-without-stacktrace-patriknw) [Patrik Nordwall]
| | * | | | |   f3cafa5 2011-11-28 | Merge branch 'master' into wip-1402-error-without-stacktrace-patriknw [Patrik Nordwall]
| | |\ \ \ \ \  
| | |/ / / / /  
| |/| | | | |   
| * | | | | |   1807851 2011-11-28 | Merge pull request #125 from jboner/wip-1401-slf4j-patriknw [patriknw]
| |\ \ \ \ \ \  
| | |_|_|/ / /  
| |/| | | | |   
| | * | | | | 19a78c0 2011-11-28 | Slf4jEventHandler should not format log message. See #1401 [Patrik Nordwall]
| | * | | | | 52c0888 2011-11-28 | Changed slf4j version to 1.6.4. See #1400 [Patrik Nordwall]
| | | * | | | 10517e5 2011-11-28 | Skip stack trace when log error without exception. See #1402 [Patrik Nordwall]
* | | | | | | 1685038 2011-11-28 | Fixing #1379 - making the DLAR the default sender in tell [Viktor Klang]
|/ / / / / /  
* | | | | | 87a22e4 2011-11-28 | stdout-loglevel = WARNING in AkkaSpec (wip-1383) [Patrik Nordwall]
* | | | | | a229140 2011-11-28 | Fixed race in trading perf test. Fixes #1383 [Patrik Nordwall]
| |/ / / /  
|/| | | |   
* | | | |   bff4644 2011-11-28 | Merge pull request #124 from jboner/wip-1373-log-config-patriknw [patriknw]
|\ \ \ \ \  
| |/ / / /  
|/| | | |   
| * | | | 3846b63 2011-11-28 | Minor review fixes (wip-1373-log-config-patriknw) [Patrik Nordwall]
| * | | |   0f410b1 2011-11-28 | Merge branch 'master' into wip-1373-log-config-patriknw [Patrik Nordwall]
| |\ \ \ \  
| |/ / / /  
|/| | | |   
* | | | | 534db2d 2011-11-28 | Add sbt settings to exclude or include tests using scalatest tags. Fixes #1389 [Peter Vlugter]
* | | | | fda8bce 2011-11-26 | Updated DefaultScheduler and its Spec after comments, see #1393 [Henrik Engstrom]
* | | | | 8bd4dad 2011-11-25 | Added check in DefaultScheduler to detect if receiving actor of a reschedule has been terminated. Fixes #1393 [Henrik Engstrom]
| |/ / /  
|/| | |   
| * | | de2de7e 2011-11-25 | Added logConfig to ActorSystem and logConfigOnStart property. See #1373 [Patrik Nordwall]
|/ / /  
* | |   bd2fdaa 2011-11-25 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| * | | 380cd1c 2011-11-25 | Added some variations of the TellThroughputPerformanceSpec (wip-perf) [Patrik Nordwall]
* | | | d2ef8b9 2011-11-25 | Fixed problems with remote configuration. [Jonas Bonér]
|/ / /  
* | |   8237271 2011-11-25 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| * \ \   e33b956 2011-11-25 | Merge pull request #123 from jboner/wip-extensions [viktorklang]
| |\ \ \  
| | * | | 603a8ed 2011-11-25 | Creating ExtensionId, AbstractExtensionId, ExtensionIdProvider and Extension [Viktor Klang]
| | * | | bf20f3f 2011-11-24 | Reinterpretation of Extensions [Viktor Klang]
| | |/ /  
* | | | 9939473 2011-11-25 | Added configuration for seed nodes in RemoteExtension and Gossiper. Also cleaned up reference config from old cluster stuff. [Jonas Bonér]
|/ / /  
* | | 3640c09 2011-11-25 | Removed obsolete sample modules and cleaned up build file. [Jonas Bonér]
* | |   0a1740c 2011-11-24 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| |/ /  
| * |   c0d3c52 2011-11-24 | Merge pull request #122 from jboner/hwt-nano-henrikengstrom [viktorklang]
| |\ \  
| | * | 8b6afa9 2011-11-24 | Removed unnecessary method and utilize System.nanoTime directly instead. See #1381 [Henrik Engstrom]
| | * | 310b273 2011-11-24 | Changed the HWT implementation to use System.nanoTime internally instead of System.currentTimeMillis. Fixes #1381 [Henrik Engstrom]
| * | |   f7bba9e 2011-11-24 | Merge pull request #121 from jboner/wip-1361-modularize-config-reference-patriknw [patriknw]
| |\ \ \  
| | * \ \   c53d5e1 2011-11-24 | Merge branch 'master' into wip-1361-modularize-config-reference-patriknw (wip-1361-modularize-config-reference-patriknw) [Patrik Nordwall]
| | |\ \ \  
| | |/ / /  
| |/| | |   
| * | | |   3ab2642 2011-11-24 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | | |/ /  
| | |/| |   
| | * | | 35d4d04 2011-11-24 | Decreased the time to wait in SchedulerSpec to make tests run a wee bit faster, see #1291 [Henrik Engstrom]
| | * | | 463c692 2011-11-24 | Fixed failing test, see #1291 [Henrik Engstrom]
| | * | |   a247b34 2011-11-23 | Merge pull request #120 from jboner/hwt-tests-henrikengstrom [Henrik Engstrom]
| | |\ \ \  
| | | * \ \   4a2a512 2011-11-24 | Merge branch 'master' into hwt-tests-henrikengstrom [Henrik Engstrom]
| | | |\ \ \  
| | | |/ / /  
| | |/| | |   
| | | * | | ddb7b57 2011-11-23 | Fixed some typos, see #1291 [Henrik Engstrom]
| | | * | | e2ad108 2011-11-23 | Updated the scheduler implementation after feedback; changed Duration(x, timeunit) to more fluent 'x timeunit' and added ScalaDoc [Henrik Engstrom]
| | | * | | 7ca5a41 2011-11-23 | Introduced Duration instead of explicit value + time unit in HWT, Scheduler and users of the schedule functionality. See #1291 [Henrik Engstrom]
| | | * | | ac03696 2011-11-22 | Added test of HWT and parameterized HWT constructor arguments (used in ActorSystem), see #1291 [Henrik Engstrom]
| * | | | | 4a64428 2011-11-24 | Moving the untrustedMode setting into the marshalling ops [Viktor Klang]
| * | | | | abcaf01 2011-11-23 | Removing legacy comment [Viktor Klang]
| |/ / / /  
| | | * | c9187e2 2011-11-24 | Minor fixes from review [Patrik Nordwall]
| | | * | 3fd629e 2011-11-24 | PORT_FIELD_NUMBER back to origin [Patrik Nordwall]
| | | * | 1793992 2011-11-22 | Modularize configuration. See #1361 [Patrik Nordwall]
| | |/ /  
| |/| |   
| * | | c56341b 2011-11-23 | Fixing FIXME to rename isShutdown to isTerminated [Viktor Klang]
| * | | 7d9a124 2011-11-23 | Removing @inline from Actor.sender since it cannot safely be inlined anyway. Also, changing the ordering of the checks for receiveTimeout_= so it passed -optimize compilation without whining [Viktor Klang]
| * | | 36e85e9 2011-11-23 | #1372 - Making sure that ActorCell is 64bytes so it fits exactly into one cache line [Viktor Klang]
| * | | 3be5c05 2011-11-23 | Removing a wasteful field in ActorCell, preparing to get to 64bytes (cache line glove-fit [Viktor Klang]
| * | | 4e6361a 2011-11-22 | Removing commented out code [Viktor Klang]
| * | | 229399d 2011-11-22 | Removing obsolete imports from ReflectiveAccess [Viktor Klang]
| * | | 6d2b090 2011-11-22 | Removing ActorContext.hasMessages [Viktor Klang]
| |/ /  
| * | 8516dbb 2011-11-22 | Adding a FIXME so we make sure to get ActorCell down to 64bytes [Viktor Klang]
| * |   5af3c72 2011-11-22 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * | 153d69d 2011-11-21 | Removed unecessary code in slf4j logger, since logSource is already resolved to String [Patrik Nordwall]
| * | | ce6dd05 2011-11-22 | Removing 1 AtomicLong from all ActorCells [Viktor Klang]
| |/ /  
| * | 4fdf698 2011-11-21 | Switching to sun.misc.Unsafe as an experiment, easily revertable [Viktor Klang]
| * |   8d356ba 2011-11-21 | Merge pull request #118 from jboner/wip-1363-remove-default-time-unit-patriknw [viktorklang]
| |\ \  
| | * | e5f8a41 2011-11-21 | Remove default time unit in config. All durations explicit. See #1363 (wip-1363-remove-default-time-unit-patriknw) [Patrik Nordwall]
| * | |   263e2d4 2011-11-21 | Merge branch 'extensions' into master [Roland]
| |\ \ \  
| | |/ /  
| |/| |   
| | * | 61f303a 2011-11-21 | add ActorSystem.hasExtension and throw IAE from .extension() [Roland]
| | * | 4102b57 2011-11-19 | add some more docs [Roland]
| | * | 69ce6aa 2011-11-17 | add extension mechanism [Roland]
| * | |   1543594 2011-11-21 | Merge pull request #116 from jboner/wip-1141-config-patriknw [patriknw]
| |\ \ \  
| | |_|/  
| |/| |   
| | * | 7d928a6 2011-11-19 | Add more compherensive tests for DeployerSpec. Fixes #1052 (wip-1141-config-patriknw) [Patrik Nordwall]
| | * | 74b5af1 2011-11-19 | Adjustments based on Viktor's review comments. [Patrik Nordwall]
| | * | 7f46583 2011-11-19 | Adjustments based on review comments. See #1141 [Patrik Nordwall]
| | * | b8be8f3 2011-11-19 | Latest config lib [Patrik Nordwall]
| | * |   a9217ce 2011-11-19 | Merge branch 'master' into wip-1141-config-patriknw [Patrik Nordwall]
| | |\ \  
| | |/ /  
| |/| |   
| * | |   d588e5a 2011-11-18 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | | |/  
| | |/|   
| | * | 7999c4c 2011-11-18 | Docs: Changed organization id from se.scalablesolutions.akka to com.typesafe.akka [Patrik Nordwall]
| | * | d41c79c 2011-11-18 | Docs: Add info about timestamped snapshot versions to docs. Fixes #1164 [Patrik Nordwall]
| * | | 9ae3d7f 2011-11-18 | Fixing (yes, I know, I've said this a biiiiiillion times) the BalancingDispatcher, removing some wasteful volatile reads in the hot path [Viktor Klang]
| * | |   069c68f 2011-11-18 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | |/ /  
| | * | 6a8e516 2011-11-18 | change source tag in log events from AnyRef to String [Roland]
| * | | 8f944f5 2011-11-18 | Removing unused utilities [Viktor Klang]
| * | | 9476c69 2011-11-18 | Switching to Switch for the shutdown flag for the bubble walker, and implementing support for stopping with an error [Viktor Klang]
| |/ /  
| | * 3d6c0fb 2011-11-18 | Removed unecessary import [Patrik Nordwall]
| | *   c6b157d 2011-11-18 | Merge branch 'master' into wip-1141-config-patriknw [Patrik Nordwall]
| | |\  
| | |/  
| |/|   
| * | d63c511 2011-11-18 | #1351 - Making sure that the remoting is shut down when the ActorSystem is shut down [Viktor Klang]
| | * 50faf18 2011-11-18 | Changed to parseString instead of parseReader [Patrik Nordwall]
| | * 4b8f11e 2011-11-15 | Replaced akka.config with new configuration utility. See #1141 and see #1342 [Patrik Nordwall]
| |/  
| *   80d766b 2011-11-17 | Adding DispatcherPrerequisites to hold the common dependencies that a dispatcher needs to be created [Viktor Klang]
| |\  
| | *   62032cb 2011-11-17 | merge system-cleanup into master [Roland]
| | |\  
| | | * 4470cf0 2011-11-17 | incorporate Viktor’s review comments [Roland]
| | | * d381b72 2011-11-17 | rename app: ActorSystem to system everywhere [Roland]
| | | * c31695b 2011-11-17 | rename AkkaConfig to Settings [Roland]
| | | * 2b6d9ca 2011-11-17 | rename ActorSystem.root to rootPath [Roland]
| | | * 5cc228e 2011-11-16 | mark timing tags so they can be omitted on Jenkins [Roland]
| | | * 648661c 2011-11-16 | clean up initialization of ActorSystem, fixes #1050 [Roland]
| | | * 6d85572 2011-11-14 | - expose ActorRefProvider.AkkaConfig - relax FSMTimingSpec a bit [Roland]
| | | * 30df7d7 2011-11-14 | remove app argument from TypedActor [Roland]
| | | * 3c61e59 2011-11-14 | remove app argument from Deployer [Roland]
| | | * 1cdc875 2011-11-14 | remove app argument from eventStream start methods [Roland]
| | | * f2bf27b 2011-11-14 | remove app argument from Dispatchers [Roland]
| | | * 79daccd 2011-11-10 | move AkkaConfig into ActorSystem companion object as normal class to make it easier to pass around. [Roland]
| | | * fc4598d 2011-11-14 | start clean-up of ActorSystem structure vs. initialization [Roland]
| | | * c3521a7 2011-11-14 | add comment in source for 85e37ea8efddac31c4b58028e5e73589abce82d8 [Roland]
| * | | 0fbe1d3 2011-11-17 | Adding warning message for non-eviction so that people can see when there's a bug [Viktor Klang]
| * | | 9f36aef 2011-11-17 | Switching to the same system message emptying strategy as for the normal Dispatcher, on the BalancingDispatcher [Viktor Klang]
| |/ /  
| * | d4cfdff 2011-11-16 | move japi subpackages into their own directories from reduced Eclipse disturbances [Roland]
* | | ef6c837 2011-11-24 | Added BroadcastRouter which broadcasts all messages to all the connections it manages, also added tests. [Jonas Bonér]
|/ /  
* | 1bf5abb 2011-11-16 | Removing UnsupportedActorRef and replacing its use with MinimalActorRef [Viktor Klang]
* | 18bfa26 2011-11-16 | Renaming startsMonitoring/stopsMonitoring to startsWatching and stopsWatching [Viktor Klang]
* | af3600b 2011-11-16 | Prolonging the timeout for the throughput performance spec [Viktor Klang]
* | 1613ff5 2011-11-16 | Making sure that dispatcher scheduling for shutdown is checked even if unregister throws up [Viktor Klang]
* | 39b374b 2011-11-16 | Switching to a Java baseclass for the MessageDispatcher so we can use primitive fields and Atmoc field updaters for cache locality [Viktor Klang]
* | 13bfee7 2011-11-16 | Removing Un(der)used locking utils (locking is evil) and removing the last locks from the MessageDispatcher [Viktor Klang]
* |   5593e86 2011-11-15 | Merge pull request #94 from kjellwinblad/master [viktorklang]
|\ \  
| * \   d07d8e8 2011-11-15 | Merge remote branch 'upstream/master' [Kjell Winblad]
| |\ \  
| |/ /  
|/| |   
* | |   3707405 2011-11-15 | Merge pull request #110 from jboner/wip-896-durable-mailboxes-patriknw [viktorklang]
|\ \ \  
| * | | a6e75fb 2011-11-15 | Added cleanUp callback in Mailbox, dused in ZooKeeper. Some minor cleanup (wip-896-durable-mailboxes-patriknw) [Patrik Nordwall]
| * | |   cf675d2 2011-11-15 | Merge branch 'master' into wip-896-durable-mailboxes-patriknw [Patrik Nordwall]
| |\ \ \  
| |/ / /  
|/| | |   
| * | | 4aa1905 2011-11-01 | Enabled durable mailboxes and implemented them with mailbox types. See #895 [Patrik Nordwall]
| | * |   1273588 2011-11-15 | Merge remote branch 'upstream/master' [Kjell Winblad]
| | |\ \  
| |_|/ /  
|/| | |   
* | | | 727c7de 2011-11-15 | Removing bounded executors since they have probably never been used, also, removing possibility to specify own RejectedExecutionHandler since Akka needs to know what to do there anyway. Implementing a sane version of CallerRuns [Viktor Klang]
* | | | 13647b2 2011-11-14 | Doh [Viktor Klang]
* | | | a7e9ff4 2011-11-14 | Switching to AbortPolicy by default [Viktor Klang]
* | | | afe1e37 2011-11-14 | BUSTED ! [Viktor Klang]
* | | | 66dd012 2011-11-14 | Temporary fix for the throughput benchmark [Viktor Klang]
* | | | d14e524 2011-11-14 | Removing yet another broken ActorPool test [Viktor Klang]
* | | | 1c35232 2011-11-14 | Removing pointless test from ActorPoolSpec, tweaking the ActiveActors...Capacitor [Viktor Klang]
* | | | 78022f4 2011-11-14 | Removing nonsensical check for current message in ActiveActorsPressureCapacitor [Viktor Klang]
* | | | e40b7cd 2011-11-14 | Fixing bug where a dispatcher would shut down the executor service before all tasks were executed, also taking the opportunity to decrease the size per mailbox by atleast 4 bytes [Viktor Klang]
* | | | 0307a65 2011-11-14 | Removing potential race condition in reflective cycle breaking stuff [Viktor Klang]
|/ / /  
* | | 86af46f 2011-11-14 | Moving comment to right section [Viktor Klang]
* | | 31fbe76 2011-11-14 | It is with great pleasure I announce that all tests are green, I now challenge thee, Jenkins, to repeat it for me. [Viktor Klang]
* | |   d978758 2011-11-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| | |/  
| |/|   
| * | a08234c 2011-11-13 | introduce base64 random names [Roland]
| * | 5d85ab3 2011-11-13 | implement 'stopping' state of actors [Roland]
| * | 92b0d17 2011-11-13 | fix more EventFilter related issues [Roland]
| * | 5563709 2011-11-13 | fix EventStreamSpec (was relying on synchronous logger start) [Roland]
| * | 6097db5 2011-11-13 | do not stop testActor [Roland]
| * | 02a5cd0 2011-11-12 | remove ActorRef from Failed/ChildTerminated and make some warnings nicer [Roland]
| * | 1ba1687 2011-11-12 | improve DeadLetter reporting [Roland]
| * | 85e37ea 2011-11-11 | fix one safe publication issue [Roland]
| * | cd5baf8 2011-11-11 | silence some more expected messages which appeared only on Jenkins (first try) [Roland]
| * | 56cb2a2 2011-11-11 | clean up test output, increase default timeouts [Roland]
| * | e5c3b39 2011-11-11 | correct cleanupMailboxFor to reset the system messages before enqueuing [Roland]
| * | aedb319 2011-11-11 | Fixed missing import [Jonas Bonér]
| * |   f0fa7bc 2011-11-11 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \  
| | * | 3808853 2011-11-11 | fix some bugs, but probably not the pesky ones [Roland]
| | * | 9a10953 2011-11-11 | Add config default for TestKit wait periods outside within() [Roland]
| | * | aa1977d 2011-11-11 | further tweak timings in FSMTimingSpec [Roland]
| | * | 997a258 2011-11-11 | adapt TestTimeSpec to recent timefactor-fix [Roland]
| * | | 9671c55 2011-11-11 | Removed RoutedProps.scala (moved the remaining code into Routing.scala). [Jonas Bonér]
| * | | 166a5df 2011-11-11 | Removed config elements for Mist. [Jonas Bonér]
| * | | e88d073 2011-11-11 | Cleaned up RoutedProps and removed all actorOf methods with RoutedProps. [Jonas Bonér]
| |/ /  
* | | f02e3be 2011-11-11 | Splitting out the TypedActor part of the ActorPoolSpec to isolate it [Viktor Klang]
|/ /  
* | a9049ec 2011-11-11 | Added test for ScatterGatherFirstCompletedRouter. Fixes #1275. [Jonas Bonér]
* |   7ec510f 2011-11-11 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \  
| * | ad79b55 2011-11-11 | make FSMTimingSpec even more robust (no, really!) [Roland]
| * | 817130d 2011-11-11 | add specific override from System.getProperties for akka.test.timefactor [Roland]
| * | aa1b39c 2011-11-11 | use actor.self when logging system message processing errors [Roland]
* | | 7931032 2011-11-11 | Removing postMessageToMailbox and taking yet another  stab at the pesky balancing dispatcher race [Viktor Klang]
|/ /  
* |   5d81c59 2011-11-11 | Merge pull request #108 from jboner/he-doc-generation-fix [viktorklang]
|\ \  
| * | f2bec70 2011-11-11 | Added explicit import to help SBT with dependencies during documentation generation [Henrik Engstrom]
|/ /  
* | 53353d7 2011-11-10 | rename MainBus to EventStream (incl. field in ActorSystem) [Roland]
* | 945b1ae 2011-11-10 | rename akka.AkkaApplication to akka.actor.ActorSystem [Roland]
* | c6e44ff 2011-11-10 | Removing hostname and port for AkkaApplication, renaming defaultAddress to address, removing Deployer.RemoteAddress and use the normal akka.remote.RemoteAddress instead [Viktor Klang]
* |   c75a8db 2011-11-10 | Merging in Henriks HashedWheelTimer stuff manually [Viktor Klang]
|\ \  
| * | 1577f8b 2011-11-10 | Updated after code review: [Henrik Engstrom]
| * | d1ebc1e 2011-11-10 | Added a Cancellable trait to encapsulate any specific scheduler implementations from leaking. Fixes #1286 [Henrik Engstrom]
| * | 896c906 2011-11-09 | Implemented HashedWheelTimer as the default scheduling mechanism in Akka. Fixes #1291 [Henrik Engstrom]
* | |   1fb1309 2011-11-10 | Merging with master [Viktor Klang]
|\ \ \  
| * | | 7553a89 2011-11-10 | turn unknown event in StandardOutLogger into warning [Roland]
| * | | 5b9a57d 2011-11-10 | optimize SubchannelClassification.publish (manual getOrElse inline) [Roland]
| * | |   3e16603 2011-11-10 | Merge pull request #107 from jboner/actor-path [Roland Kuhn]
| |\ \ \  
| | * | | 0c75318 2011-11-09 | Extend waiting time for "waves of actors" test by explicitly waiting for all children to stop. [Peter Vlugter]
| | * | | a7ed5d7 2011-11-08 | Update deployer to use actor path rather than old address (name) [Peter Vlugter]
| | * | | 7b8a865 2011-11-08 | Rename address to name or path where appropriate [Peter Vlugter]
| | * | | 3f7cff1 2011-11-08 | Add an initial implementation of actor paths [Peter Vlugter]
* | | | | ba9281e 2011-11-10 | Removing InetSocketAddress as much as possible from the remoting, switching to RemoteAddress for an easier way forward with different transports. Also removing quite a few allocations internally in the remoting as a side-efect of this. [Viktor Klang]
|/ / / /  
* | | | 0800511 2011-11-10 | Removing one allocation per remote message send [Viktor Klang]
* | | |   91a251e 2011-11-10 | Merge branch 'foo' [Viktor Klang]
|\ \ \ \  
| * | | | 7802236 2011-11-10 | Removing the distinction between client and server module for the remoting [Viktor Klang]
* | | | | 019dd9f 2011-11-10 | minor fix to logging [Jonas Bonér]
|/ / / /  
* | | | 66cd1db 2011-11-10 | Removed 'GENERIC' log level, now transformed into Debug(..) [Jonas Bonér]
|/ / /  
* | | 00b434d 2011-11-10 | Removed CircuitBreaker and its spec. [Jonas Bonér]
* | |   85fc8be 2011-11-10 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| |/ /  
| * | b2d548b 2011-11-10 | implement SubchannelClassification in MainBus, fixes #1340 [Roland]
| * |   70ae4e1 2011-11-09 | Merge branch 'logging' [Roland]
| |\ \  
| | * | 594f521 2011-11-09 | actually make buddies comparator consistent now [Roland]
| | * | 402258f 2011-11-09 | make BalancingDispatcher.buddies comparator transitive [Roland]
| | * |   a747ef7 2011-11-09 | Merge remote branch 'origin/master' into logging [Roland]
| | |\ \  
| | * | | b3249e0 2011-11-09 | finish EventFilter work and apply in three places [Roland]
| | * | | c1a9475 2011-11-07 | TestEventFilter overhaul [Roland]
| | * | | 6559511 2011-11-06 | FSMTimingSpec overhaul [Roland]
| | * | | 4f4227a 2011-11-04 | work-around compiler bug in ActiveRemoteClient [Roland]
| | * | | b4ab673 2011-11-04 | fix Logging.format by implementing {} replacement directly [Roland]
| | * | | 3f21c8a 2011-11-04 | fix ActorDocSpec by allowing INFO loglevel to pass through [Roland]
| | * | | 7198dd6 2011-11-03 | fix FSMActorSpec (wrongly setting loglevel) [Roland]
| | * | | d4c91ef 2011-11-03 | expand MainBusSpec wrt. logLevel setting [Roland]
| | * | | 91bee03 2011-11-03 | fix TestKit.receiveWhile when using implicitly discovered maximum wait time (i.e. default argument) [Roland]
| | * | | 05b9cbc 2011-11-03 | fix LoggingReceiveSpec [Roland]
| | * | | b35f8de 2011-11-03 | incorporate review from Viktor & Jonas [Roland]
| | * | | c671600 2011-10-30 | fix up Slf4jEventHandler to handle InitializeLogger message [Roland]
| | * | | 55f8962 2011-10-29 | some polishing of new Logging [Roland]
| | * | | d1e0f41 2011-10-28 | clean up application structure [Roland]
| | * | | 01d8b00 2011-10-27 | first time Eclipse deceived me: fix three more import statements [Roland]
| | * | | 897c7bd 2011-10-27 | fix overlooked Gossiper change (from rebase) [Roland]
| | * | | f46c6dc 2011-10-27 | introducing: MainBus feat. LoggingBus [Roland]
| * | | | c8325f6 2011-11-09 | Check mailbox status before readding buddies in balancing dispatcher. Fixes #1338 (or appears to) [Peter Vlugter]
| * | | | 0b2690e 2011-11-09 | Adding support for reusing inbound connections for outbound messages, PROFIT [Viktor Klang]
| * | | | 51a01e2 2011-11-09 | Removing akka-http, making so that 'waves of actors'-test fails when there's a problem and removing unused config sections in the conf file [Viktor Klang]
| * | | | 3bffeae 2011-11-09 | De-complecting the notion of address in the remoting server [Viktor Klang]
| * | | | 39ba4fb 2011-11-09 | Removing a pointless TODO and a semicolon [Viktor Klang]
| * | | | ed3ff93 2011-11-09 | Simplifying remote error interception and getting rid of retarded exception back-propagation [Viktor Klang]
| * | | | b6d53aa 2011-11-09 | Removing a couple of lines of now defunct code from the remoting [Viktor Klang]
| * | | | f04b6a5 2011-11-09 | Removing executionHandler from Netty remoting since we do 0 (yes, Daisy, you heard me) blocking ops in the message sends [Viktor Klang]
| * | | |   bd5b07c 2011-11-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | * | | | d04ad32 2011-11-09 | Get rst docs building again and add some adjustments to the new cluster documentation [Peter Vlugter]
| | | |/ /  
| | |/| |   
| * | | | c5de779 2011-11-09 | Removing some bad docs [Viktor Klang]
| |/ / /  
| * | | 294c71d 2011-11-08 | Adding stubs for implementing support for outbound passive connections for remoting [Viktor Klang]
| * | | f12914f 2011-11-08 | Turning all eventHandler log messages left in NettyRemoteSupport into debug messages and remove more dual entries (log + event) [Viktor Klang]
| * | | 7e66d93 2011-11-08 | Removign dual logging of remote events, switching to only dumping it into the eventHandler [Viktor Klang]
| * | | 01df0c3 2011-11-08 | Removing the guard (ReentrantGuard) from RemoteServerModule and switching from executing reconnections and shutdowns in the HashWheelTimer instead of Future w. default dispatcher [Viktor Klang]
| * | | 3021baa 2011-11-08 | Fixing the BuilderParents generated by protobuf with FQN and fixing @returns => @return [Viktor Klang]
| * | | 55d2a48 2011-11-08 | Adding a project definition for akka-amqp (but without code) [Viktor Klang]
| * | | 8470672 2011-11-08 | Renaming ActorCell.supervisor to 'parent', adding 'parent' to ActorContext [Viktor Klang]
* | | | f4740a4 2011-11-10 | Moved 'failure-detector' config from 'akka.actor.deployment.address' to 'akka.remote'. Made AccrualFailureDetector configurable from config. [Jonas Bonér]
|/ / /  
* | | 39d1696 2011-11-08 | Dropping akka-http (see 1330) [Viktor Klang]
* | | 48dbfda 2011-11-08 | Reducing sleep time for ActorPoolSpec for typed actors and removing defaultSupervisor from Props [Viktor Klang]
* | | fd130d0 2011-11-08 | Fixing ActorPoolSpec (more specifically the ActiveActorsPressure thingie-device) and stopping the typed actors after the test of the spec [Viktor Klang]
* | | 3681d0f 2011-10-31 | Separate latency and throughput measurement in performance tests. Fixes #1333 (wip) [Patrik Nordwall]
* | | 1e3ab26 2011-11-05 | Slight tweak to solution for ticket 1313 [Derek Williams]
* | | d8d322c 2011-11-04 | Moving in Deployer udner the provider [Viktor Klang]
* | | a75310a 2011-11-04 | Removing unused code in ReflectiveAccess, fixing a performance-related issue in LocalDeployer and switched back to non-systemServices in the LocalActorRefProviderSpec [Viktor Klang]
* | | a044e41 2011-11-03 | Removing outdated and wrong serialization docs [Viktor Klang]
* | |   5efe091 2011-11-03 | Merge pull request #103 from jboner/no-uuid [viktorklang]
|\ \ \  
| * | | e958987 2011-11-03 | Switching to AddressProtocol for the remote origin address [Viktor Klang]
| * | | 37ba03e 2011-11-03 | Adding initial support in the protocol to get the public host/port of the connecting remote server [Viktor Klang]
| * | | 601df04 2011-11-03 | Folding RemoteEncoder into the RemoteMarshallingOps [Viktor Klang]
| * | | a040a0c 2011-11-03 | Profit! Removing Uuids from ActorCells and ActorRefs and essentially replacing the remoting with a new implementation. [Viktor Klang]
|/ / /  
* | | 2f52f43 2011-11-01 | Refining the DeadLetterActorRef serialization test to be a bit more specific [Viktor Klang]
* | |   f427c99 2011-11-01 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| * | | 06c66bd 2011-11-01 | Fix deprecation warnings in sbt plugin [Peter Vlugter]
* | | | 3aed09c 2011-11-01 | #1320 - Implementing readResolve and writeReplace for the DeadLetterActorRef [Viktor Klang]
|/ / /  
* | | 6e5de8b 2011-10-31 | Fixing a compilation quirk in Future.scala and switching to explicit timeout in TypedActor internals [Viktor Klang]
* | | a6c4bfa 2011-10-31 | #1324 - making sure that ActorPools are prefilled [Viktor Klang]
* | | f5f2ac0 2011-10-29 | Fixing erronous test renames [Viktor Klang]
* | | a52e0fa 2011-10-29 | Renaming JavaAPI.java:mustAcceptSingleArgTryTell to mustAcceptSingleArgTell [Viktor Klang]
* | | 24bac14 2011-10-29 | Removing unused handleFailure-method [Viktor Klang]
* | | 631c734 2011-10-29 | Increasing the timeout of the ActorPoolSpec for the typed actors [Viktor Klang]
* | | 91545a4 2011-10-29 | Fixing TestActorRefSpec, now everything's green [Viktor Klang]
* | | d64b2a7 2011-10-28 | All green, fixing issues with the new ask implementation and remoting [Viktor Klang]
* | | 5d4ef80 2011-10-28 | Fixing ActorModelSpec for CallingThreadDispatcher [Viktor Klang]
* | | df27942 2011-10-28 | Fixing FutureSpec and adding finals to ActoCell and removing leftover debug print from TypedActor [Viktor Klang]
* | | 029e1f5 2011-10-27 | Removing obsolete test for completing futures in the dispatcher [Viktor Klang]
* | | 36c5919 2011-10-27 | Porting the Supervisor spec [Viktor Klang]
* | | c37d673 2011-10-27 | Fixing ActorModelSpec to work with the new ask/? [Viktor Klang]
* | | c998485 2011-10-27 | Fixing ask/? for the routers so that tests pass and stuff [Viktor Klang]
* | | e71d9f7 2011-10-27 | Fixing TypedActors so that exceptions are propagated back [Viktor Klang]
* | | cb1b461 2011-10-27 | Fixing ActorRefSpec that depended on the semantics of ?/Ask to get ActorKilledException from PoisonPill [Viktor Klang]
* | | 4ac7f4d 2011-10-27 | Making sure that akka.transactor.test.CoordinatedIncrementSpec works with machines with less than 4 cores [Viktor Klang]
* | | 8a7290b 2011-10-27 | Making sure that akka.transactor.test.TransactorSpec works with machines with less than 4 cores [Viktor Klang]
* | | 0c30917 2011-10-27 | Making sure that the JavaUntypedTransactorSpec works with tinier machines [Viktor Klang]
* | | f8ef631 2011-10-27 | Fixing UntypedCoordinatedIncrementTest so it works with computers with less CPUs than 5 :p [Viktor Klang]
* | | 26f45a5 2011-10-26 | Making walker a def in remote [Viktor Klang]
* | | 3e3cf86 2011-10-23 | Removing futures from the remoting [Viktor Klang]
* | | 1b730b5 2011-10-22 | Removing Channel(s), tryTell etc, everything compiles but all tests are semibroken [Viktor Klang]
* | | cccf6b4 2011-10-30 | remove references to !! from docs (apart from camel internals) (wip2) [Roland]
* | | bb51bfd 2011-10-30 | Added a simple performance test, without domain complexity [Patrik Nordwall]
* | | 84da972 2011-10-30 | Changed so that clients doesn't wait for each message to be processed before sending next [Patrik Nordwall]
* | |   a32ca5d 2011-10-28 | Merge branch 'master' into wip-1313-derekjw [Derek Williams]
|\ \ \  
| * | | 38d2108 2011-10-20 | Add chart for comparing throughput to benchmark. Fixes #1318 [Patrik Nordwall]
| * | | 7cde84b 2011-10-20 | Fixed benchmark reporting, which was broken in AkkaApplication refactoring. Repo must be global to keep results [Patrik Nordwall]
| * | | 9bf9cea 2011-10-28 | Removed trailing whitespace [Jonas Bonér]
| * | | e9dfaf7 2011-10-28 | Fixed misc FIXMEs [Jonas Bonér]
| * | | 7b485f6 2011-10-28 | Added documentation page on guaranteed delivery. [Jonas Bonér]
* | | | 885fdfe 2011-10-27 | Send tasks back to the Dispatcher if Future.await is called. Fixes #1313 [Derek Williams]
|/ / /  
* | | fef4075 2011-10-27 | Added section about how to do a distributed dynamo-style datastorage on top of akka cluster [Jonas Bonér]
* | |   ef4262f 2011-10-27 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| * | | 706692d 2011-10-27 | Some more cluster documentation [Peter Vlugter]
| |/ /  
* | | c1152a0 2011-10-27 | Fixed minor stuff in Gossiper after code review feedback. [Jonas Bonér]
|/ /  
* | c8b17b9 2011-10-27 | reformatting [Jonas Bonér]
* |   09a219b 2011-10-27 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| * | 4bd9650 2011-10-26 | Fixing memory size regression introduced by non-disclosed colleague ;-) [Viktor Klang]
| * | b2f84ad 2011-10-26 | Rename new cluster docs from 'new' to 'cluster' [Peter Vlugter]
| * | 709f6d5 2011-10-26 | Some more updates to the new cluster documentation [Peter Vlugter]
| * |   70f2bec 2011-10-26 | Merge pull request #99 from amir343/master [Jonas Bonér]
| |\ \  
| | * | 037dcfa 2011-10-26 | Conversion of class names into literal blocks [Amir Moulavi]
| | * | b5a4018 2011-10-26 | Formatting of TransactionFactory settings is changed to be compatible with Configuration section [Amir Moulavi]
| * | | 3b62873 2011-10-26 | fix CallingThreadDispatcher’s assumption of mailbox type [Roland]
* | | | b9bf133 2011-10-27 | Removed all old failure detectors. [Jonas Bonér]
* | | | cf404b0 2011-10-26 | Cleaned up new cluster specification. [Jonas Bonér]
* | | | b288828 2011-10-26 | Turned pendingChanges in Gossip into an Option[Vector]. [Jonas Bonér]
* | | | 6ed5bff 2011-10-26 | Improved ScalaDoc. [Jonas Bonér]
|/ / /  
* | | a254521 2011-10-26 | Added 'Intro' section to new cluster specification/docs. Also minor other edits. [Jonas Bonér]
|/ /  
* |   ba365f8 2011-10-26 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| * | a8c7bd5 2011-10-26 | Defer a latch count down in transactor spec [Peter Vlugter]
* | | 12554cd 2011-10-26 | Added some sections to new clustering specification and also did various reformatting, restructuring and improvements. [Jonas Bonér]
|/ /  
* | a857078 2011-10-26 | Renamed RemoteDaemon.scala to Remote.scala. [Jonas Bonér]
* | 80282d1 2011-10-26 | Initial version of gossip based cluster membership. [Jonas Bonér]
* |   2582797 2011-10-25 | Merge pull request #98 from amir343/master [Jonas Bonér]
|\ \  
| * | ef0491f 2011-10-25 | Class names and types in the text are converted into literal blocks [Amir Moulavi]
| * | 314c9fc 2011-10-25 | broken bullet list is corrected [Amir Moulavi]
| * | dd1d712 2011-10-25 | broken bullet list is corrected [Amir Moulavi]
* | | 80250cd 2011-10-25 | Some docs for new clustering [Peter Vlugter]
* | | 173ef04 2011-10-25 | add dispatcher.shutdown() at app stop and make core pool size smaller to let the tests run [Roland]
* | | 6bcdba4 2011-10-25 | fix InterruptedException handling in CallingThreadDispatcher [Roland]
* | |   c059d1b 2011-10-25 | Merge branch 'parental-supervision' [Roland]
|\ \ \  
| * | | b39bef6 2011-10-21 | Fix bug in DeathWatchSpec (I had forgotten to wrap a Failed) [Roland]
| * | | 92321cd 2011-10-21 | relax over-eager time constraint in FSMTimingSpec [Roland]
| * | | fc8ab7d 2011-10-21 | fix CallingThreadDispatcher and re-enable its test [Roland]
| * | | bb94275 2011-10-21 | make most AkkaSpec-based tests runnable in Eclipse [Roland]
| * | |   d55f02e 2011-10-21 | merge master into parental-supervision, fixing up resulting breakage [Roland]
| |\ \ \  
| * | | | 3b698b9 2011-10-20 | nearly done, only two known test failures [Roland]
| * | | | 172ab31 2011-10-20 | improve some, but tests are STILL FAILING [Roland]
| * | | | d3837b9 2011-10-18 | Introduce parental supervision, BUT TESTS ARE STILL FAILING [Roland]
| * | | | 25e8eb1 2011-10-15 | teach new tricks to old FaultHandlingStrategy [Roland]
| * | | | 10c87d5 2011-10-13 | split out fault handling stuff from ActorCell.scala to FaultHandling.scala [Roland]
* | | | | c54e7b2 2011-10-16 | add pimp for Future.pipeTo(Channel), closes #1235 [Roland]
* | | | | 3e3f532 2011-10-16 | document anonymous actors and their perils, fixes #1242 [Roland]
* | | | | 676a712 2011-10-16 | remove all use of Class.getSimpleName; fixes #1288 [Roland]
* | | | | 076ec4d 2011-10-16 | add missing .start() to testing.rst, fixes #1266 [Roland]
| |_|/ /  
|/| | |   
* | | |   33bcb38 2011-10-24 | Merge pull request #96 from amir343/master [viktorklang]
|\ \ \ \  
| * | | | 4638f83 2011-10-21 | A typo is corrected in Future example Scala code [Amir Moulavi]
* | | | | f762575 2011-10-23 | #1109 - Fixing some formatting and finding that Jonas has already fixed this [Viktor Klang]
* | | | | e1a3c9d 2011-10-23 | #1059 - Removing ListenerManagement from RemoteSupport, publishing to AkkaApplication.eventHandler [Viktor Klang]
* | | | | bb0b845 2011-10-21 | Preparing to remove channels and ActorPromise etc [Viktor Klang]
|/ / / /  
* | | | 9dd0385 2011-10-21 | Moving postMessageToMailbox* to ScalaActorRef for some additional shielding. [Viktor Klang]
* | | | 52595f3 2011-10-21 | Fix the scaladoc generation again so that nightlies work [Peter Vlugter]
| |/ /  
|/| |   
* | | 550ed58 2011-10-21 | Included akka-sbt-plugin in build, since I need timestamped version to be published (sbt-plugin-m0) [Patrik Nordwall]
| | * f21ed09 2011-10-20 | Made improvements to IndexSpec after comments from Viktor. [Kjell Winblad]
| | *   6a137f5 2011-10-20 | Merge branch 'master' of https://github.com/jboner/akka [Kjell Winblad]
| | |\  
| |_|/  
|/| |   
* | | 10fc175 2011-10-20 | Removed reference to non-committed code [Jonas Bonér]
* | |   303d346 2011-10-20 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
* | | | c8215df 2011-10-19 | Added Gossip messages and management to remote protocol. Various refactorings and improvements of remoting layer. [Jonas Bonér]
* | | | 2fcafb2 2011-10-19 | Changed API in VectorClock and added references in scaladoc. [Jonas Bonér]
| | | *   adec2bb 2011-10-20 | Merge branch 'master' of https://github.com/jboner/akka [Kjell Winblad]
| | | |\  
| | |_|/  
| |/| |   
| * | | e0a7b88 2011-10-20 | Adding Actor.watch and Actor.unwatch - verrrry niiiice [Viktor Klang]
| * | | 25f436d 2011-10-20 | add TestKit.fishForMessage [Roland]
| * | | 6a2f203 2011-10-20 | Rewriting DeathWatchSpec and FSMTransitionSpec to do the startsMonitoring inside the Actor [Viktor Klang]
| * | | 4fc1080 2011-10-19 | I've stopped hating Jenkins, fixed the pesky elusing DeathWatch bug [Viktor Klang]
| * | | bf4af15 2011-10-19 | Making the DeadLetterActorRef push notifications to the EventHandler [Viktor Klang]
| * | | 57e9943 2011-10-19 | Making sender always return an ActorRef, which will be the DeadLetterActor if there is no real sender [Viktor Klang]
| * | | adccc9b 2011-10-19 | Adding possibility to specify Actor.address to TypedActor [Viktor Klang]
| * | | 70bacc4 2011-10-19 | Fixing yet another potential race in the DeathWatchSpec [Viktor Klang]
| * | | 83e17aa 2011-10-19 | Removing the 'def config', removing the null check for every message being processed and adding some TODOs [Viktor Klang]
| * | | f68c170 2011-10-19 | Removing senderFuture, in preparation for 'sender ! response' [Viktor Klang]
| * | | 77dc9e9 2011-10-19 | #1299 - Removing reply and tryReply, preparing the way for 'sender ! response' [Viktor Klang]
| * | | 2d4251f 2011-10-19 | Fixing a race in DeathWatchSpec [Viktor Klang]
| * | | 0dc3c5a 2011-10-19 | Removing receiver from Envelope and switch to use the Mailbox.actor instead, this should speed up the BalancingDispatcher by some since it doesn't entail any allocations in adopting a message [Viktor Klang]
| * | | bde3969 2011-10-19 | #1297 - Fixing two tests that have been failing on Jenkins but working everywhere else [Viktor Klang]
| * | |   51ac8b1 2011-10-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | |/ /  
| | * | 5c823ad 2011-10-18 | replace ConcurrentLinkedQueue with single-linked list for Mailbox.systemQueue [Roland]
| * | | 7d87994 2011-10-19 | #1210 - fixing typo [Viktor Klang]
| |/ /  
| * | 01efcd7 2011-10-18 | Removing ActorCell.ref (use ActorCell.self instead), introducing Props.randomAddress which will use the toString of the uuid of the actor ref as address, bypassing deployer for actors with 'randomAddress' since it isn't possible to know what the address will be anyway, removing Address.validate since it serves no useful purpose, removing guard.withGuard in MessageDispatcher in favor of the less costly lock try-finally unlock strategy [Viktor Klang]
| * | 474787a 2011-10-18 | Renaming createActor to actorOf [Viktor Klang]
| * |   3f258f8 2011-10-18 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * \   df29fac 2011-10-18 | merge in Viktor’s dispatcher uuid map removal [Roland]
| | |\ \  
| | * | | 183dfb4 2011-10-18 | remove SystemEnvelope [Roland]
| * | | | 6150beb 2011-10-18 | Pushing the memory per actor down to 464 bytes. Returning None for the Deploy if there is no config [Viktor Klang]
| | |/ /  
| |/| |   
| * | | 304d39d 2011-10-18 | Removing uuid tracking in MessageDispatcher, isn't needed and will be reducing the overall memory footprint per actor [Viktor Klang]
| |/ /  
| * | 65868d7 2011-10-18 | Making sure that the RemoteActorRefProvider delegates systemServices down to the LocalActorRefProvider [Viktor Klang]
| * | 1c3b9a3 2011-10-18 | Adding clarification to DeathWatchSpec as well as making sure that systemServices aren't passed into the deployer [Viktor Klang]
| * | 7a65089 2011-10-18 | Adding extra output to give more hope in reproducing weird test failure that only happens in Jenkins [Viktor Klang]
| * | cb8a0ad 2011-10-18 | Switching to a cached version of Stack.empty, saving 16 bytes per Actor. Switching to purging the Promises in the ActorRefProvider after successful creation to conserve memory. Stopping to clone the props everytime to set the application default dispatcher, and doing a conditional in ActorCell instead. [Viktor Klang]
| * | 4e960e5 2011-10-17 | Changing so that the mailbox status is ack:ed after the _whole_ processing of the current batch, which means that Akka only does 1 volatile write per batch (of course the backing mailboxes might do their own volatile writes) [Viktor Klang]
| * | fa1a261 2011-10-17 | Removing RemoteActorSystemMessage.Stop in favor of the sexier Terminate message [Viktor Klang]
| * | 3795157 2011-10-17 | Tidying up some superflous lines of code in Scheduler [Viktor Klang]
| * |   bd39ab0 2011-10-17 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * | f75d16f 2011-10-17 | fix small naming errors in supervision.rst [Roland]
| | * | ab4f62c 2011-10-17 | add first draft of supervision spec [Roland]
| * | | 050411b 2011-10-17 | Making a Java API for Scheduler (JScheduler) and an abstract class Scheduler that extends it, to make the Scheduler pluggable, moving it into AkkaApplication and migrating the code. [Viktor Klang]
| |/ /  
| * | 2270395 2011-10-17 | Adding try-finally in the system message processing to ensure that the cleanup is performed accurately [Viktor Klang]
| * | 3dc84a0 2011-10-17 | Renaming InVMMonitoring to LocalDeathWatch and moved it into AkkaApplication, also created a createDeathWatch method in ActorRefProvider so that it's seeded from there, and then removed @volatile from alot of vars in ActorCell since the fields are now protected by the Mailbox status field [Viktor Klang]
|/ /  
* | 3a543ed 2011-10-15 | Relized that setAsIdle doesn't need to call acknowledgeStatus since it's already called within the systemInvoke and invoke [Viktor Klang]
* | 36cd652 2011-10-14 | Removing Gossiper. Was added prematurely by mistake. [Jonas Bonér]
* |   44e460b 2011-10-14 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| * | 5788ed4 2011-10-14 | Renaming link/unlink to startsMonitoring/stopsMonitoring [Viktor Klang]
| * | 07a7b27 2011-10-14 | Cleaning up a section of the ActorPool [Viktor Klang]
| * |   d4619b0 2011-10-13 | Merge master into tame-globals branch [Peter Vlugter]
| |\ \  
| | * | c5ed2a8 2011-10-13 | Making so that if the given address to a LocalActorRef is null or the empty string, it should use the toString of the uuid [Viktor Klang]
| | * | 54b70b1 2011-10-13 | Removing pointless AtomicReference since register/unregister is already lock protected [Viktor Klang]
| * | | d9e0088 2011-10-13 | Get remoting working under the remote actor ref provider [Peter Vlugter]
| * | | e94860b 2011-10-13 | fix remaining issues apart from multi-jvm [Roland]
| * | | 9e80914 2011-10-13 | rename application to app everywhere to make it consistent [Roland]
| * | |   44b9464 2011-10-13 | Merge with Peter's work (i.e. merging master into tame-globals) [Roland]
| |\ \ \  
| | * \ \   317b8bc 2011-10-13 | Merge master into tame-globals branch [Peter Vlugter]
| | |\ \ \  
| | | |/ /  
| * | | | 3709a8f 2011-10-13 | fix akka-docs compilation, remove duplicate applications from STM tests [Roland]
| * | | | 85b7acc 2011-10-13 | make EventHandler non-global [Roland]
| |/ / /  
| * | | e25ee9f 2011-10-12 | Fix remote main compile and testkit tests [Peter Vlugter]
| * | | e2f9528 2011-10-12 | fix compilation of akka-docs [Roland]
| * | | fa94198 2011-10-12 | Fix remaining tests in akka-actor-tests [Peter Vlugter]
| * | | f7c1123 2011-10-12 | pre-fix but disable LoggingReceiveSpec: depends on future EventHandler rework [Roland]
| * | | e5d24b0 2011-10-12 | remove superfluous AkkaApplication.akkaConfig() method (can use AkkaConfig() instead) [Roland]
| * | | 9444ed8 2011-10-12 | introduce nicer AkkaSpec constructor and fix FSMActorSpec [Roland]
| * | | 36ec202 2011-10-12 | rename AkkaConfig values to CamelCase [Roland]
| * | | 42e1bba 2011-10-12 | Fix actor ref spec [Peter Vlugter]
| * | | 14751f7 2011-10-12 | make everything except tutorial-second compile [Roland]
| * | | 93b1ef3 2011-10-11 | make akka-actor-tests compile again [Roland]
| * | | 1e1409e 2011-10-10 | Start on getting the actor tests compiling and running again [Peter Vlugter]
| * | | 2599de0 2011-10-10 | Scalariform & add AkkaSpec [Roland]
| * | |   67a9a01 2011-10-07 | Merge master into tame-globals branch [Peter Vlugter]
| |\ \ \  
| * | | | 3aadcd7 2011-10-07 | Update stm module to work with AkkaApplication [Peter Vlugter]
| * | | | 2381ec5 2011-10-06 | introduce AkkaApplication [Roland]
| * | | | ccb429d 2011-10-06 | add Eclipse .cache directories to .gitignore [Roland]
* | | | | 88edec3 2011-10-14 | Vector clock implementation, including tests. To be used in Gossip protocol. [Jonas Bonér]
* | | | |   faa5b08 2011-10-13 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| | |_|/ /  
| |/| | |   
| * | | | 963ea0d 2011-10-12 | Added sampling of latency measurement [Patrik Nordwall]
| * | | | 045b9d9 2011-10-12 | Added .cached to .gitignore [Patrik Nordwall]
* | | | | 19cc618 2011-10-13 | Simplified (and improved speed) of PHI calculation in AccrualFailureDetector, according to discussion at https://issues.apache.org/jira/browse/CASSANDRA-2597 . [Jonas Bonér]
| | | | * 86546d4 2011-10-13 | Fixed spelling error [Kjell Winblad]
| | | | * c877b69 2011-10-13 | Added test for parallel access of Index [Kjell Winblad]
| | | | * bff7d10 2011-10-12 | Added test for akka.util.Index (ticket #1282) [Kjell Winblad]
| | |_|/  
| |/| |   
| * | | 3567d55 2011-10-12 | Adding documentation for the ExecutorService and ThreadPoolConfig DSLs [Viktor Klang]
| * | | c950679 2011-10-12 | #1285 - Implementing different internal states for the DefaultPromise [Viktor Klang]
| * | | fe3c22f 2011-10-12 | #1192 - Removing the 'guaranteed delivery'/message resend in NettyRemoteSupport [Viktor Klang]
| * | | 41029e2 2011-10-12 | Removing pointless Index in the Remoting [Viktor Klang]
| * | | 44e1562 2011-10-12 | Documenting the EventBus API and removing some superflous/premature traits [Viktor Klang]
| * | | aa1c636 2011-10-12 | Adding support for giving a Scala function to Index for comparison, and fixed a compilation error in NEttyRemoteSupport [Viktor Klang]
| * | |   dd9555f 2011-10-12 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * | | b4a1c95 2011-10-12 | Fix for right arrows in pdf docs [Peter Vlugter]
| |/ / /  
|/| | |   
* | | | 19b7bc0 2011-10-11 | Added an Accrual Failure Detector (plus tests). This is the best general purpose detector and will replace all others. [Jonas Bonér]
| * | | d34e3d6 2011-10-12 | Adding a Java API to EventBus and adding tests for the Java configurations [Viktor Klang]
| * | | 5318763 2011-10-12 | Enabling the possibility to specify mapSize and the comparator to use to compare values for Index [Viktor Klang]
|/ / /  
* | | d24e273 2011-10-11 | Adding another test to verify that multiple messages get published to the same subscriber [Viktor Klang]
* | | a07dd97 2011-10-11 | Switching to have the entire String event being the classification of the Event, just to enfore uniqueness [Viktor Klang]
* | | 7d7350c 2011-10-11 | Making sure that all subscribers are generated uniquely so that if they don't, the test fails [Viktor Klang]
* | | 0a88765 2011-10-11 | Adding even more tests to the EventBus fixture [Viktor Klang]
* | | 54338b5 2011-10-11 | Adding EventBus API and changing the signature of DeathWatch to use the new EventBus API, adding some rudimentary test fixtures to EventBus [Viktor Klang]
* | |   a6caa4c 2011-10-11 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| * | | e20866c 2011-10-11 | Moved method for creating a RoutedActorRef from 'Routing.actorOf' to 'Actor.actorOf' [Jonas Bonér]
| * | |   84f4840 2011-10-11 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| * | | | d31057d 2011-10-11 | Added support for custom user-defined routers [Jonas Bonér]
* | | | | c80690a 2011-10-11 | Changing DeathWatchSpec to hopefully work better on Jenkins [Viktor Klang]
| |/ / /  
|/| | |   
* | | |   878be07 2011-10-10 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \  
| |/ / /  
| * | | e779690 2011-10-10 | Cleaned up internal API [Jonas Bonér]
| * | |   5fc905c 2011-10-10 | Merge branch 'ditch-registry' [Jonas Bonér]
| |\ \ \  
| | * | | 3e6decf 2011-10-07 | Removed the ActorRegistry, the different ActorRefProvider implementations now holds an Address->ActorRef registry. Looking up by UUID is gone together with all the other lookup methods such as 'foreach' etc. which do not make sense in a distributed env. 'shutdownAll' is also removed but will be replaced by parental supervision. [Jonas Bonér]
* | | | | 0b86e96 2011-10-10 | Increasing the timeouts in the RestartStrategySpec [Viktor Klang]
|/ / / /  
* | | | 6eaf04a 2011-10-10 | 'fixing' the DeathWatchSpec, since the build machine is slow [Viktor Klang]
* | | |   101ebbd 2011-10-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \  
| |/ / /  
| * | |   114abe1 2011-10-07 | Merge branch 'failure-detector-refactoring' [Jonas Bonér]
| |\ \ \  
| | |_|/  
| |/| |   
| | * | 4ec050c 2011-10-07 | Major refactoring of RemoteActorRefProvider, remote Routing and FailureDetector, including lots of fixes and improvements. [Jonas Bonér]
* | | | 149205c 2011-10-07 | #1239 - fixing Crypt.hexify entropy [Viktor Klang]
|/ / /  
* | | 9c88873 2011-10-07 | Fixing the short timeout on PromiseStreamSpec and ignoring the faulty LoggingReceiveSpec (which will be fixed by AkkaApplication [Viktor Klang]
* | | 9a300f8 2011-10-07 | Removing a synchronization in UUIDGen.java and replace it with a CCAS [Viktor Klang]
* | | 4490f0e 2011-10-07 | Fixing a bug in supervise, contains <--- HERE BE DRAGONS, switched to exists [Viktor Klang]
* | | d94ef52 2011-10-07 | Removing a TODO that was fixed [Viktor Klang]
* | | cfe8a32 2011-10-07 | Renaming linkedActors to children, and getLinkedActors to getChildren [Viktor Klang]
* | | 4313a28 2011-10-07 | Adding final declarations on a number of case-classes in Mailbox.scala, and also made constants of methods that were called frequently in the hot path [Viktor Klang]
* | | 913ef5d 2011-10-07 | Implementing support for custom eviction actions in ActorPool as well as providing default Props for workers [Viktor Klang]
* | | 972f4b5 2011-10-06 | Fix publishing and update organization (groupId) [Peter Vlugter]
|/ /  
* | 78193d7 2011-10-06 | Added multi-jvm tests for remote actor configured with Random router. [Jonas Bonér]
* |   e4b66da 2011-10-05 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| * | 6bb721d 2011-10-05 | Remove the sun.tools.tree.FinallyStatement import [Peter Vlugter]
| * | 7884691 2011-10-05 | Begin using compiled code examples in the docs. See #781 [Peter Vlugter]
| |/  
| * 56f8f85 2011-10-04 | Adding AbstractPromise to create an AtomicReferenceFieldUpdater to get rid of the AtomicReference allocation [Viktor Klang]
| *   c4508a3 2011-10-04 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | * 2252c38 2011-10-04 | switch on force inlining of Mailbox one-liners [Roland]
| | * 4b5c99e 2011-10-04 | optimize Mailbox._status usage [Roland]
| * | fec129e 2011-10-04 | Preventing wasteful computation in BalancingDispatcher if buddy and receiver is the same guy [Viktor Klang]
| * | 815f710 2011-10-04 | Removing PriorityDispatcher since you can now use Dispatcher with UnboundedPriorityMailbox or BoundedProprityMailbox [Viktor Klang]
| |/  
| * 4df9d62 2011-10-04 | Fixing DispatcherActorSpec timeout [Viktor Klang]
| * d000a51 2011-10-04 | Removing the AtomicInteger in Mailbox and implementing it as a volatile int + AtomicIntegerFieldUpdater in AbstractMailbox.java [Viktor Klang]
| * 6b8ed86 2011-10-04 | Tidying up some of the CAS:es in the mailbox status management [Viktor Klang]
| *   df94449 2011-10-04 | Merge remote branch 'origin/remove-dispatcherLock' [Viktor Klang]
| |\  
| | * ece571a 2011-09-28 | rename {Message,Mailbox}Handling.scala [Roland]
| | * ca22e04 2011-09-28 | fold Mailbox.dispatcherLock into _status [Roland]
| * |   a718d8a 2011-10-04 | Merge branch 'deathwatch' [Viktor Klang]
| |\ \  
| | * | 393e997 2011-10-04 | Renaming InVMMonitoring [Viktor Klang]
| | * | 785e2a2 2011-10-04 | Moving the cause into Recreate [Viktor Klang]
| | * | 5321d02 2011-10-03 | Removing actorClass from ActorRef signature, makes no sense from the perspective of distribution, and with async start it is racy [Viktor Klang]
| | * | 284a8c4 2011-10-03 | Removing getSupervisor and supervisor from ActorRef, doesn't make sense in a distributed setting [Viktor Klang]
| | * | 0f049d6 2011-10-03 | Removing ActorRef.isRunning - replaced in full by isShutdown, if it returns true the actor is forever dead, if it returns false, it might be (race) [Viktor Klang]
| | * | a1593c0 2011-10-03 | Fixing a logic error in the default DeathWatch [Viktor Klang]
| | * | dcf4d35 2011-10-03 | Fixing the bookkeeping of monitors etc so that it's threadsafe [Viktor Klang]
| | * | 2e20fb5 2011-10-03 | All tests pass! Supervision is in place and Monitoring (naïve impl) works as well [Viktor Klang]
| | * | 69768db 2011-09-30 | Removing the old Supervision-DSL and replacing it with a temporary one [Viktor Klang]
| | * | d9cc9e3 2011-09-30 | Temporarily fixing RestartStrategySpec [Viktor Klang]
| | * | 7ae81b4 2011-09-30 | Fixing Ticket669Spec to use the new explicit Supervisor [Viktor Klang]
| | * | 897676b 2011-09-30 | Removing defaultDeployId from Props [Viktor Klang]
| | * | c34b74e 2011-09-29 | Adding a todo for AtomicReferenceFieldUpdater in Future.scala [Viktor Klang]
| | * | bea0232 2011-09-29 | Fixing the SchedulerSpec by introducing an explicit supervisor [Viktor Klang]
| | * | 4c06b55 2011-09-29 | Restructuring the ordering of recreating actor instances so it's more safe if the constructor fails on recreate [Viktor Klang]
| | * | 950b118 2011-09-29 | Improving the ActorPool code and fixing the tests for the new supervision [Viktor Klang]
| | * |   a12ee36 2011-09-29 | Merge commit [Viktor Klang]
| | |\ \  
| | * | | d94f6de 2011-09-29 | Adding more tests to ActorLifeCycleSpec and the DeatchWatchSpec [Viktor Klang]
| | * | | 8a876cc 2011-09-29 | Switched the signature of Props(self => Receive) to Props(context => Receive) [Viktor Klang]
| | * | | 03a5042 2011-09-28 | Switching to filterException [Viktor Klang]
| | * | | fed0cf7 2011-09-28 | Replacing ActorRestartSpec with ActorLifeCycleSpec [Viktor Klang]
| | * | | a6f53d8 2011-09-28 | Major rework of supervision and death watch, still not fully functioning [Viktor Klang]
| | * | | 24d9a4d 2011-09-27 | Merging with master [Viktor Klang]
| * | | | fc64d8e 2011-09-30 | Placed the plugin in package akka.sbt (sbt-plugin) [Patrik Nordwall]
| | |/ /  
| |/| |   
* | | | 182aff1 2011-10-05 | Added multi-jvm test for remote actor configured with RoundRobin router. [Jonas Bonér]
* | | | 9e580b0 2011-10-05 | Added multi-jvm test for remote actor configured with Direct router. [Jonas Bonér]
* | | | d124f6e 2011-10-05 | Added configuration based routing (direct, random and round-robin) to the remote actors created by the RemoteActorRefProvider, also changed the configuration to allow specifying multiple remote nodes for a remotely configured actor. [Jonas Bonér]
|/ / /  
* | | 0957e41 2011-09-28 | Renamed 'replication-factor' config element to 'nr-of-instances' and 'ReplicationFactor' case class to 'NrOfInstances'. [Jonas Bonér]
* | | 16e4be6 2011-09-28 | Now treating actor deployed and configured with Direct routing and LocalScope as a "normal" in-process actor (LocalActorRef). [Jonas Bonér]
* | | 20f1c80 2011-09-28 | Added misc tests for local configured routers: direct, round-robin and random. [Jonas Bonér]
* | | 08c1e91 2011-09-28 | Fixed broken 'stop' method on RoutedActorRef, now shuts down its connections by sending out a 'Broadcast(PoisonPill)'. [Jonas Bonér]
* | |   5c49e6d 2011-09-28 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| | |/  
| |/|   
| * | 2b4868f 2011-09-28 | log warning upon unhandled message in FSM, fixes #1233 [Roland]
| * | fd78af4 2011-09-28 | add () after side-effecting TestKit methods, fixes #1234 [Roland]
* | | 9721dbc 2011-09-28 | Added configuration based routing for local ActorRefProvider, also moved replication-factor from 'cluster' section to generic section of config' [Jonas Bonér]
|/ /  
* | 8297f45 2011-09-27 | Some clean up of the compile and test output [Peter Vlugter]
|/  
* db8a20e 2011-09-27 | Changed all 'def foo(): Unit = { .. }' to 'def foo() { .. }' [Jonas Bonér]
* 07b29c0 2011-09-27 | Added error handling to the actor creation in the different providers [Jonas Bonér]
*   07012b3 2011-09-27 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\  
| * 7cf8eb0 2011-09-27 | Fix IO actor spec by ensuring startup order [Peter Vlugter]
* | cbdf39d 2011-09-27 | Fixed issues with LocalActorRefProviderSpec [Jonas Bonér]
* |   11cc9d9 2011-09-27 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| |/  
| *   dc5cc16 2011-09-27 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | * f60d0bd 2011-09-27 | Fix scaladoc generation manually again [Peter Vlugter]
| * | 670eb02 2011-09-27 | Making sure that the receiver doesn't become the buddy that gets tried to register [Viktor Klang]
| |/  
* | b1554b7 2011-09-27 | Disabled akka-camel until issues with it have been resolved. [Jonas Bonér]
* |   315570c 2011-09-27 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| |/  
| * a7d73c3 2011-09-27 | Implementing buddy failover [Viktor Klang]
| *   a7cb5b5 2011-09-27 | Upgrading to SBT 0.11 [Viktor Klang]
| |\  
| * | 18e3ed5 2011-09-27 | Adding comment about thread-safety [Viktor Klang]
| * | 6a4d9e2 2011-09-26 | Fixing a typo in the event handler dispatcher configuration name and also deprecating some dangerous methods and adding TODOs and FIXMEs [Viktor Klang]
| * | e7bc084 2011-09-26 | Fixing pesky race condition in Dispatcher [Viktor Klang]
| * | 63053ab 2011-09-26 | Making the event-handlers dispatcher configurable in config, also fixing a nasty shutdown in it [Viktor Klang]
| * | 2edd9d9 2011-09-26 | Removing shutdownAllAttachedActors from MessageDispatcher and moving starting of the dispatcher close to the registration for execution [Viktor Klang]
| * |   d46d768 2011-09-26 | Merge branch 'async-system-messages' of github.com:jboner/akka into async-system-messages [Viktor Klang]
| |\ \  
| | * | c84d33e 2011-09-26 | Fix ticket 1111 spec by creating compatible exception-throwing behaviour for now. [Peter Vlugter]
| * | | d8d639e 2011-09-26 | Enforing an acknowledgement of the mailbox status after each message has been processed [Viktor Klang]
| |/ /  
| * | c2a8e8d 2011-09-26 | Fixing broken camel test [Viktor Klang]
| * | d40221e 2011-09-26 | Adding a more friendly error message to Future.as [Viktor Klang]
| * | 7f8f0e8 2011-09-26 | Switching so that createMailbox is called in the ActorCell constructor [Viktor Klang]
| * | 648c869 2011-09-26 | Fix restart strategy spec by resuming on restart [Peter Vlugter]
| * | e4947de 2011-09-26 | Making sure that you cannot go from Mailbox.Closed to anything else [Viktor Klang]
| * | 29a327a 2011-09-26 | Changing Mailbox.Status to be an Int [Viktor Klang]
| * | 1b90a46 2011-09-26 | Removing freshInstance and reverting some of the new BalancingDispatcher code to expose the race condition better [Viktor Klang]
| * | 288287e 2011-09-23 | Partial fix for the raciness of BalancingDispatcher [Viktor Klang]
| * | a38a26f 2011-09-23 | Changing so that it executes system messages first, then normal message then a subsequent pass of all system messages before being done [Viktor Klang]
| * | 1edd52c 2011-09-23 | Rewriting so that the termination flag is on the mailbox instead of the ActorCell [Viktor Klang]
| * |   f30bc27 2011-09-23 | Merge branch 'async-system-messages' of github.com:jboner/akka into async-system-messages [Viktor Klang]
| |\ \  
| | * | 4c87d70 2011-09-23 | reorder detach vs. terminated=true [Roland]
| * | | 1662d25 2011-09-23 | Rewriting the Balancing dispatcher [Viktor Klang]
| |/ /  
| * | e4e8ddc 2011-09-23 | fix more bugs, clean up tests [Roland]
| * | 6e0e991 2011-09-22 | Fixing the camel tests for real this time by introducing separate registered/unregistered events for actors and typed actors [Viktor Klang]
| * | 6109a17 2011-09-22 | fix ActorModelSpec [Roland]
| * | a935998 2011-09-22 | Fixing Camel tests [Viktor Klang]
| * | d764229 2011-09-22 | Fixing SupervisorHierarchySpec [Viktor Klang]
| * | af0d223 2011-09-22 | Fixing the SupervisorHierachySpec [Viktor Klang]
| * | 4eb948a 2011-09-21 | Fixing the mailboxes and asserts in the ActorModelSpec [Viktor Klang]
| * | d827b52 2011-09-21 | Fix FSMActorSpec lazy val trick by waiting for start [Peter Vlugter]
| * | 5795395 2011-09-21 | Fix some tests: ActorRefSpec, ActorRegistrySpec, TestActorRefSpec. [Peter Vlugter]
| * | 049d653 2011-09-21 | Fixing ReceiveTimeout [Viktor Klang]
| * | 3d12e47 2011-09-21 | Decoupling system message implementation details from the Mailbox [Viktor Klang]
| * | 7c63f94 2011-09-21 | Refactor Mailbox handling [Roland]
| * | d6eb768 2011-09-21 | Implementing spinlocking for underlyingActorInstance [Viktor Klang]
| * | fbf9700 2011-09-21 | Renaming Death to Failure and separating Failure from the user-lever lifecycle monitoring message Terminated [Viktor Klang]
| * | 03706ba 2011-09-21 | Fixing actorref serialization test [Viktor Klang]
| * | 95b42d8 2011-09-21 | fix some of the immediate issues, especially those hindering debugging [Roland]
| * | 9007b6e 2011-09-20 | Almost there... ActorRefSpec still has a failing test [Viktor Klang]
* | | 3472fc4 2011-09-27 | Added eviction of actor instance and its Future in the ActorRefProvider to make the registry work again. Re-enabled the ActorRegistrySpec. [Jonas Bonér]
* | |   a19e105 2011-09-27 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| | |/  
| |/|   
| * | 002afda 2011-09-26 | Add file-based barrier for multi-jvm tests [Peter Vlugter]
| * | 7941733 2011-09-26 | Update to scala 2.9.1 [Peter Vlugter]
| * | 9080921 2011-09-26 | Update to sbt 0.11.0 [Peter Vlugter]
| * | 5550376 2011-09-24 | Update to ivy-published plugins [Peter Vlugter]
| * | 1e7f598 2011-09-23 | Update to sbt 0.11.0-RC1 [Peter Vlugter]
* | | ce79744 2011-09-27 | Fixed race condition in actor creation in *ActorRefProvider [Jonas Bonér]
|/ /  
* |   00d3b87 2011-09-22 | Merge branch 'remote-actorref-provider' [Jonas Bonér]
|\ \  
| * | 1bfe371 2011-09-22 | Added first test of remote actor provisioning using RemoteActorRefProvider. [Jonas Bonér]
| * | af49b99 2011-09-22 | Completed RemoteActorRefProvider and parsing/management of 'remote' section akka.conf, now does provisioning and local instantiation of remote actor on its home node. Also changed command line option 'akka.cluster.port' to 'akka.remote.port'. [Jonas Bonér]
| * | db51115 2011-09-21 | Added doc about message ordering guarantees. [Jonas Bonér]
| * | 978cbe4 2011-09-20 | Change the package name of all classes in remote module to 'akka.remote'. [Jonas Bonér]
| * | 7bc698f 2011-09-19 | Moved remote-centric config sections out of 'cluster' to 'remote'. Minor renames and refactorings. [Jonas Bonér]
| * | 298b67f 2011-09-19 | RemoteActorRefProvider now instantiates actor on remote host before creating RemoteActorRef and binds it to it. [Jonas Bonér]
| * | 1e37b62 2011-09-19 | Added deployment.remote config for deploying remote services and added BannagePeriodFailureDetectorFailureDetector config. [Jonas Bonér]
| * | e70ab6d 2011-09-19 | Added lockless FailureDetector.putIfAbsent(connection). [Jonas Bonér]
| * | cd4a3c3 2011-09-15 | Added RemoteActorRefProvider which deploys and instantiates actor on a remote host. [Jonas Bonér]
| * |   990d492 2011-09-14 | Merge branch 'master' into remote-actorref-provider [Jonas Bonér]
| |\ \  
| * | | 349dbb1 2011-09-14 | Changed 'deployment.clustered' to 'deployment.cluster' [Jonas Bonér]
* | | |   099846a 2011-09-20 | Merge pull request #90 from havocp/havocp-actor-pool [Jonas Bonér]
|\ \ \ \  
| |_|_|/  
|/| | |   
| * | | d7185fd 2011-09-18 | add more API docs to routing/Pool.scala [Havoc Pennington]
* | | | 48deb31 2011-09-20 | Rename ActorInstance to ActorCell [Peter Vlugter]
* | | |   f9e23c3 2011-09-19 | Resolve merge conflict with master [Viktor Klang]
|\ \ \ \  
| * | | | 5a9b6b1 2011-09-19 | Fix for message dispatcher attach (and async init) [Peter Vlugter]
| * | | | 10ce9d7 2011-09-19 | Use non-automatically settings in akka kernel plugin [Peter Vlugter]
| * | | | 7b1cdb4 2011-09-19 | Remove SelfActorRef and use ActorContext to access state in ActorInstance. See #1202 [Peter Vlugter]
| |/ / /  
* | | | b66d45e 2011-09-19 | Removing deployId from config, should be replaced with patterns in deployment configuration that is checked towards the address [Viktor Klang]
* | | | f993219 2011-09-19 | Removing compression from the remote pipeline since it has caused nothing but headaches and less-than-desired performance [Viktor Klang]
|/ / /  
* | |   d926b05 2011-09-18 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| * | | c0bc83b 2011-09-16 | improve documentation of Future.onComplete wrt. ordering [Roland]
* | | | 4ee9efc 2011-09-17 | unborkin master [Viktor Klang]
|/ / /  
* | | 9b21dd0 2011-09-16 | Adding 'asynchronous' init of actors (done blocking right now to maintain backwards compat) [Viktor Klang]
* | |   56cbf6d 2011-09-16 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| * | | c1d9b49 2011-09-16 | improve failure messages in case of expectMsg... timeout [Roland]
| * | | 27bb7b4 2011-09-15 | Add empty actor context (just wrapping self). See #1202 [Peter Vlugter]
* | | | a4aafed 2011-09-16 | Switching to full stack traces so you know wtf is wrong [Viktor Klang]
|/ / /  
* | | b96f3d9 2011-09-15 | Initial breakout of ActorInstance. See #1195 [Peter Vlugter]
* | |   b94d1ce 2011-09-15 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
|\ \ \  
| * | | df1d4d4 2011-09-14 | Adding a write-up of supervision features that needs to be tested [Viktor Klang]
| * | | 6e90d91 2011-09-14 | Removing debug printouts [Viktor Klang]
| * | |   acaacd9 2011-09-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | | |/  
| | |/|   
| | * | 85941c0 2011-09-14 | Changed 'deployment.clustered' to 'deployment.cluster' [Jonas Bonér]
| | |/  
| * | 4a0358a 2011-09-14 | Removing LifeCycle from Props, it's now a part of AllForOnePermanent, OneForOnePermanent etc [Viktor Klang]
| |/  
* | 43edfb0 2011-09-15 | Temporarily exclude test until race condition is analyzed and fixed [Martin Krasser]
|/  
* 7e2af6a 2011-09-14 | Renamed to AkkaKernelPlugin [Patrik Nordwall]
*   eb84ce7 2011-09-12 | Improved comments [Jonas Bonér]
|\  
| * 0c6d182 2011-09-12 | Hide uuid from user API. Fixes #1184 [Peter Vlugter]
* |   91581cd 2011-09-12 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| |/  
| * b91ab10 2011-09-12 | Use address rather than uuid for ActorRef equality [Peter Vlugter]
* | 9d7b8b8 2011-09-12 | Added ActorRefProvider trait, LocalActorRefProvider class and ActorRefProviders container/registry class. Refactored Actor.actorOf to use it. [Jonas Bonér]
|/  
*   a67d4fa 2011-09-09 | Trying to merge with master [Viktor Klang]
|\  
| * 358d2b1 2011-09-09 | Removed all files that were moved to akka-remote. [Jonas Bonér]
| * a4c74f1 2011-09-09 | Added akka.sublime-workspace to .gitignore. [Jonas Bonér]
| * 90525bd 2011-09-09 | Created NetworkEventStream Channel and Listener - an event bus for remote and cluster life-cycle events. [Jonas Bonér]
* | 1d80f93 2011-09-09 | Starting to work on DeathWatch [Viktor Klang]
* |   94da087 2011-09-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \  
| |/  
| * 38c2fe1 2011-09-09 | Moved remote-only stuff from akka-cluster to new module akka-remote. [Jonas Bonér]
* |   65e7548 2011-09-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \  
| |/  
| * 702d596 2011-09-09 | disabled akka-cluster for now, getting ready to re-add akka-remote [Jonas Bonér]
* | 51993f8 2011-09-09 | Commenting out -optimize, to reduce compile time [Viktor Klang]
* |   8a7eacb 2011-09-09 | Merge with master [Viktor Klang]
|\ \  
| |/  
| * abf3e6e 2011-09-09 | Added FIXME comments to BannagePeriodFailureDetector [Jonas Bonér]
| *   3ea6f70 2011-09-09 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\  
| | * 92ffd2a 2011-09-09 | Fix the scaladoc generation. See #1017 [Peter Vlugter]
| * | 3370d07 2011-09-09 | Added tests for the CircuitBreaker [Jonas Bonér]
| * | 5b4d48d 2011-09-09 | Added NOTE to CircuitBreaker about its status and non-general purpose [Jonas Bonér]
| |/  
| *   2dea305 2011-09-08 | Merge branch 'master' into wip-remote-connection-failover [Jonas Bonér]
| |\  
| * | d7ce594 2011-09-08 | Rewrote CircuitBreaker internal state management to use optimistic lock-less concurrency, and added possibility to register callbacks on state changes. [Jonas Bonér]
| * | 1663bf4 2011-09-08 | Rewrote and abstracted remote failure detection and added BannagePeriodFailureDetector. [Jonas Bonér]
| * | 47bfafe 2011-09-08 | Moved FailureDetector trait and utility companion object to its own file. [Jonas Bonér]
| * | 72e0c60 2011-09-08 | Reformatting. [Jonas Bonér]
| * | 603a062 2011-09-08 | Added old 'clustering.rst' to disabled documents. To be edited and included into the documentation. [Jonas Bonér]
| * | bf7ef72 2011-09-01 | Refactored and renamed API for FailureDetector. [Jonas Bonér]
* | |   6114df1 2011-09-08 | Merge branch 'master' into wip-nostart [Viktor Klang]
|\ \ \  
| | |/  
| |/|   
| * | 0430e28 2011-09-08 | Get the (non-multi-jvm) cluster tests working again [Peter Vlugter]
| * | d8390a6 2011-09-08 | #1180 - moving the Java API to Futures and Scala API to Future [Viktor Klang]
* | | bbb79d8 2011-09-08 | Start removed but cluster is broken [Viktor Klang]
|/ /  
* |   24fb967 2011-09-05 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \  
| * | 950f311 2011-09-03 | fix compilation warnings (failed @Inline, erased types) [Roland]
| * | 34fdac2 2011-09-02 | remove akka.util.Helpers.flatten [Roland]
| * | 75427e4 2011-09-02 | silence two remaining expected exceptions in akka-actor-tests [Roland]
* | | 93786a3 2011-09-05 | Removing preStart invocation for each restart [Viktor Klang]
|/ /  
* | b670917 2011-09-04 | Removing wasteful level-field from Event [Viktor Klang]
* |   06f28cb 2011-09-03 | Merge pull request #87 from jrudolph/patch-1 [Roland Kuhn]
|\ \  
| * | c8a2e65 2011-07-27 | documentation: fix bullet list [Johannes Rudolph]
* | | 396207f 2011-09-02 | fixes #976: move tests to directories matching their package declaration [Roland]
* | |   0626f53 2011-09-02 | Merge branch 'master' of github.com:jboner/akka [Roland]
|\ \ \  
| * \ \   e26077a 2011-09-01 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | | |/  
| | |/|   
| * | | 6a68a70 2011-08-31 | Removing localActorOf [Viktor Klang]
* | | | 7a834c1 2011-09-02 | improve wording of doc for Future.await(atMost), fixes #1158 [Roland]
| |/ /  
|/| |   
* | | 089dd26 2011-08-31 | Removed the ClusterProtocol.proto file [Jonas Bonér]
* | | 4fe4218 2011-08-31 | Merged the ClusterProtocol with the RemoteProtocol. [Jonas Bonér]
* | |   7c1c777 2011-08-31 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| |/ /  
| * | 2909113 2011-08-31 | Switching to geronimos 2.0 impl instead of glassfish since it's in the sbt default maven repo [Viktor Klang]
* | | 0a63350 2011-08-31 | Added configuration for failure detection; both via akka.conf and via Deploy(..). [Jonas Bonér]
|/ /  
* | b362211 2011-08-31 | Removed old duplicated RemoteProtocol.java. [Jonas Bonér]
* | 8a55fc9 2011-08-31 | Fixed typos in Cluster API. [Jonas Bonér]
* |   c8d738f 2011-08-30 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| * | 7bc5b2c 2011-08-30 | Fixing race-condition in ActorRegistry.actorFor(address) [Viktor Klang]
| * | e2856c0 2011-08-30 | Removing wasteful locking in BalancingDispatcher [Viktor Klang]
* | | e17a376 2011-08-30 | Refactored state management in routing fail over. [Jonas Bonér]
* | | eb2cf56 2011-08-30 | Fixed toString and hashCode in config element. Fixing DeploymentSpec. [Jonas Bonér]
* | | 796137c 2011-08-30 | Disabled ClusterActorRefCleanupMultiJvmSpec until fail over impl completed. [Jonas Bonér]
* | | 5230255 2011-08-30 | Disabled the replication tests until fixed. [Jonas Bonér]
* | | 0881139 2011-08-30 | Added recompiled versions of Protobuf classes, after change of package name and upgrade to Protobuf 2.4.1. [Jonas Bonér]
* | | 59fba76 2011-08-30 | Disabled the replication tests until they are fixed. [Jonas Bonér]
* | |   6010e6e 2011-08-30 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| |/ /  
| * | 548ba08 2011-08-30 | #1145 - Changing private[akka] to protected[akka] in MessageDispatcher so that inheriting classes can access those methods [Viktor Klang]
* | | 49763ec 2011-08-30 | Changed 'connectionSize' to 'nrOfConnections'. [Jonas Bonér]
* | | 311cc1e 2011-08-30 | Added toString to ReplicationFactor config element. [Jonas Bonér]
* | |   814852b 2011-08-30 | Merge branch 'wip-remote-connection-failover' [Jonas Bonér]
|\ \ \  
| |/ /  
|/| |   
| * | e0385e5 2011-08-30 | Added failure detection to clustered and local routing. [Jonas Bonér]
| * | aabb5ff 2011-08-30 | Fixed wrong package in NetworkFailureSpec. [Jonas Bonér]
| * | 1e75cd3 2011-08-30 | Cleaned up JavaAPI and added copyright header. [Jonas Bonér]
| * | 344dab9 2011-08-29 | Misc reformatting, clean-ups and removal of '()' at a bunch of methods. [Jonas Bonér]
| * | 62f5d47 2011-08-29 | Removed trailing whitespace. [Jonas Bonér]
| * | 0e063f0 2011-08-29 | Converted tabs to spaces. [Jonas Bonér]
| * | e4b9111 2011-08-29 | Re-added NetworkFailureSpec for emulating shaky network, slow responses, network disconnect etc. [Jonas Bonér]
* | | 11b2e10 2011-08-29 | Fixed misstake, missed logger(instance), in previous commit [Patrik Nordwall]
* | | d21c58c 2011-08-29 | Included event.thread.getName in log message again. See #1154 [Patrik Nordwall]
* | | 40a887d 2011-08-29 | Use ActorRef.address as SL4FJ logger name. Fixes #1153 [Patrik Nordwall]
* | | c064aee 2011-08-29 | Replaced toString of message with exc.getMessage when logging exception from receive. Fixes 1152 [Patrik Nordwall]
* | | bbb9bc2 2011-08-29 | Manually fix protocols for scaladoc generation. See #1017 [Peter Vlugter]
* | | 7da2341 2011-08-29 | Use slf4j logger from the incoming instance.getClass.getName. Fixes #1121 [Patrik Nordwall]
|/ /  
* |   5e290ec 2011-08-29 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| * \   650e78b 2011-08-29 | Merge ClusterActoRef & RoutedActorRef: After merge with master, part 2 [Peter Veentjer]
| |\ \  
| | * | e2ef840 2011-08-28 | Added disclaimer about typesafe repo, and info about underlaying repositories. See #1127 (cherry picked from commit 11aef33e3913aee922b78fcc684416a03439d9a5) [Patrik Nordwall]
| | * | 3cee2fc 2011-08-28 | Internal Metrics API. Fixes #939 [Vasil Remeniuk]
| * | |   56d4fc7 2011-08-29 | Merge ClusterActoRef & RoutedActorRef: After merge with master [Peter Veentjer]
| |\ \ \  
| | |/ /  
| |/| |   
| | * | ee4d241 2011-08-27 | Use RoutedProps to configure Routing (local and remote). Ticket #1060 [Peter Veentjer]
| * | | cb9196c 2011-08-26 | #1146 - Switching from STringBuffer to StringBuilder for AkkaException [Viktor Klang]
| * | | 6bd4a9f 2011-08-26 | Removing the dispatcher from inside the EventHandler [Viktor Klang]
| * | |   fa5d521 2011-08-26 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * | | 35ba837 2011-08-26 | Updated documentation for use with sbt 0.10. Merge from 1.2 branch to master. See #1127 [Patrik Nordwall]
| | * | | fabc0a1 2011-08-26 | Upgrade to Camel 2.8.0 [Martin Krasser]
| * | | | c7d58c6 2011-08-26 | Adding initial support for Props [Viktor Klang]
| |/ / /  
| * | | 4bc0cfe 2011-08-26 | Revert "Fixing erronous cherry-pick change in EventHandler" [Viktor Klang]
* | | | 933e4f4 2011-08-29 | Added initial (very much non-complete) version of failure detection management system. [Jonas Bonér]
* | | | c797f2a 2011-08-29 | Added Thread name to the formatting of Slf4j handler. [Jonas Bonér]
* | | | 640487b 2011-08-29 | Removed Tab and Newline from formatting in Slf4j handler. [Jonas Bonér]
* | | | 66f339e 2011-08-29 | Moved all 'akka.remote' to 'akka.cluster', no more 'remote' package. [Jonas Bonér]
* | | |   43bbc19 2011-08-26 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| |/ / /  
| * | | 95976b4 2011-08-25 | Fixing erronous cherry-pick change in EventHandler [Viktor Klang]
| * | |   b0d0e96 2011-08-25 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| * | | | 79b663f 2011-08-25 | #1143 - Fixing EventHandler.levelFor by switching to isAssignableFrom" [Viktor Klang]
* | | | | a4c66bb 2011-08-26 | Minor changes to logging and reformatting. [Jonas Bonér]
* | | | | 2646ecd 2011-08-26 | Fixed bug in Cluster; registration of actor address per node mapping was done in wrong order and using ephemeral node. [Jonas Bonér]
* | | | | 9ade2d7 2011-08-26 | Fixed bug in NettyRemoteSupport in which we swallowed Netty exceptions and treated them as user exceptions and just completed the Future with the exception and did not rethrow. [Jonas Bonér]
* | | | | fe1d32b 2011-08-25 | Rewrote RandomFailoverMultiJvm and RoundRobinFailoverMultiJvm to use polling instead of Thread.sleep. [Jonas Bonér]
* | | | | 66bb47d 2011-08-25 | Changed akka.cluster.client.read-timout option. [Jonas Bonér]
* | | | | 2c25f5d 2011-08-25 | Changed remote client read timeout from 10 to 30 seconds. [Jonas Bonér]
| |/ / /  
|/| | |   
* | | | 9a5b1a8 2011-08-24 | Added explicit call to Cluster.node.start() in various tests. [Jonas Bonér]
* | | | 60f55c7 2011-08-24 | Switched from Thread.sleep to Timer.isTicking in various tests. [Jonas Bonér]
* | | | f9e82ea 2011-08-24 | Added method to ClusterNode to check if an actor is checked out on a specific (other) node, also added 'start' and 'boot' methods. [Jonas Bonér]
* | | | aeb8178 2011-08-24 | Added Timer class to be used in testing and more. [Jonas Bonér]
* | | | 5eeda5b 2011-08-24 | Refactored and enhanced EventHandler with optional synchronous logging to STDOUT to be used during testing and debugging sessions. [Jonas Bonér]
* | | |   775099a 2011-08-24 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| |/ / /  
| * | | a909bf8 2011-08-24 | #1140 - Adding support for specifying ArrayBlockingQueue or LinkedBlockingQueue in akka.conf and in the builder [Viktor Klang]
| * | | b76c02d 2011-08-24 | #1139 - Added akka.actor.DefaultBootableActorLoaderService for the Java API [Viktor Klang]
* | | | c20acef 2011-08-24 | Fixed completely broken spec: ClusterActorRefCleanupMultiJvmSpec. [Jonas Bonér]
* | | | 4c8b46d 2011-08-24 | Added preferred node to ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmSpec to make it more stable. [Jonas Bonér]
* | | | 472b505 2011-08-24 | Adding ClusterActorRef to ActorRegistry. Renamed Address to RemoteAddress. Added isShutdown flag to ClusterNode. Fixes #1132. [Jonas Bonér]
* | | | 53b4942 2011-08-24 | Removed unused Routing.scala file. [Jonas Bonér]
* | | | 3ae7b52 2011-08-24 | Removed logging in startup of TransactionLog to avoid triggering of cluster deployment. [Jonas Bonér]
|/ / /  
* | | 8475561 2011-08-23 | Build RemoteProtocol protobuf classes for Protobuf 2.4.1 [Jonas Bonér]
* | | 22738a2 2011-08-19 | Changed style of using empty ZK barrier bodies from 'apply()' to 'await()' [Jonas Bonér]
* | | 0ad8e6e 2011-08-19 | Moved the Migration multi-jvm test to different package. [Jonas Bonér]
* | | b361a79 2011-08-19 | Removed MigrationAutomaticMultiJvmSpec since the same thing is tested in a better way in the router fail-over tests. [Jonas Bonér]
* | | 6c7c3d4 2011-08-19 | Fixed problem with ClusterActorRefCleanupMultiJvmSpec: added missing/unbalanced barrier. [Jonas Bonér]
* | |   8ca638e 2011-08-19 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| * | | 63e3659 2011-08-19 | Fixed randomly failing test for Ticket #1111 [Vasil Remeniuk]
* | | | 4a011eb 2011-08-19 | Fixed race problem in actor initialization in Random3ReplicasMultiJvmSpec. [Jonas Bonér]
* | | | fcc3e41 2011-08-19 | Fixed problems with RandomFailoverMultiJvmSpec. [Jonas Bonér]
* | | | b371dc6 2011-08-19 | Disabled test in Ticket1111 (scatter gather router) which fails randomly, awaiting fix. [Jonas Bonér]
* | | | 950ad21 2011-08-19 | Fixed problem with node and test infrastructure initialization order causing multi-jvm test to fail randomly. [Jonas Bonér]
* | | | 5c7e0cd 2011-08-19 | Renamed and rewrote DirectRoutingFailoverMultiJvmSpec. [Jonas Bonér]
|/ / /  
* | | ea92c55 2011-08-18 | Fixed broken tests: RoundRobin2Replicas and RoundRobin3Replicas [Jonas Bonér]
* | | ea95588 2011-08-18 | Fixed broken tests: NewLeaderChangeListenerMultiJvmSpec and ReplicationTransactionLogWriteThroughNoSnapshotMultiJvmSpec. [Jonas Bonér]
* | | 67e0c59 2011-08-18 | Fixed broken test ReplicationTransactionLogWriteBehindNoSnapshotMultiJvmSpec [Jonas Bonér]
* | | dfc1a68 2011-08-18 | Fixed race condition in initial and dynamic management of node connections in Cluster * Using CAS optimistic concurrency using versioning to fix initial and dynamic management of node connections in Cluster * Fixed broken bootstrap of ClusterNode - reorganized booting and removed lazy from some fields * Removed 'start' and 'isRunning' from Cluster * Removed 'isStarted' Switch in Cluster which was sprinkled all-over cluster impl * Added more and better logging * Moved local Cluster ops from Cluster to LocalCluster * Rewrote RoundRobinFailoverMultiJvmSpec to be correct * RoundRobinFailoverMultiJvmSpec now passes * Minor reformatting and edits [Jonas Bonér]
* | | 0daa28a 2011-08-18 | Disabled mongo durable mailboxes until compilation error is solved [Jonas Bonér]
|/ /  
* | b121da7 2011-08-17 | Scatter-gather router. ScatterGatherRouter boradcasts the message to all connections of a clustered actor, and aggregates responses due to a specified strategy. Fixes #1111 [Vasil Remeniuk]
* |   4e0fb42 2011-08-17 | Merge branch 'master' of github.com:jboner/akka [Vasil Remeniuk]
|\ \  
| * | 21be1ac 2011-08-16 | Docs on how to use supervision from Java is wrong. Fixes #1114 [Patrik Nordwall]
* | |   d61b252 2011-08-16 | Merge branch 'master' of https://github.com/jboner/akka [Vasil Remeniuk]
|\ \ \  
| |/ /  
| * | 4e312fc 2011-08-15 | Instruction of how to write reference to ticket number in first line. [Patrik Nordwall]
* | |   ea201b4 2011-08-15 | Merge branch 'master' of https://github.com/jboner/akka [Vasil Remeniuk]
|\ \ \  
| |/ /  
| * |   482f55f 2011-08-15 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * \   879efd7 2011-08-15 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \  
| | | * | 18b5033 2011-08-15 | fix cancellation of FSM state timeout by named timer messages, fixes #1108 [Roland]
| | | * |   2d1a54a 2011-08-14 | Merge forward-ports from branch 'release-1.2' [Roland]
| | | |\ \  
| | | | * | c9e8e45 2011-08-09 | remove now-unused "package object akka" [Roland]
| | | | * | cdae21e 2011-08-09 | restore behavior of Future.as[T] and .asSilently[T] (fixes #1088) [Roland]
| | | | * | f894ae1 2011-08-09 | clarify origin of stats in release notes [Roland]
| | | | * | ccfc6aa 2011-08-09 | remove pluggable serializers from release notes, since they are only in 2.0 [Roland]
| | | | * | 1c80484 2011-08-08 | first stab at release notes for release 1.2 [Roland]
| | | * | | 817634c 2011-08-14 | IO: Include cause of closed socket [Derek Williams]
| | * | | | 48ee9de 2011-08-15 | Split up the TransactionLogSpec into two tests: AsynchronousTransactionLogSpec and SynchronousTransactionLogSpec, also did various minor edits and comments. [Jonas Bonér]
| * | | | | ce2df3b 2011-08-15 | Making TypedActor use actorOf(props) [Viktor Klang]
| * | | | | 5138fa9 2011-08-15 | Removing global dispatcher [Viktor Klang]
| | |/ / /  
| |/| | |   
* | | | |   93ee10d 2011-08-14 | Merge branch 'master' of https://github.com/jboner/akka [Vasil Remeniuk]
|\ \ \ \ \  
| |/ / / /  
| * | | | 90ef28f 2011-08-13 | Avoid MatchError in typed and untyped consumer publishers [Martin Krasser]
| * | | | 3159a80 2011-08-13 | More work on trying to get jenkins to run again [Peter Veentjer]
| * | | | 1d4505d 2011-08-13 | Attemp to get jenkinks running again; added .start after actorOf for clusteredActorRef [Peter Veentjer]
| * | | | 1c39ed1 2011-08-13 | cleanup of unused imports [Peter Veentjer]
| * | | | 37a6844 2011-08-12 | Changing default connection timeout to 100 seconds and adding Future Java API with tests [Viktor Klang]
| |/ / /  
| * | | 5b2b463 2011-08-12 | Changed the elements in actor.debug section in the config from receive = "false" to receive = off. Minor other reformatting changes [Jonas Bonér]
| * | | 45101a6 2011-08-12 | removal of some unwanted println in test [Peter Veentjer]
| * | | e23edde 2011-08-12 | minor doc improvent [Peter Veentjer]
| * | | 571f9f2 2011-08-12 | minor doc improvent [Peter Veentjer]
| * | | 6786f93 2011-08-12 | minor doc improvent [Peter Veentjer]
| * | |   c49d5e1 2011-08-12 | Merge branch 'wip-1075' [Peter Veentjer]
| |\ \ \  
| | * | | 0c58b38 2011-08-12 | RoutedActorRef makes use of composition instead of inheritance. Solves ticket 1075 and 1062 (broken roundrobin router)* [Peter Veentjer]
| * | | | 86bd9aa 2011-08-11 | Forward-porting documentation-fixes and small API problems from release-1.2 [Viktor Klang]
* | | | | 54bed05 2011-08-14 | more logging [Vasil Remeniuk]
|/ / / /  
* | | |   d52d705 2011-08-10 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| | |/ /  
| |/| |   
| * | |   deb7497 2011-08-09 | Merge channel-cleanup into remote branch 'origin/master' [Roland]
| |\ \ \  
| | * | | 5eb1540 2011-08-09 | Adding DeployId and LocalOnly to Props [Viktor Klang]
| | |/ /  
| * | | 2aa86ed 2011-08-03 | make tryTell nice from Scala AND Java [Roland]
| * | | a43418a 2011-08-01 | clean up Channel API (fixes #1070) [Roland]
* | | | 3d77356 2011-08-10 | Added policy about commit messages to the Developer Guidelines documentation [Jonas Bonér]
| |/ /  
|/| |   
* | | 6a49efd 2011-08-09 | ticket 989 #Allow specifying preferred-nodes of other type than node:<name>/ currently the task has been downscoped to only allow node:name since we need to rethink this part. Metadata is going to make it / it possible to deal with way more nodes [Peter Veentjer]
* | |   eae045f 2011-08-09 | ticket 989 #Allow specifying preferred-nodes of other type than node:<name>/ currently the task has been downscoped to only allow node:name since we need to rethink this part. Metadata is going to make it / it possible to deal with way more nodes [Peter Veentjer]
|\ \ \  
| * | | f1bc34c 2011-08-09 | ticket 989 #Allow specifying preferred-nodes of other type than node:<name>/ currently the task has been downscoped to only allow node:name since we need to rethink this part. Metadata is going to make it / it possible to deal with way more nodes [Peter Veentjer]
* | | | c8e938a 2011-08-08 | ticket #992: misc fixes for transaction log, processed review comments [Peter Veentjer]
* | | |   403f425 2011-08-08 | Merge branch 'wip-992' [Peter Veentjer]
|\ \ \ \  
| * | | | bd04971 2011-08-08 | ticket #992: misc fixes for transaction log, processed review comments [Peter Veentjer]
* | | | |   65f0d70 2011-08-08 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
|\ \ \ \ \  
| * | | | | e3bfd2f 2011-08-08 | Removing the shutdownHook from the Actor object since we are no longer using Configgy and hence no risk of leaks thereof [Viktor Klang]
* | | | | | 74b425f 2011-08-08 | Fix of failing ClusterActorRefCleanupMultiJvmSpec: also removed some debugging code [Peter Veentjer]
* | | | | | e310771 2011-08-08 | Fix of failing ClusterActorRefCleanupMultiJvmSpec [Peter Veentjer]
|/ / / / /  
* | | | | 5c44887 2011-08-08 | Fixing FutureTimeoutException so that it has a String constructor so it's deserializable in remoting [Viktor Klang]
* | | | |   49a61e2 2011-08-08 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| * | | | | e433eda 2011-08-08 | fix for a failing test:who changes the compression to none but didn't fix the testsgit add -A..you badgit add -A [Peter Veentjer]
| |/ / / /  
* | | | | b8af8df 2011-08-08 | Fixing ClusterSpec [Viktor Klang]
|/ / / /  
* | | |   560701a 2011-08-07 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
|\ \ \ \  
| * | | | 4cd4917 2011-08-07 | Turning remote compression off by default, closing ticket #1083 [Viktor Klang]
* | | | | 8a9e13e 2011-08-07 | ticket 934: fixed review comments [Peter Veentjer]
|/ / / /  
* | | |   06fe2d5 2011-08-07 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
|\ \ \ \  
| * \ \ \   363c196 2011-08-06 | Merge branch 'masterfuture' [Derek Williams]
| |\ \ \ \  
| | * | | | 0ae7a72 2011-08-06 | Future: Reschedule onTimeout/orElse if not yet expired [Derek Williams]
| | * | | | bc1f756 2011-08-06 | Fixed race in Future.await, and minor changes to Future.result and Future.exception [Derek Williams]
| | * | | | 8a1d316 2011-08-06 | Removing deprecated methods from Future and removing one of the bad guys _as_ [Viktor Klang]
| | * | | | 811e14e 2011-08-06 | Fixing await so that it respects infinite timeouts [Viktor Klang]
| | * | | | 9fb91e9 2011-08-06 | Removing awaitBlocking from Future since Futures cannot be completed after timed out, also cleaning up a lot of code to use pattern matching instead of if/else while simplifying and avoiding allocations [Viktor Klang]
| | * | | | 458724d 2011-08-05 | Reimplementing DefaultCompletableFuture to be as non-blocking internally as possible [Viktor Klang]
| * | | | | a17b75f 2011-08-06 | Add check for jdk7 to disable -optimize [Derek Williams]
* | | | | | a5ca2ba 2011-08-07 | ticket #958, resolved review comments [Peter Veentjer]
|/ / / / /  
* | | | |   32df67cb 2011-08-06 | ticket #992 after merge [Peter Veentjer]
|\ \ \ \ \  
| |/ / / /  
|/| | | |   
| * | | | aaec3ae 2011-08-06 | ticket #992 [Peter Veentjer]
* | | | | b1c6465 2011-08-05 | IO: add method to retry current message [Derek Williams]
* | | | |   d46e506 2011-08-05 | Merge branch 'master' of github.com:jboner/akka [Derek Williams]
|\ \ \ \ \  
| * \ \ \ \   0f640fb 2011-08-05 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \  
| | * | | | | 0057acd 2011-08-05 | Some more quietening of tests [Peter Vlugter]
| | * | | | | 562ebd9 2011-08-05 | Quieten the stm test output [Peter Vlugter]
| | * | | | | d5c0237 2011-08-04 | Disable parallel execution in global scope [Peter Vlugter]
| | * | | | | b2bfe9a 2011-08-04 | Some quietening of camel test output [Peter Vlugter]
| | * | | | |   bb3a4d7 2011-08-05 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| | |\ \ \ \ \  
| | * | | | | | b66ebdc 2011-08-05 | ticket #1032.. more cleanup [Peter Veentjer]
| * | | | | | | bb33a11 2011-08-05 | Adding parens to postStop in FSM, closing ticket #1079 [Viktor Klang]
| | |/ / / / /  
| |/| | | | |   
* | | | | | | c6bdd33 2011-08-05 | Future: make callback stack usable outside of DefaultPromise, make Future.flow use callback stack, hide java api from Scala [Derek Williams]
|/ / / / / /  
* | | | | | b41778f 2011-08-04 | Remove heap size option for multi-jvm [Peter Vlugter]
* | | | | | ea369a4 2011-08-04 | Quieten the multi-jvm tests [Peter Vlugter]
|/ / / / /  
* | | | |   6a476b6 2011-08-04 | Merge branch 'wip-1032' [Peter Veentjer]
|\ \ \ \ \  
| * | | | | d67fe8b 2011-08-04 | ticket #1032 [Peter Veentjer]
* | | | | | d378818 2011-08-03 | Use dispatcher from the passed in Future [Derek Williams]
* | | | | |   30594e9 2011-08-03 | Fixing merge conflict with the name of the mutable fold test [Viktor Klang]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | | b87b50d 2011-08-03 | uncomment test [Derek Williams]
* | | | | | e565f9c 2011-08-03 | Reenabling mutable zeroes test [Viktor Klang]
* | | | | | f70bf9e 2011-08-03 | Adding Props to akka.actor package [Viktor Klang]
|/ / / / /  
* | | | |   814eb1e 2011-08-03 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| * | | | | 00494cf 2011-08-02 | Fix for error while generating scaladocs [Derek Williams]
| * | | | | 053dbf3 2011-08-02 | Update Future docs [Derek Williams]
| * | | | | fbbeacc 2011-08-02 | Allow a Duration to be used with Future.apply [Derek Williams]
| * | | | | d568380 2011-08-02 | revert scalacOptions [Derek Williams]
| * | | | |   8db226f 2011-08-02 | Merge branch 'master' into derekjw-1054 [Derek Williams]
| |\ \ \ \ \  
| * | | | | | a0350d0 2011-08-02 | Future: move implicit dispatcher from methods to constructor [Derek Williams]
| * | | | | | 377fc2b 2011-07-28 | Add test for ticket 1054 [Derek Williams]
| * | | | | | 17b1656 2011-07-28 | Add test for ticket 1054 [Derek Williams]
| * | | | | | 0ea10b9 2011-07-28 | Formatting fix [Derek Williams]
| * | | | | | 04ba991 2011-07-28 | KeptPromise executes callbacks async [Derek Williams]
| * | | | | |   00a7baa 2011-07-28 | Merge branch 'master' into derekjw-1054 [Derek Williams]
| |\ \ \ \ \ \  
| * | | | | | | cd41daf 2011-07-28 | Future.onComplete now uses threadlocal callback stack to reduce amount of tasks sent to dispatcher [Derek Williams]
| * | | | | | | 5cb459c 2011-07-27 | reverting optimized Future methods due to more consistent behavior and performance increase was small [Derek Williams]
| * | | | | | | da98713 2011-07-26 | Partial fix for ticket #1054: execute callbacks in dispatcher [Derek Williams]
* | | | | | | | a589238 2011-08-03 | Set PartialFunction[T,Unit] as onResult callback, closing ticket #1077 [Viktor Klang]
* | | | | | | | 4fac3aa 2011-08-03 | Changing initialization and shutdown order to minimize risk of having a stopped actor in the registry [Viktor Klang]
| |_|/ / / / /  
|/| | | | | |   
* | | | | | | ef3b82a 2011-08-02 | Updating Netty to 3.2.5, closing ticket #1074 [Viktor Klang]
* | | | | | |   24fa23f 2011-08-02 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
|\ \ \ \ \ \ \  
| | |_|_|_|/ /  
| |/| | | | |   
| * | | | | | 04729bc 2011-08-01 | Renaming sendOneWay to tell, closing ticket #1072 [Viktor Klang]
| * | | | | | 29ca6a8 2011-08-01 | Making MessageDispatcher an abstract class, as well as ActorRef [Viktor Klang]
| * | | | | | 87ac860 2011-08-01 | Deprecating getSender and getSenderFuture [Viktor Klang]
| * | | | | | 088e202 2011-08-01 | Minor cleanup in Future.scala [Viktor Klang]
| * | | | | | 824d202 2011-08-01 | Refactor Future.await to remove boiler and make it correct in the face of infinity [Viktor Klang]
| * | | | | | 43031cb 2011-08-01 | ticket #889 some cleanup [Peter Veentjer]
| * | | | | | 3dc1280 2011-08-01 | Update sbt plugins [Peter Vlugter]
| * | | | | | a254fa6 2011-08-01 | Update multi-jvm docs [Peter Vlugter]
| * | | | | |   7530bee 2011-07-30 | Merge branch 'master' of github.com:jboner/akka [Roland]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   ad07fa3 2011-07-31 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| | |\ \ \ \ \ \  
| | * \ \ \ \ \ \   6065b3c 2011-07-31 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | 433c83c 2011-07-31 | fixed redis based serialization logic, added RedisClientPool, which is needed to handle multiple asynchronous message persistence over single threaded Redis - now runs all test cases in DurableMailboxSpec [Debasish Ghosh]
| | | |_|_|_|/ / /  
| | |/| | | | | |   
| * | | | | | | | 0341a87 2011-07-30 | oops, sorry :-( [Roland]
| | |_|/ / / / /  
| |/| | | | | |   
| * | | | | | | acdc521 2011-07-30 | clean up test output some more [Roland]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | 50bb14d 2011-07-29 | Test output cleaned up in akka-actor-tests and akka-testkit [Derek Williams]
| |/ / / / /  
* | | | | | 320ee3c 2011-08-02 | ticket #934 [Peter Veentjer]
|/ / / / /  
* | | | | 02aeec6 2011-07-28 | Simpler method for filtering EventHandler during testing [Derek Williams]
| |/ / /  
|/| | |   
* | | |   4b4f38c 2011-07-28 | ticket #889 after merge [Peter Veentjer]
|\ \ \ \  
| |_|_|/  
|/| | |   
| * | | 0fcc35d 2011-07-28 | ticket #889 [Peter Veentjer]
| * | | 21fee0f 2011-07-26 | ticket 889, initial checkin [Peter Veentjer]
* | | | f1733aa 2011-07-26 | Reducing IO load again to try and pass test on CI build [Derek Williams]
| |/ /  
|/| |   
* | | 4da526d 2011-07-26 | CI build failure was due to IOManager not shutting down in time. Use different ports for each test so failure doesn't happen [Derek Williams]
* | | 6625376 2011-07-26 | Reduce load during test to see if CI build will succeed. [Derek Williams]
* | | eea12dc 2011-07-26 | Don't use global dispatcher in case other tests don't cleanup [Derek Williams]
* | | 749b63e 2011-07-26 | formatting fixes [Derek Williams]
* | | ebc50b5 2011-07-27 | Update scalariform plugin [Peter Vlugter]
* | |   5b5d3cd 2011-07-26 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \  
| * | | 0351858 2011-07-26 | Lots of code cleanup and bugfixes of Deployment, still not AOT/JIT separation [Viktor Klang]
| * | | 0b1d5c7 2011-07-26 | Cleaning up some code [Viktor Klang]
| * | | 340ed11 2011-07-26 | Reformat with scalariform [Peter Vlugter]
| * | | 6f2fcc9 2011-07-26 | Add scalariform plugin [Peter Vlugter]
| * | | 6e337bf 2011-07-26 | Update to sbt 0.10.1 [Peter Vlugter]
| |/ /  
| * |   15cebdb 2011-07-26 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| |\ \  
| * \ \   c495822 2011-07-26 | ticket #958 after merge [Peter Veentjer]
| |\ \ \  
| | * | | 96cc0a0 2011-07-26 | ticket #958 [Peter Veentjer]
| | | |/  
| | |/|   
* | | |   6d343b0 2011-07-26 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \ \  
| | |_|/  
| |/| |   
| * | | 374f5dc 2011-07-25 | Added docs for Amazon Elastic Beanstalk [viktorklang]
| |/ /  
| * |   0153d8b 2011-07-25 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| |\ \  
| | * | f406cd9 2011-07-25 | Add abstract method for dispatcher name [Peter Vlugter]
| | * |   ae35e61 2011-07-22 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \  
| | | * | a5bbbb5 2011-07-22 | Ticket 981: Include akka properties in report [Patrik Nordwall]
| | | * | eccee3d 2011-07-22 | Ticket 981: minor [Patrik Nordwall]
| | | * | 8271059 2011-07-22 | Ticket 981: Moved general stuff to separate package [Patrik Nordwall]
| | | * | 031253f 2011-07-22 | Ticket 981: Include system info in report [Patrik Nordwall]
| | | * | 4105cd9 2011-07-22 | Ticket 981: Better initialization of MatchingEngineRouting [Patrik Nordwall]
| | | * | 9874f5e 2011-07-20 | Ticket 981: Added mean to percentiles and mean chart [Patrik Nordwall]
| | | * | fcfc0bd 2011-07-20 | Ticket 981: Added mean to latency and througput chart [Patrik Nordwall]
| | | * | cfa8856 2011-07-20 | Ticket 981: Adjusted how report files are stored and result logged [Patrik Nordwall]
| | | * | 1006fa6 2011-07-22 | ticket 1043 [Peter Veentjer]
| | | |/  
| | * | ecb0935 2011-07-21 | Removing superflous fields from LocalActorRef [Viktor Klang]
| * | | 1b1720d 2011-07-25 | ticket #1048 [Peter Veentjer]
| | |/  
| |/|   
| * |   6a91ec0 2011-07-21 | ticket 917, after merge [Peter Veentjer]
| |\ \  
| | |/  
| | *   77e71a9 2011-07-21 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\  
| | | * 0788862 2011-07-21 | Ticket 1002: dist dependsOn packageBin [Patrik Nordwall]
| | * | 0fa3ed1 2011-07-21 | Forward porting fix for #1034 [Viktor Klang]
| | |/  
| | * b23a8ff 2011-07-20 | removing replySafe and replyUnsafe in favor of the unified reply/tryReply [Viktor Klang]
| * | c0c6047 2011-07-21 | ticket 917 [Peter Veentjer]
| |/  
| *   4258abf 2011-07-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | *   3370994 2011-07-20 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| | |\  
| | * \   fee47cd 2011-07-20 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| | |\ \  
| | * | | bf7882c 2011-07-20 | ticket 885 [Peter Veentjer]
| * | | | 79d585e 2011-07-20 | Moving the config of the Scalariform [Viktor Klang]
| | |_|/  
| |/| |   
| * | |   ade2899 2011-07-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | | |/  
| | |/|   
| | * | 6222e5d 2011-07-19 | mark Actor.freshInstance as @experimental [Roland]
| | * | 0fe749f 2011-07-11 | add @experimental annotation in akka package [Roland]
| | * |   4276959 2011-07-19 | Merge branch 'ticket955' [Roland]
| | |\ \  
| | | * | 5de2ca7 2011-07-03 | remove Actor.preRestart(cause: Throwable) [Roland]
| | | * | 00b9166 2011-07-03 | make ActorRestartSpec thread safe [Roland]
| | | * | 956d055 2011-06-27 | make currentMessage available in preRestart, test #957 [Roland]
| | | * | 1025699 2011-06-27 | add Actor.freshInstance hook, test #955 [Roland]
| | | * | 48feec0 2011-06-26 | add debug output to investigate cause of RoutingSpec sporadic failures [Roland]
| | | * | e7f3945 2011-06-26 | add TestKit.expectMsgType [Roland]
| | * | | df3d536 2011-07-19 | improve docs for dispatcher throughput [Roland]
| | * | | 48b772c 2011-07-19 | Ticket 1002: Include target jars from dependent subprojects [Patrik Nordwall]
| | * | | 741b8cc 2011-07-19 | Ticket 1002: Only dist for kernel projects [Patrik Nordwall]
| | * | | e0ae830 2011-07-19 | Ticket 1002: Fixed dist:clean [Patrik Nordwall]
| | * | | 9c5b789 2011-07-19 | Ticket 1002: group conf settings [Patrik Nordwall]
| * | | | cc9b368 2011-07-19 | Adding Scalariform plugin [Viktor Klang]
| * | | | 5592cce 2011-07-19 | Testing out scalariform for SBT 0.10 [Viktor Klang]
| |/ / /  
| * | | 3bc7db0 2011-07-19 | Closing ticket #1030, removing lots of warnings [Viktor Klang]
| * | | fe1051a 2011-07-19 | Adding some ScalaDoc to the Serializer [Viktor Klang]
| * | | bb558c0 2011-07-19 | Switching to Serializer.Identifier for storing within ZK [Viktor Klang]
| * | |   013656e 2011-07-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | | |/  
| | |/|   
| | * |   c8199e0 2011-07-19 | Merge branch 'wip-974' [Peter Veentjer]
| | |\ \  
| | | * | e9f4c3e 2011-07-19 | ticket 974: Fix of the ambiguity problem in the Configuration.scala [Peter Veentjer]
| * | | | 4e6dd9e 2011-07-19 | The Unb0rkening [Viktor Klang]
| |/ / /  
| * | | e06983e 2011-07-19 | Optimizing serialization of TypedActor messages by adding an identifier to all Serializers so they can be fetched through that at the other end [Viktor Klang]
| |/ /  
| * | a145e07 2011-07-18 | ticket 974, part 2 [Peter Veentjer]
| * | 5635c9f 2011-07-18 | ticket 974 [Peter Veentjer]
| * |   e557bd3 2011-07-17 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| |\ \  
| | * | 892c6e0 2011-07-16 | improve scaladoc of TestKit.expectMsgAllOf [Roland]
| | * | a348025 2011-07-16 | improve testing docs [Roland]
| | * | 18dd81b 2011-07-16 | changed scope of spring-jms dependency to runtime [Martin Krasser]
| | * | a3408bf 2011-07-16 | Move sampleCamel-specific Ivy XML to Dependencies object [Martin Krasser]
| | * | 13da777 2011-07-16 | Excluded conflicting jetty dependency [Martin Krasser]
| | * | 68fdaaa 2011-07-16 | re-added akka-sample-camel to build [Martin Krasser]
| * | | 7983a66 2011-07-17 | Ticket 964: rename of reply? [Peter Veentjer]
| |/ /  
| * | e7b33d4 2011-07-15 | fix bad merge [Garrick Evans]
| * |   7f62717 2011-07-15 | Merge branch 'master' of github.com:jboner/akka [Garrick Evans]
| |\ \  
| * | | 05a1144 2011-07-15 | Covers tickets 988 and 915. Changed ActorRef to notify supervisor with max retry msg when temporary actor shutdown. Actor pool now requires supervision strategy. Updated pool docs. [Garrick Evans]
* | | | a3243f7 2011-07-15 | In progress documentation update [Derek Williams]
* | | |   fb32185 2011-07-15 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \ \  
| | |/ /  
| |/| |   
| * | | 2cf64bc 2011-07-15 | Adding support for having method parameters individually serialized and deserialized using its own serializer, closing ticket #765 [Viktor Klang]
| * | | c6297fa 2011-07-15 | Increasing timeouts for the RoundRobin2Replicas multijvm test [Viktor Klang]
| * | | 2871685 2011-07-15 | Adding TODO declarations in Serialization [Viktor Klang]
| * | | 0bfe21a 2011-07-15 | Fixing a type and adding clarification to ticket #956 [Viktor Klang]
| * | | f3c019d 2011-07-15 | Tweaking the interrupt restore it and breaking out of throughput [Viktor Klang]
| * | |   11cbebb 2011-07-15 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| |\ \ \  
| | * | | 6ce8be6 2011-07-15 | Adding warning docs for register/unregister [Viktor Klang]
| * | | | 4017a86 2011-07-15 | 1025: some cleanup [Peter Veentjer]
| |/ / /  
| * | | 966f7d9 2011-07-15 | ticket 1025 [Peter Veentjer]
| * | | 5678692 2011-07-15 | issue 956 [Peter Veentjer]
| * | | 964c532 2011-07-15 | issue 956 [Peter Veentjer]
| * | | 8bbb9b0 2011-07-15 | issue 956 [Peter Veentjer]
| * | |   4df8cb7 2011-07-15 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| |\ \ \  
| | * | | b41c7bc 2011-07-14 | Ticket 981: Generate html report with results and charts [Patrik Nordwall]
| * | | | f93624e 2011-07-15 | ticket 972 [Peter Veentjer]
| |/ / /  
* | | |   50dcdd4 2011-07-15 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \ \  
| |/ / /  
| * | |   0e933d2 2011-07-14 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| |\ \ \  
| | * | | ee5ce76 2011-07-14 | Ticket 1002: Inital sbt 0.10 migration [Patrik Nordwall]
| | * | | fd8c2ec 2011-07-14 | Ticket 1002: Inital sbt 0.10 migration [Patrik Nordwall]
| | * | |   9f3ce1f 2011-07-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \  
| | * | | | 9d71be7 2011-07-14 | Updating copyright section to Typesafe Inc. etc [Viktor Klang]
| * | | | |   198ffbf 2011-07-14 | Merge branch 'wip-1018' [Peter Veentjer]
| |\ \ \ \ \  
| | |_|/ / /  
| |/| | | |   
| | * | | | 43b3c1f 2011-07-14 | #1018, removal of git add akka-actor/src/main/scala/akka/actor/ActorRef.scala [Peter Veentjer]
| * | | | | 68d7db6 2011-07-14 | fix 909 and 1011 part IV [Peter Veentjer]
| * | | | |   c6d8ff4 2011-07-14 | 1011 and 909 [Peter Veentjer]
| |\ \ \ \ \  
| | |_|/ / /  
| |/| | | |   
| | * | | | 8a265ca 2011-07-14 | 1011 and 909 [Peter Veentjer]
| * | | | | d52267b 2011-07-14 | Reviving BadAddressDirectRoutingMultiJvmSpec, MultiReplicaDirectRoutingMultiJvmSpec, SingleReplicaDirectRoutingMultiJvmSpec, RoundRobin2ReplicasMultiJvmSpec [Viktor Klang]
| * | | | | e9f498a 2011-07-14 | Unbreaking the build, adding missing file to checkin, I apologize for the inconvenience [Viktor Klang]
| * | | | | 749b3da 2011-07-14 | Adding method return types to Serialization, and adding ScalaDoc, and cleaning up some of the code [Viktor Klang]
| * | | | | 97ac487 2011-07-14 | Making sure that access restrictions is not loosened for private[akka] methods in PinnedDispatcher [Viktor Klang]
| * | | | | eb08863 2011-07-14 | Adding ScalaDoc to akka.util.Switch [Viktor Klang]
| * | | | | f842b7d 2011-07-14 | Add test exclude to sbt build [Peter Vlugter]
| * | | | | 6fc34fe 2011-07-14 | Early abort coordinated transactions on exception (fixes #909). Rethrow akka-specific exceptions (fixes #1011). [Peter Vlugter]
| * | | | | 8207044 2011-07-14 | Update multi-jvm plugin [Peter Vlugter]
| * | | | | a5e98cc 2011-07-13 | Tweaking the RoutingSpec to be more deterministic [Viktor Klang]
| * | | | |   6df1349 2011-07-13 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \  
| | * \ \ \ \   cfd9507 2011-07-13 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| | |\ \ \ \ \  
| | | * | | | | a051bc6 2011-07-13 | Ticket 981: Added generation of anoher chart for display of throughput and latency in same graph [Patrik Nordwall]
| | | * | | | | 77ec8c1 2011-07-11 | Ticket 981: Minor improvement of error handling [Patrik Nordwall]
| | * | | | | | 8472df3 2011-07-13 | Fixes some MBean compliance issues [Peter Veentjer]
| | | |/ / / /  
| | |/| | | |   
| * | | | | | 31ad9db 2011-07-13 | Enabling 'cluster' for the routing tests [Viktor Klang]
| | |/ / / /  
| |/| | | |   
| * | | | | 99aafc6 2011-07-13 | Fixing broken test in ActorSerializeSpec by adding a .get call for an assertion [Viktor Klang]
| |/ / / /  
| * | | |   5238caf 2011-07-13 | Merge branches 'wip-1018' and 'master' [Peter Veentjer]
| |\ \ \ \  
| | |/ / /  
| | * | | 3b6b76a 2011-07-13 | 1018: removal of deprecated git status [Peter Veentjer]
| * | | | 3c21dfe 2011-07-13 | Merge branches 'wip-1018' and 'master' [Peter Veentjer]
| |/ / /  
| * | | d0a456f 2011-07-13 | removal of deprecated git checkout wip-1018 [Peter Veentjer]
| * | | d14f2f6 2011-07-13 | Issue 990: MBean for Cluster improvement [Peter Veentjer]
| * | | 2e8232f 2011-07-13 | Update the multi-jvm testing docs [Peter Vlugter]
| * | |   68b8b15 2011-07-12 | Merge pull request #86 from bwmcadams/master [viktorklang]
| |\ \ \  
| | * \ \   7a6f194 2011-07-12 | Merge branch 'master' of https://github.com/jboner/akka [Brendan W. McAdams]
| | |\ \ \  
| | |/ / /  
| |/| | |   
| * | | | b27448a 2011-07-12 | Revert "Commenting out the Mongo mailboxen until @rit has published the jar ;-)" [Viktor Klang]
| * | | | 321a9e0 2011-07-12 | Removing Scheduler.shutdown from public API and making the SchedulerSpec clean up after itself instead [Viktor Klang]
| * | | | 5486d9f 2011-07-12 | Making the RoutingSpec deterministic for SmallestMailboxFirst [Viktor Klang]
| * | | |   701af67 2011-07-12 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | * | | | 02dbc4c 2011-07-12 | Ticket 1005: Changed WeakReference to lookup by uuid [Patrik Nordwall]
| * | | | | a5f4490 2011-07-12 | Commenting out the Mongo mailboxen until @rit has published the jar ;-) [Viktor Klang]
| |/ / / /  
| * | | |   0acc01f 2011-07-12 | Merge pull request #85 from bwmcadams/master [viktorklang]
| |\ \ \ \  
| | |_|/ /  
| |/| | |   
| | | * | 225f476 2011-07-12 | Documentation for MongoDB-based Durable Mailboxes [Brendan W. McAdams]
| | |/ /  
| | * |   8e94626 2011-07-11 | Merge branch 'master' of https://github.com/jboner/akka [Brendan W. McAdams]
| | |\ \  
| | |/ /  
| |/| |   
| * | | 2ec7c84 2011-07-12 | Update the building akka docs [Peter Vlugter]
| * | | 5b87b52 2011-07-12 | Fix for scaladoc - manually edit the protocol [Peter Vlugter]
| | * | 2a56322 2011-07-11 | Migrate to Hammersmith (com.mongodb.async) URI Format based URLs; new config options     - Mongo URI config options     - Configurable timeout values for Read and Write [Brendan W. McAdams]
| | * | 5092adc 2011-07-11 | Add the Mongo Durable Mailbox stuff into the 0.10 SBT Build [Brendan W. McAdams]
| | * |   1c88077 2011-07-11 | Merge branch 'master' of https://github.com/jboner/akka [Brendan W. McAdams]
| | |\ \  
| | |/ /  
| |/| |   
| | * | 67e9d5a 2011-07-09 | Clean up some comments from the original template mailbox [Brendan W. McAdams]
| | * | 9ed458e 2011-07-09 | Must actively complete the future even if no messages exist; setting it to null on no match fixes testing issues. [Brendan W. McAdams]
| | * |   87d1094 2011-07-09 | Merge branch 'master' of https://github.com/jboner/akka [Brendan W. McAdams]
| | |\ \  
| | * | | bed3eae 2011-07-09 | Migrated to new Option[T] based findAndModify code to more cleanly detect 'no matching document' [Brendan W. McAdams]
| | * | | f3b7c16 2011-07-09 | Update Hammersmith dependency to the 0.2.6 release. [Brendan W. McAdams]
| | * | | 6af798e 2011-07-08 | Mongo Durable Mailboxes now Working against snapshot hammersmith. [Brendan W. McAdams]
| | * | |   7eecff5 2011-07-05 | Merge branch 'master' of github.com:bwmcadams/akka [Brendan W. McAdams]
| | |\ \ \  
| | * | | | 1f4cf47 2011-07-05 | First pass at Mongo Durable Mailboxes in a "Naive" approach.  Some bugs in Hammersmith currently surfacing that need resolving. [Brendan W. McAdams]
| | * | | | 73edd8e 2011-07-05 | First pass at Mongo Durable Mailboxes in a "Naive" approach.  Some bugs in Hammersmith currently surfacing that need resolving. [Brendan W. McAdams]
* | | | | | dba8749 2011-07-11 | Add testkit as a test dependency for all modules [Derek Williams]
* | | | | | 0e9ae44 2011-07-11 | Getting akka-actor-tests to run again [Derek Williams]
* | | | | |   34ca784 2011-07-11 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | |   2bf5ccd 2011-07-11 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| |\ \ \ \ \  
| | * | | | | c577664 2011-07-11 | Fixing ticket #984 by renaming Exit to Death [Viktor Klang]
| | * | | | | 522a163 2011-07-11 | Adding support for daemonizing MonitorableThreads [Viktor Klang]
| * | | | | | 54f79aa 2011-07-11 | disabled test because they keep failing, will be fixed later [Peter Veentjer]
| |/ / / / /  
| * | | | | 8e57d62 2011-07-11 | moved sources [Peter Veentjer]
| * | | | | 4de3aec 2011-07-11 | moved files to a different directory [Peter Veentjer]
| * | | | |   8b90bd5 2011-07-11 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| |\ \ \ \ \  
| | * | | | | c8c12ab 2011-07-11 | Fixing ticket #1005 by using WeakReference for LocalActorRefs [Viktor Klang]
| | * | | | | 24250d0 2011-07-11 | Changing 'flow' to use onException instead of other boilerplate [Viktor Klang]
| | * | | | | 3c98cce 2011-07-11 | Removing the ForkJoin Dispatcher after some interesting discussions with Doug Lea, will potentiall be resurrected in the future, with a vengeance ;-) [Viktor Klang]
| | * | | | | c0d60a1 2011-07-11 | Move multi-jvm tests to src/multi-jvm [Peter Vlugter]
| | * | | | | 29106c0 2011-07-08 | Add publish settings to sbt build [Peter Vlugter]
| | * | | | | 82b459b 2011-07-08 | Add reST docs task to sbt build [Peter Vlugter]
| | * | | | | 6923e17 2011-07-08 | Disable cross paths in sbt build [Peter Vlugter]
| | * | | | | 8947a69 2011-07-08 | Add unified scaladoc to sbt build [Peter Vlugter]
| | * | | | | 5776362 2011-07-07 | Update build and include multi-jvm tests [Peter Vlugter]
| | * | | | | 5f6a393 2011-07-04 | Basis for sbt 0.10 build [Peter Vlugter]
| | * | | | | 64b69a8 2011-07-04 | Move sbt 0.7 build to one side [Peter Vlugter]
| | * | | | | 07c4028 2011-07-10 | Ticket 981: Prefixed all properties with benchmark. [Patrik Nordwall]
| | * | | | | 33d9157 2011-07-10 | Ticket 981: Added possibility to compare benchmarks with each other [Patrik Nordwall]
| | * | | | | 2b2fcea 2011-07-10 | Fixing ticket #997, unsafe publication corrected [Viktor Klang]
| | * | | | |   3ef6f35 2011-07-10 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \  
| | | * | | | | 858609d 2011-07-10 | Ticket 981: Added charts for percentiles [Patrik Nordwall]
| | | * | | | | 8b9a56e 2011-07-10 | Index is moved to the akka.util package [Peter Veentjer]
| | | * | | | | 015fef1 2011-07-09 | jmm doc improvement [Peter Veentjer]
| | * | | | | | ba6a250 2011-07-10 | Making sure that RemoteActorRef.start cannot revive a RemoteActorRefs current status [Viktor Klang]
| | * | | | | | 5e94ca6 2011-07-10 | Fixing ticket #982, will backport to 1.2 [Viktor Klang]
| | |/ / / / /  
| | * | | | | ac311a3 2011-07-09 | Fixing a visibility problem with Scheduler thread id [Viktor Klang]
| | * | | | | 5df3fbf 2011-07-09 | Fixing ticket #1008, removing tryWithLock [Viktor Klang]
| | * | | | |   36ed15e 2011-07-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \  
| | | | |_|/ /  
| | | |/| | |   
| | | * | | |   947ee8c 2011-07-08 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \  
| | | * | | | | 34c838d 2011-07-08 | 1. Completed replication over BookKeeper based transaction log with configurable actor snapshotting every X message. 2. Completed replay of of transaction log on all replicated actors on migration after node crash. 3. Added end to end tests for write behind and write through replication and replay on fail-over. [Jonas Bonér]
| | | * | | | | 0b1ee75 2011-07-08 | 1. Implemented replication through transaction log, e.g. logging all messages and replaying them after actor migration 2. Added first replication test (out of many) 3. Improved ScalaDoc 4. Enhanced the remote protocol with replication info [Jonas Bonér]
| | * | | | | | e09a1d6 2011-07-06 | Seems to be no idea to reinitialize the FJTask [Viktor Klang]
| | | |/ / / /  
| | |/| | | |   
| * | | | | |   ce451c3 2011-07-07 | Contains the new tests for the direct routing [Peter Veentjer]
| |\ \ \ \ \ \  
| | |/ / / / /  
| |/| | | | |   
| | * | | | | 1843293 2011-07-07 | Contains the new tests for the direct routing [Peter Veentjer]
| * | | | | | 9e4017b 2011-07-06 | Adding guards in FJDispatcher so that multiple FJDispatchers do not interact badly with one and another [Viktor Klang]
| | |/ / / /  
| |/| | | |   
| * | | | | 6117e59 2011-07-06 | Added more info about how to create tickets in assembla [Jonas Bonér]
| * | | | | 01be882 2011-07-06 | Removed unnecessary check in ActorRegistry [Jonas Bonér]
| * | | | |   1dbcdb0 2011-07-06 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | 20abaaa 2011-07-06 | Changed semantics for 'Actor.actorOf' to be the same locally as on cluster: If an actor of the same logical address already exists in the registry then just return that, if not create a new one. [Jonas Bonér]
| * | | | | | c95e0e6 2011-07-06 | Ensured that if an actor of the same logical address already exists in the registry then just return that, if not create a new one. [Jonas Bonér]
| |/ / / / /  
| * | | | | 1b336ab 2011-07-05 | Added some Thread.sleep in the tests for the async TransactionLog API. [Jonas Bonér]
| * | | | | 95dbd42 2011-07-05 | 1. Fixed problems with actor fail-over migration. 2. Readded the tests for explicit and automatic migration 3. Fixed timeout issue in FutureSpec [Jonas Bonér]
| | |_|/ /  
| |/| | |   
| * | | |   2d4cb40 2011-07-05 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| | | |/ /  
| | |/| |   
| | * | | 0524d97 2011-07-04 | Ticket 981: Removed the blocking call that was used to initialize the routing rules in the OrderReceiver [Patrik Nordwall]
| | * | | 360c5ad 2011-07-04 | Ticket 981: EventHandler instead of println [Patrik Nordwall]
| | * | | 2c3b6ba 2011-07-04 | Ticket 981: Refactoring, renamed and consolidated [Patrik Nordwall]
| | * | | 8d724c1 2011-07-04 | Ticket 981: Reduced default repeat factor to make tests quick when not benchmarking [Patrik Nordwall]
| | * | | dbcea8f 2011-07-04 | Ticket 981: Removed TxLog, not interesting [Patrik Nordwall]
| | * | | a1bb7a7 2011-07-04 | Inital import of akka-sample-trading. Same as original except rename of root package [Patrik Nordwall]
| | * | |   f186928 2011-07-04 | Merge remote branch 'remotes/origin/modules-migration' [Martin Krasser]
| | |\ \ \  
| | | * | | 2c0d532 2011-05-25 | updates to remove references to akka-modules (origin/modules-migration) [ticktock]
| | | * | | b4698d7 2011-05-25 | adding modules docs [ticktock]
| | | * | | 3d54d6e 2011-05-25 | fixing sample-camel config [ticktock]
| | | * | | 0770e54 2011-05-25 | copied over akka-sample-camel [ticktock]
| * | | | | 1176c6c 2011-07-05 | Removed call to 'start()' in the constructor of ClusterActorRef [Jonas Bonér]
| * | | | | 9af5df4 2011-07-05 | Minor refactoring and restructuring [Jonas Bonér]
| * | | | | 4a179d1 2011-07-05 | 1. Makes sure to check if 'akka.enabled-modules=["cluster"]' is set before checking if the akka-cluster.jar is on the classpath, allowing non-cluster deployment even with the JAR on the classpath 2. Fixed bug with duplicate entries in replica set for an actor address 3. Turned on clustering for all Multi JVM tests [Jonas Bonér]
| * | | | | f2dd6bd 2011-07-04 | 1. Added configuration option for 'preferred-nodes' for a clustered actor. The replica set is now tried to be satisfied by the nodes in the list of preferred nodes, if that is not possible, it is randomly selected among the rest. 2. Added test for it. 3. Fixed wrong Java fault-tolerance docs 4. Fixed race condition in maintenance of connections to new nodes [Jonas Bonér]
| |/ / / /  
| * | | | e28db64 2011-07-02 | Disabled the migration test until race condition solved [Jonas Bonér]
| * | | |   f48d91c 2011-07-02 | Merged with master [Jonas Bonér]
| |\ \ \ \  
| | * | | | fc51bc4 2011-07-01 | Adding support for ForkJoin dispatcher as FJDispatcher [Viktor Klang]
| | * | | | 99b6255 2011-07-01 | Added ScalaDoc to TypedActor [Viktor Klang]
| | * | | |   1be1fbd 2011-07-01 | Merge branch 'wip-cluster-test' [Peter Veentjer]
| | |\ \ \ \  
| | | | |/ /  
| | | |/| |   
| | | * | | 19bb806 2011-07-01 | work on the clustered test; deployment of actor fails and this test finds that bug [Peter Veentjer]
| | * | | |   f555058 2011-07-01 | Merge branch 'wip-cluster-test' [Peter Veentjer]
| | |\ \ \ \  
| | | |/ / /  
| | | * | | a595728 2011-07-01 | Added a test for actors not being deployed in the correct node [Peter Veentjer]
| | * | | | ca6efa1 2011-07-01 | Use new cluster test node classes in round robin failover [Peter Vlugter]
| | * | | | 7a21473 2011-07-01 | Remove double creation in durable mailbox storage [Peter Vlugter]
| | * | | | f50537e 2011-07-01 | Cluster test printlns in correct order [Peter Vlugter]
| | * | | |   6465984 2011-06-30 | Merge branch 'wip-cluster-test' [Peter Veentjer]
| | |\ \ \ \  
| | | |/ / /  
| | | * | | a4102d5 2011-06-30 | more work on the roundrobin tests [Peter Veentjer]
| | * | | |   fa82ab7 2011-06-30 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| | |\ \ \ \  
| | | |/ / /  
| | |/| | |   
| | | * | | b2a636d 2011-06-30 | Closing ticket #963 [Viktor Klang]
| | * | | | f189584 2011-06-30 | Removed the peterexample tests [Peter Veentjer]
| | * | | | 3263531 2011-06-30 | Lot of work in the routing tests [Peter Veentjer]
| | |/ / /  
| | * | | 64717ce 2011-06-30 | Closing ticket #979 [Viktor Klang]
| | * | |   6d38a41 2011-06-30 | Manual merge [Viktor Klang]
| | |\ \ \  
| | | * | | 622a2fb 2011-06-30 | Comment out zookeeper mailbox test [Peter Vlugter]
| | | * | | 494a0af 2011-06-30 | Attempt to get the zoo under control [Peter Vlugter]
| | | * | | f141201 2011-06-30 | Comment out automatic migration test [Peter Vlugter]
| | | * | | 56d41a4 2011-06-29 | - removed unused imports [Peter Veentjer]
| | | * | |   c5052c9 2011-06-29 | Merge branch 'master' of github.com:jboner/akka [Peter Veentjer]
| | | |\ \ \  
| | | * | | | b75a92c 2011-06-29 | removed unused class [Peter Veentjer]
| | * | | | | c83baf6 2011-06-29 | WIP of serialization cleanup [Viktor Klang]
| | * | | | | e51601d 2011-06-29 | Formatting [Viktor Klang]
| | | |/ / /  
| | |/| | |   
| | * | | | 44025a3 2011-06-29 | Ticket 975, moved package object for duration to correct directory [Patrik Nordwall]
| | |/ / /  
| * | | | 828f035 2011-07-02 | 1. Changed the internal structure of cluster meta-data and how it is stored in ZooKeeper. Affected most of the cluster internals which have been rewritten to a large extent. Lots of code removed. 2. Fixed many issues and both known and hidden bugs in the migration code as well as other parts of the cluster functionality. 3. Made the node holding the ClusterActorRef being potentially part of the replica set for the actor it is representing. 4. Changed and cleaned up ClusterNode API, especially the ClusterNode.store methods. 5. Commented out ClusterNode.remove methods until we have a full story how to do removal 6. Renamed Peter's PeterExample test to a more descriptive name 7. Added round robin router test with 3 replicas 8. Rewrote migration tests to actually test correctly 9. Rewrote existing round robin router tests, now more solid 10. Misc improved logging and documentation and ScalaDoc [Jonas Bonér]
| |/ / /  
| * | | 9297480 2011-06-29 | readded the storage tests [Peter Veentjer]
| * | | fcea22f 2011-06-29 | added missing storage dir [Peter Veentjer]
| * | | 9c6527e 2011-06-29 | - initial example on the clustered test to get me up and running.. will be refactored to a more useful testin the very near future. [Peter Veentjer]
| * | | ce14078 2011-06-29 | - initial example on the clustered test to get me up and running.. will be refactored to a more useful testin the very near future. [Peter Veentjer]
| * | | ce35862 2011-06-29 | Added description of how to cancel scheduled task [Patrik Nordwall]
* | | | ccb8440 2011-06-28 | Move Timeout into actor package to make more accessible, use overloaded '?' method to handle explicit Timeout [Derek Williams]
* | | |   81b361e 2011-06-28 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \ \  
| |/ / /  
| * | | d25b8ce 2011-06-28 | re-enable akka-camel and akka-camel-typed and fix compile and test errors [Martin Krasser]
| * | | 86cfc86 2011-06-28 | Include package name when calling multi-jvm tests [Peter Vlugter]
| * | | 9ee978f 2011-06-27 | - moving the storage related file to the storage package - added some return types to the Cluster.scala [Peter Veentjer]
| * | |   c145dcb 2011-06-27 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * | | 2a19ea1 2011-06-27 | - more work on the storage functionality [Peter Veentjer]
| * | | | 82391e7 2011-06-27 | Fixed broken support for automatic migration of actors residing on crashed node. Also hardened the test for automatic migration of actors. [Jonas Bonér]
| |/ / /  
| * | | fefb902 2011-06-27 | Added 'private[cluster]' to all methods in ClusterNode API that deals with UUID. [Jonas Bonér]
| * | | 5a04095 2011-06-27 | Added multi-jvm test for ClusterDeployer [Jonas Bonér]
| * | | 426b132 2011-06-26 | Added 'node.shutdown()' to all multi-jvm tests. Renamed MigrationExplicit test. [Jonas Bonér]
| * | | 7a5c95e 2011-06-26 | Added tests for automatic actor migration when node is shut down. Updated to modified version of ZkClient (0.3, forked and fixed to allow interrupting connection retry). [Jonas Bonér]
| * | | 15addf2 2011-06-26 | Reorganized tests into matching subfolders. [Jonas Bonér]
| * | | a0abd5e 2011-06-26 | Fixed problems with actor migration in cluster and added tests for explicit actor migration through API [Jonas Bonér]
* | | | 1d710cc 2011-06-25 | Make better use of implicit Timeouts, fixes problem with using KeptPromise with aggregate methods (like traverse and sequence) [Derek Williams]
* | | | 2abb768 2011-06-23 | formatting fix [Derek Williams]
* | | | 7fb61de 2011-06-23 | Fix dependencies after merge [Derek Williams]
* | | |   878b8c1 2011-06-23 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \ \  
| |/ / /  
| * | | 8e4bcb3 2011-06-23 | Moved remoting code into akka-cluster. Removed akka-remote. [Jonas Bonér]
| * | | 56db84f 2011-06-23 | Uncommented failing leader election tests [Jonas Bonér]
| * | | 38e50b7 2011-06-23 | Removed link to second non-existing chapter in the getting started guide [Jonas Bonér]
| * | | 833238c 2011-06-22 | Added tests for storing, retrieving and removing custom configuration data in cluster storage. [Jonas Bonér]
| * | | df8c4da 2011-06-22 | Added methods for Cluster.remove and Cluster.release that accepts ActorRef [Jonas Bonér]
| * | | 5d4f8b4 2011-06-22 | Added test scenarios to cluster registry test suite. [Jonas Bonér]
| * | |   a498044 2011-06-22 | Merge with upstream master [Jonas Bonér]
| |\ \ \  
| | | |/  
| | |/|   
| * | | a65a3b1 2011-06-22 | Added multi-jvm test for doing 'store' on one node and 'use' on another. E.g. use of cluster registry. [Jonas Bonér]
| * | | a58b381 2011-06-22 | Added multi-jvm test for leader election in cluster [Jonas Bonér]
| * | | 4d31751 2011-06-22 | Fixed clustered management of actor serializer. Various renames and refactorings. Changed all internal usages of 'actorOf' to 'localActorOf'. [Jonas Bonér]
* | | | eab78b3 2011-06-20 | Add Future.orElse(x) to supply value if future expires [Derek Williams]
* | | | 068e77b 2011-06-20 | Silence some more noisy tests [Derek Williams]
* | | | d1b8b47 2011-06-20 | Silence some more noisy tests [Derek Williams]
* | | | dabf14e 2011-06-19 | Basic scalacheck properties for ByteString [Derek Williams]
* | | |   23dcb5d 2011-06-19 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \ \  
| | |/ /  
| |/| |   
| * | |   1c97275 2011-06-19 | Merge branch 'temp' [Roland]
| |\ \ \  
| | * | | 22c067e 2011-06-05 | add TestFSMRef docs [Roland]
| | * | | db2d296 2011-06-05 | ActorRef.start() returns this.type [Roland]
| | * | | 7deadce 2011-06-05 | move FSMLogEntry into FSM object [Roland]
| | * | | 3d40a0f 2011-06-05 | add TestFSMRefSpec and make TestFSMRef better accessible [Roland]
| | * | | b1533cb 2011-06-04 | add TestFSMRef [Roland]
| | * | | a45267e 2011-06-04 | break out LoggingFSM trait and add rolling event log [Roland]
| | * | | 39e41c6 2011-06-02 | change all actor logging to use Actor, not ActorRef as source instance [Roland]
| | * | | 76e8ef4 2011-05-31 | add debug traceability to FSM (plus docs) [Roland]
| | * | | ca36b55 2011-05-31 | add terminate(Shutdown) to FSM.postStop [Roland]
| | * | | 89bc194 2011-05-29 | FSM: make sure terminating because of Failure is logged [Roland]
| | * | | 2852e1a 2011-05-29 | make available TestKitLight without implicit ActorRef [Roland]
| | * | | 6b6ec0d 2011-05-29 | add stateName and stateData accessors to FSM [Roland]
| | * | | 1970b96 2011-05-29 | make TestKit methods return most specific type [Roland]
| | * | | 1e4084e 2011-05-29 | document logging and testing settings [Roland]
| | * | | 8c80548 2011-05-26 | document actor logging options [Roland]
| | * | | f3a7c41 2011-05-26 | document channel and !!/!!! changes [Roland]
| | * | | f770cfc 2011-05-25 | improve usability of TestKit.expectMsgPF [Roland]
| | * | | 899b7cc 2011-05-23 | first part of scala/actors docs [Roland]
| | * | | 4c4fc2f 2011-05-23 | enable quick build of akka-docs (html) [Roland]
| * | | |   8dffee2 2011-06-19 | Merge branch 'master' of github.com:jboner/akka [Roland]
| |\ \ \ \  
| | |/ / /  
| |/| | |   
| * | | | cba5faf 2011-06-17 | enable actor message and lifecycle tracing [Roland]
| * | | | d1caf65 2011-05-26 | relax FSMTimingSpec timeouts [Roland]
| * | | | bd0b389 2011-06-17 | introduce generations for FSM named timers, from release-1.2 [Roland]
* | | | | 2c11662 2011-06-19 | Improved TestEventListener [Derek Williams]
* | | | | b28b9ac 2011-06-19 | Make it possible to use infinite timeout with Actors [Derek Williams]
* | | | |   ae8555c 2011-06-18 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \ \ \  
| | |/ / /  
| |/| | |   
| * | | | 5747fb7 2011-06-18 | Added myself to the team [Derek Williams]
| |/ / /  
* | | | e639b2c 2011-06-18 | Start migrating Future to use Actor.Timeout, including support for never timing out. More testing and optimization still needed [Derek Williams]
* | | | daab5bd 2011-06-18 | Fix publishing [Derek Williams]
* | | | bf2a8cd 2011-06-18 | Better solution to ticket #853, better performance for DefaultPromise if already completed, especially if performing chained callbacks [Derek Williams]
* | | | 6eec2ae 2011-06-18 | tracking down reason for failing scalacheck test, jvm needs '-XX:-OmitStackTraceInFastThrow' option so it doesn't start giving exceptions with no message or stacktrace. Will try to find better solution. [Derek Williams]
* | | | bdb163b 2011-06-18 | Test for null Event message [Derek Williams]
* | | | da5a442 2011-06-17 | Nothing to see here... move along... [Derek Williams]
* | | | 95329e4 2011-06-17 | Add several instances of silencing events [Derek Williams]
* | | | a5bb34a 2011-06-17 | Filter based on message in TestEventListener [Derek Williams]
* | | | 7bde1d8 2011-06-17 | Add akka-testkit as a test dependency [Derek Williams]
* | | |   9bd9a35 2011-06-17 | Merge branch 'master' into wip-derekjw [Derek Williams]
|\ \ \ \  
| |/ / /  
| * | |   1d59f86 2011-06-17 | Merge branch 'master' of github.com:jboner/akka [Roland]
| |\ \ \  
| | |/ /  
| | * | 1ad99bd 2011-06-17 | Renamed sample class for compute grid [Jonas Bonér]
| | * | 532b556 2011-06-17 | Added test for ChangeListener.newLeader in cluster module [Jonas Bonér]
| | * | a0fcc62 2011-06-17 | Added ChangeListener.nodeDisconnected test [Jonas Bonér]
| | * |   6990c73 2011-06-17 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \  
| | | * \   626c4fe 2011-06-17 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \  
| | | * | | 0b1174f 2011-06-17 | Fixing mem leak in NettyRemoteSupport.unregister [Viktor Klang]
| | | * | |   d2c80a4 2011-06-17 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \ \  
| | | * | | | b5d3a67 2011-06-17 | Adding some ScalaDoc to Future [Viktor Klang]
| | * | | | | b937550 2011-06-17 | Added test for Cluster ChangeListener: NodeConnected, more to come. Also fixed bug in Cluster [Jonas Bonér]
| | | |_|/ /  
| | |/| | |   
| | * | | | 241831c 2011-06-17 | Added some more localActorOf methods and use them internally in cluster [Jonas Bonér]
| | * | | | 1997d97 2011-06-17 | Added 'localActorOf' method to get an actor that by-passes the deployment. Made use of it in EventHandler [Jonas Bonér]
| | * | | |   a6bdf9d 2011-06-17 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \  
| | | | |/ /  
| | | |/| |   
| | | * | | a41737e 2011-06-16 | Changed a typo [viktorklang]
| | | |/ /  
| | * | | e81a1d3 2011-06-17 | Added transaction log spec [Jonas Bonér]
| | * | | 549f33a 2011-06-17 | Improved error handling in Cluster.scala [Jonas Bonér]
| | * | |   5bcbdb2 2011-06-16 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \  
| | | |/ /  
| | * | | 6f89c62 2011-06-16 | Renamed ReplicationSpec to TransactionLogSpec. + Added sections to the Developer Guidelines on process [Jonas Bonér]
| * | | | 5933780 2011-06-17 | TestKit timeouts and awaitCond (from release-1.2) [Roland]
| * | | | f34d14e 2011-06-17 | remove stack trace duplication for AkkaException [Roland]
| | |/ /  
| |/| |   
* | | | 6b73e09 2011-06-17 | Added TestEventListener [Derek Williams]
* | | |   389893a 2011-06-17 | Merging all my branches together [Derek Williams]
|\ \ \ \  
| * \ \ \   964dd76 2011-06-14 | Merge branch 'master' into nio-actor [Derek Williams]
| |\ \ \ \  
| * \ \ \ \   e92672f 2011-06-13 | Merge branch 'master' into nio-actor [Derek Williams]
| |\ \ \ \ \  
| * | | | | | 2cb7bea 2011-06-13 | Update to latest master [Derek Williams]
| * | | | | |   d1a71a1 2011-06-13 | Merge branch 'master' into nio-actor [Derek Williams]
| |\ \ \ \ \ \  
| * \ \ \ \ \ \   9d6738d 2011-06-05 | Merge branch 'master' into nio-actor [Derek Williams]
| |\ \ \ \ \ \ \  
| * | | | | | | | 9c30aea 2011-06-05 | Combine ByteString and ByteRope [Derek Williams]
| * | | | | | | | 6400ec8 2011-06-04 | Fix type [Derek Williams]
| * | | | | | | | b559c11 2011-06-04 | Manually transform CPS in Future loops in order to optimize [Derek Williams]
| * | | | | | | | 03997ef 2011-06-04 | IO continuations seem to not suffer from stack overflow. yay! [Derek Williams]
| * | | | | | | | b1063e2 2011-06-04 | implement the rest of CPSLoop for Future. Need to create one for IO now [Derek Williams]
| * | | | | | | | 4967c8c 2011-06-04 | might have a workable solution to stack overflow [Derek Williams]
| * | | | | | | | 9d91990 2011-06-04 | Added failing test due to stack overflow, will try and fix [Derek Williams]
| * | | | | | | | d158514 2011-06-04 | Forgot to add cps utils. Suspect TailCalls is not actually goign to stop stack overflow. will test. [Derek Williams]
| * | | | | | | | fdcfbbd 2011-06-03 | update to be compatible with latest master [Derek Williams]
| * | | | | | | |   6200eb3 2011-06-03 | Merge branch 'master' into nio-actor [Derek Williams]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | 5e326bc 2011-06-03 | refactoring for simplicity, and moving cps helper methods to akka.utils, should work with dataflow as well [Derek Williams]
| * | | | | | | | | 3b796de 2011-06-03 | Found way to use @suspendable without type errors [Derek Williams]
| * | | | | | | | | b041e2f 2011-06-02 | Expand K/V Store IO test [Derek Williams]
| * | | | | | | | | 3af8912 2011-06-02 | these aren't promises, they are futures [Derek Williams]
| * | | | | | | | | 2236038 2011-06-02 | Improve clarity and type safety [Derek Williams]
| * | | | | | | | | 60139e0 2011-06-02 | move all IO api methods into IO object, no trait required for basic IO support, IO trait only needed for continuations [Derek Williams]
| * | | | | | | | | c4ff23a 2011-06-02 | Add cps friendly loops, remove nonsequential message handling (use multiple actors instead) [Derek Williams]
| * | | | | | | | |   2d17f5a 2011-06-02 | Merge branch 'master' into nio-actor [Derek Williams]
| |\ \ \ \ \ \ \ \ \  
| * | | | | | | | | | b9832cf 2011-06-02 | Change from @suspendable to @cps[Any] to avoid silly type errors [Derek Williams]
| * | | | | | | | | | 5fbbba3 2011-05-27 | change read with delimiter to drop the delimiter when not inclusive, and change that to the default. [Derek Williams]
| * | | | | | | | | | 8b16645 2011-05-27 | Add test of basic Redis-style key-value store [Derek Williams]
| * | | | | | | | | | ec4e7f7 2011-05-27 | Add support for reading all bytes, or reading up until a delimiter [Derek Williams]
| * | | | | | | | | | 4cc901c 2011-05-27 | Add ByteRope for concatenating ByteStrings without copying [Derek Williams]
| * | | | | | | | | | 07d9f13 2011-05-27 | Rename IO.Token to IO.Handle (the name I wanted but couldn't remember) [Derek Williams]
| * | | | | | | | | | f5a1dc1 2011-05-26 | Hold state in mutable collections to reduce allocations [Derek Williams]
| * | | | | | | | | | 2386728 2011-05-26 | IOActor can now handle multiple channels [Derek Williams]
| * | | | | | | | | | e165d35 2011-05-26 | remove unused val [Derek Williams]
| * | | | | | | | | | 4acce04 2011-05-26 | Option to process each message sequentially (safer), or only each read sequentially (better performing) [Derek Williams]
| * | | | | | | | | | a2cc661 2011-05-25 | Reduce copying ByteString data [Derek Williams]
| * | | | | | | | | |   83be09a 2011-05-25 | Merge branch 'master' into nio-actor [Derek Williams]
| |\ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | 2431361 2011-05-25 | refactor test [Derek Williams]
| * | | | | | | | | | | 8727be3 2011-05-25 | First try at implementing delimited continuations to handle IO reads [Derek Williams]
| * | | | | | | | | | | 9ca5df0 2011-05-25 | Move thread into IOWorker [Derek Williams]
| * | | | | | | | | | | 45cfbec 2011-05-24 | Add ByteString.concat to companion object [Derek Williams]
| * | | | | | | | | | | e4a68cd 2011-05-24 | ByteString will retain it's backing array when possible [Derek Williams]
| * | | | | | | | | | |   0eb8bb8 2011-05-23 | Merge branch 'master' into nio-actor [Derek Williams]
| |\ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | ad663c6 2011-05-23 | Handle shutdowns and closing channels [Derek Williams]
| * | | | | | | | | | | | f6205f9 2011-05-23 | Use InetSocketAddress instead of String/Int [Derek Williams]
| * | | | | | | | | | | | dd1a04b 2011-05-23 | Allow an Actor to have more than 1 channel, and add a client Actor to the test [Derek Williams]
| * | | | | | | | | | | |   ef40dbd 2011-05-23 | Merge branch 'master' into nio-actor [Derek Williams]
| |\ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | f741b00 2011-05-22 | Rename NIO to IO [Derek Williams]
| * | | | | | | | | | | | | 8d4622b 2011-05-22 | IO Actor initial rewrite [Derek Williams]
| * | | | | | | | | | | | | e547ca0 2011-05-22 | ByteString improvements [Derek Williams]
| * | | | | | | | | | | | | 05e93e3 2011-05-22 | ByteString.apply optimized for Byte [Derek Williams]
| * | | | | | | | | | | | | 9ef8ea5 2011-05-22 | Basic immutable ByteString implmentation, probably needs some methods overriden for efficiency [Derek Williams]
| * | | | | | | | | | | | | 61178a9 2011-05-21 | Improved NIO Actor API [Derek Williams]
| * | | | | | | | | | | | | 170eb47 2011-05-20 | add NIO trait for Actor to handle nonblocking IO [Derek Williams]
* | | | | | | | | | | | | | 9466e4a 2011-06-17 | wip - add detailed unit tests and scalacheck property checks [Derek Williams]
* | | | | | | | | | | | | | f5d4bb2 2011-06-17 | Update scalatest to 1.6.1, and add scalacheck to akka-actor-tests [Derek Williams]
* | | | | | | | | | | | | | d71954c 2011-06-17 | Add Future.onTimeout [Derek Williams]
* | | | | | | | | | | | | |   d9f5561 2011-06-15 | Merge branch 'master' into promisestream [Derek Williams]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|_|_|_|_|_|_|_|_|/ / /  
| |/| | | | | | | | | | | |   
| * | | | | | | | | | | | | 6a3049e 2011-06-15 | document Channel contravariance [Roland]
| * | | | | | | | | | | | | da7d878 2011-06-15 | make Channel contravariant [Roland]
| | |_|_|_|_|_|_|_|_|_|/ /  
| |/| | | | | | | | | | |   
| * | | | | | | | | | | | 3712015 2011-06-15 | Fixing ticket #908 [Viktor Klang]
| * | | | | | | | | | | | 0c21afe 2011-06-15 | Fixing ticket #907 [Viktor Klang]
| * | | | | | | | | | | |   79f71fd 2011-06-15 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \ \   cc27b8f 2011-06-15 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \ \ \   217b5ad 2011-06-15 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | 9bb5460 2011-06-15 | removed duplicate copy of Serializer.scala [Debasish Ghosh]
| | * | | | | | | | | | | | | | e5de16e 2011-06-15 | Fixed implicit deadlock in cluster deployment [Jonas Bonér]
| | | |/ / / / / / / / / / / /  
| | |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | | 204f4e2 2011-06-15 | reformatted akka-reference.conf [Jonas Bonér]
| | |/ / / / / / / / / / / /  
| | * | | | | | | | | | | | a7dffdd 2011-06-15 | pluggable serializers - changed entry name in akka.conf to serialization-bindings. Also updated akka-reference.conf with a commented section on pluggable serializers [Debasish Ghosh]
| | | |_|_|_|_|_|_|_|_|/ /  
| | |/| | | | | | | | | |   
| * | | | | | | | | | | | 986661f 2011-06-15 | Adding initial test cases for serialization of MethodCalls, preparing for hooking in Serialization.serialize/deserialize [Viktor Klang]
| * | | | | | | | | | | | 19950b6 2011-06-15 | Switching from -123456789 as magic number to Long.MinValue for noTimeoutGiven [Viktor Klang]
| * | | | | | | | | | | | 00e8fd7 2011-06-15 | Moving the timeout so that it isn't calculated unless the actor is running [Viktor Klang]
| * | | | | | | | | | | | df316b9 2011-06-15 | Making the default-serializer condition throw NoSerializerFoundException instead of plain Exception' [Viktor Klang]
| |/ / / / / / / / / / /  
* | | | | | | | | | | | 1e0291a 2011-06-14 | Specialize mapTo for KeptPromise [Derek Williams]
* | | | | | | | | | | |   42ec626 2011-06-14 | Merge branch 'master' into promisestream [Derek Williams]
|\ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / /  
| * | | | | | | | | | |   01c01e9 2011-06-14 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | 5aaf977 2011-06-14 | re-add implicit timeout to ActorRef.?, but with better semantics: [Roland]
| * | | | | | | | | | | | bf0515b 2011-06-14 | Fixed remaining issues in pluggable serializers (cluster impl) [Jonas Bonér]
| * | | | | | | | | | | |   e0e9696 2011-06-14 | Merge branch 'master' into pluggable-serializer [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / /  
| | * | | | | | | | | | |   79fb193 2011-06-14 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | 1eeb9d9 2011-06-14 | 1. Removed implicit scoped timeout in ? method. Reason: 'pingPongActor.?(Ping)(timeout = TimeoutMillis)' breaks old user code and is so ugly. It is not worth it. Now user write (as he used to): 'pingPongActor ? (Ping, TimeoutMillis)' 2. Fixed broken Cluster communication 3. Added constructor with only String arg for UnhandledMessageException to allow client instantiation of remote exception [Jonas Bonér]
| | | |_|_|_|_|_|_|_|_|/ /  
| | |/| | | | | | | | | |   
| * | | | | | | | | | | |   04bf416 2011-06-14 | Merge branch 'master' into pluggable-serializer [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / /  
| | |/| | | | | | | | | |   
| | * | | | | | | | | | | 26500be 2011-06-14 | 1. Removed implicit scoped timeout in ? method. Reason: 'pingPongActor.?(Ping)(timeout = TimeoutMillis)' breaks old user code and is so ugly. It is not worth it. Now user write (as he used to): 'pingPongActor ? (Ping, TimeoutMillis)' 2. Fixed broken Cluster communication 3. Added constructor with only String arg for UnhandledMessageException to allow client instantiation of remote exception [Jonas Bonér]
| | |/ / / / / / / / / /  
| | * | | | | | | | | | e18cc7b 2011-06-13 | revert changes to java api [Derek Williams]
| | * | | | | | | | | | c62e609 2011-06-13 | Didn't test that change, reverted to my original [Derek Williams]
| | * | | | | | | | | | cbdfd0f 2011-06-13 | Fix Future type issues [Derek Williams]
| | | |_|_|_|_|_|_|/ /  
| | |/| | | | | | | |   
| | * | | | | | | | | d917f99 2011-06-14 | Adjust all protocols to work with scaladoc [Peter Vlugter]
| | * | | | | | | | |   05783ba 2011-06-14 | Merge branch 'master' of github.com:jboner/akka [Roland]
| | |\ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | 73694af 2011-06-14 | Manually fix remote protocol for scaladoc [Peter Vlugter]
| | * | | | | | | | | |   ca592ef 2011-06-14 | Merge branch 'master' of github.com:jboner/akka [Roland]
| | |\ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / /  
| | | * | | | | | | | |   3d54c09 2011-06-13 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | 2bbbeba 2011-06-13 | - more work on the storage functionality [Peter Veentjer]
| | | * | | | | | | | | | e3e389e 2011-06-13 | Fixing spelling errors in docs [Viktor Klang]
| | | |/ / / / / / / / /  
| | | * | | | | | | | | 9c1a50b 2011-06-13 | Refactoring the use of !! to ?+.get for Akka internal code [Viktor Klang]
| | | * | | | | | | | | 6ddee70 2011-06-13 | Creating a package object for the akka-package to put the .as-conversions there, also switching over at places from !! to ? [Viktor Klang]
| | | * | | | | | | | | fa0478b 2011-06-13 | Replacing !!! with ? [Viktor Klang]
| | | * | | | | | | | | fd5afde 2011-06-13 | Adding 'ask' to replace 'sendRequestReplyFuture' and removing sendRequestReply [Viktor Klang]
| | * | | | | | | | | | 7712c20 2011-06-13 | unify sender/senderFuture into channel (++) [Roland]
| * | | | | | | | | | | 0d471ae 2011-06-13 | fixed protobuf new version issues and some minor changes in Actor and ActorRef from merging [Debasish Ghosh]
| * | | | | | | | | | |   f27e23b 2011-06-13 | Merged with master [Debasish Ghosh]
| |\ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / /  
| | |/| | | | | | | | |   
| | * | | | | | | | | | ec9c2e1 2011-06-12 | Fixing formatting for SLF4J errors [Viktor Klang]
| | * | | | | | | | | | 2fc0188 2011-06-12 | Fixing ticket #913 by switching to an explicit AnyRef Array [Viktor Klang]
| | * | | | | | | | | | 54960f7 2011-06-12 | Fixing ticket #916, adding a catch-all logger for exceptions around message processing [Viktor Klang]
| | * | | | | | | | | | 5e192c5 2011-06-12 | Adding microkernel-server.xml to Akka since kernel has moved here [Viktor Klang]
| | * | | | | | | | | |   634e470 2011-06-11 | Merge with master [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | 5006f8e 2011-06-10 | renamed 'remote' module config option to 'cluster' + cleaned up config file comments [Jonas Bonér]
| | | * | | | | | | | | | 5c92a27 2011-06-10 | Moved 'akka.remote' config elements into 'akka.cluster' [Jonas Bonér]
| | | * | | | | | | | | | 8098a8d 2011-06-10 | Added storage models to remote protocol and refactored all clustering to use it. [Jonas Bonér]
| | | * | | | | | | | | |   7dbc5ac 2011-06-10 | Merged with master [Jonas Bonér]
| | | |\ \ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / / / /  
| | | | * | | | | | | | | cee934a 2011-06-07 | Fixed failing RoutingSpec [Martin Krasser]
| | | | * | | | | | | | |   df62230 2011-06-07 | Merge branch 'master' into 911-krasserm [Martin Krasser]
| | | | |\ \ \ \ \ \ \ \ \  
| | | | * \ \ \ \ \ \ \ \ \   2bd7751 2011-06-06 | Merge branch 'master' into 911-krasserm [Martin Krasser]
| | | | |\ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | efd9a89 2011-06-06 | Migrate akka-camel-typed to new typed actor implementation. [Martin Krasser]
| | | | | |_|_|_|_|_|/ / / /  
| | | | |/| | | | | | | | |   
| | | * | | | | | | | | | | 85e7423 2011-06-07 | - Made ClusterActorRef not extends RemoteActorRef anymore - Refactored and cleaned up Transaction Log initialization [Jonas Bonér]
| | | * | | | | | | | | | | 5a24ba5 2011-06-07 | Added ZK flag for cluster deployment completed + methods for checking and invalidating it [Jonas Bonér]
| | | * | | | | | | | | | | 04efc44 2011-06-07 | 1. Made LocalActorRef aware of replication 2. Added configuration for transaction log replication 3. Added replication schemes WriteThrough and WriteBehind 4. Refactored serializer creation and lookup in Actor.scala 5. Extended network protocol with replication strategy 6. Added BookKeeper management to tests 7. Improved logging and error messages 8. Removed ReplicatedActorRef 9. Added snapshot management to TransactionLog [Jonas Bonér]
| | | |/ / / / / / / / / /  
| | * | | | | | | | | | | cc1b44a 2011-06-07 | Improving error messages from creating Actors outside of actorOf, and some minor code reorganizing [Viktor Klang]
| | * | | | | | | | | | | 417fcc7 2011-06-07 | Adding support for mailboxIsEmpty on MessageDispatcher and removing getMailboxSize and mailboxSize from ActorRef, use actorref.dispatcher.mailboxSize(actorref) and actorref.dispatcher.mailboxIsEmpty(actorref) [Viktor Klang]
| | | |_|/ / / / / / / /  
| | |/| | | | | | | | |   
| | * | | | | | | | | | ed5ac01 2011-06-07 | Removing Jersey from the docs [Viktor Klang]
| | * | | | | | | | | | c2626d2 2011-06-06 | Adding TODOs to TypedActor so we know what needs to be done before 2.0 [Viktor Klang]
| | * | | | | | | | | | e41a3b1 2011-06-06 | Making ActorRef.address a def instead of a val [Viktor Klang]
| | * | | | | | | | | | 4c8360e 2011-06-06 | Minor renames of parameters of non-user API an some code cleanup [Viktor Klang]
| | * | | | | | | | | | 39ca090 2011-06-06 | Adding tests for LocalActorRef serialization (with remoting disabled) [Viktor Klang]
| | * | | | | | | | | | 6f05019 2011-06-06 | Fixing bugs in actorref creation, or rather, glitches [Viktor Klang]
| | * | | | | | | | | | dacc29d 2011-06-05 | Some more minor code cleanups of Mist [Viktor Klang]
| | * | | | | | | | | | 94c977e 2011-06-05 | Adding documentation for specifying Mist root endpoint id in web.xml [Viktor Klang]
| | * | | | | | | | | | c52a352 2011-06-05 | Cleaning up some Mist code [Viktor Klang]
| | * | | | | | | | | | c3ee124 2011-06-05 | Removing the Jersey Http Security Module plus the AkkaRestServlet [Viktor Klang]
| | * | | | | | | | | | 29f515f 2011-06-05 | Adding support for specifying the root endpoint id in web.xml for Mist [Viktor Klang]
| | | |/ / / / / / / /  
| | |/| | | | | | | |   
| * | | | | | | | | | 40d1ca6 2011-06-07 | Issue #595: Pluggable serializers - basic implementation [Debasish Ghosh]
| |/ / / / / / / / /  
| * | | | | | | | | b600d0c 2011-06-05 | Rewriting some serialization hook in Actor [Viktor Klang]
| * | | | | | | | | c6019ce 2011-06-05 | Removing pointless guard in ActorRegistry [Viktor Klang]
| * | | | | | | | | 3e989b5 2011-06-05 | Removing AOP stuff from the project [Viktor Klang]
| | |_|_|_|_|_|/ /  
| |/| | | | | | |   
| * | | | | | | | 56a172b 2011-06-04 | Removing residue of old ActorRef interface [Viktor Klang]
| * | | | | | | | 66ad188 2011-06-04 | Fixing #648, adding transparent support for Java Serialization of ActorRef (local + remote) [Viktor Klang]
| * | | | | | | | 21fe205 2011-06-04 | Removing deprecation warnings [Viktor Klang]
| * | | | | | | | f9d0b18 2011-06-04 | Removing ActorRef.isDefinedAt and Future.empty and moving Future.channel to Promise, renaming future to promise for the channel [Viktor Klang]
| | |_|_|_|_|/ /  
| |/| | | | | |   
| * | | | | | | 07eaf0b 2011-06-02 | Attempt to solve ticket #902 [Viktor Klang]
| * | | | | | | b0952e5 2011-06-02 | Renaming Future.failure to Future.recover [Viktor Klang]
| |/ / / / / /  
| * | | | | |   3d7a717 2011-05-30 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | a85bba7 2011-05-27 | - more work on the storage functionality [Peter Veentjer]
| | * | | | | | 49883d8 2011-05-26 | Adding support for completing senderFutures when actor is stopped, closing ticket #894. Also renaming DurableEventBasedDispatcher to DurableDispatcher [Viktor Klang]
| | * | | | | | e94b722 2011-05-26 | Adding withFilter to Future, fixing signature of filter, cleaning up foreach [Viktor Klang]
| | * | | | | | b98352e 2011-05-26 | Allow find-replace to replace versions in the docs [Peter Vlugter]
| | * | | | | | fb200b0 2011-05-26 | Fix warnings in docs [Peter Vlugter]
| | * | | | | | 046399c 2011-05-26 | Update docs [Peter Vlugter]
| | * | | | | | 89cb493 2011-05-26 | Update release scripts for modules merge [Peter Vlugter]
| | * | | | | | b7d0fb6 2011-05-26 | Add microkernel dist [Peter Vlugter]
| | | |_|_|/ /  
| | |/| | | |   
| * | | | | | 112ddef 2011-05-30 | refactoring and minor edits [Jonas Bonér]
| | |_|_|_|/  
| |/| | | |   
* | | | | |   7ef6a37 2011-05-25 | Merge branch 'master' into promisestream [Derek Williams]
|\ \ \ \ \ \  
| | |/ / / /  
| |/| | | |   
| * | | | | 9567c5e 2011-05-25 | lining up config name with reference conf [ticktock]
| * | | | |   9611021 2011-05-25 | Merge branch 'master' of https://github.com/jboner/akka [ticktock]
| |\ \ \ \ \  
| | |/ / / /  
| | * | | | 6f1ff4e 2011-05-25 | Fixed failing akka-camel module, Upgrade to Camel 2.7.1, included akka-camel and akka-kernel again into AkkaProject [Martin Krasser]
| | * | | | 30d5b93 2011-05-25 | Commented out akka-camel and akka-kernel [Jonas Bonér]
| | * | | | dcb4907 2011-05-25 | Added tests for round-robin routing with 3 nodes and 2 replicas [Jonas Bonér]
| | * | | | abe6047 2011-05-25 | Minor changes and some added FIXMEs [Jonas Bonér]
| | * | | |   7311a8b 2011-05-25 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \  
| | * | | | | e6fa55b 2011-05-25 | - Changed implementation of Actor.actorOf to work in the the new world of cluster.ref, cluster.use and cluster.store. - Changed semantics of replica config. Default replicas is now 0. Replica 1 means one copy of the actor is instantiated on another node. - Actor.remote.actorFor/Actor.remote.register is now separated and orthogonal from cluster implementation. - cluster.ref now creates and instantiates its replicas automatically, e.g. it can be created first and will then set up what it needs. - Added logging everywhere, better warning messages etc. - Each node now fetches the whole deployment configuration from the cluster on boot. - Added some config options to cluster [Jonas Bonér]
| * | | | | | 3423b26 2011-05-25 | updates to remove references to akka-modules [ticktock]
| * | | | | | a6e096d 2011-05-25 | adding modules docs [ticktock]
| | |/ / / /  
| |/| | | |   
| * | | | | 4e1fe76 2011-05-24 | fixing compile looks like some spurious chars were added accidentally [ticktock]
| * | | | | 39caed3 2011-05-24 | reverting the commenting out of akka-camel, since kernel needs it [ticktock]
| * | | | |   cd1806c 2011-05-24 | Merge branch 'master' of https://github.com/jboner/akka into modules-migration [ticktock]
| |\ \ \ \ \  
| | |/ / / /  
| | * | | |   71beab8 2011-05-24 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \  
| | | * | | | 3ccfdf8 2011-05-24 | Adding a Java API method for generic proxying [Viktor Klang]
| | | * | | |   2b09434 2011-05-24 | Merge with master [Viktor Klang]
| | | |\ \ \ \  
| | | | * | | | 724cbf4 2011-05-24 | Removing hte slick interfaces/implementation classes because it`s just not good enough [Viktor Klang]
| | | * | | | | 5310789 2011-05-24 | Removing hte slick interfaces/implementation classes because it`s just not good enough [Viktor Klang]
| | | |/ / / /  
| | | * | | | 2642c9a 2011-05-24 | Adding support for retrieving interfaces proxied and the current implementation behind the proxy, intended for annotation processing etc [Viktor Klang]
| | | * | | | 6f6d919 2011-05-24 | Fixing ticket #888, adding startLink to Supervisor [Viktor Klang]
| | * | | | | f75dcdb 2011-05-24 | Full clustering circle now works, remote communication. Added test for cluster communication. Refactored deployment parsing. Added InetSocketAddress to remote protocol. [Jonas Bonér]
| | |/ / / /  
| | * | | | 5fd1097 2011-05-24 | - added the InMemoryRawStorage (tests will follow) [Peter Veentjer]
| | | |_|/  
| | |/| |   
| * | | | 2cb9476 2011-05-24 | commenting out camel, typed-camel, spring for the time being [ticktock]
| * | | |   88727d8 2011-05-23 | Merge branch 'master' of https://github.com/jboner/akka into modules-migration [sclasen]
| |\ \ \ \  
| | |/ / /  
| * | | | 960257e 2011-05-23 | move over some more config [sclasen]
| * | | | da4fade 2011-05-23 | fix compilation by changing ref to CompletableFuture to Promise [sclasen]
| * | | | 2e2d1eb 2011-05-23 | second pass, mostly compiles [sclasen]
| * | | | 5e8f844 2011-05-23 | first pass at moving modules over, sbt project compiles properly [sclasen]
| | |_|/  
| |/| |   
* | | |   e1e89ce 2011-05-23 | Merge branch 'master' into promisestream [Derek Williams]
|\ \ \ \  
| | |/ /  
| |/| |   
| * | | 7f455fd 2011-05-23 | removed duplicated NodeAddress [Jonas Bonér]
| * | |   96367d9 2011-05-23 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * | | 6941340 2011-05-23 | - fixed the type problems. This time with a compile (since no tests currently are available for this code). [Peter Veentjer]
| | * | | e84a7cb 2011-05-23 | - disabled the functionality for the rawstorage, will inspect it locally. But at least the build will be running again. [Peter Veentjer]
| | * | |   d25ca82 2011-05-23 | Merge remote branch 'origin/master' [Peter Veentjer]
| | |\ \ \  
| | | * | | 556ee4b 2011-05-23 | Added a default configuration object to avoid object allocation for the trivial case [Viktor Klang]
| | | * | | 39481f0 2011-05-23 | Adding a test to verify usage of TypedActor.self outside of a TypedActor [Viktor Klang]
| | | * | | e320825 2011-05-23 | Added some API to be able to wrap interfaces on top of Actors, solving the ActorPool for TypedActor dilemma, closing ticket #724 [Viktor Klang]
| | | |/ /  
| | | * | 1f5a04c 2011-05-23 | Adding support for customization of the TypedActor impl to be used when creating a new TypedActor, internal only, intended for things like ActorPool etc [Viktor Klang]
| | * | | 27e9d71 2011-05-23 | - initial checkin of the storage functionality [Peter Veentjer]
| | |/ /  
| | * | 19cf26b 2011-05-23 | Rewriting one of the tests in ActorRegistrySpec not to use non-volatile global state for verification [Viktor Klang]
| | * | 3b8c395 2011-05-23 | Adding assertions to ensure that the registry doesnt include the actor after stop [Viktor Klang]
| | * | cf0970d 2011-05-23 | Removing duplicate code for TypedActor [Viktor Klang]
| | * | 8a790b1 2011-05-23 | Renaming CompletableFuture to Promise, Renaming AlreadyCompletedFuture to KeptPromise, closing ticket #854 [Viktor Klang]
| | * | aa52486 2011-05-23 | Fixing erronous use of actor uuid as string in ActorRegistry, closing ticket #886 [Viktor Klang]
| * | | ddb2a69 2011-05-23 | Moved ClusterNode interface, NodeAddress and ChangeListener into akka-actor as real Trait instead of using structural typing. Refactored boot dependency in Cluster/Actor/Deployer. Added multi-jvm test for testing clustered actor deployment, check out as LocalActorRef and ClusterActorRef. [Jonas Bonér]
| |/ /  
| * | 7778c93 2011-05-23 | Added docs about setting JVM options and override akka.conf options to multi-jvm-testing.rst [Jonas Bonér]
| * | ef1bb9c 2011-05-23 | removed ./docs [Jonas Bonér]
| * | ce69b25 2011-05-23 | Add individual options and config to multi-jvm tests [Peter Vlugter]
| * | d84a169 2011-05-21 | Removing excessive allocations and traversal [Viktor Klang]
| * | 00f8374 2011-05-21 | Reformatting and some cleanup of the Cluster.scala code [Viktor Klang]
| * | 2f87da5 2011-05-21 | Removing some boilerplate code in Deployer [Viktor Klang]
| * | e5035be 2011-05-21 | Tidying up some code in ClusteredActorRef [Viktor Klang]
| * | 5f03cc5 2011-05-21 | Switching to a non-blocking strategy for the CyclicIterator and the RoundRobin router [Viktor Klang]
| * | e735b33 2011-05-21 | Removing lots of duplicated code [Viktor Klang]
| * | 70137b4 2011-05-21 | Fixing a race in CyclicIterator [Viktor Klang]
| * | 50bf97b 2011-05-21 | Removing allocations and excessive traversals [Viktor Klang]
| * | 3181905 2011-05-20 | Renaming EBEDD to Dispatcher, EBEDWSD to BalancingDispatcher, ThreadBasedDispatcher to PinnedDispatcher and PEBEDD to PriorityDispatcher, closing ticket #784 [Viktor Klang]
| * | 1f024f1 2011-05-20 | Harmonizing constructors and Dispatcher-factories, closing ticket #807 [Viktor Klang]
| * |   476334b 2011-05-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | |/  
| | * cd18e72 2011-05-20 | Fixed issues with 'ClusterNode.use(address): ActorRef'. Various other fixes and minor additions. [Jonas Bonér]
| | * 763dfff 2011-05-20 | Changed creating ClusterDeployer ZK client on object creation cime rather than on 'init' method time [Jonas Bonér]
| * | 41a0823 2011-05-20 | Moved secure cookie exchange to on connect established, this means I could remove the synchronization on send, enabling muuuch more throughput, also, since the cookie isn`t sent in each message, message size should drop considerably when secure cookie handshakes are enabled. I do however have no way of testing this since it seems like the clustering stuff is totally not working when it comes to the RemoteSupport [Viktor Klang]
| |/  
| * b9a1d49 2011-05-20 | Fixed problems with trying to boot up cluster through accessing Actor.remote when it should not [Jonas Bonér]
| *   19f6e6a 2011-05-20 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\  
| | * 64c7107 2011-05-20 | Replacing hook + provider with just a PartialFunction[String,ActorRef], closing ticket #749 [Viktor Klang]
| | * 2f62d30 2011-05-20 | Fixing import shadowing of TransactionLog [Viktor Klang]
| | * 5a9be1b 2011-05-20 | Adding the migration guide from 0.10 to 1.0 closing ticket #871 [Viktor Klang]
| | * cd3cb8c 2011-05-20 | Renaming akka.routing.Dispatcher to Router, as per ticket #729 [Viktor Klang]
| | * f9a335e 2011-05-20 | Adding support for obtaining the reference to the proxy of the currently executing TypedActor, this is suitable for passing on a safe alternative to _this_ [Viktor Klang]
| * | f0be165 2011-05-20 | Refactored and changed boot of Cluster and ClusterDeployer. Fixed problems with ClusterDeployerSpec and ClusterMultiJvmSpec. Removed all akka-remote tests and samples (needs to be rewritten later). Added Actor.cluster member field. Removed Actor.remote member field. [Jonas Bonér]
| |/  
| *   b95382c 2011-05-20 | Merge branch 'wip-new-serialization' [Jonas Bonér]
| |\  
| | * b63709d 2011-05-20 | Removed typeclass serialization in favor of reflection-based. Removed possibility to create multiple ClusterNode, now just one in Cluster.node. Changed timestamp format for default EventHandler listener to display NANOS. Cleaned up ClusterModule in ReflectiveAccess. [Jonas Bonér]
* | | e4b96b1 2011-05-19 | Refactor for improved clarity and performance. 'Either' already uses pattern matching internally for all of it's methods, and the 'right' and 'left' methods allocate an 'Option' which is now avoided. [Derek Williams]
* | |   3008aa7 2011-05-19 | Merge branch 'master' into promisestream [Derek Williams]
|\ \ \  
| |/ /  
| * | 4809b63 2011-05-19 | fix bad move of CallingThreadDispatcherModelSpec [Roland]
* | | 5b99014 2011-05-19 | Refactor to avoid allocations [Derek Williams]
* | | 634d26a 2011-05-19 | Add PromiseStream [Derek Williams]
* | | 8058a55 2011-05-19 | Specialize monadic methods for AlreadyCompletedFuture, fixes #853 [Derek Williams]
|/ /  
* | a48f6fd 2011-05-19 | add copyright headers [Roland]
* | 7955912 2011-05-19 | Reverting use of esoteric character for field [Viktor Klang]
* | e3daf11 2011-05-19 | Removed some more boilerplate [Viktor Klang]
* | 1e5e46c 2011-05-19 | Cleaned up the TypedActor coe some more [Viktor Klang]
* | 23614fe 2011-05-19 | Removing some boilerplate [Viktor Klang]
* | d3e85f0 2011-05-19 | Implementing the typedActor-methods in ActorRegistry AND added support for multi-interface proxying [Viktor Klang]
* | 59025e5 2011-05-19 | Merge with master [Viktor Klang]
* |   c49498f 2011-05-19 | Merge with master [Viktor Klang]
|\ \  
| * | 8894688 2011-05-19 | Fixed wrong import in multi-jvm-test.rst. Plus added info about where the trait resides. [Jonas Bonér]
| |/  
| * 76d9c3c 2011-05-19 | 1. Added docs on how to run the multi-jvm tests 2. Fixed cyclic dependency in deployer/cluster boot up 3. Refactored actorOf for clustered actor deployment, all actorOf now works [Jonas Bonér]
* | 236d8e0 2011-05-19 | Removing the old typed actors [Viktor Klang]
|/  
* 8741454 2011-05-18 | Added copyright header [Jonas Bonér]
*   08ee482 2011-05-18 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\  
| * 34f4fd7 2011-05-18 | Turned of verbose mode of formatting [Jonas Bonér]
* |   c29ef4b 2011-05-18 | Merge with master [Viktor Klang]
|\ \  
| |/  
| *   f7ff547 2011-05-18 | merged with scalariform branch [Jonas Bonér]
| |\  
| | * 4deeb77 2011-05-18 | converted tabs to spaces [Jonas Bonér]
| | * a7311c8 2011-05-18 | Added Scalariform sbt plugin which formats code on each compile. Also checking in reformatted code [Jonas Bonér]
| | * 5949673 2011-05-18 | using Config.nodename [Jonas Bonér]
* | | 6461de7 2011-05-18 | Removed the not implemented transactor code and removed duplicate code [Viktor Klang]
|/ /  
* | 8f28125 2011-05-18 | Normalizing the constructors, mixing manifests and java api wasn`t ideal [Viktor Klang]
* | 07acfb4 2011-05-18 | Making the MethodCall:s serializable so that they can be stored in durable mailboxes, and can be sent to remote nodes [Viktor Klang]
* | 404118c 2011-05-18 | Adding tests and support for stacked traits for the interface part of a ThaipedActor [Viktor Klang]
* | fccfb55 2011-05-18 | Adding support and tests for exceptions thrown inside a ThaipedActor [Viktor Klang]
|/  
*   d4d2807 2011-05-18 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\  
| * 1ac7b47 2011-05-18 | Added a future-composition test to ThaipedActor [Viktor Klang]
* |   0f88dd9 2011-05-18 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \  
| |/  
| *   cd7538e 2011-05-18 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | * 8f7bd69 2011-05-18 | - more style related cleanup [Peter Veentjer]
| * | 1f05440 2011-05-18 | ThaipedActor seems to come along nicely [Viktor Klang]
| |/  
| * e52c24f 2011-05-18 | Adding warning of using WorkStealingDispatcher with TypedActors [Viktor Klang]
| *   858c107 2011-05-18 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| * | c312fb7 2011-05-18 | ThaipedActor is alive [Viktor Klang]
* | | 2af5097 2011-05-18 | Added command line options for setting 'nodename', 'hostname' and 'remote-server-port' when booting up an Akka node/microkernel [Jonas Bonér]
* | | 4a99082 2011-05-18 | Added deployment code for all 'actorOf' methods [Jonas Bonér]
| |/  
|/|   
* | 263f441 2011-05-18 | fixed typo in docs [Jonas Bonér]
* | e7d1eaf 2011-05-18 | - more style related cleanup [Peter Veentjer]
* | 61fb04a 2011-05-18 | Move embedded-repo jars to akka.io [Peter Vlugter]
* | d1ddb8e 2011-05-18 | Getting API generation back on track [Peter Vlugter]
* | b1122c1 2011-05-18 | Bump docs version to 2.0-SNAPSHOT [Peter Vlugter]
* | ca7aea9 2011-05-18 | Bump version to 2.0-SNAPSHOT [Peter Vlugter]
* | 53038ca 2011-05-18 | Fix docs [Peter Vlugter]
|/  
* 3a255ef 2011-05-17 | Fixing typos [Viktor Klang]
*   2fd1f76 2011-05-17 | Merge branch 'master' into thaiped [Viktor Klang]
|\  
| * 2630a54 2011-05-17 | Commenting out the 855 spec since it`s not really applicable right now [Viktor Klang]
| * d92f699 2011-05-17 | moved some classes so package and directory match, and s some other other stuff like style issues to remove a lot of yellow and red bars in IntelliJ [alarmnummer]
| * cfea06c 2011-05-17 | Added consistent hashing abstraction class [Jonas Bonér]
| *   d5a912e 2011-05-17 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\  
| * | 962ee1e 2011-05-17 | Added docs for durable mailboxes, plus filtered out replication tests [Jonas Bonér]
| * | 160ff86 2011-05-17 | Added durable mailboxes: File-based, Redis-based, Zookeeper-based and Beanstalk-based [Jonas Bonér]
* | | 7435fe7 2011-05-17 | Playing around w new typed actor impl [Viktor Klang]
| |/  
|/|   
* |   61723ff 2011-05-17 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \  
| |/  
| *   5d88ffe 2011-05-17 | Merge branch 'wip-2.0' [Jonas Bonér]
| |\  
| | * 7cf3d08 2011-05-17 | Added tests for usage of cluster actor plus code backing the test. Added more cluster API to ReflectiveAccess. Plus misc refactorings and cleanup. [Jonas Bonér]
* | | 49660a2 2011-05-17 | Removing the use of the local maven repo, and added the jsr31-api ModuleConfig, hopefully this will improve reliability of updates [Viktor Klang]
|/ /  
* | a45419e 2011-05-17 | Adding the needed repos for the clustering and jmx stuff [Viktor Klang]
* | 4456731 2011-05-17 | Deactivating the 855 spec since the remoting is going to get changed [Viktor Klang]
* | e2042d0 2011-05-17 | Resolve merge conflict in cherry-pick [Viktor Klang]
* | 9b512e9 2011-05-17 | Resolve merge conflict [Viktor Klang]
* | e6f2870 2011-05-16 | add some tests for Duration [Roland]
|/  
* 1396e2d 2011-05-16 | Commented away failing remoting tests [Jonas Bonér]
* 70bbeba 2011-05-16 | Added MultiJvmTests trait [Jonas Bonér]
*   2655d44 2011-05-16 | Merged wip-2.0 branch with latest master [Jonas Bonér]
|\  
| * 62427f5 2011-05-16 | Added dataflow article [Jonas Bonér]
| * 5f51b72 2011-05-16 | Changed Scalable Solutions to Typesafe [Patrik Nordwall]
| * 08f663c 2011-05-15 | - removed unused imports [alarmnummer]
| * 04541c3 2011-05-15 | add summary of system properties to configuration.rst [Roland]
| * f53abcf 2011-05-13 | Rewriting TypedActor.future to be more clean [Viktor Klang]
| * bab4429 2011-05-13 | Removing weird text about remote actors, closing ticket #851 [Viktor Klang]
| * fd26f28 2011-05-13 | Update to scala 2.9.0 and sbt 0.7.7 [Peter Vlugter]
| * 53cf507 2011-05-12 | Reusing `akka.output.config.source` system property to output default values when set to non-null value, closing ticket #573 [Viktor Klang]
| * d3e29e8 2011-05-12 | Silencing the output of the source of the configuration, can be re-endabled through setting system property `akka.output.config.source` to anything but null, closing ticket #848 [Viktor Klang]
| * 62209d0 2011-05-12 | Removing the use of currentTimeMillis for the restart logic, closing ticket #845 [Viktor Klang]
| * 5b54de7 2011-05-11 | Changing signature of traverse and sequence to return java.lang.Iterable, updated docs as well, closing #847 [Viktor Klang]
| * 3ab4cab 2011-05-11 | Updating Jackson to 1.8.0 - closing ticket #839 [Viktor Klang]
| * 3184a70 2011-05-11 | Updating Netty to 3.2.4, closing ticket #838 [Viktor Klang]
| * 86414fe 2011-05-11 | Exclude all dist projects from publishing [Peter Vlugter]
| * ae9a13d 2011-05-11 | Update docs version to 1.2-SNAPSHOT [Peter Vlugter]
| * c2e3d94 2011-05-11 | Fix nightly build by not publishing tutorials [Peter Vlugter]
| * c70250b 2011-05-11 | Some updates to docs [Peter Vlugter]
| * 54b31d0 2011-05-11 | Update pdf links [Peter Vlugter]
| * 5350d4e 2011-05-11 | Adjust docs html header [Peter Vlugter]
| * 650f9be 2011-05-10 | Added PDF link at top banner (cherry picked from commit 655f9051fcb144c29ebf0e993047dc3010547948) [Patrik Nordwall]
| * bc38831 2011-05-10 | Docs: Added links to PDF, removed links to old versions (cherry picked from commit f0b3b8bbb39353ac582fb569624aeba414708d3a) [Patrik Nordwall]
| * c0efbc6 2011-05-10 | Adding some tests to make sure that the Future Java API doesn`t change breakingly [Viktor Klang]
| * 0896807 2011-05-10 | Adding docs for Futures.traverse and fixing imports [Viktor Klang]
| *   daee27c 2011-05-10 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | * 1b58247 2011-05-10 | Docs: fixed missing ref (cherry picked from commit 210261e70e270d37c939fa3e551b598d87164faa) [Patrik Nordwall]
| | * 76b4592 2011-05-10 | Docs: Added other-doc, links to documentation for other versions (cherry picked from commit d2b634ee085bd7ff69c27626390ae944f71eeaa9) [Patrik Nordwall]
| * | f3b6e53 2011-05-10 | Adding future docs for fold and reduce for both Java and Scala API [Viktor Klang]
| |/  
| * 207a374 2011-05-10 | Adding Future docs for Java API and fixing warning for routing.rst [Viktor Klang]
| * a3499bc 2011-05-10 | Docs: Some minor corrections (cherry picked from commit 52a0b2c6b89b4887f84f052dd85c458a8f4fb68a) [Patrik Nordwall]
| * fe85ae1 2011-05-10 | Docs: Guice Integration in two places, removed from typed-actors (cherry picked from commit 5b1a610c57e42aeb28a6a1d9c3eb922e1871d334) [Patrik Nordwall]
| * 789475a 2011-05-10 | Docs: fixed wrong heading [Patrik Nordwall]
| *   bfd3f22 2011-05-10 | merge [Patrik Nordwall]
| |\  
| | * aa706a4 2011-05-10 | Update tutorial sbt defs to match main project [Peter Vlugter]
| | * 5856860 2011-05-10 | Rework modular dist and include in release build [Peter Vlugter]
| | * 8dec4bc 2011-05-10 | Update tutorials and include source in the distribution [Peter Vlugter]
| | * c581e4b 2011-05-10 | Update loader banner and version [Peter Vlugter]
| | * 5e63ebc 2011-05-10 | Fix new api task [Peter Vlugter]
| | * a769fb3 2011-05-10 | Update header in html docs [Peter Vlugter]
| | * a6f8a9f 2011-05-10 | Include docs and api in release process [Peter Vlugter]
| | * 867dc0f 2011-05-10 | Change the automatic release branch to avoid conflicts [Peter Vlugter]
| | * b30a164 2011-05-09 | Removing logging.rst and servlet.rst and moving the guice-integration.rst into the Java section [Viktor Klang]
| | * b72b455 2011-05-09 | Removing unused imports [Viktor Klang]
| | * 48f2cee 2011-05-09 | Adding some docs to AllForOne and OneForOne [Viktor Klang]
| | * 692820e 2011-05-09 | Fixing #846 [Viktor Klang]
| | * 1e87f13 2011-05-09 | Don't deliver akka dist project [Peter Vlugter]
| | * 44fb8bf 2011-05-09 | Rework dist yet again [Peter Vlugter]
| | * b94b91c 2011-05-09 | Update gfx [Viktor Klang]
| * | 1051c07 2011-05-10 | Docs: fixed broken links [Patrik Nordwall]
| * | b455dff 2011-05-10 | Docs: Removed pending [Patrik Nordwall]
| |/  
| * 5b08cf5 2011-05-09 | Removed jta section from akka-reference.conf (cherry picked from commit 3518f4ffbbdda8ddae26e394604f7e0d22324a38) [Patrik Nordwall]
| * ca77eb0 2011-05-09 | Docs: Removed JTA (cherry picked from commit d92d25b15894e34c0df0715886206fefec862d17) [Patrik Nordwall]
| * 1b54d01 2011-05-08 | clarify time measurement in testkit [Roland]
| * 20be7c4 2011-05-08 | Minor changes to docs + added new akka logo [Jonas Bonér]
| * 73fd077 2011-05-08 | Docs: Various improvements (cherry picked from commit aad2c29c68a239b8f2512abc5e93d58ca4354a49) [Patrik Nordwall]
| * 0b010b6 2011-05-08 | Docs: Moved slf4j from pending (cherry picked from commit ff4107f8e0531b88db317021eeb56d636ba80bad) [Patrik Nordwall]
| * 48f3229 2011-05-08 | Docs: Moved security from pending (cherry picked from commit efcc7325d0b4063dc225f84535ecf1cacf4fd4ee) [Patrik Nordwall]
| * a0f5cbf 2011-05-08 | Docs: Fix of sample in 'Serializer API using reflection' (cherry picked from commit dfc2ba15efe49ee81aecf3b64e5291aa1ec487c0) [Patrik Nordwall]
| * e1c5e2b 2011-05-08 | Docs: Re-write of Getting Started page (cherry picked from commit 55ef6998dd473f759803e9d6dc862af80b1cfceb) [Patrik Nordwall]
| * 66ec36b 2011-05-08 | Docs: Moved getting-started from pending (cherry picked from commit d3f83b2bed989885f318f3d044668a4fac721a3d) [Patrik Nordwall]
| * 9c02cd1 2011-05-06 | Docs: Converted release notes (cherry picked from commit 0df737e1c7484047616112af8b6daab44d557e73) [Patrik Nordwall]
| * 18b58d9 2011-05-06 | Docs: Cleanup of routing (cherry picked from commit 744d4c2dd626913898ed1456bb5fc287236e83a2) [Patrik Nordwall]
| * 97105ba 2011-05-06 | Docs: Moved routing from pending (cherry picked from commit d60de88117e18fc8cadb6b845351c4ad95f5dd43) [Patrik Nordwall]
| * 0019015 2011-05-06 | Docs: Fixed third-party-integrations (cherry picked from commit 560296035172737efac428fbfb8f2d4e1e5e8cea) [Patrik Nordwall]
| * 90e0456 2011-05-06 | Docs: Fixed third-party-integrations (cherry picked from commit 720545de2b5ca0c47ae98428f3803109b1ff31c8) [Patrik Nordwall]
| * ed97e07 2011-05-06 | Docs: Fixed benchmarks (cherry picked from commit 9099a6ae18f7f3a6edf0bead6f095e74c12030db) [Patrik Nordwall]
| * 3a0858a 2011-05-06 | Docs: fixed stability matrix (cherry picked from commit 6ef88eae3b96278e25ef386b657af82d9a1ec28f) [Patrik Nordwall]
| * ce0aa39 2011-05-06 | Docs: Release notes is totally broken (cherry picked from commit 72ba6a69f034f6b7c15680274c6507c936b797e4) [Patrik Nordwall]
| * a597915 2011-05-06 | Docs: Restructured toc (cherry picked from commit b6ea22e994ab900dad2661861cd90a7ab969ceb4) [Patrik Nordwall]
| * 2639344 2011-05-06 | Docs: Moved from pending (cherry picked from commit c10d94439dcca4bb9f08be2bc2f92442bb7b3cd4) [Patrik Nordwall]
| * e3189ab 2011-05-06 | Docs: Added some missing links (cherry picked from commit a70b7c027fba7f24ff1b7496cf8087bc2e9d5038) [Patrik Nordwall]
| * 897884b 2011-05-05 | method dispatch (cherry picked from commit 1494a32cc8621c7cd53f1d6ed54d4da99b543bc1) [Patrik Nordwall]
| * 6000b05 2011-05-06 | Add released API links [Peter Vlugter]
| * 9fa6c32 2011-05-06 | Add links to snapshot scaladoc [Peter Vlugter]
| * 9ace0a9 2011-05-06 | Bump version to 1.2-SNAPSHOT [Peter Vlugter]
| * f01db73 2011-05-06 | Fix up the migration guide [Peter Vlugter]
| * 7f9dd2d 2011-05-06 | Fix scaladoc errors [Peter Vlugter]
| * 6f17d2c 2011-05-06 | Fix @deprecated warnings [Peter Vlugter]
| * 60484e2 2011-05-06 | Update find-replace script [Peter Vlugter]
| * 803b765 2011-05-06 | Add sh action to parent project [Peter Vlugter]
| * 289733a 2011-05-05 | clarify messages in TestActorRef example, fixes #840 [Roland]
| * ad41906 2011-05-05 | add import statements to FSM docs [Roland]
| *   02d8a51 2011-05-05 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | * 8303c45 2011-05-05 | Don't git ignore project/build [Peter Vlugter]
| | * 0ac0a18 2011-05-05 | Generate combined scaladoc across subprojects [Peter Vlugter]
| | * aad5f67 2011-05-05 | Make akka parent project an actual parent project [Peter Vlugter]
| | * dafd04a 2011-05-05 | Update to scala RC3 [Peter Vlugter]
| | * c46c4cc 2011-05-04 | Fixed toRemoteActorRefProtocol [Patrik Nordwall]
| * | aaa8b16 2011-05-04 | Adding tests for the actor ref serialization bug, 837 [Viktor Klang]
| |/  
| *   e30f85a 2011-05-04 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | * f930a72 2011-05-04 | updated sjson ver to RC3 [Debasish Ghosh]
| * | 276f30d 2011-05-04 | Fixing ticket #837, broken remote actor refs when serialized [Viktor Klang]
| |/  
| * d8add4c 2011-05-04 | Fixing Akka boot config version printing [Viktor Klang]
| * e6f58c7 2011-05-04 | Fixing use-cases documentation in reST [Viktor Klang]
| * 696b7bc 2011-05-04 | Added explicit sjson.json.Serializer.SJSON [Patrik Nordwall]
| *   01af69f 2011-05-03 | Merge branch 'master' of github.com:jboner/akka [Derek Williams]
| |\  
| | * 10637dc 2011-05-04 | Update release scripts [Peter Vlugter]
| * | 753c3bb 2011-05-03 | Remove hardcoded Scala version for continuations plugin, fixes #834 [Derek Williams]
| |/  
| * d451381 2011-05-03 | Dataflow examples all migrated to new api, all tested to work in sbt console [Derek Williams]
| * d0b27c4 2011-05-03 | Use Promise in tests [Derek Williams]
| * 8752eb5 2011-05-03 | Add Promise factory object for creating new CompletableFutures [Derek Williams]
| * 5b18f18 2011-05-03 | Fix bug with 'Future << x', must be used within a 'flow' block now [Derek Williams]
| * 7afe35c 2011-05-03 | Begin updating Dataflow docs [Derek Williams]
| * 85bf38d 2011-05-03 | Enable continuations plugin within sbt console [Derek Williams]
| * 13e98f0 2011-05-03 | Add exception handling section [Derek Williams]
| * c0cecfc 2011-05-03 | Bring Future docs up to date [Derek Williams]
| * 131890f 2011-05-03 | Cleanup [Patrik Nordwall]
| * 4f4af3c 2011-05-03 | Moved dataflow from pending [Patrik Nordwall]
| * 0e8e4ef 2011-05-03 | Fixed deployment-scenarios [Patrik Nordwall]
| * ea23b0f 2011-05-03 | Moved deployment-scenarios from pending [Patrik Nordwall]
| * 625f844 2011-05-03 | Moved futures from pending [Patrik Nordwall]
| * 854614d 2011-05-03 | Fixed what-is-akka [Patrik Nordwall]
| * 65eb70c 2011-05-03 | Cleanup of fault-tolerance [Patrik Nordwall]
| * cf17b77 2011-05-03 | Moved fault-tolerance from pending [Patrik Nordwall]
| *   1b024e6 2011-05-03 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | * d805c81 2011-05-03 | Typos and clarification for futures. [Eugene Vigdorchik]
| | *   98816b1 2011-05-03 | Merge branch 'master' of http://github.com/jboner/akka [Eugene Vigdorchik]
| | |\  
| | * | bd54c9b 2011-04-26 | A typo and small clarification to actor-registry-java documentation. [Eugene Vigdorchik]
| * | | 417acee 2011-05-03 | Adding implicit conversion for: (actor !!! msg).as[X] [Viktor Klang]
| * | | c81748c 2011-05-03 | Removing the intermediate InvocationTargetException and harmonizing creation of Actor instances [Viktor Klang]
| | |/  
| |/|   
| * | d97e0c1 2011-05-03 | fix import in testkit example; fixes #833 [Roland]
| * |   23d85e4 2011-05-02 | Merge branch 'master' of github.com:jboner/akka [Derek Williams]
| |\ \  
| | * | 6c0d5c7 2011-05-03 | add import statements to testing.rst [Roland]
| | * | d175ef4 2011-05-02 | fix pygments dependency and add forgotten common/duration.rst [Roland]
| * | | cb2d607 2011-05-02 | remove extra allocations and fix scaladoc type inference problem [Derek Williams]
| |/ /  
| * | 67f1e2f 2011-05-02 | Fix Future.flow compile time type safety [Derek Williams]
| * | 859b61d 2011-05-02 | move Duration docs below common/ next to Scheduler [Roland Kuhn]
| * | ece7657 2011-05-02 | move FSM._ import into class, fixes #831 [Roland Kuhn]
| * | dadc572 2011-05-02 | no need to install pygments on every single run [Roland Kuhn]
| * | 4eddce0 2011-05-02 | Porting licenses.rst and removing boldness in sponsors title [Viktor Klang]
| * | 0d476c2 2011-05-02 | Converting team.rst, sponsors.rst and fixing some details in dev-guides [Viktor Klang]
| * | 05db33e 2011-05-02 | Moving Developer guidelines to the Dev section [Viktor Klang]
| * | 25a56ef 2011-05-02 | Converting the issue-tracking doc and putting it under General [Viktor Klang]
| * | a97bdda 2011-05-02 | Converting the Akka Developer Guidelines and add then to the General section [Viktor Klang]
| * | c978ba1 2011-05-02 | Scrapping parts of Home.rst and add a what-is-akka.rst under intro [Viktor Klang]
| * | 15c2e10 2011-05-02 | Adding a Common section to the docs and fixing the Scheduler docs [Viktor Klang]
| * | 4127356 2011-05-02 | Removing web.rst because of being severely outdated [Viktor Klang]
| * | 03943cd 2011-05-02 | Fixing typos in scaladoc [Viktor Klang]
| * | 9bb1840 2011-05-02 | Fixing ticket #824 [Viktor Klang]
| * |   fc0a9b4 2011-05-02 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * \   5838583 2011-05-02 | Merge branch 'master' of github.com:jboner/akka [Derek Williams]
| | |\ \  
| | | * \   47a8298 2011-05-02 | Merge branch 'master' of https://github.com/jboner/akka [alarmnummer]
| | | |\ \  
| | | | * | 2a26a70 2011-05-02 | Reviewed and improved serialization docs, still error with in/out, waiting for answer from Debasish [Patrik Nordwall]
| | | * | | de7741a 2011-05-02 | added jmm documentation for actors and stm [alarmnummer]
| | | |/ /  
| | | * | 3b3f8d3 2011-05-01 | fixed typo [Patrik Nordwall]
| | | * | 56acccf 2011-05-01 | Added installation instructions for Sphinx etc [Patrik Nordwall]
| | * | | 2d2bdee 2011-05-02 | Will always infer type as Any, so should explicitly state it. [Derek Williams]
| | |/ /  
| | * | c744419 2011-05-01 | Ticket 739. Beefing up config documentation. [Patrik Nordwall]
| | * | e4e53af 2011-04-30 | Fix Scaladoc generation failure [Derek Williams]
| | * |   846d63a 2011-04-30 | Merge branch 'master' into delimited-continuations [Derek Williams]
| | |\ \  
| | * \ \   233310b 2011-04-26 | Merge with upstream [Viktor Klang]
| | |\ \ \  
| | | * | | d567a08 2011-04-26 | Added a test to validate the API, it´s gorgeous [Viktor Klang]
| | * | | | 0fbf8d3 2011-04-26 | Added a test to validate the API, it´s gorgeous [Viktor Klang]
| | |/ / /  
| | * | | 0b5ab21 2011-04-26 | Adding yet another CPS test [Viktor Klang]
| | * | | 25f2824 2011-04-26 | Adding a test for the emulation of blocking [Viktor Klang]
| | * | | 2432101 2011-04-26 | Fixing docs for Future.get [Viktor Klang]
| | * | | 74fcef3 2011-04-25 | Add Future.failure [Derek Williams]
| | * | | da8e506 2011-04-25 | Fix failing tests [Derek Williams]
| | * | | 997151e 2011-04-24 | Improve pattern matching within for comprehensions with Future [Derek Williams]
| | * | | 7613d8e 2011-04-24 | Fix continuation dependency. Building from clean project was causing errors [Derek Williams]
| | * | | e74aa8f 2011-04-23 | Add documentation to Future.flow and Future.apply [Derek Williams]
| | * | | 2c9a813 2011-04-23 | Refactor Future.flow [Derek Williams]
| | * | | 5dfc416 2011-04-23 | make test more aggressive [Derek Williams]
| | * | | 530be7b 2011-04-23 | Fix CompletableFuture.<<(other: Future) to return a Future instead of the result [Derek Williams]
| | * | | b692a8c 2011-04-23 | Use simpler annotation [Derek Williams]
| | * | | 62c3419 2011-04-23 | Remove redundant Future [Derek Williams]
| | * | | eecfea5 2011-04-23 | Add additional test to make sure Future.flow does not block on long running Futures [Derek Williams]
| | * | | 120f12d 2011-04-21 | Adding delimited continuations to Future [Derek Williams]
| * | | | 769078e 2011-05-02 | Added alter and alterOff to Agent, #758 [Viktor Klang]
| * | | | 39519af 2011-05-02 | Removing the CONFIG val in BootableActorLoaderService to fix #825 [Viktor Klang]
| | |/ /  
| |/| |   
| * | | 08049c5 2011-04-30 | Rewriting matches to use case-class extractors [Viktor Klang]
| * | |   535b552 2011-04-30 | Merge branch 'ticket-808' [Viktor Klang]
| |\ \ \  
| | * | | 20c5be2 2011-04-30 | fix exception logging [Roland Kuhn]
| | * | | 8d95f18 2011-04-29 | also adapt createInstance(String, ...) and getObjectFor [Roland Kuhn]
| * | | |   3da926a 2011-04-30 | Merge branch 'ticket-808' [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| | * | | c2486cd 2011-04-29 | Fixing ticket 808 [Viktor Klang]
| * | | |   1970976 2011-04-29 | Merge branch 'master' of github.com:jboner/akka [Patrik Nordwall]
| |\ \ \ \  
| | |/ / /  
| | * | | b5873ff 2011-04-29 | Improving throughput for WorkStealer even more [Viktor Klang]
| | * | | d89c286 2011-04-29 | Switching from DynamicVariable to ThreadLocal to avoid child threads inheriting the current value [Viktor Klang]
| | * | | d69baf7 2011-04-29 | Reverting to ThreadLocal [Viktor Klang]
| | * | |   e428382 2011-04-29 | Merge branch 'future-stackoverflow' [Viktor Klang]
| | |\ \ \  
| | | * | | 1fb228c 2011-04-29 | Reducing object creation overhead [Viktor Klang]
| | | * | | 2bfa5e5 2011-04-28 | Add @tailrec check [Derek Williams]
| | | * | | ae481fc 2011-04-28 | Avoid unneeded allocations [Derek Williams]
| | | * | | f6e142a 2011-04-28 | prevent chain of callbacks from overflowing the stack [Derek Williams]
| | * | | | e4e99ef 2011-04-29 | Reenabling the on-send-redistribution of messages in WorkStealer [Viktor Klang]
| | * | | | 36535d5 2011-04-29 | Added instructions on how to check out the tutorial code using git [Jonas Bonér]
| | * | | | c240634 2011-04-29 | Added instructions to checkout tutorial with git [Jonas Bonér]
| * | | | | 1c29885 2011-04-29 | Reviewed and improved remote-actors doc [Patrik Nordwall]
| * | | | | 888af34 2011-04-29 | Cleanup of serialization docs [Patrik Nordwall]
| * | | | | 3366dd5 2011-04-29 | Moved serialization from pending [Patrik Nordwall]
| |/ / / /  
| * | | | 5e3f8d3 2011-04-29 | Failing test due to timeout, decreased number of messages [Patrik Nordwall]
| * | | | 6576cd5 2011-04-29 | Scala style fixes, added parens for side effecting shutdown methods [Patrik Nordwall]
| * | | | cf49478 2011-04-29 | Scala style fixes, added parens for side effecting shutdown methods [Patrik Nordwall]
| * | | | cdf9da1 2011-04-29 | Cleanup [Patrik Nordwall]
| * | | | 52e7d07 2011-04-29 | Cleanup [Patrik Nordwall]
| * | | | 2cec337 2011-04-29 | Moved remote-actors from pending [Patrik Nordwall]
| * | | | c2f810e 2011-04-29 | Fixed typo [Patrik Nordwall]
| |/ / /  
| * | | 2451d4a 2011-04-28 | Added instructions for SBT project and IDE [Patrik Nordwall]
| * | | 390176b 2011-04-28 | Added sbt reload before initial update [Patrik Nordwall]
| * | | 241a21a 2011-04-28 | Cross linking [Patrik Nordwall]
| * | | 9a582b7 2011-04-28 | Removing redundant isOff call [Viktor Klang]
| * | | 7d5bc13 2011-04-28 | Removing uses of awaitBlocking in the FutureSpec [Viktor Klang]
| * | | baa1298 2011-04-28 | Fixing ticket #813 [Viktor Klang]
| * | |   e4a5fe9 2011-04-28 | Merge branch 'ticket-812' [Viktor Klang]
| |\ \ \  
| | * | | 4bedb48 2011-04-28 | Fixing tickets #816, #814, #817 and Dereks fixes on #812 [Viktor Klang]
| | * | | c61f1a4 2011-04-28 | make sure lock is aquired when accessing shutdownSchedule [Derek Williams]
| | * | | 485013a 2011-04-27 | Dispatcher executed Future will be cleaned up even after expiring [Derek Williams]
| | * | | 43fc3bf 2011-04-27 | Add failing test for Ticket #812 [Derek Williams]
| * | | | 65e553a 2011-04-28 | Added sample to Transactional Agents [Patrik Nordwall]
| |/ / /  
| * | | 43ebe61 2011-04-27 | Reviewed and improved transactors doc [Patrik Nordwall]
| * | | 9fadbc4 2011-04-27 | Moved transactors from pending [Patrik Nordwall]
| * | | 7224abd 2011-04-27 | Fixing the docs for the Actor Pool with regards to the factory vs instance question and closing ticket #744 [Viktor Klang]
| * | | 8008407 2011-04-27 | Making it impossible to complete a future after it`s expired, and not run onComplete callbacks if it hasn`t been completed before expiry, fixing ticket #811 [Viktor Klang]
| * | | 2da2712 2011-04-27 | Renaming a test [Viktor Klang]
| * | | 82a1111 2011-04-27 | Removing awaitValue and valueWithin, and adding await(atMost: Duration) [Viktor Klang]
| * | | 71a7a92 2011-04-27 | Removing the Client Managed Remote Actor sample from the docs and akka-sample-remote, fixing #804 [Viktor Klang]
| * | | 5068f0d 2011-04-27 | Removing blocking dequeues from MailboxConfig due to high risk and no gain [Viktor Klang]
| * | |   3ae80d9 2011-04-27 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * | | 7a33e90 2011-04-27 | Remove microkernel dist stuff [Peter Vlugter]
| | * | | fd46de1 2011-04-27 | Remove akka modules build info [Peter Vlugter]
| | * | | ad0b55c 2011-04-27 | Fix warnings in docs [Peter Vlugter]
| | * | | 2a4e967 2011-04-27 | Move building and configuration to general [Peter Vlugter]
| | * | | 735252d 2011-04-27 | Update building akka docs [Peter Vlugter]
| | * | | 0b405aa 2011-04-26 | Cleanup [Patrik Nordwall]
| | * | | ce99b60 2011-04-26 | Moved tutorial-chat-server from pending [Patrik Nordwall]
| | * | | 929f845 2011-04-26 | Cleanup [Patrik Nordwall]
| | * | | 0cc6499 2011-04-26 | Moved stm from pending [Patrik Nordwall]
| | * | | e3a5aa7 2011-04-26 | Sidebar toc [Patrik Nordwall]
| | * | | a44031d 2011-04-26 | Moved agents from pending [Patrik Nordwall]
| | * | | 884a9ae 2011-04-26 | Cleanup [Patrik Nordwall]
| | * | | e2c0d11 2011-04-26 | Moved dispatchers from pending [Patrik Nordwall]
| | * | | 850536b 2011-04-26 | cleanup [Patrik Nordwall]
| | * | | 40533a7 2011-04-26 | Moved event-handler from pending [Patrik Nordwall]
| | * | | b19bd27 2011-04-26 | typo [Patrik Nordwall]
| | * | | 0544033 2011-04-26 | Added serialize-messages description to scala typed actors doc [Patrik Nordwall]
| | * | | 89b1814 2011-04-26 | Added sidebar toc [Patrik Nordwall]
| | * | | 2efa82f 2011-04-26 | Moved typed-actors from pending [Patrik Nordwall]
| | * | | a0f5211 2011-04-26 | Moved actor-registry from pending [Patrik Nordwall]
| | * | | 88d400a 2011-04-26 | index for java api [Patrik Nordwall]
| | * | | 9c7242f 2011-04-26 | Moved untyped-actors from pending [Patrik Nordwall]
| | * | | bce7d17 2011-04-26 | Added parens to override of preStart and postStop [Patrik Nordwall]
| | * | | d0447c7 2011-04-26 | Minor, added import [Patrik Nordwall]
| | | |/  
| | |/|   
| | * | e5cee9f 2011-04-26 | Improved stm docs [Patrik Nordwall]
| | * | 371ac01 2011-04-26 | Improved stm docs [Patrik Nordwall]
| | * | 60b6501 2011-04-24 | Improved agents doc [Patrik Nordwall]
| * | | c60f468 2011-04-26 | Removing some boiler in Future [Viktor Klang]
| |/ /  
| * | d9fbefd 2011-04-26 | Fixing ticket #805 [Viktor Klang]
| * | 5ff0165 2011-04-26 | Pointing Jersey sample to v1.0 tag, closing #776 [Viktor Klang]
| * | 6685329 2011-04-26 | Making ActorRegistry public so it`ll be in the scaladoc, constructor remains private tho, closing #743 [Viktor Klang]
| * | 7446afd 2011-04-26 | Adding the class that failed to instantiate, closing ticket #803 [Viktor Klang]
| * | 8f43d6f 2011-04-26 | Adding parens to preStart and postStop closing #798 [Viktor Klang]
| * | 69ee799 2011-04-26 | Removing unused imports, closing ticket #802 [Viktor Klang]
* | | 6c6089e 2011-05-16 | Misc fixes everywhere; deployment, serialization etc. [Jonas Bonér]
* | | d50ab24 2011-05-04 | Changed the 'home' cluster config option to one of 'host:<hostname>', 'ip:<ip address>'' or 'node:<node name>' [Jonas Bonér]
* | | bd5cc53 2011-05-03 | Clustered deployment in ZooKeeper implemented. Read and write deployments from cluster test passing. [Jonas Bonér]
* | | 13abf05 2011-04-30 | Added outline on how to implement clustered deployment [Jonas Bonér]
* | | 517f9a2 2011-04-29 | Massive refactorings in akka-cluster. Also added ClusterDeployer that manages deployment data in ZooKeeper [Jonas Bonér]
* | | 2b1332e 2011-04-28 | fixed compilation problems in akka-cluster [Jonas Bonér]
* | | fb00863 2011-04-27 | All tests passing except akka-remote [Jonas Bonér]
* | | c03c4d3 2011-04-27 | Added clustering module from Cloudy Akka [Jonas Bonér]
* | |   868ec62 2011-04-27 | Rebased from master branch [Jonas Bonér]
|\ \ \  
| * | | 9706d17 2011-04-27 | Added Deployer with DeployerSpec which parses the deployment configuration and adds deployment rules [Jonas Bonér]
| * | | 2e7c76d 2011-04-27 | Rebased from master [Jonas Bonér]
| |/ /  
| * | b041329 2011-04-24 | Update Jetty to version 7.4.0 [Derek Williams]
| * | 2468f9e 2011-04-24 | Fixed broken pi calc algo [Jonas Bonér]
| * | e221130 2011-04-24 | Fixed broken pi calc algo [Jonas Bonér]
| * | b16108b 2011-04-24 | Applied the last typo fixes also [Patrik Nordwall]
| * |   a2da2bf 2011-04-23 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \  
| | * \   0eb921c 2011-04-23 | Merge branch 'future-rk' [Roland]
| | |\ \  
| | | * | 33f0585 2011-04-23 | use Future.empty in Future.channel [Roland]
| | | * | 6e45ab7 2011-04-23 | add Future.empty[T] [Roland]
| * | | | 2770f47 2011-04-23 | Fixed problem in Pi calculation algorithm [Jonas Bonér]
| |/ / /  
| * | | 06c134c 2011-04-23 | Added EventHandler.shutdown()' [Jonas Bonér]
| * | | e3a7a2c 2011-04-23 | Removed logging ClassNotFoundException to EventHandler in ReflectiveAccess [Jonas Bonér]
| * | | 18bfe15 2011-04-23 | Changed default event-handler level to INFO [Jonas Bonér]
| * | |   e4c1d8f 2011-04-23 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | |/ /  
| | * |   1ee2446 2011-04-23 | Merge branch 'wip-testkit2' [Roland]
| | |\ \  
| | | * | 2adc45c 2011-04-23 | move Duration doc to general/util.rst move migration guide links one level down [Roland]
| | | * | d55c9ce 2011-04-23 | add Java API to Duration [Roland Kuhn]
| | | * | 7825047 2011-04-21 | add references to testkit example from Ray [Roland]
| | | * | 1715e3c 2011-04-21 | add testing doc (scala) [Roland]
| | | * | df9be27 2011-04-21 | test exception reception on TestActorRef.apply() [Roland]
| | | * |   c86b63c 2011-04-21 | merge master and wip-testkit into wip-testkit2 [Roland]
| | | |\ \  
| | | | |/  
| | | * | cb332b2 2011-04-21 | make testActor.dispatcher=CallingThreadDispatcher [Roland]
| | | * | b6446f5 2011-04-21 | proxy isDefinedAt/apply through TestActorRef [Roland]
| | | * | 4868f72 2011-04-21 | add Future.channel() for obtaining a completable channel [Roland]
| | | * | 9c539e9 2011-04-16 | add TestActorRef [Roland Kuhn]
| | | * |   85daa9f 2011-04-15 | Merge branch 'master' into wip-testkit [Roland Kuhn]
| | | |\ \  
| | | * | | b169f35 2011-03-27 | fix error handling in TestKit.within [Roland Kuhn]
| | | * | | 03ae610 2011-03-27 | document CTD adaption of ActorModelSpec [Roland Kuhn]
| * | | | | 78beaa9 2011-04-23 | added some generic lookup methods to Configuration [Jonas Bonér]
| |/ / / /  
| * | | | a60bbc5 2011-04-23 | Applied patch from bruce.mitchener, with minor adjustment [Patrik Nordwall]
| | |_|/  
| |/| |   
| * | |   b1db0f4 2011-04-21 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * | | cff4ca7 2011-04-21 | Removed OSGi from all modules except for akka-actor. Added OSGi example. [Heiko Seeberger]
| * | | | f804340 2011-04-21 | Tweaked the migration guide documentation [Viktor Klang]
| |/ / /  
| * | | 83cac71 2011-04-21 | Added a little to meta-docs [Peter Vlugter]
| * | | 66c7c31 2011-04-20 | Additional improvement of documentation of dispatchers [Patrik Nordwall]
| * | | fc76062 2011-04-20 | Improved documentation of dispatchers [Patrik Nordwall]
| * | |   be4c0ec 2011-04-20 | Merge branch 'master' of github.com:jboner/akka [Patrik Nordwall]
| |\ \ \  
| | * \ \   aefbace 2011-04-20 | Merge branch 'eclipse-tutorial' [Jonas Bonér]
| | |\ \ \  
| | | * | | eb9f1f4 2011-04-20 | Added Iulian's Akka Eclipse tutorial with some minor edits of Jonas [Jonas Bonér]
| | * | | | 392c947 2011-04-20 | Deprecating methods as discussed on ML [Viktor Klang]
| | * | | | a184715 2011-04-20 | Updating most of the migration docs, added a _general_ section of the docs [Viktor Klang]
| | * | | | e18127d 2011-04-20 | Adding Devoxx talk to docs [Viktor Klang]
| | * | | |   634252c 2011-04-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \  
| | | |/ / /  
| | | * | |   05f635c 2011-04-20 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \  
| | | | * | | df4ba5d 2011-04-20 | Add meta-docs [Peter Vlugter]
| | | * | | |   6e6cd14 2011-04-20 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \  
| | | | |/ / /  
| | | * | | | 5e86f2e 2011-04-20 | incorporated feedback on the java tutorial [Jonas Bonér]
| | * | | | | 46b6468 2011-04-20 | Two birds with one stone, fixing #791 and #792 [Viktor Klang]
| | | |/ / /  
| | |/| | |   
| | * | | | bb8dca5 2011-04-20 | Try reorganised docs [Peter Vlugter]
| | * | | | 0f436f0 2011-04-20 | Fix bug in actor pool round robin selector [Peter Vlugter]
| | * | | | 2f7b7b0 2011-04-20 | Fix another Predef.error warning [Peter Vlugter]
| | * | | | 21c1aa2 2011-04-20 | Simplify docs html links for now [Peter Vlugter]
| | * | | | 8cbcbd3 2011-04-20 | Add configuration to docs [Peter Vlugter]
| | * | | | c8003e3 2011-04-20 | Add building akka page to docs [Peter Vlugter]
| | * | | | 2e356ac 2011-04-20 | Add the why akka page to the docs [Peter Vlugter]
| * | | | |   c518f4d 2011-04-19 | Merge branch 'master' of github.com:jboner/akka [Patrik Nordwall]
| |\ \ \ \ \  
| | |/ / / /  
| | * | | | 95cf98c 2011-04-19 | Fixed some typos [Jonas Bonér]
| | * | | | 345f2f1 2011-04-19 | Fixed some typos [Jonas Bonér]
| * | | | | c0b4d6b 2011-04-19 | fixed small typos [Patrik Nordwall]
| |/ / / /  
| * | | | 75309c6 2011-04-19 | removed old commented line [Patrik Nordwall]
| * | | |   804245b 2011-04-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| | * | | 90d6844 2011-04-19 | Added missing semicolon to Pi.java [Jonas Bonér]
| | * | | a1cd8a9 2011-04-19 | Corrected wrong URI to source file. [Jonas Bonér]
| | * | |   902fe7b 2011-04-19 | Cleaned up formatting in tutorials [Jonas Bonér]
| | |\ \ \  
| | * | | | 51a075b 2011-04-19 | Added Maven project file to first Java tutorial [Jonas Bonér]
| | * | | | 04f2767 2011-04-19 | Added Java version of first tutorial + polished Scala version as well [Jonas Bonér]
| * | | | |   4283d0c 2011-04-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \  
| | | |/ / /  
| | |/| | |   
| | * | | | 427a6cf 2011-04-19 | Rework routing spec - failing under jenkins [Peter Vlugter]
| | * | | | 897504f 2011-04-19 | Make includecode directive python 2.6 friendly [Peter Vlugter]
| | * | | | 4f79a2a 2011-04-19 | mkdir -p [Peter Vlugter]
| | * | | | f4fd3ed 2011-04-19 | Rework local python packages for akka-docs [Peter Vlugter]
| | * | | | 6151d17 2011-04-19 | Use new includecode directive for getting started docs [Peter Vlugter]
| | * | | | c179cbf 2011-04-19 | Add includecode directive for akka-docs [Peter Vlugter]
| | * | | | 14334a6 2011-04-19 | Fix sbt download link [Peter Vlugter]
| | * | | | 0f6a2d1 2011-04-19 | Comment out pending toc entries [Peter Vlugter]
| | * | | | 4cd2693 2011-04-19 | Automatically install pygments style [Peter Vlugter]
| | * | | | 0b4fe35 2011-04-19 | Fix broken compile - revert import in pi tutorial [Peter Vlugter]
| | * | | | 807579e 2011-04-18 | optimize performance optimization (away) [Roland Kuhn]
| | |/ / /  
| | * | |   21d8720 2011-04-18 | Added links to the SBT download [Jonas Bonér]
| | |\ \ \  
| | | * | | f6dd2fe 2011-04-18 | changed all versions in the getting started guide to current versions [Jonas Bonér]
| | * | | | 8d99fb3 2011-04-18 | changed all versions in the getting started guide to current versions [Jonas Bonér]
| | |/ / /  
| | * | | 7db48c2 2011-04-18 | Added some more detailed impl explanation [Jonas Bonér]
| | * | |   380f472 2011-04-18 | merge with upstream [Jonas Bonér]
| | |\ \ \  
| | | * \ \   fda2fa6 2011-04-11 | Merge remote branch 'upstream/master' [Havoc Pennington]
| | | |\ \ \  
| | | * | | | ccd36c5 2011-04-11 | Pedantic language tweaks to the first getting started chapter. [Havoc Pennington]
| * | | | | | f2254a4 2011-04-19 | Putting RemoteActorRefs on a diet [Viktor Klang]
| * | | | | | d5c7e12 2011-04-18 | Adding possibility to use akka.japi.Option in remote typed actor [Viktor Klang]
| |/ / / / /  
| * | | | | 0c623e6 2011-04-18 | Added a couple of articles [Jonas Bonér]
| * | | | | d1737db 2011-04-18 | add Timeout vs. Duration to FSM docs [Roland]
| * | | | |   25f24b0 2011-04-18 | Merge branch 'wip-fsmdoc' [Roland]
| |\ \ \ \ \  
| | * | | | | 144f83c 2011-04-17 | document FSM timer handling [Roland Kuhn]
| | * | | | | bd18e7e 2011-04-17 | use ListenerManagement in FSM [Roland Kuhn]
| | * | | | | 779c543 2011-04-17 | exclude pending/ again from sphinx build [Roland Kuhn]
| | * | | | | e952569 2011-04-17 | rewrite FSM docs in reST [Roland Kuhn]
| | * | | | | 34c1626 2011-04-17 | make transition notifier more robust [Roland Kuhn]
| | * | | | | 8756a5a 2011-04-17 | properly handle infinite timeouts in FSM [Roland Kuhn]
| * | | | | | d2d0ccf 2011-04-17 | minor edit [Jonas Bonér]
| |/ / / / /  
| * | | | |   4ba72d4 2011-04-16 | Merge branch 'wip-ticktock' [ticktock]
| |\ \ \ \ \  
| | * | | | | b96eca5 2011-04-14 | change the type of the handler function, and go down the rabbit hole a bit. Add a Procedure2[T1,T2] to the Java API, and add JavaEventHandler that gives access from java to the EventHandler, add docs for configuring the handler in declarative Supervision for Scala and Java. [ticktock]
| | * | | | | 41ef284 2011-04-13 | add the ability to configure a handler for MaximumNumberOfRestartsWithinTimeRangeReached to declarative Supervision [ticktock]
| | | |_|_|/  
| | |/| | |   
| * | | | | ff711a4 2011-04-15 | Add Java API versions of Future.{traverse, sequence}, closes #786 [Derek Williams]
| |/ / / /  
| * | | | 414122b 2011-04-13 | Set actor-tests as test dependency only of typed-actor [Peter Vlugter]
| * | | | c6474e6 2011-04-13 | updated sjson to version 0.11 [Debasish Ghosh]
| * | | | e667ff6 2011-04-12 | Quick fix for #773 [Viktor Klang]
| * | | | a38e0ca 2011-04-12 | Refining the PriorityGenerator API and also adding PriorityDispatcher to the docs [Viktor Klang]
| * | | | e49d675 2011-04-12 | changed wrong time unit [Patrik Nordwall]
| * | | | 03e5944 2011-04-12 | changed wrong time unit [Patrik Nordwall]
| * | | | 178171b 2011-04-12 | Added parens to latch countDown [Patrik Nordwall]
| * | | | 1f9d54d 2011-04-12 | Added parens to unbecome [Patrik Nordwall]
| * | | | 3c903ca 2011-04-12 | Added parens to shutdownAll [Patrik Nordwall]
| * | | | 3c8e375 2011-04-12 | Added parens to stop [Patrik Nordwall]
| * | | | 087191f 2011-04-12 | Added parens to start [Patrik Nordwall]
| * | | | 97d4fc8 2011-04-12 | Adjust sleep in supervisor tree spec [Peter Vlugter]
| * | | | 8572c63 2011-04-11 | fixed warning [Patrik Nordwall]
| * | | | c308e88 2011-04-12 | Remove project definitions in tutorials [Peter Vlugter]
| * | | | 8b30512 2011-04-11 | Fixing section headings [Derek Williams]
| * | | | 39bd77e 2011-04-11 | Fixing section headings [Derek Williams]
| * | | | c3e4942 2011-04-11 | Fixing section headings [Derek Williams]
| * | | | 2f7ff68 2011-04-11 | Fix section headings [Derek Williams]
| * | | | 70a5bb0 2011-04-11 | Fix section headings [Derek Williams]
| * | | | 9396808 2011-04-11 | Documentation fixes [Derek Williams]
| * | | | a81ec07 2011-04-11 | Minor fixes to Agent docs [Derek Williams]
| * | | | 4ccf4f8 2011-04-11 | Made a pass through Actor docs [Derek Williams]
| * | | | 171c8c8 2011-04-12 | Exclude pending docs [Peter Vlugter]
| * | | | d5e012a 2011-04-12 | Update docs logo and version [Peter Vlugter]
| * | | |   5459148 2011-04-11 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | | |/ /  
| | |/| |   
| | * | | 9c08ca7 2011-04-11 | fixed bugs in typed actors doc [Patrik Nordwall]
| | * | | f878ee4 2011-04-11 | minor improvements [Patrik Nordwall]
| | * | |   996fa5f 2011-04-11 | merge [Patrik Nordwall]
| | |\ \ \  
| | | |/ /  
| | |/| |   
| | | * | 4aba589 2011-04-11 | cleanup [Patrik Nordwall]
| | | * | bb88242 2011-04-11 | improved actors doc [Patrik Nordwall]
| | | * | 2142ca1 2011-04-11 | fixed wrong code block syntax [Patrik Nordwall]
| | | * | 909e90d 2011-04-11 | removed Logging trait [Patrik Nordwall]
| | | * | f753e2a 2011-04-11 | typo [Patrik Nordwall]
| | * | | 6641b30 2011-04-11 | Another minor coding style correction in akka-tutorial. [Heiko Seeberger]
| | * | | 7df7343 2011-04-11 | cleanup docs [Derek Williams]
| | |/ /  
| | * | 1e28baa 2011-04-11 | included imports also [Patrik Nordwall]
| * | | 3770a32 2011-04-11 | Fixing 2 wrong types in PriorityEBEDD and added tests for the message processing ordering [Viktor Klang]
| |/ /  
| * | 6537c75 2011-04-11 | improved documentation of actor registry [Patrik Nordwall]
| * | b8097f3 2011-04-10 | Documentation cleanup [Derek Williams]
| * | 2ad80c3 2011-04-10 | Code changes according to my review (https://groups.google.com/a/typesafe.com/group/everyone/browse_thread/thread/6661e205caf3434d?hl=de) plus some more Scala style improvements. [Heiko Seeberger]
| * | 84f6e4f 2011-04-10 | Text changes according to my review (https://groups.google.com/a/typesafe.com/group/everyone/browse_thread/thread/6661e205caf3434d?hl=de). [Heiko Seeberger]
| * | 4ab8bbe 2011-04-09 | Add converted wiki pages to akka-docs [Derek Williams]
| * | fb1a248 2011-04-09 | added more text about why we are using a 'latch' and alternatives to it [Jonas Bonér]
| * | 58ddf8b 2011-04-09 | Changed a sub-heading in the tutorial [Jonas Bonér]
| * | 3976e30 2011-04-09 | Incorporated feedback on tutorial text plus added sections on SBT and some other stuff here and there [Jonas Bonér]
| * | c91d746 2011-04-09 | Override lifecycle methods in TypedActor to avoid warnings about bridge methods [Peter Vlugter]
| * | 614f58c 2011-04-08 | fixed warnings, compile without -Xmigration [Patrik Nordwall]
| * | 9be19a4 2011-04-08 | fixed warnings, serializable [Patrik Nordwall]
| * | ac33764 2011-04-08 | fixed warnings, serializable [Patrik Nordwall]
| * | e13fb60 2011-04-08 | fixed warnings, asScalaIterable -> collectionAsScalaIterable [Patrik Nordwall]
| * | c22ca54 2011-04-08 | fixed warnings, unchecked [Patrik Nordwall]
| * | 5216437 2011-04-08 | fixed warnings, asScalaIterable -> collectionAsScalaIterable [Patrik Nordwall]
| * | d451083 2011-04-08 | fixed warnings, error -> sys.error [Patrik Nordwall]
| * | 523c5a4 2011-04-08 | fixed warnings, error -> sys.error [Patrik Nordwall]
| * | ab05bf9 2011-04-08 | fixed warnings, @serializable -> extends scala.Serializable [Patrik Nordwall]
| * | 79f6133 2011-04-08 | Adjusted chat sample to run with latest, and without Redis [Patrik Nordwall]
| * | c882b2c 2011-04-08 | Adjusted chat sample to run with latest, and without Redis [Patrik Nordwall]
| * | f9ced68 2011-04-08 | Added akka-sample-chat again [Patrik Nordwall]
| * | 9ca3072 2011-04-08 | added isInfoEnabled and isDebugEnabled, needed for Java api [Patrik Nordwall]
| * | 07e428c 2011-04-08 | Drop the -1 in pi range instead [Peter Vlugter]
| * | 63357fe 2011-04-08 | Small fix to make tailrec pi the same as other implementations [Peter Vlugter]
| * | 3da35fb 2011-04-07 | reverted back to call-by-name parameter [Patrik Nordwall]
| * |   9be6314 2011-04-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * | 15bcaef 2011-04-07 | minor correction of typos [patriknw]
| | * | e8ee6b3 2011-04-07 | notify with call-by-name included again [patriknw]
| | * |   a885c09 2011-04-07 | Merge branch 'wip-fsm' [Roland Kuhn]
| | |\ \  
| | | * | 42b72f0 2011-04-07 | add documentation and compat implicit to FSM [Roland Kuhn]
| | | * | 3d04acf 2011-04-04 | make onTransition easier to use [Roland]
| * | | | 30c2bd2 2011-04-07 | Changing the complete* signature from : CompletableFuture to Future, since they can only be written once anyway [Viktor Klang]
| * | | | 96badb2 2011-04-07 | Deprecating two newRemoteInstance methods in TypedActor [Viktor Klang]
* | | | | 4557f0d 2011-04-26 | Deployer and deployment config parsing complete, test passing [Jonas Bonér]
* | | | | 896e174 2011-04-20 | mid-address-refactoring: all tests except remote and serialization tests passes [Jonas Bonér]
* | | | | d775ec8 2011-04-20 | New remote 'address' refactoring all compiles [Jonas Bonér]
* | | | | d1bdddd 2011-04-18 | mid address refactoring [Jonas Bonér]
* | | | |   3374eef 2011-04-08 | Merged with Viktors work with removing client managed actors. Also removed actor.id, added actor.address [Jonas Bonér]
|\ \ \ \ \  
| * | | | | 75be2bd 2011-04-07 | Changing the complete* signature from : CompletableFuture to Future, since they can only be written once anyway [Viktor Klang]
| * | | | | 05ba449 2011-04-07 | Removing registerSupervisorAsRemoteActor from ActorRef + SerializationProtocol [Viktor Klang]
| * | | | | 87069f6 2011-04-07 | Adding TODOs for solving the problem with sender references and senderproxies for remote TypedActor calls [Viktor Klang]
| * | | | | b41ecfe 2011-04-07 | Removing even more client-managed remote actors residue, damn, this stuff is sticky [Viktor Klang]
| * | | | | 3f49ead 2011-04-07 | Removing more deprecation warnings and more client-managed actors residue [Viktor Klang]
| * | | | | 0dff50f 2011-04-07 | Removed client-managed actors, a lot of deprecated methods and DataFlowVariable (superceded by Future) [Viktor Klang]
| |/ / / /  
* | | | | 5f918e5 2011-04-08 | commit in the middle of address refactoring [Jonas Bonér]
| |/ / /  
|/| | |   
* | | |   654fc31 2011-04-07 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * | | | 3581295 2011-04-07 | Adjusted EventHandler to support Java API [patriknw]
| * | | | 6d9700a 2011-04-07 | Setup for publishing snapshots [Peter Vlugter]
* | | | | f6a618b 2011-04-07 | Completed first Akka/Scala/SBT tutorial [Jonas Bonér]
|/ / / /  
* | | |   7614619 2011-04-06 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * | | | c2439ee 2011-04-06 | completed first iteration of first getting started guide [Jonas Bonér]
* | | | | ef95a1b 2011-04-06 | completed first iteration of first getting started guide [Jonas Bonér]
|/ / / /  
* | | | b420866 2011-04-06 | Added new module 'akka-docs' as the basis for the new Sphinx/reST based docs. Also added the first draft of the new Getting Started Guide [Jonas Bonér]
* | | |   0cf28f5 2011-04-06 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * \ \ \   28ceda0 2011-04-06 | Merge branch 'master' of github.com:jboner/akka [patriknw]
| |\ \ \ \  
| | |/ / /  
| * | | | dd16929 2011-04-06 | reply to channel [patriknw]
* | | | |   d60f656 2011-04-06 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| | |/ / /  
| |/| | |   
| * | | |   46460a9 2011-04-06 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| * | | | e8259e1 2011-04-06 | Closing ticket #757 [Viktor Klang]
| * | | | da29f51 2011-04-06 | Deprecating the spawn* methods [Viktor Klang]
* | | | | 7eaecf9 2011-04-06 | If there is no EventHandler listener defined and an empty default config is used then the default listener is now added and used [Jonas Bonér]
| |/ / /  
|/| | |   
* | | | 10ecd85 2011-04-06 | Turned 'sendRequestReplyFuture(..): Future[_]' into 'sendRequestReplyFuture[T <: AnyRef](..): Future[T] [Jonas Bonér]
|/ / /  
* | |   899144d 2011-04-06 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| * | | 132bba3 2011-04-06 | working version of second.Pi.java [patriknw]
* | | | 5513505 2011-04-06 | Moved 'channel' from ScalaActorRef to ActorRef to make it available from Java [Jonas Bonér]
* | | | 276a97d 2011-04-06 | Added overridden Actor life-cycle methods to UntypedActor to avoid warnings about bridge methods [Jonas Bonér]
|/ / /  
* | |   7949e80 2011-04-06 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| * | | 2bf360c 2011-04-06 | first, non-working version of second Pi.java [patriknw]
* | | | cea277e 2011-04-06 | Addded method 'context' to UntypedActor. Changed the Pi calculation to use tail-recursive function [Jonas Bonér]
|/ / /  
* | |   eb5a38c 2011-04-06 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| * | | c26879f 2011-04-06 | minor cleanup [patriknw]
| * | |   706e128 2011-04-06 | Merge branch 'master' of github.com:jboner/akka [patriknw]
| |\ \ \  
| | * | | 7e99f12 2011-04-06 | Add simple stack trace to string method to AkkaException [Peter Vlugter]
| | * | | eea7986 2011-04-06 | Another timeout increase [Peter Vlugter]
| | * | | 389817e 2011-04-05 | add warning message to git-remove-history.sh [Roland]
| | * | |   852232d 2011-04-05 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \  
| | * | | | 19f2e7f 2011-04-05 | Removing a compilation warning by restructuring the code [Viktor Klang]
| * | | | | 0abf51d 2011-04-06 | moved tests from akka-actor to new module akka-actor-tests to fix circular dependencies between testkit and akka-actor [patriknw]
| |/ / / /  
* | | | | e58aae5 2011-04-06 | Added alt foldLeft algo for Pi calculation in tutorial [Jonas Bonér]
| |/ / /  
|/| | |   
* | | | ca1fc49 2011-04-05 | added first and second parts of Pi tutorial for both Scala and Java [Jonas Bonér]
* | | | 3827a89 2011-04-05 | Added PoisonPill and Kill for UntypedActor [Jonas Bonér]
* | | |   26f87ca 2011-04-05 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| |/ / /  
| * | | 71a32c7 2011-04-05 | Changing mailbox-capacity to be unbounded for the case where the bounds are 0, as per the docs in the config [Viktor Klang]
| * | |   5be70e8 2011-04-05 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * | | 2214c7f 2011-04-05 | 734: moved package objects to package.scala files [patriknw]
| | * | | bb55de4 2011-04-05 | Remove infinite loop in AkkaException [Peter Vlugter]
| | * | | b3d2cdb 2011-04-05 | Restoring SNAPSHOT version [Peter Vlugter]
| | * | | 17d3282 2011-04-05 | Update version for release 1.1-M1 (v1.1-M1) [Peter Vlugter]
| * | | | f483ae7 2011-04-04 | Adding the sender reference to remote typed actor calls, not a complete fix for 731 [Viktor Klang]
| |/ / /  
| * | | f280e28 2011-04-04 | Switched to Option(...) instead of Some(...) for TypedActor dispatch [Viktor Klang]
| |/ /  
| * | 7da83a7 2011-04-04 | Update supervisor spec [Peter Vlugter]
| * | be6035f 2011-04-04 | Add delay in typed actor lifecycle test [Peter Vlugter]
| * | 5106f05 2011-04-04 | Specify objenesis repo directly [Peter Vlugter]
| * | 2700970 2011-04-03 | Add basic documentation to Futures.{sequence,traverse} [Derek Williams]
| * | 1d69665 2011-04-02 | Add tests for Futures.{sequence,traverse} [Derek Williams]
| * | 696c0a2 2011-04-02 | Make sure there is no type error when used from a val [Derek Williams]
| * | 3c9e199 2011-04-02 | Bumping SBT version to 0.7.6-RC0 to fix jline problem with sbt console [Viktor Klang]
| * | c3eb0b4 2011-04-02 | Adding Pi2, fixing router shutdown in Pi, cleaning up the generation of workers in Pi [Viktor Klang]
| * | 2e70b4a 2011-04-02 | Simplified the Master/Worker interaction in first tutorial [Jonas Bonér]
| * | 8b9abc2 2011-04-02 | Added the tutorial projects to the akka core project file [Jonas Bonér]
| * | b0c6eb6 2011-04-02 | Added missing project file for pi tutorial [Jonas Bonér]
| * | 942c8b7 2011-04-01 | rewrote algo for Pi calculation to use 'map' instead of 'for-yield' [Jonas Bonér]
| * |   a947dc8 2011-04-01 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \  
| | * | 22b5cf5 2011-04-01 | Fix after removal of akka-sbt-plugin [Derek Williams]
| | * |   40287a2 2011-04-01 | Merge branch 'master' into ticket-622 [Derek Williams]
| | |\ \  
| | * \ \   da99296 2011-03-30 | Merge remote-tracking branch 'origin/master' into ticket-622 [Derek Williams]
| | |\ \ \  
| | * | | | dc3ee82 2011-03-29 | Move akka-sbt-plugin to akka-modules [Derek Williams]
| * | | | | bafae2a 2011-04-01 | Changed Iterator to take immutable.Seq instead of mutable.Seq. Also changed Pi tutorial to use Vector instead of Array [Jonas Bonér]
| | |_|/ /  
| |/| | |   
| * | | | 95ec4a6 2011-04-01 | Added some comments and scaladoc to Pi tutorial sample [Jonas Bonér]
| * | | | 7e3301d 2011-04-01 | Changed logging level in ReflectiveAccess and set the default Akka log level to INFO [Jonas Bonér]
| * | | | b226605 2011-04-01 | Added Routing.Broadcast message and handling to be able to broadcast a message to all the actors a load-balancer represents [Jonas Bonér]
| * | | | d276b6b 2011-04-01 | removed JARs added by mistake [Jonas Bonér]
| * | | | 3334f05 2011-04-01 | Fixed bug with not shutting down remote event handler listener properly [Jonas Bonér]
| * | | |   7f791be 2011-04-01 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| | * | | | 2430341 2011-04-01 | Adding Kill message, synonymous with Restart(new ActorKilledException(msg)) [Viktor Klang]
| * | | | | e977267 2011-04-01 | Changed *Iterator to take a Seq instead of a List [Jonas Bonér]
| * | | | | 329e8bb 2011-04-01 | Added first tutorial based on Scala and SBT [Jonas Bonér]
| |/ / / /  
| * | | |   e7cf485 2011-03-31 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| | * | | | 1574f51 2011-03-31 | Should should be must, should must be must [Peter Vlugter]
| | * | | | 380f385 2011-03-31 | Rework the tests in actor/actor [Peter Vlugter]
| | | |/ /  
| | |/| |   
| * | | | 2f9ec86 2011-03-31 | Added comment about broken TypedActor remoting behavior [Jonas Bonér]
| |/ / /  
| * | | 1c8b557 2011-03-31 | Add general mechanism for excluding tests [Peter Vlugter]
| * | |   8760c00 2011-03-30 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * | | d476303 2011-03-30 | More test timing adjustments [Peter Vlugter]
| | * | | a4ea251 2011-03-30 | Multiply test timing in ActorModelSpec [Peter Vlugter]
| | * | | 264904b 2011-03-30 | Multiply test timing in FSMTimingSpec [Peter Vlugter]
| | * | | c7db3e5 2011-03-30 | Add some testing times to FSM tests (for Jenkins) [Peter Vlugter]
| | * | | 4dfb3eb 2011-03-29 | Adding -optimise to the compile options [Viktor Klang]
| | * | | f397bea 2011-03-29 | Temporarily disabling send-time-work-redistribution until I can devise a good way of avoiding a worst-case-stack-overflow [Viktor Klang]
| * | | | 3b18943 2011-03-30 | improved scaladoc in Future [Jonas Bonér]
| * | | | f39c77f 2011-03-30 | Added check to ensure that messages are not null. Also cleaned up misc code [Jonas Bonér]
| * | | | d582caf 2011-03-30 | added some methods to the TypedActor context and deprecated all methods starting with 'get*' [Jonas Bonér]
| |/ / /  
| * | |   0043e70 2011-03-29 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * | | cfa1a61 2011-03-29 | AspectWerkz license changed to Apache 2 [Jonas Bonér]
| | |/ /  
| * | | 2ec2234 2011-03-29 | Added configuration to define capacity to the remote client buffer messages on failure to send [Jonas Bonér]
| |/ /  
| * | 092c3a9 2011-03-29 | Update to sbt 0.7.5.RC1 [Peter Vlugter]
| * |   5b668ea 2011-03-29 | Merge branch 'master' into wip-2.9.0 [Peter Vlugter]
| |\ \  
| | * \   8c24a98 2011-03-29 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \  
| | * | | 1a8e1d3 2011-03-29 | Removing warninit from project def [Viktor Klang]
| | | |/  
| | |/|   
| * | |   427e32e 2011-03-28 | Merge branch 'master' into wip-2.9.0 [Peter Vlugter]
| |\ \ \  
| | | |/  
| | |/|   
| | * |   c885d4f 2011-03-28 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \  
| | | * \   06cdc36 2011-03-28 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | |\ \  
| | * | \ \   a986694 2011-03-28 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \  
| | | |/ / /  
| | |/| / /   
| | | |/ /    
| | | * |   e001c84 2011-03-28 | Merge branch 'wip_resend_message_on_remote_failure' [Jonas Bonér]
| | | |\ \  
| | | | |/  
| | | |/|   
| | * | |   abd7622 2011-03-28 | Renamed config option for remote client retry message send. [Jonas Bonér]
| | |\ \ \  
| | | |/ /  
| | |/| /   
| | | |/    
| | | * 28b6933 2011-03-28 | Add sudo directly to network tests [Peter Vlugter]
| | | * 6c5f630 2011-03-28 | Add system property to enable network failure tests [Peter Vlugter]
| | | * 78faeaa 2011-03-27 | 1. Added config option to enable/disable the remote client transaction log for resending failed messages. 2. Swallows exceptions on appending to transaction log and do not complete the Future matching the message. [Jonas Bonér]
| | | * 6d13a39 2011-03-25 | Changed UnknownRemoteException to CannotInstantiateRemoteExceptionDueToRemoteProtocolParsingErrorException - should be more clear now. [Jonas Bonér]
| | | * 604a21d 2011-03-25 | added script to simulate network failure scenarios and restore original settings [Jonas Bonér]
| | | * 3f719ef 2011-03-25 | 1. Fixed issues with remote message tx log. 2. Added trait for network failure testing that supports 'TCP RST', 'TCP DENY' and message throttling/delay. 3. Added test for the remote transaction log. Both for TCP RST and TCP DENY. [Jonas Bonér]
| | | * 259ecf8 2011-03-25 | Added accessor for pending messages [Jonas Bonér]
| | | *   08e9329 2011-03-24 | merged with upstream [Jonas Bonér]
| | | |\  
| | | | * cd93f57 2011-03-24 | 1. Added a 'pending-messages' tx log for all pending messages that are not yet delivered to the remote host, this tx log is retried upon successful remote client reconnect. 2. Fixed broken code in UnparsableException and renamed it to UnknownRemoteException. [Jonas Bonér]
| | | * | b425643 2011-03-24 | 1. Added a 'pending-messages' tx log for all pending messages that are not yet delivered to the remote host, this tx log is retried upon successful remote client reconnect. 2. Fixed broken code in UnparsableException and renamed it to UnknownRemoteException. [Jonas Bonér]
| | | |/  
| | | * dfcbf75 2011-03-24 | refactored remote event handler and added deregistration of it on remote shutdown [Jonas Bonér]
| | | * 2867a45 2011-03-24 | Added a remote event handler that pipes remote server and client events to the standard EventHandler system [Jonas Bonér]
| * | | 4548671 2011-03-28 | Update plugins [Peter Vlugter]
| * | | 4ea8e18 2011-03-28 | Re-enable ants sample [Peter Vlugter]
| * | |   814cb13 2011-03-28 | Merge branch 'master' into wip-2.9.0 [Peter Vlugter]
| |\ \ \  
| | |/ /  
| | * | e15a2fc 2011-03-27 | Potential fix for #723 [Viktor Klang]
| * | | e5df67c 2011-03-26 | Upgrading to Scala 2.9.0-RC1 [Viktor Klang]
| * | |   7fc7e4d 2011-03-26 | Merging in master [Viktor Klang]
| |\ \ \  
| | |/ /  
| | * |   26ca48a 2011-03-26 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| | |\ \  
| | | * | 1ac56ff 2011-03-25 | Removing race in isDefinedAt and in apply, closing ticket #722 [Viktor Klang]
| | * | | d9e6175 2011-03-26 | changed version of sjson to 0.10 [Debasish Ghosh]
| | |/ /  
| | * | a579d6b 2011-03-25 | Closing ticket #721, shutting down the VM if theres a broken config supplied [Viktor Klang]
| | * | a7e397f 2011-03-25 | Add a couple more test timing adjustments [Peter Vlugter]
| | * | ede34ad 2011-03-25 | Replace sleep with latch in valueWithin test [Peter Vlugter]
| | * | 40666ca 2011-03-25 | Introduce testing time factor (for Jenkins builds) [Peter Vlugter]
| | * | 1547c99 2011-03-25 | Fix race in ActorModelSpec [Peter Vlugter]
| | * | b436ff9 2011-03-25 | Catch possible actor init exceptions [Peter Vlugter]
| | * | 898d555 2011-03-25 | Fix race with PoisonPill [Peter Vlugter]
| | * | 479e47b 2011-03-24 | Fixing order-of-initialization-bug [Viktor Klang]
| | |/  
| | *   a3b61f7 2011-03-24 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\  
| | | *   bc89d63 2011-03-23 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\  
| | | * | 7ce67ef 2011-03-23 | Moving Initializer to akka-kernel, add manually for other uses, removing ListWriter, changing akka-http to depend on akka-actor instead of akka-remote, closing ticket #716 [Viktor Klang]
| | * | | 5f86e80 2011-03-24 | moved slf4j to 'akka.event.slf4j' [Jonas Bonér]
| | * | | 865192e 2011-03-23 | added error reporting to the ReflectiveAccess object [Jonas Bonér]
| | | |/  
| | |/|   
| | * |   ca0a37f 2011-03-23 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \  
| | | |/  
| | | * 7169803 2011-03-23 | Adding synchronous writes to NettyRemoteSupport [Viktor Klang]
| | | * bc0b6c6 2011-03-23 | Removing printlns [Viktor Klang]
| | | *   532a346 2011-03-23 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\  
| | | * | ed1a113 2011-03-23 | Adding OrderedMemoryAwareThreadPoolExecutor with an ExecutionHandler to the NettyRemoteServer [Viktor Klang]
| | * | | 711e62f 2011-03-23 | Moved EventHandler to 'akka.event' plus added 'error' method without exception param [Jonas Bonér]
| | | |/  
| | |/|   
| | * | a955e99 2011-03-23 | Remove akka-specific transaction and hooks [Peter Vlugter]
| | |/  
| | * c87de86 2011-03-23 | Deprecating the current impl of DataFlowVariable [Viktor Klang]
| | * f74151a 2011-03-22 | Switched to FutureTimeoutException for Future.apply/Future.get [Viktor Klang]
| | *   65f2487 2011-03-22 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\  
| | | *   0a94356 2011-03-22 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | |\  
| | | * | 3bd5cc9 2011-03-22 | Added SLF4 module with Logging trait and Event Handler [Jonas Bonér]
| | * | | 3197596 2011-03-22 | Adding Java API for reduce, fold, apply and firstCompletedOf, adding << and apply() to CompletableFuture + a lot of docs [Viktor Klang]
| | | |/  
| | |/|   
| | * | 566d55a 2011-03-22 | Renaming resultWithin to valueWithin, awaitResult to awaitValue to aling the naming, and then deprecating the blocking methods in Futures [Viktor Klang]
| | * | 1dfd454 2011-03-22 | Switching AlreadyCompletedFuture to always be expired, good for GC eligibility etc [Viktor Klang]
| * | | c337fdd 2011-03-22 | Updating to Scala 2.9.0, SJSON still needs to be released for 2.9.0-SNAPSHOT tho [Viktor Klang]
| |/ /  
| * |   2a516be 2011-03-22 | Merge branch '667-krasserm' [Martin Krasser]
| |\ \  
| | * | ef4bdcf 2011-03-17 | Preliminary upgrade to latest Camel development snapshot. [Martin Krasser]
| * | | 23f5a51 2011-03-21 | Minor optimization for getClassFor and added some comments [Viktor Klang]
| | |/  
| |/|   
| * | 815bb07 2011-03-21 | Fixing classloader priority loading [Viktor Klang]
| * |   5069e55 2011-03-21 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * | 32be316 2011-03-20 | Prevent throwables thrown in futures from disrupting the rest of the system. Fixes #710 [Derek Williams]
| | * | 29e7328 2011-03-20 | added test cases for Java serialization of actors in course of documenting the stuff in the wiki [Debasish Ghosh]
| * | | 3111484 2011-03-20 | Rewriting getClassFor to do a fall-back approach, first test the specified classloader, then test the current threads context loader, then try the ReflectiveAccess` classloader and the Class.forName [Viktor Klang]
| |/ /  
| * |   d5ffc7e 2011-03-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * \   59319cf 2011-03-19 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \  
| | * | | 24a1d39 2011-03-19 | added event handler logging + minor reformatting and cleanup [Jonas Bonér]
| | * | | 0dba3ac 2011-03-19 | removed some println [Jonas Bonér]
| | * | | e7a410d 2011-03-18 | Fixed bug with restarting supervised supervisor that had done linking in constructor + Changed all calls to EventHandler to use direct 'error' and 'warning' methods for improved performance [Jonas Bonér]
| * | | | bee3e79 2011-03-19 | Giving a 1s time window for the requested change to occur [Viktor Klang]
| | |/ /  
| |/| |   
| * | |   89ecb74 2011-03-18 | Resolving conflict [Viktor Klang]
| |\ \ \  
| | |/ /  
| | * | 18b4c55 2011-03-18 | Added hierarchical event handler level to generic event publishing [Jonas Bonér]
| * | | c9338e6 2011-03-18 | Switching to PoisonPill to shut down Per-Session actors, and restructuring some Future-code to avoid wasteful object creation [Viktor Klang]
| * | | 5485029 2011-03-18 | Removing 2 vars from Future, and adding some ScalaDoc [Viktor Klang]
| * | | 496cbae 2011-03-18 | Making thread transient in Event and adding WTF comment [Viktor Klang]
| |/ /  
| * |   bf44911 2011-03-18 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * | 94a4d09 2011-03-18 | Fix for event handler levels [Peter Vlugter]
| * | | 3dd4cb9 2011-03-18 |  Removing verbose type annotation [Viktor Klang]
| * | | 2255be4 2011-03-18 | Fixing stall issue in remote pipeline [Viktor Klang]
| * | | 452a97d 2011-03-18 | Reducing overhead and locking involved in Futures.fold and Futures.reduce [Viktor Klang]
| * | |   8b8999c 2011-03-17 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | |/ /  
| | * |   52e5e35 2011-03-17 | Merge branch 'wip-CallingThreadDispatcher' [Roland Kuhn]
| | |\ \  
| | | * | 18080cb 2011-03-17 | make FSMTimingSpec more deterministic [Roland Kuhn]
| | | * | d15e5e7 2011-03-17 | ignore VIM swap files (and clean up previous accident) [Roland Kuhn]
| | | * | 0e66cd0 2011-03-06 | add locking to CTD-mbox [Roland Kuhn]
| | | * | e1b266c 2011-03-06 | add test to ActorModelSpec [Roland Kuhn]
| | | * | 3d28e6a 2011-03-05 | create akka-testkit subproject [Roland Kuhn]
| | | * | 337d34e 2011-02-20 | first shot at CallingThreadDispatcher [Roland Kuhn]
| * | | | 7cf5e59 2011-03-16 | Making sure that theres no allocation for ActorRef.invoke() [Viktor Klang]
| * | | | 53c8dff 2011-03-16 | Adding yet another comment to ActorPool [Viktor Klang]
| * | | | a5aa5b4 2011-03-16 | Faster than Derek! Changing completeWith(Future) to be lazy and not eager [Viktor Klang]
| * | | | 6ee5420 2011-03-16 | Added some more comments to ActorPool [Viktor Klang]
| * | | | 1c649d4 2011-03-16 | ActorPool code cleanup, fixing some qmarks and some minor defects [Viktor Klang]
| |/ / /  
| * | | d237e09 2011-03-16 | Restructuring some methods in ActorPool, and switch to PoisonPill for postStop cleanup, to let workers finish their tasks before shutting down [Viktor Klang]
| * | | f7e215c 2011-03-16 | Refactoring, reformatting and fixes to ActorPool, including ticket 705 [Viktor Klang]
| * | | fadd30e 2011-03-16 | Fixing #706 [Viktor Klang]
| * | | 2d80ff4 2011-03-16 | Just saved 3 allocations per Actor instance [Viktor Klang]
| * | | 2a88dd6 2011-03-15 | Switching to unfair locking [Viktor Klang]
| * | | 3880506 2011-03-15 | Adding a test for ticket 703 [Viktor Klang]
| * | | 63bad2b 2011-03-15 | No, seriously, fixing ticket #703 [Viktor Klang]
| * | | 38b2917 2011-03-14 | Upgrading the fix for overloading and TypedActors [Viktor Klang]
| * | | a856913 2011-03-14 | Moving AkkaLoader from akka.servlet in akka-http to akka.util, closing ticket #701 [Viktor Klang]
| * | | b83268d 2011-03-14 | Removign leftover debug statement. My bad. [Viktor Klang]
| * | |   63bbf4d 2011-03-14 | Merge with master [Viktor Klang]
| |\ \ \  
| | * | | ac0273b 2011-03-14 | changed event handler dispatcher name [Jonas Bonér]
| | * | | dd69a4b 2011-03-14 | Added generic event handler [Jonas Bonér]
| | * | |   c8f1d10 2011-03-14 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \  
| | * | | | 33dc617 2011-03-14 | Changed API for EventHandler and added support for log levels [Jonas Bonér]
| * | | | | 98d9ce8 2011-03-14 | Fixing ticket #703 and reformatting Pool.scala [Viktor Klang]
| * | | | | f4a4563 2011-03-14 | Adding a unit test for ticket 552, but havent solved the ticket [Viktor Klang]
| * | | | | 74257b2 2011-03-14 | All tests pass, might actually have solved the typed actor method resolution issue [Viktor Klang]
| * | | | | a1c6c65 2011-03-14 | Pulling out _resolveMethod_ from NettyRemoteSupport and moving it into ReflectiveAccess [Viktor Klang]
| * | | | | 875c45c 2011-03-14 | Potential fix for the remote dispatch of TypedActor methods when overloading is used. [Viktor Klang]
| * | | | | 292abdb 2011-03-14 | Fixing ReadTimeoutException, and implement proper shutdown after timeout [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | |   8b03622 2011-03-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | | |_|/  
| | |/| |   
| | * | |   9f15439 2011-03-13 | Merge branch '647-krasserm' [Martin Krasser]
| | |\ \ \  
| | | * | | edda6f6 2011-03-07 | Dropped dependency to AspectInitRegistry and usage of internal registry in TypedActorComponent [Martin Krasser]
| * | | | | b2b84d8 2011-03-14 | Reverting change to SynchronousQueue [Viktor Klang]
| * | | | | d99ef9b 2011-03-14 | Revert "Switching ThreadBasedDispatcher to use SynchronousQueue since only one actor should be in it" [Viktor Klang]
| |/ / / /  
| * | | | f980dc3 2011-03-11 | Switching ThreadBasedDispatcher to use SynchronousQueue since only one actor should be in it [Viktor Klang]
| * | | |   e2c36e0 2011-03-11 | Merge branch 'future-covariant' [Derek Williams]
| |\ \ \ \  
| | * | | | 67ead66 2011-03-11 | Improve Future API when using UntypedActors, and add overloads for Java API [Derek Williams]
| * | | | | 626d44d 2011-03-11 | Optimization for the mostly used mailbox, switch to non-blocking queue [Viktor Klang]
| * | | | | f624cb4 2011-03-11 | Beefed up the concurrency level for the mailbox tests [Viktor Klang]
| * | | | | 9418c34 2011-03-11 | Adding a rather untested BoundedBlockingQueue to wrap PriorityQueue for BoundedPriorityMessageQueue [Viktor Klang]
| |/ / / /  
| * | | | 89f2bf3 2011-03-10 | Deprecating client-managed TypedActor [Viktor Klang]
| * | | | f0cf589 2011-03-10 | Deprecating Client-managed remote actors [Viktor Klang]
| * | | | 5f438a3 2011-03-10 | Commented out the BoundedPriorityMailbox, since it wasn´t bounded, and broke out the mailbox logic into PriorityMailbox [Viktor Klang]
| * | | | 6c26731 2011-03-09 | Adding PriorityExecutorBasedEventDrivenDispatcher [Viktor Klang]
| * | | | 9070175 2011-03-09 | Adding unbounded and bounded MessageQueues based on PriorityBlockingQueue [Viktor Klang]
| * | | | 769f266 2011-03-09 | Changing order as to avoid DNS lookup in worst-case scenario [Viktor Klang]
| * | | |   493fbbb 2011-03-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | * \ \ \   de169a9 2011-03-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \  
| * | \ \ \ \   53b19fa 2011-03-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \  
| | |/ / / / /  
| |/| / / / /   
| | |/ / / /    
| | * | | | 00393dc 2011-03-09 | Add future and await to agent [Peter Vlugter]
| | | |/ /  
| | |/| |   
| * | | | 416c356 2011-03-09 | Removing legacy, non-functional, SSL support from akka-remote [Viktor Klang]
| |/ / /  
| * | | 36c6c9f 2011-03-09 | Fix problems with config lists and default config [Peter Vlugter]
| * | | 88dc18c 2011-03-08 | Removing the use of embedded-repo and deleting it, closing ticket #623 [Viktor Klang]
| * | |   927a065 2011-03-08 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * | | 8aa6a80 2011-03-08 | Adding ModuleConfiguration for net.debasishg [Viktor Klang]
| * | | | 935e722 2011-03-08 | Adding ModuleConfiguration for net.debasishg [Viktor Klang]
| |/ / /  
| * | | 61e6634 2011-03-08 | Removing ssl options since SSL isnt ready yet [Viktor Klang]
| * | | a8d9e4e 2011-03-08 | closes #689: All properties from the configuration file are unit-tested now. [Heiko Seeberger]
| * | | b88bc4b 2011-03-08 | re #689: Verified config for akka-stm. [Heiko Seeberger]
| * | | 3a1f9b1 2011-03-08 | re #689: Verified config for akka-remote. [Heiko Seeberger]
| * | | e180300 2011-03-08 | re #689: Verified config for akka-http. [Heiko Seeberger]
| * | | 4f6444c 2011-03-08 | re #689: Verified config for akka-actor. [Heiko Seeberger]
| * | |   6ee2626 2011-03-08 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * | | dbe9e07 2011-03-08 | Reduce config footprint [Peter Vlugter]
| * | | | 3d78342 2011-03-08 | Removing SBinary artifacts from embedded repo [Viktor Klang]
| |/ / /  
| * | | 06cc030 2011-03-08 | Removing support for SBinary as per #686 [Viktor Klang]
| * | | 17b85c3 2011-03-08 | Removing dead code [Viktor Klang]
| * | | 006e833 2011-03-08 | Reverting fix for due to design flaw in *ByUuid [Viktor Klang]
| * | | 8cad134 2011-03-07 | Remove uneeded parameter [Derek Williams]
| * | | cda0c1b 2011-03-07 | Fix calls to EventHandler [Derek Williams]
| * | |   eee9445 2011-03-07 | Merge branch 'master' into derekjw-future-dispatch [Derek Williams]
| |\ \ \  
| | * | | 4901ad3 2011-03-07 | Fixing #655: Stopping all actors connected to remote server on shutdown [Viktor Klang]
| | * | | 48949f2 2011-03-07 | Removing non-needed jersey module configuration [Viktor Klang]
| | * | |   02107f0 2011-03-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \  
| | | * \ \   2511cc6 2011-03-07 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \  
| | | | * \ \   e89c565 2011-03-07 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | | |\ \ \  
| | | | | |/ /  
| | | * | | |   6a5bd2c 2011-03-07 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \  
| | | | |/ / /  
| | | |/| / /   
| | | | |/ /    
| | | * | | 939d4ca 2011-03-07 | Changed event handler config to a list of the FQN of listeners [Jonas Bonér]
| | * | | | 92fa322 2011-03-07 | Moving most of the Jersey and Jetty deps into MicroKernel (akka-modules), closing #593 [Viktor Klang]
| | | |/ /  
| | |/| |   
| | * | | f3af6bd 2011-03-06 | Tweaking AkkaException [Viktor Klang]
| | * | | 6f05ce1 2011-03-06 | Adding export of the embedded uuid lib to the OSGi manifest [Viktor Klang]
| | * | | 90470e8 2011-03-05 | reverting changes to avoid breaking serialization [Viktor Klang]
| | * | | c0fcbae 2011-03-05 |  Speeding up remote tests by removing superfluous Thread.sleep [Viktor Klang]
| | * | | f8727fc 2011-03-05 | Removed some superfluous code [Viktor Klang]
| | * | | 3e7289c 2011-03-05 | Add Future GC comment [Viktor Klang]
| | * | | f1a1770 2011-03-05 | Adding support for clean exit of remote server [Viktor Klang]
| | * | | 70a0602 2011-03-05 | Updating the Remote protocol to support control messages [Viktor Klang]
| | * | | c952358 2011-03-04 | Adding support for MessageDispatcherConfigurator, which means that you can configure homegrown dispatchers in akka.conf [Viktor Klang]
| | * | | fe5ead9 2011-03-05 | Fixed #675 : preStart() is called twice when creating new instance of TypedActor [Debasish Ghosh]
| | * | | 31cf3e6 2011-03-05 | fixed repo of scalatest which was incorrectly pointing to ScalaToolsSnapshot [Debasish Ghosh]
| | |/ /  
| * | | ca24247 2011-03-04 | Use scalatools release repo for scalatest [Derek Williams]
| * | |   a647f32 2011-03-04 | Merge branch 'master' into derekjw-future-dispatch [Derek Williams]
| |\ \ \  
| | |/ /  
| | * | b090f87 2011-03-04 | Remove logback config [Peter Vlugter]
| | * |   4514df2 2011-03-04 | merged with upstream [Jonas Bonér]
| | |\ \  
| | * | | f3d87b2 2011-03-04 | reverted tests supported 2.9.0 to 2.8.1 [Jonas Bonér]
| | * | |   c909bb2 2011-03-04 | Merge branch '0deps', remote branch 'origin' into 0deps [Jonas Bonér]
| | |\ \ \  
| | | * | | f808243 2011-03-04 | Update Java STM API to include STM utils [Peter Vlugter]
| | * | | | ff97c28 2011-03-04 | reverting back to 2.8.1 [Jonas Bonér]
| * | | | | ee418e1 2011-03-03 | Cleaner exception matching in tests [Derek Williams]
| * | | | |   db80212 2011-03-03 | Merge remote-tracking branch 'origin/0deps' into 0deps-future-dispatch [Derek Williams]
| |\ \ \ \ \  
| | | |_|/ /  
| | |/| | |   
| | * | | | ddb226a 2011-03-03 | Removing shutdownLinkedActors, making linkedActors and getLinkedActors public, fixing checkinit problem in AkkaException [Viktor Klang]
| | * | | |   6c52b95 2011-03-03 | Merge remote branch 'origin/0deps' into 0deps [Viktor Klang]
| | |\ \ \ \  
| | | * | | | a877b1d 2011-03-03 | Remove configgy from embedded repo [Peter Vlugter]
| | | |/ / /  
| | * | | | 6188988 2011-03-03 | Removing Thrift jars [Viktor Klang]
| | |/ / /  
| | * | | fb92014 2011-03-03 | Incorporate configgy with some renaming and stripping down [Peter Vlugter]
| | * | | 83cd721 2011-03-02 | Add configgy sources under akka package [Peter Vlugter]
| * | | | f2903c4 2011-03-02 | remove debugging line from test [Derek Williams]
| * | | | 8123a2c 2011-03-02 | Fix test after merge [Derek Williams]
| * | | |   d1fcb6d 2011-03-02 | Merge remote-tracking branch 'origin/0deps' into 0deps-future-dispatch [Derek Williams]
| |\ \ \ \  
| | |/ / /  
| | * | | 3dfd76d 2011-03-02 | Updating to Scala 2.9.0 and enabling -optimise and -Xcheckinit [Viktor Klang]
| | * | |   9d78ca6 2011-03-02 | Merge remote branch 'origin/0deps' into 0deps [Viktor Klang]
| | |\ \ \  
| | | * \ \   db980df 2011-03-02 | merged with upstream [Jonas Bonér]
| | | |\ \ \  
| | | * | | | 8fd6122 2011-03-02 | Renamed to EventHandler and added 'info, debug, warning and error' [Jonas Bonér]
| | | * | | |   fcff388 2011-03-02 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \  
| | | | | |/ /  
| | | | |/| |   
| | | * | | | 6f6d459 2011-03-02 | Added the ErrorHandler notifications to all try-catch blocks [Jonas Bonér]
| | | * | | | ce00125 2011-03-01 | Added ErrorHandler and a default listener which prints error logging to STDOUT [Jonas Bonér]
| | | * | | | 6e1b795 2011-02-28 | tabs to spaces [Jonas Bonér]
| | | * | | | 1425267 2011-02-28 | Removed logging [Jonas Bonér]
| | * | | | | 3e25d36 2011-03-02 | Potential fix for #672 [Viktor Klang]
| | | |_|/ /  
| | |/| | |   
| | * | | | 7417a4b 2011-03-02 | Upding Jackson to 1.7 and commons-io to 2.0.1 [Viktor Klang]
| | * | | | 0658439 2011-03-02 | Removing wasteful guarding in spawn/*Link methods [Viktor Klang]
| | * | | | 4b3bcdb 2011-03-02 | Removing 4 unused dependencies [Viktor Klang]
| | * | | | 2676b48 2011-03-02 | Removing old versions of Configgy [Viktor Klang]
| | * | | | 015415d 2011-03-02 | Embedding the Uuid lib, deleting it from the embedded repo and dropping the jsr166z.jar [Viktor Klang]
| | * | | | a3b324e 2011-03-02 | Rework of WorkStealer done, also, removal of DB Dispatch [Viktor Klang]
| | * | | |   b5e6f9a 2011-03-02 | Merge branch 'master' of github.com:jboner/akka into 0deps [Viktor Klang]
| | |\ \ \ \  
| | | | |/ /  
| | | |/| |   
| | * | | |   728cb92 2011-02-27 | Merge branch 'master' into 0deps [Viktor Klang]
| | |\ \ \ \  
| | | | |/ /  
| | | |/| |   
| | * | | | ba3a473 2011-02-27 | Optimizing for bestcase when sending an actor a message [Viktor Klang]
| | * | | | dea85ef 2011-02-27 | Removing logging from EBEDD [Viktor Klang]
| | * | | | a6bfe64 2011-02-27 | Removing HawtDispatch, the old WorkStealing dispatcher, replace old workstealer with new workstealer based on EBEDD, and remove jsr166x dependency, only 3 more deps to go until 0 deps for akka-actor [Viktor Klang]
| * | | | |   2b37b60 2011-03-01 | Merge branch 'master' into derekjw-future-dispatch [Derek Williams]
| |\ \ \ \ \  
| | | |_|/ /  
| | |/| | |   
| | * | | | ec84822 2011-03-01 | update Buncher to make it more generic [Roland Kuhn]
| | * | | |   31a717a 2011-03-01 | Merge branch 'master' into 669-krasserm [Martin Krasser]
| | |\ \ \ \  
| | | * | | | 65aa143 2011-03-01 | now using sjson without scalaz dependency [Debasish Ghosh]
| | | | |/ /  
| | | |/| |   
| | * | | | 8fe909a 2011-03-01 | Reset currentMessage if InterruptedException is thrown [Martin Krasser]
| | * | | | 7be3ddb 2011-03-01 | Ensure proper cleanup even if postStop throws an exception. [Martin Krasser]
| | * | | | 42cf34d 2011-03-01 | Support self.reply in preRestart and postStop after exception in receive. Closes #669 [Martin Krasser]
| | |/ / /  
| | * | |   887b184 2011-02-26 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| | |\ \ \  
| | * | | | 1d0b038 2011-02-26 | removed sjson from embedded_repo. Now available from scala-tools. Upgraded sjson version to 0.9 which compiles on Scala 2.8.1 [Debasish Ghosh]
| * | | | | 0380957 2011-03-01 | Add first test of Future in Java [Derek Williams]
| * | | | | fbd3bd6 2011-02-28 | Add java friendly methods [Derek Williams]
| * | | | | 3ba5c9b 2011-02-28 | Reverse listeners before executing them [Derek Williams]
| * | | | | b05be42 2011-02-28 | Add locking in dispatchFuture [Derek Williams]
| * | | | | 162d059 2011-02-28 | Add friendlier method of starting a Future [Derek Williams]
| * | | | | 5d290dc 2011-02-28 | move unneeded test outside of if statement [Derek Williams]
| * | | | | 445c2e6 2011-02-28 | Add low priority implicit for the default dispatcher [Derek Williams]
| * | | | | 92a1761 2011-02-28 | no need to hold onto the lock to throw the exception [Derek Williams]
| * | | | | 810499f 2011-02-28 | Revert lock bypass. move lock calls outside of try block [Derek Williams]
| * | | | | df2e209 2011-02-27 | bypass lock when not needed [Derek Williams]
| * | | | | 4137a6c 2011-02-26 | removed sjson from embedded_repo. Now available from scala-tools. Upgraded sjson version to 0.9 which compiles on Scala 2.8.1 [Debasish Ghosh]
| * | | | | b19e104 2011-02-25 | Reorder Futures.future params to take better advantage of default values [Derek Williams]
| * | | | | 3a62cab 2011-02-25 | Reorder Futures.future params to take better advantage of default values [Derek Williams]
| * | | | | 40afb9e 2011-02-25 | Can't share uuid lists [Derek Williams]
| * | | | |   622c272 2011-02-25 | Merge branch 'master' into derekjw-future-dispatch [Derek Williams]
| |\ \ \ \ \  
| | | |/ / /  
| | |/| | |   
| | * | | | 532baf5 2011-02-25 | Fix for timeout not being inherited from the builder future [Derek Williams]
| | | |/ /  
| | |/| |   
| * | | | b2c62ba 2011-02-25 | Run independent futures on the dispatcher directly [Derek Williams]
| |/ / /  
| * | | a76e620 2011-02-25 | Specialized traverse and sequence methods for Traversable[Future[A]] => Future[Traversable[A]] [Derek Williams]
| |/ /  
| * | 783fc85 2011-02-23 | document some methods on Future [Derek Williams]
| * | 444ddc6 2011-02-24 | Removing method that shouldve been removed in 1.0: startLinkRemote [Viktor Klang]
| * | 885ad83 2011-02-24 | Removing method that shouldve been removed in 1.0: startLinkRemote [Viktor Klang]
| * |   52343c4 2011-02-23 | Merge branch 'derekjw-future' [Derek Williams]
| |\ \  
| | * | 1e66d31 2011-02-22 | Reduce allocations [Derek Williams]
| | * |   b1c1f22 2011-02-22 | Merge branch 'master' into derekjw-future [Derek Williams]
| | |\ \  
| | * | | 9d22fd0 2011-02-22 | Add test for folding futures by composing [Derek Williams]
| | * | | 9d63746 2011-02-22 | Add test for composing futures [Derek Williams]
| | * | | 5c5f7d5 2011-02-21 | add Future.filter for use in for comprehensions [Derek Williams]
| | * | | e0cb666 2011-02-21 | Add methods to Future for map, flatMap, and foreach [Derek Williams]
| * | | | bab02c9 2011-02-23 | Fixing bug in ifOffYield [Viktor Klang]
| | |/ /  
| |/| |   
| * | | 388b878 2011-02-22 | Fixing a regression in Actor [Viktor Klang]
| * | |   03a9033 2011-02-22 | Merge branch 'wip-ebedd-tune' [Viktor Klang]
| |\ \ \  
| | |/ /  
| |/| |   
| | * | c4bd68a 2011-02-21 | Added some minor migration comments for Scala 2.9.0 [Viktor Klang]
| | * | 002fb70 2011-02-20 | Added a couple of final declarations on methods and reduced volatile reads [Viktor Klang]
| | * | 808426d 2011-02-15 | Manual inlining and indentation [Viktor Klang]
| | * | 2fc0e11 2011-02-15 | Lowering overhead for receiving messages [Viktor Klang]
| | * |   1f257d7 2011-02-14 | Merge branch 'master' of github.com:jboner/akka into wip-ebedd-tune [Viktor Klang]
| | |\ \  
| | * | | 4c0b911 2011-02-14 | Spellchecking and elided a try-block [Viktor Klang]
| | * | | 05c1274 2011-02-14 | Removing conditional scheduling [Viktor Klang]
| | * | | fef5bc4 2011-02-14 | Possible optimization for EBEDD [Viktor Klang]
| * | | |   147edfe 2011-02-15 | Merge branch 'master' of github.com:jboner/akka [Garrick Evans]
| |\ \ \ \  
| | * | | | daaa596 2011-02-16 | Update to Multiverse 0.6.2 [Peter Vlugter]
| * | | | | 9d3fb15 2011-02-15 | ticket 634; adds filters to respond to raw pressure functions; updated test spec [Garrick Evans]
| |/ / / /  
| * | | |   31baec6 2011-02-14 | Merge branch 'master' of github.com:jboner/akka [Derek Williams]
| |\ \ \ \  
| | | |/ /  
| | |/| |   
| | * | | 540dc22 2011-02-14 | Adding support for PoisonPill [Viktor Klang]
| * | | | 93b2ef5 2011-02-14 | Small change to better take advantage of latest Future changes [Derek Williams]
| |/ / /  
| * | | 8a98406 2011-02-13 | Add Future.receive(pf: PartialFunction[Any,Unit]), closes #636 [Derek Williams]
| * | |   649a438 2011-02-13 | Merge branch '661-derekjw' [Derek Williams]
| |\ \ \  
| | |/ /  
| |/| |   
| | * | b23ac5f 2011-02-13 | Refactoring based on Viktor's suggestions [Derek Williams]
| | * | 9f3c38f 2011-02-12 | Allow specifying the timeunit of a Future's timeout. The compiler should also no longer store the timeout field since it is not referenced in any methods anymore [Derek Williams]
| | * | 0db618f 2011-02-12 | Add method on Future to await and return the result. Works like resultWithin, but does not need an explicit timeout. [Derek Williams]
| | * | 8df62e9 2011-02-12 | move repeated code to it's own method, replace loop with tailrec [Derek Williams]
| | * | 311a881 2011-02-12 | Rename completeWithValue to complete [Derek Williams]
| | * | 87bd862 2011-02-11 | Throw an exception if Future.await is called on an expired and uncompleted Future. Ref #659 [Derek Williams]
| | * | 2ec6233 2011-02-11 | Use an Option[Either[Throwable, T]] to hold the value of a Future [Derek Williams]
| | |/  
| * | 0fe4d8c 2011-02-12 | fix tabs; remove debugging log line [Garrick Evans]
| * | a274c5f 2011-02-12 | ticket 664 - update continuation handling to (re)support updating timeout [Garrick Evans]
| |/  
| *   c74bb06 2011-02-11 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | * b1223ac 2011-02-11 | Update scalatest to version 1.3, closes #663 [Derek Williams]
| * | c43f8ae 2011-02-11 | Potential fix for race-condition in RemoteClient [Viktor Klang]
| |/  
| * d1213f2 2011-02-09 | Fixing neglected configuration in WorkStealer [Viktor Klang]
| *   418b5ce 2011-02-08 | Merge branch 'master' of github.com:jboner/akka [Garrick Evans]
| |\  
| | *   b472346 2011-02-08 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\  
| | * | e43ccf3 2011-02-08 | API improvements to Futures and some code cleanup [Viktor Klang]
| | * | 83f9c49 2011-02-08 | Fixing ticket #652 - Reaping expired futures [Viktor Klang]
| * | | acab31a 2011-02-08 | changed pass criteria for testBoundedCapacityActorPoolWithMailboxPressure to account for more capacity additions [Garrick Evans]
| | |/  
| |/|   
| * | e2e0abe 2011-02-08 | Exclude samples and sbt plugin from parent pom [Peter Vlugter]
| * | b23528b 2011-02-08 | Fix publish release to include parent poms correctly [Peter Vlugter]
| |/  
| * bc423fc 2011-02-07 | Fixing ticket #645 adding support for resultWithin on Future [Viktor Klang]
| * 4b9621d 2011-02-07 | Fixing #648 Adding support for configuring Netty backlog in akka config [Viktor Klang]
| * ca9b234 2011-02-04 | Fix for local actor ref home address [Peter Vlugter]
| * d9d4db4 2011-02-03 | Adding Java API for ReceiveTimeout [Viktor Klang]
| *   93411d7 2011-02-01 | Merge branch 'master' of github.com:jboner/akka [Garrick Evans]
| |\  
| | * d3f4e00 2011-02-02 | Disable -optimise and -Xcheckinit compiler options [Peter Vlugter]
| * | e4efff1 2011-02-01 | ticket #634 - add actor pool. initial version with unit tests [Garrick Evans]
| |/  
| * 83d0b12 2011-02-01 | Enable compile options in sub projects [Peter Vlugter]
| * 3c9ce3b 2011-01-31 | Fixing a possible race-condition in netty [Viktor Klang]
| * bd185eb 2011-01-28 | Changing to getPathInfo instead of getRequestURI for Mist [Viktor Klang]
| * 303c03d 2011-01-26 | Porting the tests from wip-628-629 [Viktor Klang]
| * 4486735 2011-01-25 | Potential fix for #628 and #629 [Viktor Klang]
| *   1749965 2011-01-26 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\  
| | * 6376061 2011-01-25 | Potential fix for #628 and #629 [Viktor Klang]
| * | 64f0e82 2011-01-25 | Potential fix for #628 and #629 [Viktor Klang]
| |/  
| * 64484f0 2011-01-24 | Added support for empty inputs for fold and reduce on Future [Viktor Klang]
| * 35457a4 2011-01-24 | Refining signatures on fold and reduce [Viktor Klang]
| * ad26903 2011-01-24 | Added Futures.reduce plus tests [Viktor Klang]
| * 9b2a187 2011-01-24 | Adding unit tests to Futures.fold [Viktor Klang]
| * ba3e71d 2011-01-24 | Adding docs to Futures.fold [Viktor Klang]
| * 2c8a8e4 2011-01-24 | Adding fold to Futures and fixed a potential memory leak in Future [Viktor Klang]
| *   1fd5fbe 2011-01-22 | Merge branch 'master' of github.com:jboner/akka [Derek Williams]
| |\  
| | * 4a7ef22 2011-01-22 | Upgrade hawtdispatch to 1.1 [Hiram Chirino]
| * | 4dd927d 2011-01-22 | Use correct config keys. Fixes #624 [Derek Williams]
| |/  
| * 0959824 2011-01-22 | Fix dist building [Peter Vlugter]
| * 149e060 2011-01-21 | Adding Odds project enhancements [Viktor Klang]
| *   b4a6e83 2011-01-21 | Merge branch 'master' of github.com:jboner/akka into newmaster [Viktor Klang]
| |\  
| | * d9539df 2011-01-21 | Add release scripts [Peter Vlugter]
| | * 208578e 2011-01-21 | Add build-release task [Peter Vlugter]
| * | 2a792cd 2011-01-20 | Making MessageInvocation a case class [Viktor Klang]
| * | 011e90b 2011-01-20 | Removing durable mailboxes from akka [Viktor Klang]
| |/  
| * 536bb18 2011-01-18 | Reverting lazy addition on repos [Viktor Klang]
| * d60694a 2011-01-18 | Fixing ticket #614 [Viktor Klang]
| * 7c99f88 2011-01-17 | Switching to Peters cleaner solution [Viktor Klang]
| * 47fb6a4 2011-01-17 | Allowing forwards where no sender of the message can be found. [Viktor Klang]
| * ea5648e 2011-01-11 | Added test for failing TypedActor with method 'String hello(String s)' [Jonas Bonér]
| * 139a064 2011-01-11 | Fixed some TypedActor tests [Jonas Bonér]
| * 8992814 2011-01-08 | Fixing ticket #608 [Viktor Klang]
| * ae85bd0 2011-01-08 | Update dependencies in sbt plugin [Peter Vlugter]
| * 8a439a8 2011-01-05 | Making optimizeLocal public [Viktor Klang]
| * afdc437 2011-01-05 | Adding more start methods for RemoteSupport because of Java, and added BeanProperty on some events [Viktor Klang]
| * 6360cd1 2011-01-05 | Minor code cleanup and deprecations etc [Viktor Klang]
| * b0a64ca 2011-01-05 | Changed URI to akka.io [Jonas Bonér]
| * d6a5b4d 2011-01-05 | Fixing ticket #603 [Viktor Klang]
| *   4d3a027 2011-01-04 | Merge branch 'remote_deluxe' [Viktor Klang]
| |\  
| | * c71fcb4 2011-01-04 | Minor typed actor lookup cleanup [Viktor Klang]
| | * e17b4f4 2011-01-04 | Removing ActorRegistry object, UntypedActor object, introducing akka.actor.Actors for the Java API [Viktor Klang]
| | *   accbfd0 2011-01-03 | Merge with master [Viktor Klang]
| | |\  
| | * | 9d2347e 2011-01-03 | Adding support for non-delivery notifications on server-side as well + more code cleanup [Viktor Klang]
| | * | 504b0e9 2011-01-03 | Major code clanup, switched from nested ifs to match statements etc [Viktor Klang]
| | * | 5af6b4d 2011-01-03 | Putting the Netty-stuff in akka.remote.netty and disposing of RemoteClient and RemoteServer [Viktor Klang]
| | * | fd831bb 2011-01-03 | Removing PassiveRemoteClient because of architectural problems [Viktor Klang]
| | * | 25f33d5 2011-01-01 | Added lock downgrades and fixed unlocking ordering [Viktor Klang]
| | * | 3c7d96f 2011-01-01 | Minor code cleanup [Viktor Klang]
| | * | 3d502a5 2011-01-01 | Added support for passive connections in Netty remoting, closing ticket #507 [Viktor Klang]
| | * | f1f8d64 2010-12-30 | Adding support for failed messages to be notified to listeners, this closes ticket #587 [Viktor Klang]
| | * | 3da0669 2010-12-29 | Removed if statement because it looked ugly [Viktor Klang]
| | * | 326a939 2010-12-29 | Fixing #586 and #588 and adding support for reconnect and shutdown of individual clients [Viktor Klang]
| | * | 3f737f9 2010-12-29 | Minor refactoring to ActorRegistry [Viktor Klang]
| | * | 9b52e82 2010-12-29 | Moving shared remote classes into RemoteInterface [Viktor Klang]
| | * | 52cb25c 2010-12-29 | Changed wording in the unoptimized local scoped spec [Viktor Klang]
| | * | 924394f 2010-12-29 | Adding tests for optimize local scoped and non-optimized local scoped [Viktor Klang]
| | * | e85715c 2010-12-29 | Moved all actorOf-methods from Actor to ActorRegistry and deprecated the forwarders in Actor [Viktor Klang]
| | * | 4b97e02 2010-12-28 | Fixing erronous test [Viktor Klang]
| | * | 10f2b4d 2010-12-28 | Adding additional tests [Viktor Klang]
| | * | b3a8cfa 2010-12-28 | Adding example in test to show how to test remotely using only one registry [Viktor Klang]
| | * |   080d8c5 2010-12-27 | Merged with current master [Viktor Klang]
| | |\ \  
| | * | | 32da307 2010-12-22 | WIP [Viktor Klang]
| | * | | 2cecd81 2010-12-21 | All tests passing, still some work to be done though, but thank God for all tests being green ;) [Viktor Klang]
| | * | | 907f495 2010-12-20 | Removing redundant call to ActorRegistry-register [Viktor Klang]
| | * | | 1feb58e 2010-12-20 | Reverted to using LocalActorRefs for client-managed actors to get supervision working, more migrated tests [Viktor Klang]
| | * | |   9bb15ce 2010-12-20 | Merged with release_1_0_RC1 plus fixed some tests [Viktor Klang]
| | |\ \ \  
| | | * | | b2dc5e0 2010-12-20 | Making sure RemoteActorRef.loader is passed into RemoteClient, also adding volatile flag to classloader in Serializer to make sure changes are propagated crossthreads [Viktor Klang]
| | | * | | 2b8621e 2010-12-20 | Giving all remote messages their own uuid, reusing actorInfo.uuid for futures, closing ticket 580 [Viktor Klang]
| | | * | | 644d399 2010-12-20 | Adding debug log of parse exception in parseException [Viktor Klang]
| | | * | | 9ad59fd 2010-12-20 | Adding UnparsableException and make sure that non-recreateable exceptions dont mess up the pipeline [Viktor Klang]
| | | * | | feba500 2010-12-19 | Give modified configgy a unique version and add a link to the source repository [Derek Williams]
| | | * | | 5c77ff3 2010-12-20 | Refine transactor doNothing (fixes #582) [Peter Vlugter]
| | | * | | 79c1b8f 2010-12-18 | Backport from master, add new Configgy version with logging removed [Derek Williams]
| | | * | | 67b020c 2010-12-16 | Update group id in sbt plugin [Peter Vlugter]
| | * | | | 17d50ed 2010-12-17 | Commented out many of the remote tests while I am porting [Viktor Klang]
| | * | | | b7ab4a1 2010-12-17 | Fixing a lot of stuff and starting to port unit tests [Viktor Klang]
| | * | | | 8814e97 2010-12-15 | Got API in place now and RemoteServer/Client/Node etc purged. Need to get test-compile to work so I can start testing the new stuff... [Viktor Klang]
| | * | | | 7bae3f2 2010-12-14 | Switch to a match instead of a not-so-cute if [Viktor Klang]
| | * | | | 922348e 2010-12-14 | First shot at re-doing akka-remote [Viktor Klang]
| | |/ / /  
| | * | | e59eed7 2010-12-14 | Fixing a glitch in the API [Viktor Klang]
| * | | |   91c2289 2011-01-04 | Merge branch 'testkit' [Roland Kuhn]
| |\ \ \ \  
| | |_|_|/  
| |/| | |   
| | * | | da65c13 2011-01-04 | fix up indentation [Roland Kuhn]
| | * | |   49b45af 2011-01-04 | Merge branch 'testkit' of git-proxy:jboner/akka into testkit [momania]
| | |\ \ \  
| | | * | | c2402e3 2011-01-03 | also test custom whenUnhandled fall-through [Roland Kuhn]
| | | * | |   d5fc6f8 2011-01-03 | merge Irmo's changes and add test case for whenUnhandled [Roland Kuhn]
| | | |\ \ \  
| | | * | | | 5092eb0 2011-01-03 | remove one more allocation in hot path [Roland Kuhn]
| | | * | | | 9dbfad3 2011-01-03 | change indentation to 2 spaces [Roland Kuhn]
| | * | | | | d46a979 2011-01-04 | small adjustment to the example, showing the correct use of the startWith and initialize [momania]
| | * | | | | e5c9e77 2011-01-03 | wrap initial sending of state to transition listener in CurrentState object with fsm actor ref [momania]
| | * | | | | a4ddcd9 2011-01-03 | add fsm self actor ref to external transition message [momania]
| | | |/ / /  
| | |/| | |   
| | * | | | aee972f 2011-01-03 | Move handleEvent var declaration _after_ handleEventDefault val declaration. Using a val before defining it causes nullpointer exceptions... [momania]
| | * | | | 31da5b9 2011-01-03 | Removed generic typed classes that are only used in the FSM itself from the companion object and put back in the typed FSM again so they will take same types. [momania]
| | * | | | d09adea 2011-01-03 | fix tests [momania]
| | * | | | 5ee0cab 2011-01-03 | - make transition handler a function taking old and new state avoiding the default use of the transition class - only create transition class when transition listeners are subscribed [momania]
| | * | | | d9b3e42 2011-01-03 | stop the timers (if any) while terminating [momania]
| | |/ / /  
| | * | | 6150279 2011-01-01 | convert test to WordSpec with MustMatchers [Roland Kuhn]
| | * | | f4d87fa 2011-01-01 | fix fallout of Duration changes in STM tests [Roland Kuhn]
| | * | | b0db21f 2010-12-31 | make TestKit assertions nicer / improve Duration [Roland Kuhn]
| | * | | eddbac5 2010-12-31 | remove unnecessary allocations in hot paths [Roland Kuhn]
| | * | | 6f0d73b 2010-12-30 | flesh out FSMTimingSpec [Roland Kuhn]
| | * | | 7e729ce 2010-12-29 | add first usage of TestKit [Roland Kuhn]
| | * | | 9474a92 2010-12-29 | code cleanup (thanks, Viktor and Irmo) [Roland Kuhn]
| | * | | 66a6351 2010-12-28 | revamp TestKit (with documentation) [Roland Kuhn]
| | * | | 4e79804 2010-12-28 | Improve Duration classes [Roland Kuhn]
| | * | | bcdb429 2010-12-28 | add facility for changing stateTimeout dynamically [Roland Kuhn]
| | * | | 66595b7 2010-12-26 | first sketch of basic TestKit architecture [Roland Kuhn]
| | * | |   1eb9abd 2010-12-26 | Merge remote branch 'origin/fsmrk2' into testkit [Roland Kuhn]
| | |\ \ \  
| | | * | | 9d4cdde 2010-12-24 | improved test - test for intial state on transition call back and use initialize function from FSM to kick of the machine. [momania]
| | | * | | 576e121 2010-12-24 | wrap stop reason in stop even with current state, so state can be referenced in onTermination call for cleanup reasons etc [momania]
| | | * | | 606216a 2010-12-20 | add minutes and hours to Duration [Roland Kuhn]
| | | * | | a94b5e5 2010-12-20 | add user documentation comments to FSM [Roland Kuhn]
| | | * | | 12ea64c 2010-12-20 | - merge in transition callback handling - renamed notifying -> onTransition - updated dining hakkers example and unit test - moved buncher to fsm sample project [momania]
| | | * | | e12eb3e 2010-12-19 | change ff=unix [Roland Kuhn]
| | | * | | 30c11fe 2010-12-19 | improvements on FSM [Roland Kuhn]
| | * | | | c7150f6 2010-12-26 | revamp akka.util.Duration [Roland Kuhn]
| * | | | | f45a86b 2010-12-30 | Adding possibility to set id for TypedActor [Viktor Klang]
| * | | | | 9b12ab3 2010-12-28 | Fixed logging glitch in ReflectiveAccess [Viktor Klang]
| * | | | | 8836d72 2010-12-28 | Making EmbeddedAppServer work without AKKA_HOME [Viktor Klang]
| | |_|_|/  
| |/| | |   
| * | | | 0eb7141 2010-12-27 | Fixing ticket 594 [Viktor Klang]
| * | | |   4a75f0a 2010-12-27 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| | * | | 57d0e85 2010-12-22 | Updated the copyright header to 2009-2011 [Jonas Bonér]
| * | | | 989b1d5 2010-12-22 | Removing not needed dependencies [Viktor Klang]
| |/ / /  
| * | | ada47ff 2010-12-22 | Closing ticket 541 [Viktor Klang]
| * | | 6edd307 2010-12-22 | removed trailing spaces [Jonas Bonér]
| * | | eefa38f 2010-12-22 | Enriched TypedActorContext [Jonas Bonér]
| * | |   98aa321 2010-12-22 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * | | 060628f 2010-12-22 | Throw runtime exception for @Coordinated when used with non-void methods [Peter Vlugter]
| * | | | c6376b0 2010-12-22 | changed config element name [Jonas Bonér]
| |/ / /  
| * | |   b279935 2010-12-21 | changed config for JMX enabling [Jonas Bonér]
| |\ \ \  
| | * | | 2d72061 2010-12-21 | added option to turn on/off JMX browsing of the configuration [Jonas Bonér]
| * | | | 185b03c 2010-12-21 | added option to turn on/off JMX browsing of the configuration [Jonas Bonér]
| |/ / /  
| * | | f2c13b2 2010-12-21 | removed persistence stuff from config [Jonas Bonér]
| * | |   417b628 2010-12-21 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * | | cdc9863 2010-12-21 | Closing ticket #585 [Viktor Klang]
| | * | | 5b22e09 2010-12-20 | Making sure RemoteActorRef.loader is passed into RemoteClient, also adding volatile flag to classloader in Serializer to make sure changes are propagated crossthreads [Viktor Klang]
| | * | | caae57f 2010-12-20 | Giving all remote messages their own uuid, reusing actorInfo.uuid for futures, closing ticket 580 [Viktor Klang]
| | * | | 1715dbf 2010-12-20 | Adding debug log of parse exception in parseException [Viktor Klang]
| | * | | 95b9145 2010-12-20 | Adding UnparsableException and make sure that non-recreateable exceptions dont mess up the pipeline [Viktor Klang]
| | * | | bb809fd 2010-12-19 | Give modified configgy a unique version and add a link to the source repository [Derek Williams]
| | * | | 87138b7 2010-12-20 | Refine transactor doNothing (fixes #582) [Peter Vlugter]
| | |/ /  
| | * | c93a447 2010-12-18 | Remove workaround since Configgy has logging removed [Derek Williams]
| | * | fe80c38 2010-12-18 | Use new configgy [Derek Williams]
| | * | 4de4990 2010-12-18 | New Configgy version with logging removed [Derek Williams]
| * | | c8335a6 2010-12-21 | Fixed bug with not setting homeAddress in RemoteActorRef [Jonas Bonér]
| |/ /  
| * | 4487e9f 2010-12-16 | Update group id in sbt plugin [Peter Vlugter]
| * | dd7a358 2010-12-14 | Fixing a glitch in the API [Viktor Klang]
| * | 89beb72 2010-12-13 | Bumping version to 1.1-SNAPSHOT [Viktor Klang]
| * |   95a9a17 2010-12-13 | Merge branch 'release_1_0_RC1' [Viktor Klang]
| |\ \  
| | |/  
| | * 7d1befc 2010-12-13 | Changing versions to 1.0-RC2-SNAPSHOT [Viktor Klang]
| | *   783b665 2010-12-10 | Merge branch 'release_1_0_RC1' of github.com:jboner/akka into release_1_0_RC1 [Jonas Bonér]
| | |\  
| | * | 15b5ef4 2010-12-10 | fixed bug in which the default configuration timeout was always overridden by 5000L [Jonas Bonér]
| | * | 01f2f01 2010-12-07 | applied McPom [Jonas Bonér]
| * | |   7a3b64d 2010-12-10 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * \ \   a158f99 2010-12-09 | Merge branch 'master' of github.com:jboner/akka into ticket-538 [ticktock]
| | |\ \ \  
| | | * \ \   4342178 2010-12-08 | Merge branch 'release_1_0_RC1' [Viktor Klang]
| | | |\ \ \  
| | | | | |/  
| | | | |/|   
| | | | * | 9769d78 2010-12-08 | Adding McPom [Viktor Klang]
| | | | |/  
| | | | * 8371fcf 2010-12-01 | changed access modifier for RemoteServer.serverFor [Jonas Bonér]
| | | | *   fc97562 2010-11-30 | Merge branch 'release_1_0_RC1' of github.com:jboner/akka into release_1_0_RC1 [Jonas Bonér]
| | | | |\  
| | | | * | 1004ec2 2010-11-30 | renamed DurableMailboxType to DurableMailbox [Jonas Bonér]
| | * | | |   821356b 2010-11-28 | merged module move refactor from master [ticktock]
| | |\ \ \ \  
| | * \ \ \ \   b6502be 2010-11-22 | Merge branch 'master' of github.com:jboner/akka into ticket-538 [ticktock]
| | |\ \ \ \ \  
| | * | | | | | d70398b 2010-11-22 | Move persistent commit to a pre-commit handler [ticktock]
| | * | | | | | 14f4cc5 2010-11-19 | factor out redundant code [ticktock]
| | * | | | | | 62f0bae 2010-11-19 | cleanup from wip merge [ticktock]
| | * | | | | | d2ed29e 2010-11-19 | check reference equality when registering a persistent datastructure, and fail if one is already there with a different reference [ticktock]
| | * | | | | | 12b50a6 2010-11-18 | added retries to persistent state commits, and restructured the storage api to provide management over the number of instances of persistent datastructures [ticktock]
| | * | | | | |   ae0292c 2010-11-18 | merge wip [ticktock]
| | |\ \ \ \ \ \  
| | | * | | | | | c97df19 2010-11-18 | add TX retries, and add a helpful error logging in ReflectiveAccess [ticktock]
| | | * | | | | | 3c89c3e 2010-11-18 | most of the work for retry-able persistence commits and managing the instances of persistent data structures [ticktock]
| | | * | | | | | 94f1687 2010-11-16 | wip [ticktock]
| | | * | | | | | 4886a28 2010-11-15 | wip [ticktock]
| | | * | | | | | 61604a7 2010-11-15 | wip [ticktock]
| | | * | | | | | 98eb924 2010-11-15 | wip [ticktock]
| | | * | | | | |   54895ce 2010-11-15 | Merge branch 'master' into wip-ticktock-persistent-transactor [ticktock]
| | | |\ \ \ \ \ \  
| | | * | | | | | | d4cd0ff 2010-11-15 | retry only failed/not executed Ops if the starabe backend is not transactional [ticktock]
| | | * | | | | | |   5494fec 2010-11-15 | Merge branch 'master' of https://github.com/jboner/akka into wip-ticktock-persistent-transactor [ticktock]
| | | |\ \ \ \ \ \ \  
| | | * | | | | | | | aa28253 2010-11-15 | initial sketch [ticktock]
| * | | | | | | | | | 1b8493c 2010-12-10 | fixed bug in which the default configuration timeout was always overridden by 5000L [Jonas Bonér]
| | |_|_|_|_|_|/ / /  
| |/| | | | | | | |   
| * | | | | | | | | 80a3b6d 2010-12-01 | Instructions on how to run the sample [Jonas Bonér]
| * | | | | | | | |   3f3d391 2010-12-01 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \   670a81f 2010-12-01 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|_|_|/  
| | |/| | | | | | | |   
| | * | | | | | | | | 3ab9f3a 2010-11-30 | Fixing SLF4J logging lib switch, insane API FTL [Viktor Klang]
| | | |_|_|_|_|_|_|/  
| | |/| | | | | | |   
| * | | | | | | | | dd7add3 2010-12-01 | changed access modifier for RemoteServer.serverFor [Jonas Bonér]
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | |   238c8da 2010-11-30 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | | ef8b0b1 2010-11-29 | Adding Java API for per session remote actors, closing ticket #561 [Viktor Klang]
| | | |_|_|_|_|/  
| | |/| | | | |   
| * | | | | | | dd5d761 2010-11-30 | renamed DurableMailboxType to DurableMailbox [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 37998f2 2010-11-26 | bumped version to 1.0-RC1 (v1.0-RC1) [Jonas Bonér]
| * | | | | | 540068e 2010-11-26 | Switched AkkaLoader to use Switch instead of volatile boolean [Viktor Klang]
| * | | | | |   82d5fc4 2010-11-25 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   d56b90f 2010-11-25 | Merge branch 'master' of github.com:jboner/akka [momania]
| | |\ \ \ \ \ \  
| | * | | | | | | 2ad8aeb 2010-11-25 | - added local maven repo as repo for libs so publishing works - added databinder repo again: needed for publish to work [momania]
| * | | | | | | | bc24bc4 2010-11-25 | Moving all message typed besides Transition into FSM object [Viktor Klang]
| | |/ / / / / /  
| |/| | | | | |   
| * | | | | | | 3494a2a 2010-11-25 | Adding AkkaRestServlet that will provide the same functionality as the AkkaServlet - Atmosphere [Viktor Klang]
| * | | | | | |   3362dab 2010-11-25 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| | * | | | | | 1ecf440 2010-11-24 | removed trailing whitespace [Jonas Bonér]
| | * | | | | | acc0883 2010-11-24 | tabs to spaces [Jonas Bonér]
| | * | | | | | 6f2124a 2010-11-24 | removed dataflow stream for good [Jonas Bonér]
| | * | | | | | 19bfa7a 2010-11-24 | renamed dataflow variable file [Jonas Bonér]
| | * | | | | |   2a0b13e 2010-11-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \  
| | * | | | | | | 2b28ba9 2010-11-24 | uncommented the dataflowstream tests and they all pass [Jonas Bonér]
| * | | | | | | |   7cff6e2 2010-11-24 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| | | |/ / / / / /  
| | |/| | | | | |   
| | * | | | | | |   03a2167 2010-11-24 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | bcc74df 2010-11-24 | - re-add fsm samples - removed ton of whitespaces from the project definition [momania]
| * | | | | | | | | afd3b39 2010-11-24 | Making HotSwap stacking not be the default [Viktor Klang]
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | | 6073f1d 2010-11-24 | Removing unused imports [Viktor Klang]
| * | | | | | | |   07aee56 2010-11-24 | Merge with master [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| | | |/ / / / / /  
| | |/| | | | | |   
| | * | | | | | |   8a2087e 2010-11-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | | |/ / / / / /  
| | | * | | | | | af18be0 2010-11-24 | - fix race condition with timeout - improved stopping mechanism - renamed 'until' to 'forMax' for less confusion - ability to specif timeunit for timeout [momania]
| | * | | | | | | 7c69a25 2010-11-24 | added effect to java api [Jonas Bonér]
| | |/ / / / / /  
| * | | | | | | ac7703c 2010-11-24 | Fixing silly error plus fixing bug in remtoe session actors [Viktor Klang]
| * | | | | | | 2e77519 2010-11-24 | Fixing %d for logging into {} [Viktor Klang]
| * | | | | | | 0bdaf52 2010-11-24 | Fixing all %s into {} for logging [Viktor Klang]
| * | | | | | | 40e40a5 2010-11-24 | Switching to raw SLF4J on internals [Viktor Klang]
| |/ / / / / /  
| * | | | | | f9f65a7 2010-11-23 | cleaned up project file [Jonas Bonér]
| * | | | | | bd29ede 2010-11-23 | Separated core from modules, moved modules to akka-modules repository [Jonas Bonér]
| * | | | | | 69777ca 2010-11-23 | Closing #555 [Viktor Klang]
| * | | | | |   cd416a8 2010-11-23 | Mist now integrated in master [Viktor Klang]
| |\ \ \ \ \ \  
| | * | | | | | 83d533e 2010-11-23 | Moving Mist into almost one file, changing Servlet3.0 into a Provided jar and adding an experimental Filter [Viktor Klang]
| | * | | | | |   7594e9d 2010-11-23 | Merge with master [Viktor Klang]
| | |\ \ \ \ \ \  
| | * | | | | | | 48815e0 2010-11-22 | Minor code tweaks, removing Atmosphere, awaiting some tests then ready for master [Viktor Klang]
| | * | | | | | |   89d7786 2010-11-22 | Merge branch 'master' into mist [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | 3d83a9c 2010-11-21 | added back old 2nd sample (http (mist)) [Garrick Evans]
| | * | | | | | | | 8c44f35 2010-11-21 | restore project and ref config to pre jetty-8 states [Garrick Evans]
| | * | | | | | | |   0fefcea 2010-11-20 | Merge branch 'wip-mist-http-garrick' of github.com:jboner/akka into wip-mist-http-garrick [Garrick Evans]
| | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | 020a9b6 2010-11-20 | removing odd git-added folder. [Garrick Evans]
| | | * | | | | | | | e5a9087 2010-11-20 | merge master to branch [Garrick Evans]
| | | * | | | | | | | 11908ab 2010-11-20 | hacking in servlet 3.0 support using embedded jetty-8 (remove atmo, hbase, volde to get around jar mismatch); wip [Garrick Evans]
| | | * | | | | | | | 92df463 2010-11-09 | most of the refactoring done and jetty is working again (need to check updating timeouts, etc); servlet 3.0 impl next [Garrick Evans]
| | | * | | | | | | | 5ff7b85 2010-11-08 | refactoring WIP - doesn't build; added servlet 3.0 api jar from glassfish to proj dep [Garrick Evans]
| | | * | | | | | | | 10f2fcc 2010-11-08 | adding back (mist) http work in a new branch. misitfy was too stale. this is WIP - trying to support both SAPI 3.0 and Jetty Continuations at once [Garrick Evans]
| | * | | | | | | | | 1eeaef0 2010-11-20 | fixing a screwy merge from master... readding files git deleted for some unknown reason [Garrick Evans]
| | * | | | | | | | | 7c2b979 2010-11-20 | removing odd git-added folder. [Garrick Evans]
| | * | | | | | | | | ae1ae76 2010-11-20 | merge master to branch [Garrick Evans]
| | * | | | | | | | | b9de374 2010-11-20 | hacking in servlet 3.0 support using embedded jetty-8 (remove atmo, hbase, volde to get around jar mismatch); wip [Garrick Evans]
| | * | | | | | | | | b177475 2010-11-09 | most of the refactoring done and jetty is working again (need to check updating timeouts, etc); servlet 3.0 impl next [Garrick Evans]
| | * | | | | | | | | d100989 2010-11-08 | refactoring WIP - doesn't build; added servlet 3.0 api jar from glassfish to proj dep [Garrick Evans]
| | * | | | | | | | | e5cf8c0 2010-11-08 | adding back (mist) http work in a new branch. misitfy was too stale. this is WIP - trying to support both SAPI 3.0 and Jetty Continuations at once [Garrick Evans]
| * | | | | | | | | | 17a512c 2010-11-23 | Switching to SBT 0.7.5.RC0 and now we can drop the ubly AkkaDeployClassLoader [Viktor Klang]
| * | | | | | | | | | 2d1fefe 2010-11-23 | upgraded to single jar aspectwerkz [Jonas Bonér]
| | |_|_|/ / / / / /  
| |/| | | | | | | |   
| * | | | | | | | | a8f94eb 2010-11-23 | Upgrade to Scala 2.8.1 [Jonas Bonér]
| * | | | | | | | | 86b3df3 2010-11-23 | Fixed problem with message toString is not lazily evaluated in RemoteClient [Jonas Bonér]
| * | | | | | | | |   96e33ea 2010-11-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|_|/  
| | |/| | | | | | |   
| | * | | | | | | | a9e7451 2010-11-23 | Disable cross paths on parent projects as well [Peter Vlugter]
| | * | | | | | | | 4ffedf9 2010-11-23 | Remove scala version from dist paths - fixes #549 [Peter Vlugter]
| | | |_|/ / / / /  
| | |/| | | | | |   
| * | | | | | | |   d6ab3de 2010-11-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | |   3cd9c0e 2010-11-22 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | e83b7d4 2010-11-22 | Fixed bug in ActorRegistry getting typed actor by manifest [Jonas Bonér]
| | * | | | | | | | 6c4e819 2010-11-22 | Merging in Actor per Session + fixing blocking problem with remote typed actors with Future response types [Viktor Klang]
| | * | | | | | | |   54f7690 2010-11-22 | Merge branch 'master' of https://github.com/paulpach/akka into paulpach-master [Viktor Klang]
| | |\ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \   1996627 2010-11-20 | Merge branch 'master' of git://github.com/jboner/akka [Paul Pacheco]
| | | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | | d08f428 2010-11-20 | tests pass [Paul Pacheco]
| | | * | | | | | | | | a4b122e 2010-11-19 | Cleaned up some semicolons Test now compiles (but does not pass) [Paul Pacheco]
| | | * | | | | | | | | 9f53ad3 2010-11-18 | Refatored createActor, separate unit tests cleanup according to viktor's suggestions [Paul Pacheco]
| | | * | | | | | | | |   bd8091b 2010-11-18 | Merge branch 'master' of git://github.com/jboner/akka [Paul Pacheco]
| | | |\ \ \ \ \ \ \ \ \  
| | | | | |_|_|_|/ / / /  
| | | | |/| | | | | | |   
| | | * | | | | | | | | 4c001fe 2010-11-18 | refactored the createActor function to make it easier to understand and remove the return statements; [Paul Pacheco]
| | | * | | | | | | | | dcf78ad 2010-11-18 | Cleaned up patch as suggested by Vicktor [Paul Pacheco]
| | | * | | | | | | | | 4ee49e3 2010-11-14 | Added remote typed session actors, along with unit tests [Paul Pacheco]
| | | * | | | | | | | | 6d62831 2010-11-14 | Added server initiated remote untyped session actors now you can register a factory function and whenever a new session starts, the actor will be created and started. When the client disconnects, the actor will be stopped. The client works the same as any other untyped remote server managed actor. [Paul Pacheco]
| | | * | | | | | | | |   8ae5e9e 2010-11-14 | Merge branch 'master' of git://github.com/jboner/akka into session-actors [Paul Pacheco]
| | | |\ \ \ \ \ \ \ \ \  
| | | | | |_|_|_|_|_|/ /  
| | | | |/| | | | | | |   
| | | * | | | | | | | | 8268ca7 2010-11-14 | Added interface for registering session actors, and adding unit test (which is failing now) [Paul Pacheco]
| * | | | | | | | | | | 188d7e9 2010-11-22 | Removed reflective coupling to akka cloud [Jonas Bonér]
| * | | | | | | | | | | fd2faec 2010-11-22 | Fixed issues with config - Ticket #535 [Jonas Bonér]
| * | | | | | | | | | | 4f8b3ea 2010-11-22 | Fixed bug in ActorRegistry getting typed actor by manifest [Jonas Bonér]
| | |_|_|_|_|/ / / / /  
| |/| | | | | | | | |   
| * | | | | | | | | | 9c07232 2010-11-22 | fixed wrong path in voldermort tests - now test are passing again [Jonas Bonér]
| * | | | | | | | | | 44d0f2d 2010-11-22 | Disable cross paths for publishing without Scala version [Peter Vlugter]
| |/ / / / / / / / /  
| * | | | | | | | |   4ab6f71 2010-11-21 | Merge with master [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | c387d00 2010-11-21 | Ticket #506 closed, caused by REPL [Viktor Klang]
| * | | | | | | | | | 02b1348 2010-11-21 | Ticket #506 closed, caused by REPL [Viktor Klang]
| |/ / / / / / / / /  
| * | | | | | | | | e119bd4 2010-11-21 | Moving dispatcher volatile field from ActorRef to LocalActorRef [Viktor Klang]
| * | | | | | | | | d73d277 2010-11-21 | Fixing ticket #533 by adding get/set LifeCycle in ActorRef [Viktor Klang]
| * | | | | | | | | a3548df 2010-11-21 | Fixing groupID for SBT plugin and change url to akka homepage [Viktor Klang]
| | |_|_|_|/ / / /  
| |/| | | | | | |   
| * | | | | | | | 7e40ee4 2010-11-20 | Adding a Java API for Channel, and adding some docs, have updated wiki, closing #536 [Viktor Klang]
| | |_|_|/ / / /  
| |/| | | | | |   
| * | | | | | | 2f62241 2010-11-20 | Added a root akka folder for source files for the docs to work properly, closing ticket #541 [Viktor Klang]
| * | | | | | | 2e12337 2010-11-20 | Changing signature for HotSwap to include self-reference, closing ticket #540 [Viktor Klang]
| * | | | | | | d80883c 2010-11-20 | Changing artifact IDs so they dont include scala version no, closing ticket #529 [Viktor Klang]
| | |_|/ / / /  
| |/| | | | |   
| * | | | | | 7290315 2010-11-18 | Making register and unregister of ActorRegistry private [Viktor Klang]
| * | | | | | bc41899 2010-11-18 | Change to akka-actor rather than akka-remote as default in sbt plugin [Peter Vlugter]
| * | | | | | 1dee84c 2010-11-18 | Change to akka-actor rather than akka-remote as default in sbt plugin [Peter Vlugter]
| * | | | | | 6c77de6 2010-11-16 | redis tests should not run by default [Debasish Ghosh]
| * | | | | | 3056668 2010-11-16 | fixed ticket #531 - Fix RedisStorage add() method in Java API : added Java test case akka-persistence/akka-persistence-redis/src/test/java/akka/persistence/redis/RedisStorageTests.java [Debasish Ghosh]
| | |_|_|_|/  
| |/| | | |   
| * | | | | b4842bb 2010-11-15 | fix ticket-532 [ticktock]
| | |/ / /  
| |/| | |   
| * | | | 2b9bfa9 2010-11-14 | Fixing ticket #530 [Viktor Klang]
| * | | | b699824 2010-11-14 | Update redis test after rebase [Peter Vlugter]
| * | | | 4ecd3f6 2010-11-14 | Remove disabled src in stm module [Peter Vlugter]
| * | | | ca64512 2010-11-14 | Move transactor.typed to other packages [Peter Vlugter]
| * | | | f57fc4a 2010-11-14 | Update agent and agent spec [Peter Vlugter]
| * | | | 6e28745 2010-11-13 | Add Atomically for transactor Java API [Peter Vlugter]
| * | | | c4a6abd 2010-11-13 | Add untyped coordinated example to be used in docs [Peter Vlugter]
| * | | | 89d2cee 2010-11-13 | Use coordinated.await in test [Peter Vlugter]
| * | | | 5fb14ae 2010-11-13 | Add coordinated transactions for typed actors [Peter Vlugter]
| * | | | a07a85a 2010-11-13 | Update new redis tests [Peter Vlugter]
| * | | | 489c3a5 2010-11-12 | Add untyped transactor [Peter Vlugter]
| * | | | 0fa9a67 2010-11-09 | Add Java API for coordinated transactions [Peter Vlugter]
| * | | | 49c2d71 2010-11-09 | Move Transactor and Coordinated to akka.transactor package [Peter Vlugter]
| * | | | 1fc7532 2010-11-09 | Some tidy up [Peter Vlugter]
| * | | | f18881b 2010-11-09 | Add reworked Agent [Peter Vlugter]
| * | | | 9a0b442 2010-11-07 | Update stm scaladoc [Peter Vlugter]
| * | | | 9f725b2 2010-11-07 | Add new transactor based on new coordinated transactions [Peter Vlugter]
| * | | | 2d3f3df 2010-11-06 | Add new mechanism for coordinated transactions [Peter Vlugter]
| * | | | 99d6d6b 2010-11-06 | Reworked stm with no global/local [Peter Vlugter]
| * | | | a32c30a 2010-11-05 | First pass on separating stm into its own module [Peter Vlugter]
| * | | |   7b0347f 2010-11-13 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | * | | | 2c9feef 2010-11-13 | Fixed Issue 528 - RedisPersistentRef should not throw in case of missing key [Debasish Ghosh]
| | * | | | ac1baa8 2010-11-13 | Implemented addition of entries with same score through zrange - updated test cases [Debasish Ghosh]
| | | |_|/  
| | |/| |   
| | * | | 0678719 2010-11-13 | Ensure unique scores for redis sorted set test [Peter Vlugter]
| | * | |   12ffe00 2010-11-12 | closing ticket 518 [ticktock]
| | |\ \ \  
| | | * | | c3af80f 2010-11-12 | minor simplification [momania]
| | | * | |   bc77464 2010-11-12 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | | |\ \ \  
| | | * | | | d22148b 2010-11-12 | - add possibility to specify channel prefetch side for consumer [momania]
| | | * | | |   15c3c8c 2010-11-08 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | | |\ \ \ \  
| | | * | | | | 5dc8012 2010-11-08 | - improved RPC, adding 'poolsize' and direct queue capabilities - add redelivery property to delivery [momania]
| | * | | | | |   febf3df 2010-11-12 | Merge branch 'ticket-518' of https://github.com/jboner/akka into ticket-518 [ticktock]
| | |\ \ \ \ \ \  
| | | * | | | | | e72e95b 2010-11-11 | fix source of compiler warnings [ticktock]
| | * | | | | | | 13bce50 2010-11-12 | clean up some code [ticktock]
| | |/ / / / / /  
| | * | | | | | cb7e987 2010-11-11 | finished enabling batch puts and gets in simpledb [ticktock]
| | * | | | | |   0c0dd6f 2010-11-11 | Merge branch 'master' of https://github.com/jboner/akka into ticket-518 [ticktock]
| | |\ \ \ \ \ \  
| | * | | | | | | 111246c 2010-11-10 | cassandra, riak, memcached, voldemort working, still need to enable bulk gets and puts in simpledb [ticktock]
| | * | | | | | | ca6569f 2010-11-10 | first pass at refactor, common access working (cassandra) now to KVAccess [ticktock]
| | | |_|_|_|/ /  
| | |/| | | | |   
| * | | | | | |   ae319f5 2010-11-12 | Merge branch 'remove-cluster' [Viktor Klang]
| |\ \ \ \ \ \ \  
| | |_|_|_|_|/ /  
| |/| | | | | |   
| | * | | | | | 98af9c4 2010-11-12 | Removing legacy code for 1.0 [Viktor Klang]
| * | | | | | |   3d69d05 2010-11-12 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \  
| | * \ \ \ \ \ \   353e306 2010-11-12 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| | |\ \ \ \ \ \ \  
| | | |/ / / / / /  
| | * | | | | | | 378deb7 2010-11-12 | updated test case to ensure that sorted sets have diff scores [Debasish Ghosh]
| | | |_|/ / / /  
| | |/| | | | |   
| * | | | | | | 23026d8 2010-11-12 | Adding configurable default dispatcher timeout and re-instating awaitEither [Viktor Klang]
| * | | | | | | 635d3d9 2010-11-12 | Adding Futures.firstCompleteOf to allow for composability [Viktor Klang]
| * | | | | | | b0f13c5 2010-11-12 | Replacing awaitOne with a listener based approach [Viktor Klang]
| * | | | | | | 78a00b4 2010-11-12 | Adding support for onComplete listeners to Future [Viktor Klang]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | df66185 2010-11-11 | Fixing ticket #519 [Viktor Klang]
| * | | | | | 4bdc209 2010-11-11 | Removing pointless synchroniation [Viktor Klang]
| * | | | | | 8c2ed8b 2010-11-11 | Fixing #522 [Viktor Klang]
| * | | | | | a89f79a 2010-11-11 | Fixing ticket #524 [Viktor Klang]
| |/ / / / /  
| * | | | | 6c20016 2010-11-11 | Fix for Ticket 513 : Implement snapshot based persistence control in SortedSet [Debasish Ghosh]
| |/ / / /  
| * | | |   39783de 2010-11-10 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | * \ \ \   b3b23ff 2010-11-09 | Merging support of amazon simpledb as a persistence backend [ticktock]
| | |\ \ \ \  
| | * | | | | c21fa12 2010-11-09 | test max key and value sizes [ticktock]
| | * | | | |   0a72006 2010-11-08 | merged master [ticktock]
| | |\ \ \ \ \  
| | * | | | | | 528a604 2010-11-08 | working simpledb backend, not the fastest thing in the world [ticktock]
| | * | | | | | 166802b 2010-11-08 | change var names for aws keys [ticktock]
| | * | | | | | 7d5a8e1 2010-11-08 | switching to aws-java-sdk, for consistent reads (Apache 2 licenced) finished impl [ticktock]
| | * | | | | | 48cf727 2010-11-08 | renaming dep [ticktock]
| | * | | | | | 9c0908e 2010-11-07 | wip for simpledb backend [ticktock]
| | | |_|_|/ /  
| | |/| | | |   
| * | | | | |   d941eff 2010-11-10 | Merge branch 'master' of https://github.com/paulpach/akka [Viktor Klang]
| |\ \ \ \ \ \  
| | * | | | | | 76f0bf8 2010-11-09 | Check that either implementation or ref are specified [Paul Pacheco]
| * | | | | | |   a013826 2010-11-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \  
| | | |_|_|/ / /  
| | |/| | | | |   
| | * | | | | |   c4af7c9 2010-11-09 | Merge branch '473-krasserm' [Martin Krasser]
| | |\ \ \ \ \ \  
| | | |_|_|/ / /  
| | |/| | | | |   
| | | * | | | | 121bb8c 2010-11-09 | Customizing routes to typed consumer actors (Scala and Java API) and refactorings. [Martin Krasser]
| | | * | | | | 78d946c 2010-11-08 | Java API for customizing routes to consumer actors [Martin Krasser]
| | | * | | | | b8c2b67 2010-11-05 | Initial support for customizing routes to consumer actors. [Martin Krasser]
| * | | | | | |   000f6ea 2010-11-09 | Merge branch 'master' of https://github.com/paulpach/akka into paulpach-master [Viktor Klang]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| |/| | / / / /   
| | | |/ / / /    
| | |/| | | |     
| | * | | | | 79f20df 2010-11-07 | Add a ref="..." attribute to untyped-actor and typed-actor so that akka can use spring created beans [Paul Pacheco]
| * | | | | |   696393b 2010-11-08 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | | |_|_|_|/  
| | |/| | | |   
| | * | | | | a4d25da 2010-11-08 | Closing ticket 476 - verify licenses [Viktor Klang]
| * | | | | |   5c7c033 2010-11-08 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 9003c46 2010-11-08 | Fixing optimistic sleep in actor model spec [Viktor Klang]
| | | |_|/ /  
| | |/| | |   
| | * | | |   950b58b 2010-11-07 | Tweaking the encoding of map keys so that there is no possibility of stomping on the key used to hold the map keyset [ticktock]
| | |\ \ \ \  
| | | * | | | eb7f555 2010-11-08 | Update sbt plugin [Peter Vlugter]
| | | * | | | 019d440 2010-11-07 | Adding support for user-controlled action for unmatched messages [Viktor Klang]
| | * | | | | ba4b240 2010-11-07 | Tweaking the encoding of map keys so that there is no possibility of stomping on the key used to hold the maps keyset [ticktock]
| | |/ / / /  
| | * | | | ad6252c 2010-11-06 | formatting [ticktock]
| | * | | | ff27358 2010-11-06 | realized that there are other MIT deps in akka, re-enabling [ticktock]
| | * | | | 277a789 2010-11-06 | commenting out memcached support pending license question [ticktock]
| | * | | | ea3d83d 2010-11-06 | freeing up a few more bytes for memcached keys, reserving a slightly less common key to hold map keysets [ticktock]
| | * | | | 1b0fdf3 2010-11-05 | updating akka-reference.conf and switching to KetamaConnectionFactory for spymemcached [ticktock]
| | * | | |   f446359 2010-11-05 | Closing ticket-29 memcached protocol support for persistence backend. tested against memcached and membase. [ticktock]
| | |\ \ \ \  
| | | * | | | f43ced3 2010-11-05 | refactored test - lazy persistent vector doesn't seem to work anymore [Debasish Ghosh]
| | | * | | | 35dab53 2010-11-05 | changed implementation of PersistentQueue so that it's now thread-safe [Debasish Ghosh]
| | | * | | |   4f94b58 2010-11-04 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \  
| | | | * \ \ \   980bde4 2010-11-04 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | | |\ \ \ \  
| | | | | |/ / /  
| | | * | | | |   ce4c0fc 2010-11-04 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \  
| | | | |/ / / /  
| | | |/| / / /   
| | | | |/ / /    
| | | * | | | 09772eb 2010-11-04 | Fixing issue with turning off secure cookies [Viktor Klang]
| | * | | | | 14698b0 2010-11-03 | merged master [ticktock]
| | * | | | |   0d0f1aa 2010-11-03 | merged master [ticktock]
| | |\ \ \ \ \  
| | | | |/ / /  
| | | |/| | |   
| | * | | | | 7fea244 2010-11-03 | Cleanup and wait/retry on futures returned from spymemcached appropriately [ticktock]
| | * | | | | 1af2085 2010-11-01 | Memcached Storage Backend [ticktock]
| * | | | | | a80477f 2010-11-08 | Fixed maven groupId and some other minor stuff [Jonas Bonér]
| | |/ / / /  
| |/| | | |   
| * | | | | 3e21ccf 2010-11-02 | Made remote message frame size configurable [Jonas Bonér]
| * | | | |   2a9af35 2010-11-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | | |/ / /  
| | |/| | |   
| | * | | | 31eddd9 2010-11-02 | Fixing #491 and lots of tiny optimizations [Viktor Klang]
| | | |/ /  
| | |/| |   
| * | | |   eda11ad 2010-11-02 | merged with upstream [Jonas Bonér]
| |\ \ \ \  
| | * | | | 64c2809 2010-11-02 | Merging of RemoteRequest and RemoteReply protocols completed [Jonas Bonér]
| | * | | | 1a36f14 2010-10-28 | mid prococol refactoring [Jonas Bonér]
| * | | | | 96c00f2 2010-10-31 | formatting [Jonas Bonér]
| | |/ / /  
| |/| | |   
| * | | |   46b84d7 2010-10-31 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| | * | | | d54eb18 2010-10-30 | Switched to server managed for Supervisor config [Viktor Klang]
| | * | | | 453fa4d 2010-10-29 | Fixing ticket #498 [Viktor Klang]
| | * | | |   6787be5 2010-10-29 | Merge with master [Viktor Klang]
| | |\ \ \ \  
| | | | |/ /  
| | | |/| |   
| | * | | | 58e96e3 2010-10-29 | Fixing ticket #481, sorry for rewriting stuff. May God have mercy. [Viktor Klang]
| * | | | | 8ebb041 2010-10-31 | Added remote client info to remote server life-cycle events [Jonas Bonér]
| | |/ / /  
| |/| | |   
| * | | | c276c62 2010-10-29 | removed trailing spaces [Jonas Bonér]
| * | | |   c27f971 2010-10-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| | |/ / /  
| | * | | c52f958 2010-10-29 | Cleaned up shutdown hook code and increased readability [Viktor Klang]
| | * | | fdf89a0 2010-10-29 | Adding shutdown hook that clears logging levels registered by Configgy, closing ticket 486 [Viktor Klang]
| | * | |   fdf2f26 2010-10-29 | Merge branch '458-krasserm' [Martin Krasser]
| | |\ \ \  
| | | * | | 757559a 2010-10-29 | Remove Camel staging repo [Martin Krasser]
| | | * | | ab6803d 2010-10-29 | Fixed compile error after resolving merge conflict [Martin Krasser]
| | | * | |   564692c 2010-10-29 | Merge remote branch 'remotes/origin/master' into 458-krasserm and resolved conflict in akka-camel/src/main/scala/CamelService.scala [Martin Krasser]
| | | |\ \ \  
| | | | | |/  
| | | | |/|   
| | | * | | e6d8357 2010-10-26 | Upgrade to Camel 2.5 release candidate 2 [Martin Krasser]
| | | * | | ac1343b 2010-10-24 | Use a cached JMS ConnectionFactory. [Martin Krasser]
| | | * | |   48c7d39 2010-10-22 | Merge branch 'master' into 458-krasserm [Martin Krasser]
| | | |\ \ \  
| | | * | | | 9515c93 2010-10-19 | Upgrade to Camel 2.5 release candidate leaving ActiveMQ at version 5.3.2 because of https://issues.apache.org/activemq/browse/AMQ-2935 [Martin Krasser]
| | | * | | | 721dbf1 2010-10-16 | Added missing Consumer trait to example actor [Martin Krasser]
| | | * | | | 77a1a98 2010-10-15 | Improve Java API to wait for endpoint activation/deactivation. Closes #472 [Martin Krasser]
| | | * | | | 8977b9e 2010-10-15 | Improve API to wait for endpoint activation/deactivation. Closes #472 [Martin Krasser]
| | | * | | | 5282b09 2010-10-14 | Upgrade to Camel 2.5-SNAPSHOT, Jetty 7.1.6.v20100715 and ActiveMQ 5.4.1 [Martin Krasser]
| * | | | | | 5a14671 2010-10-29 | Changed default remote port from 9999 to 2552 (AKKA) :-) [Jonas Bonér]
| |/ / / / /  
| * | | | |   b80689d 2010-10-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | 3efad99 2010-10-28 | Refactored a CommonStorageBackend out of the KVBackend, tweaked the CassandraBackend to extend it, and tweaked the Vold and Riak backends to use the updated KVBackend [ticktock]
| | * | | | |   b4dc22b 2010-10-28 | Merge branch 'master' of https://github.com/jboner/akka into ticket-438 [ticktock]
| | |\ \ \ \ \  
| | | | |_|/ /  
| | | |/| | |   
| | | * | | |   7bcbdd7 2010-10-28 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | | |\ \ \ \  
| | | * | | | | eef6350 2010-10-28 | More sugar on the syntax [momania]
| | * | | | | |   55dcf5e 2010-10-28 | merged master after the package rename changeset [ticktock]
| | |\ \ \ \ \ \  
| | | | |/ / / /  
| | | |/| | | |   
| | | * | | | | c83804c 2010-10-28 | Optimization, 2 less allocs and 1 less field in actorref [Viktor Klang]
| | | * | | | | 620e234 2010-10-28 | Bumping Jackson version to 1.4.3 [Viktor Klang]
| | | * | | | |   774debb 2010-10-28 | Merge with master [Viktor Klang]
| | | |\ \ \ \ \  
| | | | | |_|_|/  
| | | | |/| | |   
| | | * | | | | deed6c4 2010-10-26 | Fixing Akka Camel with the new package [Viktor Klang]
| | | * | | | | 103969f 2010-10-26 | Fixing missing renames of se.scalablesolutions [Viktor Klang]
| | | * | | | | 8fd6361 2010-10-26 | BREAKAGE: switching from se.scalablesolutions.akka to akka for all packages [Viktor Klang]
| | * | | | | | 975cdc7 2010-10-26 | Adding PersistentQueue to CassandraStorage [ticktock]
| | * | | | | | 4b07539 2010-10-26 | refactored KVStorageBackend to also work with Cassandra, refactored CassandraStorageBackend to use KVStorageBackend, so Cassandra backend is now fully compliant with the test specs and supports PersistentQueue and Vector.pop [ticktock]
| | * | | | | | 9175984 2010-10-25 | Refactoring KVStoragebackend such that it is possible to create a Cassandra based impl [ticktock]
| | * | | | | | f26fc4b 2010-10-25 | adding compatibility tests for cassandra (failing currently) and refactor KVStorageBackend to make some functionality easier to get at [ticktock]
| | * | | | | | 9dc370f 2010-10-25 | Making PersistentVector.pop required, removed support for it being optional [ticktock]
| | * | | | | | bfa115b 2010-10-24 | updating common tests so that impls that dont support pop wont fail [ticktock]
| | * | | | | |   1b53de8 2010-10-24 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| | |\ \ \ \ \ \  
| | * | | | | | | e4adcbf 2010-10-24 | Finished off adding vector.pop as an optional operation [ticktock]
| | * | | | | | | b5f5c0f 2010-10-24 | initial tests of vector backend remove [ticktock]
| | * | | | | | | 9aa70b9 2010-10-22 | Initial frontend code to support vector pop, and KVStorageBackend changes to put the scaffolding in place to support this [ticktock]
| * | | | | | | | 3fda716 2010-10-28 | Added untrusted-mode for remote server which disallows client-managed remote actors and al lifecycle messages [Jonas Bonér]
| | |_|_|/ / / /  
| |/| | | | | |   
| * | | | | | |   43092e9 2010-10-27 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | | |_|_|/ / /  
| | |/| | | | |   
| | * | | | | |   3abd282 2010-10-27 | Merge branch 'master' into fsm [unknown]
| | |\ \ \ \ \ \  
| | * | | | | | | 440d36a 2010-10-27 | polishing up code [imn]
| | * | | | | | | 40ddac6 2010-10-27 | use nice case objects for the states :-) [imn]
| | * | | | | | | ff81cfb 2010-10-26 | refactoring the FSM part [imn]
| | | |_|_|/ / /  
| | |/| | | | |   
| * | | | | | | 658b073 2010-10-27 | Improved secure cookie generation script [Jonas Bonér]
| * | | | | | | 83ab962 2010-10-26 | converted tabs to spaces [Jonas Bonér]
| * | | | | | | 90642f8 2010-10-26 | Changed the script to spit out a full akka.conf file with the secure cookie [Jonas Bonér]
| * | | | | | |   4d1b09b 2010-10-26 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | | |/ / / / /  
| | |/| | | | |   
| | * | | | | | 9b59bff 2010-10-26 | Adding possibility to take naps between scans for finished future, closing ticket #449 [Viktor Klang]
| | * | | | | | 2b46fce 2010-10-26 | Added support for remote agent [Viktor Klang]
| * | | | | | |   df710a7 2010-10-26 | Completed Erlang-style cookie handshake between RemoteClient and RemoteServer [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| | * | | | | | 4a43c93 2010-10-26 | Switching to non-SSL repo for jBoss [Viktor Klang]
| | |/ / / / /  
| * | | | | | e300b76 2010-10-26 | Added Erlang-style secure cookie authentication for remote client/server [Jonas Bonér]
| * | | | | |   7931169 2010-10-26 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 5c9fab8 2010-10-25 | Fixing a cranky compiler whine on a match statement [Viktor Klang]
| | * | | | | a35dccd 2010-10-25 | Making ThreadBasedDispatcher Unbounded if no capacity specced and fix a possible mem leak in it [Viktor Klang]
| | * | | | | bd364a2 2010-10-25 | Handling Interrupts for ThreadBasedDispatcher, EBEDD and EBEDWSD [Viktor Klang]
| | * | | | |   c081a4c 2010-10-25 | Merge branch 'wip-rework_dispatcher_config' [Viktor Klang]
| | |\ \ \ \ \  
| | | * \ \ \ \   a3f78b0 2010-10-25 | Merge branch 'master' of github.com:jboner/akka into wip-rework_dispatcher_config [Viktor Klang]
| | | |\ \ \ \ \  
| | | * | | | | | 7850f0b 2010-10-25 | Adding a flooding test to reproduce error reported by user [Viktor Klang]
| | | * | | | | | 2c4304f 2010-10-25 | Added the ActorModel specification to HawtDispatcher and EBEDWSD [Viktor Klang]
| | | * | | | | | 5e984d2 2010-10-25 | Added tests for suspend/resume [Viktor Klang]
| | | * | | | | | 58a7eb7 2010-10-25 | Added test for dispatcher parallelism [Viktor Klang]
| | | * | | | | | f948b63 2010-10-25 | Adding test harness for ActorModel (Dispatcher), work-in-progress [Viktor Klang]
| | | * | | | | |   993db5f 2010-10-25 | Merge branch 'master' of github.com:jboner/akka into wip-rework_dispatcher_config [Viktor Klang]
| | | |\ \ \ \ \ \  
| | | * \ \ \ \ \ \   70168ec 2010-10-25 | Merge branch 'master' into wip-rework_dispatcher_config [Viktor Klang]
| | | |\ \ \ \ \ \ \  
| | | | | |_|_|/ / /  
| | | | |/| | | | |   
| | | * | | | | | | b075b80 2010-10-25 | Removed boilerplate, added final optmization [Viktor Klang]
| | | * | | | | | | a630cae 2010-10-25 | Rewrote timed shutdown facility, causes less than 5% overhead now [Viktor Klang]
| | | * | | | | | | c90580a 2010-10-24 | Naïve implementation of timeout completed [Viktor Klang]
| | | * | | | | | | b745f98 2010-10-24 | Renamed stopAllLinkedActors to stopAllAttachedActors [Viktor Klang]
| | | * | | | | | | 990b933 2010-10-24 | Moved active flag into MessageDispatcher and let it handle the callbacks, also fixed race in DataFlowSpec [Viktor Klang]
| | | * | | | | | | 53e67d6 2010-10-24 | Fixing race-conditions, now works albeit inefficiently when adding/removing actors rapidly [Viktor Klang]
| | | * | | | | | | 149d346 2010-10-24 | Removing unused code and the isShutdown method [Viktor Klang]
| | | * | | | | | | c241703 2010-10-24 | Tests green, config basically in place, need to work on start/stop semantics and countdowns [Viktor Klang]
| | | * | | | | | |   4478474 2010-10-23 | Merge branch 'master' of github.com:jboner/akka into wip-rework_dispatcher_config [Viktor Klang]
| | | |\ \ \ \ \ \ \  
| | | | | |_|_|_|_|/  
| | | | |/| | | | |   
| | | * | | | | | | 3ecb38b 2010-10-22 | WIP [Viktor Klang]
| | | | |_|_|_|/ /  
| | | |/| | | | |   
| | * | | | | | | 5354f77 2010-10-25 | Updating Netty to 3.2.3, closing ticket #495 [Viktor Klang]
| | | |_|_|_|/ /  
| | |/| | | | |   
| | * | | | | | 8ce57c0 2010-10-25 | added more tests and fixed corner case to TypedActor Option return value [Viktor Klang]
| | * | | | | | fde1baa 2010-10-25 | Closing ticket #471 [Viktor Klang]
| | | |_|_|/ /  
| | |/| | | |   
| | * | | | | 253f77c 2010-10-25 | Closing ticket #460 [Viktor Klang]
| | | |_|/ /  
| | |/| | |   
| | * | | | ceeaffd 2010-10-25 | Fixing #492 [Viktor Klang]
| | | |/ /  
| | |/| |   
| | * | |   6f76dc0 2010-10-22 | Merge branch '479-krasserm' [Martin Krasser]
| | |\ \ \  
| | | |/ /  
| | |/| |   
| | | * | 082daa4 2010-10-21 | Closes #479. Do not register listeners when CamelService is turned off by configuration [Martin Krasser]
| * | | | d87a715 2010-10-26 | Fixed bug in startLink and friends + Added cryptographically secure cookie generator [Jonas Bonér]
| |/ / /  
| * | | 7b56928 2010-10-21 | Final tweaks to common KVStorageBackend factored out of Riak and Voldemort backends [ticktock]
| * | | c00aef3 2010-10-21 | Voldemort Tests now working as well as Riak [ticktock]
| * | | 68d832e 2010-10-21 | riak working, all vold tests work individually, just not in sequence [ticktock]
| * | | e15f926 2010-10-20 | refactoring complete, vold tests still acting up [ticktock]
| * | | 630ccce 2010-10-21 | Improving SupervisorConfig for Java [Viktor Klang]
| |/ /  
| * | 35b64b0 2010-10-21 | Changes publication from sourcess to sources [Viktor Klang]
| * |   3746196 2010-10-21 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \  
| | * \   78fd415 2010-10-20 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | |\ \  
| | | * | 9ff4ca7 2010-10-20 | Reducing object creation per ActorRef + removed unsafe concurrent publication [Viktor Klang]
| | * | | c225177 2010-10-20 | remove usage of 'actor' function [momania]
| | |/ /  
| | * | 3ffba77 2010-10-20 | fix for the fix for #480 : new version of redisclient [Debasish Ghosh]
| | * | 2aec12e 2010-10-19 | fix for issue #480 Regression multibulk replies redis client with a new version of redisclient [Debasish Ghosh]
| | * | df79b08 2010-10-19 | Added Java API constructor to supervision configuration [Viktor Klang]
| | * | 32592b4 2010-10-19 | Refining Supervision API and remove AllForOne, OneForOne and replace with AllForOneStrategy, OneForOneStrategy etc [Viktor Klang]
| | * | 7b1d234 2010-10-19 | Moved Faulthandling into Supvervision [Viktor Klang]
| | * | e9c946d 2010-10-18 | Refactored declarative supervision, removed ScalaConfig and JavaConfig, moved things around [Viktor Klang]
| | * | 4977439 2010-10-18 | Removing local caching of actor self fields [Viktor Klang]
| | * |   dd6430e 2010-10-15 | Merge branch 'master' of https://github.com/jboner/akka [ticktock]
| | |\ \  
| | | * | 98e4824 2010-10-15 | Closing #456 [Viktor Klang]
| | * | | 942ed3d 2010-10-15 | adding default riak config to akka-reference.conf [ticktock]
| | |/ /  
| | * | 34e0745 2010-10-15 | final tweaks before pushing to master [ticktock]
| | * |   54800f6 2010-10-15 | merging master [ticktock]
| | |\ \  
| | * \ \   645e7ec 2010-10-15 | Merge with master [Viktor Klang]
| | |\ \ \  
| | * | | | 732edcf 2010-10-14 | added fork of riak-java-pb-client to embedded repo, udpated backend to use new code therein, and all tests pass [ticktock]
| | * | | | 950ed9a 2010-10-13 | fix an inconsistency [ticktock]
| | * | | | 20007f7 2010-10-13 | Initial Port of the Voldemort Backend to Riak [ticktock]
| | * | | | a3a9dcd 2010-10-12 | First pass at Riak Backend [ticktock]
| | * | | | e7b483b 2010-10-09 | Initial Scaffold of Riak Module [ticktock]
| * | | | | bb1338a 2010-10-21 | Made Format serializers serializable [Jonas Bonér]
| | |_|/ /  
| |/| | |   
| * | | | 1c96a11 2010-10-15 | Added Java API for Supervise [Viktor Klang]
| | |/ /  
| |/| |   
| * | | 536f634 2010-10-14 | Closing ticket #469 [Viktor Klang]
| | |/  
| |/|   
| * | 2d2df8b 2010-10-13 | Removed duplicate code [Viktor Klang]
| * |   c0aadf4 2010-10-12 | Merge branch 'master' into Kahlen-master [Viktor Klang]
| |\ \  
| | * \   a759491 2010-10-12 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \  
| | * | | 99ec2b9 2010-10-12 | Improvements to actor-component API docs [Martin Krasser]
| * | | | 2dc7c30 2010-10-12 | Merging in CouchDB support [Viktor Klang]
| * | | |   7ce3e86 2010-10-12 | Merge branch 'master' of http://github.com/Kahlen/akka into Kahlen-master [Viktor Klang]
| |\ \ \ \  
| | |_|/ /  
| |/| | |   
| | * | | ac1f1dd 2010-10-06 | completed!! [Kahlen]
| | * | | e20ce5d 2010-10-06 | merge with yllan's commit [Kahlen]
| | * | |   93a9682 2010-10-06 | Merge branch 'couchdb' of http://github.com/yllan/akka into couchdb [Kahlen]
| | |\ \ \  
| | | * | | abb7edf 2010-10-06 | Copied the actor spec from mongo and voldemort. [Yung-Luen Lan]
| | | * | | 18b2e29 2010-10-06 | clean up db for actor test. [Yung-Luen Lan]
| | | * | | f3b42cb 2010-10-06 | Add actor spec (but didn't pass) [Yung-Luen Lan]
| | | * | | 9819222 2010-10-06 | Add tags to gitignore. [Yung-Luen Lan]
| | * | | | 085cb0a 2010-10-06 | Merge my stashed code for removeMapStorageFor [Kahlen]
| | |/ / /  
| | * | |   71e7de4 2010-10-06 | Merge branch 'master' of http://github.com/jboner/akka into couchdb [Yung-Luen Lan]
| | |\ \ \  
| | * \ \ \   a158e49 2010-10-05 | Merge branch 'couchdb' of http://github.com/Kahlen/akka into couchdb [Yung-Luen Lan]
| | |\ \ \ \  
| | | * | | | b1df660 2010-10-05 | my first commit [Kahlen Lin]
| | * | | | | 4eb17b1 2010-10-05 | Add couchdb support [Yung-Luen Lan]
| | |/ / / /  
| | * | | |   ef2d7cc 2010-10-04 | Merge branch 'master' of http://github.com/jboner/akka [Yung-Luen Lan]
| | |\ \ \ \  
| | * | | | | 9c3da5a 2010-10-04 | Add couch db plugable persistence module scheme. [Yung-Luen Lan]
| * | | | | |   236ff9d 2010-10-12 | Merge branch 'ticket462' [Viktor Klang]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   f1e70e9 2010-10-12 | Merge branch 'master' of github.com:jboner/akka into ticket462 [Viktor Klang]
| | |\ \ \ \ \ \  
| | | | |_|_|/ /  
| | | |/| | | |   
| | * | | | | | fdbfbe3 2010-10-11 | Switching to volatile int instead of AtomicInteger until ticket 384 is done [Viktor Klang]
| | * | | | | | b42dfbc 2010-10-11 | Tuned test to work, also fixed a bug in the restart logic [Viktor Klang]
| | * | | | | | 6b9a895 2010-10-11 | Rewrote restart code, resetting restarts outside tiem window etc [Viktor Klang]
| | * | | | | | ae3768c 2010-10-11 | Initial attempt at suspend/resume [Viktor Klang]
| * | | | | | | 7eb75cd 2010-10-12 | Fixing #467 [Viktor Klang]
| * | | | | | | 93d2385 2010-10-12 | Adding implicit dispatcher to spawn [Viktor Klang]
| * | | | | | | 654e154 2010-10-12 | Removing anonymous actor methods as per discussion on ML [Viktor Klang]
| | |/ / / / /  
| |/| | | | |   
| * | | | | |   1598f6c 2010-10-11 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 3a9994b 2010-10-11 | Switching to Switch and restructuring some EBEDD code [Viktor Klang]
| | * | | | | 25ebfba 2010-10-11 | Switching to Switch for EBEDWSD active status [Viktor Klang]
| | * | | | | 66fcd62 2010-10-11 | Fixing performance regression [Viktor Klang]
| | * | | | | 685c6df 2010-10-11 | Fixed akka-jta bug and added tests [Viktor Klang]
| | * | | | |   0738f51 2010-10-10 | Merge branch 'ticket257' [Viktor Klang]
| | |\ \ \ \ \  
| | | * | | | | 541fe7b 2010-10-10 | Switched to JavaConversion wrappers [Viktor Klang]
| | | * | | | | cefe20e 2010-10-07 | added java API for PersistentMap, PersistentVector [Michael Kober]
| | * | | | | |   7bea305 2010-10-10 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \  
| | | | |_|_|_|/  
| | | |/| | | |   
| | * | | | | | bca3ecc 2010-10-10 | Removed errornous method in Future [Jonas Bonér]
| * | | | | | | ab91ec5 2010-10-11 | Dynamic message routing to actors. Closes #465 [Martin Krasser]
| * | | | | | | 2d456c1 2010-10-11 | Refactorings [Martin Krasser]
| | |/ / / / /  
| |/| | | | |   
| * | | | | |   d01fb42 2010-10-09 | Merge branch '457-krasserm' [Martin Krasser]
| |\ \ \ \ \ \  
| | * | | | | | 3270250 2010-10-09 | Tests for Message Java API [Martin Krasser]
| | * | | | | | bd29013 2010-10-09 | Java API for Message and Failure classes [Martin Krasser]
| * | | | | | | fa0db6c 2010-10-09 | Folding 3 volatiles into 1, all transactor-based stuff [Viktor Klang]
| | |/ / / / /  
| |/| | | | |   
| * | | | | |   33593e8 2010-10-09 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | d7b8c0f 2010-10-08 | Removed all allocations from the canRestart-method [Viktor Klang]
| | * | | | | |   da3c23c 2010-10-08 | Merge branch 'master' of http://github.com/andreypopp/akka into andreypopp-master [Viktor Klang]
| | |\ \ \ \ \ \  
| | | * \ \ \ \ \   6b01da4 2010-10-08 | Merge branch 'master' of http://github.com/jboner/akka [Andrey Popp]
| | | |\ \ \ \ \ \  
| | | | * | | | | | 91dbb59 2010-10-08 | after merge cleanup [momania]
| | | | * | | | | |   53bdcd4 2010-10-08 | Merge branch 'master' into amqp [momania]
| | | | |\ \ \ \ \ \  
| | | | * | | | | | | 69f26a1 2010-10-08 | - Finshed up java api for RPC - Made case objects 'java compatible' via getInstance function - Added RPC examples in java examplesession [momania]
| | | | * | | | | | | 3d12b50 2010-10-08 | - made channel and connection callback java compatible [momania]
| | | | * | | | | | | 9cce10d 2010-10-08 | - changed exchange types to case classes for java compatibility - made java api for string and protobuf convenience producers/consumers - implemented string and protobuf java api examples [momania]
| | | | * | | | | | | b046836 2010-09-24 | initial take on java examples [momania]
| | | | * | | | | | | 96895a4 2010-09-24 | add test filter to the amqp project [momania]
| | | | * | | | | | | 31933c8 2010-09-24 | wait a bit longer than the deadline... so test always works... [momania]
| | | | * | | | | | | 10f3f4c 2010-09-24 | renamed tests to support integration test selection via sbt [momania]
| | | | * | | | | | |   0c99fae 2010-09-24 | Merge branch 'master' into amqp [momania]
| | | | |\ \ \ \ \ \ \  
| | | | * | | | | | | | d2a0cf8 2010-09-24 | back to original project settings :S [momania]
| | | | * | | | | | | | 2b4a3a2 2010-09-23 | Disable test before push [momania]
| | | | * | | | | | | | 5a284d7 2010-09-23 | Make tests pass again... [momania]
| | | | * | | | | | | | 0b0041d 2010-09-23 | Updated test to changes in api [momania]
| | | | * | | | | | | | 84785b3 2010-09-23 | - Adding java api to AMQP module - Reorg of params, especially declaration attributes and exhange name/params [momania]
| | | * | | | | | | | | cf2e003 2010-10-08 | Rework restart strategy restart decision. [Andrey Popp]
| | | * | | | | | | | | adfbe93 2010-10-08 | Add more specs for restart strategy params. [Andrey Popp]
| | | | |_|_|_|_|_|_|/  
| | | |/| | | | | | |   
| | * | | | | | | | | b403329 2010-10-08 | Switching to a more accurate approach that involves no locking and no thread locals [Viktor Klang]
| | | |_|_|/ / / / /  
| | |/| | | | | | |   
| | * | | | | | | | c3cecd3 2010-10-08 | Serialization of RemoteActorRef unborked [Viktor Klang]
| | * | | | | | | | a73e993 2010-10-08 | Removing isInInitialization, reading that from actorRefInCreation, -1 volatile field per actorref [Viktor Klang]
| | * | | | | | | | b892c12 2010-10-08 | Removing linkedActorsAsList, switching _linkedActors to be a volatile lazy val instead of Option with lazy semantics [Viktor Klang]
| | * | | | | | | |   d165f14 2010-10-08 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | e809d98 2010-10-04 | register client managed remote actors by uuid [Michael Kober]
| | | | |_|_|_|/ / /  
| | | |/| | | | | |   
| | * | | | | | | | 848a69d 2010-10-08 | Changed != SHUTDOWN to == RUNNING [Viktor Klang]
| | * | | | | | | | 4904c80 2010-10-08 | -1 volatile field in ActorRef, trapExit is migrated into faultHandler [Viktor Klang]
| | |/ / / / / / /  
| | * | | | | | |   51612c9 2010-10-07 | Merge remote branch 'remotes/origin/master' into java-api [Martin Krasser]
| | |\ \ \ \ \ \ \  
| | | |_|_|_|/ / /  
| | |/| | | | | |   
| | | * | | | | | 06135f6 2010-10-07 | Fixing bug where ReceiveTimeout wasn´t turned off on actorref.stop [Viktor Klang]
| | | * | | | | |   c41cc60 2010-10-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \  
| | | | * | | | | | df853ca 2010-10-07 | fix:ensure that typed actor module is enabled in typed actor methods [Michael Kober]
| | | * | | | | | | d14b3df 2010-10-07 | Fixing UUID remote request bug [Viktor Klang]
| | | |/ / / / / /  
| | * | | | | | | 5077071 2010-10-07 | Tests for Java API support [Martin Krasser]
| | * | | | | | | 7f63ee8 2010-10-07 | Moved Java API support to japi package. [Martin Krasser]
| | * | | | | | | af1983f 2010-10-06 | Minor reformattings [Martin Krasser]
| | * | | | | | | 5667203 2010-10-06 | Java API for CamelServiceManager and CamelContextManager (refactorings) [Martin Krasser]
| | * | | | | | | 972a1b0 2010-10-05 | Java API for CamelServiceManager and CamelContextManager (usage of JavaAPI.Option) [Martin Krasser]
| | * | | | | | | 41867a7 2010-10-05 | CamelServiceManager.service returns Option[CamelService] (Scala API) CamelServiceManager.getService() returns Option[CamelService] (Java API) Re #457 [Martin Krasser]
| | | |_|_|_|_|/  
| | |/| | | | |   
| * | | | | | | 7e1b46b 2010-10-08 | Added serialization of 'hotswap' stack + tests [Jonas Bonér]
| * | | | | | | 5f9700c 2010-10-08 | Made 'hotswap' a Stack instead of Option + addded 'RevertHotSwap' and 'unbecome' + added tests for pushing and popping the hotswap stack [Jonas Bonér]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | ac85e40 2010-10-06 | Upgraded to Scala 1.2 final. [Jonas Bonér]
| * | | | | |   1000db8 2010-10-06 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | | |/ / / /  
| | |/| | | |   
| | * | | | | de75824 2010-10-05 | Removed pointless check for N/A as Actor ID [Viktor Klang]
| | * | | | | 441387b 2010-10-05 | Added some more methods to Index, as well as added return-types for put and remove as well as restructured some of the code [Viktor Klang]
| | * | | | | 30712c6 2010-10-05 | Removing more boilerplate from AkkaServlet [Viktor Klang]
| | * | | | |   92c1f16 2010-10-05 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \  
| | | * | | | | 26ab1c6 2010-10-04 | porting a ticket 450 change over [ticktock]
| | | * | | | |   b6c8e68 2010-10-04 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| | | |\ \ \ \ \  
| | | * \ \ \ \ \   09c2fb8 2010-10-03 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| | | |\ \ \ \ \ \  
| | | | | |/ / / /  
| | | | |/| | | |   
| | | * | | | | | ef14ead 2010-10-01 | Added tests of proper null handling for Ref,Vector,Map,Queue and voldemort impl/tweak [ticktock]
| | | * | | | | | 820f8ab 2010-09-30 | two more stub tests in Vector Spec [ticktock]
| | | * | | | | | 7175e46 2010-09-30 | More VectorStorageBackend tests plus an abstract Ticket343Test with a working VoldemortImpl [ticktock]
| | | * | | | | | 3d6c1f2 2010-09-30 | Map Spec [ticktock]
| | | * | | | | | 3a0e181 2010-09-30 | Moved implicit Ordering(ArraySeq[Byte]) to a new PersistentMapBinary companion object  and created an implicit Ordering(Array[Byte]) that can be used on the backends too [ticktock]
| | | * | | | | |   fd7b6d3 2010-09-30 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| | | |\ \ \ \ \ \  
| | | | | |_|_|_|/  
| | | | |/| | | |   
| | | * | | | | | 2809157 2010-09-29 | Initial QueueStorageBackend Spec [ticktock]
| | | * | | | | |   31a2f15 2010-09-29 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| | | |\ \ \ \ \ \  
| | | * | | | | | | 57a856a 2010-09-29 | Initial QueueStorageBackend Spec [ticktock]
| | | * | | | | | | e88dec5 2010-09-28 | Initial Spec for MapStorageBackend [ticktock]
| | | * | | | | | | 6157a90 2010-09-28 | Persistence Compatibility Test Harness and Voldemort Implementation [ticktock]
| | | * | | | | | | 6d0ce27 2010-09-28 | Initial Sketch of Persistence Compatibility Tests [ticktock]
| | | * | | | | | | 39c732c 2010-09-27 | Initial PersistentRef spec [ticktock]
| | * | | | | | | | 6988b10 2010-10-05 | Cleaned up code and added more comments [Viktor Klang]
| | | |_|_|_|/ / /  
| | |/| | | | | |   
| | * | | | | | | 11532a4 2010-10-04 | Fixing ReceiveTimeout as per #446, now need to do: self.receiveTimeout = None to shut it off [Viktor Klang]
| | * | | | | | | 641657f 2010-10-04 | Ensure that at most 1 CometSupport is created per servlet. and remove boiler [Viktor Klang]
| * | | | | | | | 05720fb 2010-10-06 | Upgraded to AspectWerkz 2.2.2 with new fix for Scala load-time weaving [Jonas Bonér]
| * | | | | | | | d9c040a 2010-10-04 | Changed ReflectiveAccess to work with enterprise module [Jonas Bonér]
| |/ / / / / / /  
| * | | | | | | 592fe44 2010-10-04 | Creating a Main object for Akka-http [Viktor Klang]
| * | | | | | | 6fd880b 2010-10-04 | Moving EmbeddedAppServer to akka-http and closing #451 [Viktor Klang]
| * | | | | | | 3efb427 2010-10-04 | Fixing ticket #450, lifeCycle = Permanent => boilerplate reduction [Viktor Klang]
| | |_|_|/ / /  
| |/| | | | |   
| * | | | | |   099820a 2010-10-02 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   166f9a0 2010-10-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \  
| | * | | | | | | 51e3e67 2010-10-02 | Added hasListener [Jonas Bonér]
| | | |_|_|/ / /  
| | |/| | | | |   
| * | | | | | | b5ba69a 2010-10-02 | Minor code cleanup of config file load [Viktor Klang]
| | |/ / / / /  
| |/| | | | |   
| * | | | | |   03f13bd 2010-10-02 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | |   f47f319 2010-09-30 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \  
| | | * \ \ \ \   2bc500c 2010-09-30 | merged ticket444 [Michael Kober]
| | | |\ \ \ \ \  
| | | | * | | | | 4817af9 2010-09-28 | closing ticket 444, moved RemoteActorSet to ActorRegistry [Michael Kober]
| | * | | | | | |   5f67d4b 2010-09-30 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | | |/ / / / / /  
| | | * | | | | | 719a054 2010-09-30 | fixed test [Michael Kober]
| | | * | | | | |   34e92b7 2010-09-30 | merged master [Michael Kober]
| | | |\ \ \ \ \ \  
| | | * | | | | | | 2e9d873 2010-09-28 | closing ticket441, implemented typed actor methods for ActorRegistry [Michael Kober]
| | * | | | | | | | 0a7ba9d 2010-09-30 | minor edit [Jonas Bonér]
| | | |/ / / / / /  
| | |/| | | | | |   
| | * | | | | | | 0dae3b1 2010-09-30 | CamelService can now be turned off by configuration. Closes #447 [Martin Krasser]
| | | |_|_|/ / /  
| | |/| | | | |   
| | * | | | | |   844bc92 2010-09-29 | Merge branch 'ticket440' [Michael Kober]
| | |\ \ \ \ \ \  
| | | * | | | | | 8495534 2010-09-29 | added Java API [Michael Kober]
| | | * | | | | | 1bbdfe9 2010-09-29 | closing ticket440, implemented typed actor with constructor args [Michael Kober]
| * | | | | | | | d87436e 2010-10-02 | Changing order of priority for akka.config and adding option to specify a mode [Viktor Klang]
| * | | | | | | | 6f11ce0 2010-10-02 | Updating Atmosphere to 0.6.2 and switching to using SimpleBroadcaster [Viktor Klang]
| * | | | | | | | 21c2f85 2010-09-29 | Changing impl of ReflectiveAccess to log to debug [Viktor Klang]
| |/ / / / / / /  
| * | | | | | | c245d33 2010-09-29 | new version of redisclient containing a redis based persistent deque [Debasish Ghosh]
| * | | | | | |   2b907a2 2010-09-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | * | | | | | | 2be8b2b 2010-09-29 | refactoring to remove compiler warnings reported by Viktor [Debasish Ghosh]
| | |/ / / / / /  
| * | | | | | | 5e0dfea 2010-09-29 | Refactored ExecutableMailbox to make it accessible for other implementations [Jonas Bonér]
| * | | | | | |   bd95d20 2010-09-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| | * | | | | | c848041 2010-09-28 | Removing runActorInitialization volatile field, replace with isRunning check [Viktor Klang]
| | * | | | | | 660b14e 2010-09-28 | Removing isDeserialize volatile field since it doesn´t seem to have any use [Viktor Klang]
| | * | | | | | e08ec0e 2010-09-28 | Removing classloader field (volatile) from LocalActorRef, wasn´t used [Viktor Klang]
| | | |/ / / /  
| | |/| | | |   
| | * | | | | ebf3dd0 2010-09-28 | Replacing use of == null and != null for Scala [Viktor Klang]
| | * | | | | 0cc2e26 2010-09-28 | Fixing compiler issue that caused problems when compiling with JDT [Viktor Klang]
| | | |/ / /  
| | |/| | |   
| | * | | |   9332fb2 2010-09-27 | Merge branch 'master' of github.com:jboner/akka [ticktock]
| | |\ \ \ \  
| | | * | | | fc77a13 2010-09-27 | Fixing ticket 413 [Viktor Klang]
| | | |/ / /  
| | * | | | ff04da0 2010-09-27 | Finished off Queue API [ticktock]
| | * | | | 9856f16 2010-09-27 | Further Queue Impl [ticktock]
| | * | | |   ce4ef89 2010-09-27 | Merge branch 'master' of https://github.com/jboner/akka [ticktock]
| | |\ \ \ \  
| | | |/ / /  
| | * | | |   b12d097 2010-09-25 | Merge branch 'master' of github.com:jboner/akka [ticktock]
| | |\ \ \ \  
| | * | | | | eccfc86 2010-09-25 | Made dequeue operation retriable in case of errors, switched from Seq to Stream for queue removal [ticktock]
| | * | | | | b9146e6 2010-09-24 | more queue implementation [ticktock]
| * | | | | |   f22ce96 2010-09-27 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | | |_|/ / /  
| | |/| | | |   
| | * | | | |   e027444 2010-09-26 | Merge branch 'ticket322' [Michael Kober]
| | |\ \ \ \ \  
| | | * | | | | 882ff90 2010-09-24 | closing ticket322 [Michael Kober]
| * | | | | | | 3fe641f 2010-09-27 | Support for more durable mailboxes [Jonas Bonér]
| |/ / / / / /  
| * | | | | |   91781c7 2010-09-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | | |_|/ / /  
| | |/| | | |   
| | * | | | | 05adfd4 2010-09-25 | Small change in the config file [David Greco]
| | | |/ / /  
| | |/| | |   
| * | | | |   3559f2f 2010-09-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | |/ / / /  
| | * | | | 96c9fec 2010-09-24 | Refactor to utilize only one voldemort store per datastructure type [ticktock]
| | * | | |   1a5466e 2010-09-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \  
| * | \ \ \ \   fe42fdf 2010-09-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| |/| / / / /   
| | |/ / / /    
| | * | | |   909db3b 2010-09-24 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \ \  
| | | * \ \ \   6bd1037 2010-09-24 | Merge remote branch 'ticktock/master' [ticktock]
| | | |\ \ \ \  
| | | | |/ / /  
| | | |/| | |   
| | | | * | | 97ff092 2010-09-23 | More Queue impl [ticktock]
| | | | * | | 60bd020 2010-09-23 | Refactoring Vector to only use 1 voldemort store, and setting up for implementing Queue [ticktock]
| | | | | |/  
| | | | |/|   
| | * | | | f6868e1 2010-09-24 | API-docs improvements. [Martin Krasser]
| | |/ / /  
| | * | |   0f7e337 2010-09-24 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \  
| | | * | | 934a9db 2010-09-24 | reducing boilerplate imports with package objects [Debasish Ghosh]
| | * | | | 72e8b95 2010-09-24 | Only execute tests matching *Test by default in akka-camel and akka-sample-camel. Rename stress tests in akka-sample-camel to *TestStress. [Martin Krasser]
| | * | | | 65ad0e2 2010-09-24 | Only execute tests matching *Test by default in akka-camel and akka-sample-camel. Rename stress tests in akka-sample-camel to *TestStress. [Martin Krasser]
| | * | | | 54ec9e3 2010-09-24 | Organized imports [Martin Krasser]
| | |/ / /  
| | * | | cc80abf 2010-09-24 | Renamed two akka-camel tests from *Spec to *Test [Martin Krasser]
| | * | | f5a3767 2010-09-24 | Aligned the hbase test to the new mechanism for optionally running integration tests [David Greco]
| | * | | c0dd6da 2010-09-24 | Aligned the hbase test to the new mechanism for optionally running integration tests [David Greco]
| | * | | 76283b4 2010-09-24 | Aligned the hbase test to the new mechanism for optionally running integration tests [David Greco]
| | * | | e92b51d 2010-09-24 | Aligned the hbase test to the new mechanism for optionally running integration tests [David Greco]
| | |/ /  
| | * |   2c52267 2010-09-23 | Merge with master [Viktor Klang]
| | |\ \  
| | | * | 4e62147 2010-09-23 | Corrected the optional run of the hbase tests [David Greco]
| | * | | 1d1ce90 2010-09-23 | Added support for having integration tests and stresstest optionally enabled [Viktor Klang]
| | |/ /  
| | * |   11b5732 2010-09-23 | Merge branch 'master' of github.com:jboner/akka [David Greco]
| | |\ \  
| | | * \   2faf30f 2010-09-23 | Merge branch 'serialization-dg-wip' [Debasish Ghosh]
| | | |\ \  
| | | | * | 131ea4f 2010-09-23 | removed unnecessary imports [Debasish Ghosh]
| | | | * | 70ac950 2010-09-22 | Integrated sjson type class based serialization into Akka - some backward incompatible changes there [Debasish Ghosh]
| | * | | | d7a2e16 2010-09-23 | Now the hbase tests don't spit out too much logs, made the running of the hbase tests optional [David Greco]
| | |/ / /  
| | * | |   dad9c8d 2010-09-23 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \  
| | | * \ \   b2a9ab8 2010-09-23 | Merging with ticktock [Viktor Klang]
| | | |\ \ \  
| | * | | | | 68ac180 2010-09-23 | Re-adding voldemort [Viktor Klang]
| | * | | | |   60db615 2010-09-23 | Merging with ticktock [Viktor Klang]
| | |\ \ \ \ \  
| | | |/ / / /  
| | |/| / / /   
| | | |/ / /    
| | | * | | f0ff68a 2010-09-23 | Removing BDB as a test-runtime dependency [ticktock]
| | * | | | ccee06a 2010-09-23 | Temporarily removing voldemort module pending license resolution [Viktor Klang]
| | * | | |   9a4f2a7 2010-09-23 | Adding Voldemort persistence plugin [Viktor Klang]
| | |\ \ \ \  
| | | |/ / /  
| | | * | | bc2ee57 2010-09-21 | making the persistent data sturctures non lazy in the ActorTest made things work...hmm seems strange though [ticktock]
| | | * | | 517888c 2010-09-21 | Adding a direct test of PersistentRef, since after merging master over, something is blowing up there with the Actor tests [ticktock]
| | | * | | 8b719b4 2010-09-21 | adding sjson as a test dependency to voldemort persistence [ticktock]
| | | * | |   fed341d 2010-09-21 | merge master of jboner/akka [ticktock]
| | | |\ \ \  
| | | | |/ /  
| | | * | | a5e67d0 2010-09-20 | provide better voldemort configuration support, and defaults definition in akka-reference.conf, and made the backend more easily testable [ticktock]
| | | * | | 063dc69 2010-09-20 | provide better voldemort configuration support, and defaults definition in akka-reference.conf, and made the backend more easily testable [ticktock]
| | | * | | ad213ea 2010-09-20 | fixing the formatting damage I did [ticktock]
| | | * | | beee516 2010-09-16 | sorted set hand serialization and working actor test [ticktock]
| | | * | | cb0bc2d 2010-09-15 | tests of PersistentRef,Map,Vector StorageBackend working [ticktock]
| | | * | | 0fd957a 2010-09-15 | more tests, working on map api [ticktock]
| | | * | | e8c88b5 2010-09-15 | Initial tests working with bdb backed voldemort, [ticktock]
| | | * | | f8f4b26 2010-09-15 | switched voldemort to log4j-over-slf4j [ticktock]
| | | * | | 5ad5a4d 2010-09-15 | finished ref map vector and some initial test scaffolding [ticktock]
| | | * | | c86497a 2010-09-14 | Initial PersistentMap backend [ticktock]
| | | * | | 34da28f 2010-09-14 | initial structures [ticktock]
| | * | | | cb3fb25 2010-09-23 | Removing registeredInRemoteNodeDuringSerialization [Viktor Klang]
| | * | | | 79dc348 2010-09-23 | Removing the running of HBase tests [Viktor Klang]
| | * | | |   e961ff6 2010-09-23 | Merge with master [Viktor Klang]
| | |\ \ \ \  
| | | * \ \ \   bd0a6f5 2010-09-23 | Merge branch 'fix-remote-test' [Michael Kober]
| | | |\ \ \ \  
| | | | * | | | 491722f 2010-09-23 | fixed some tests [Michael Kober]
| | | * | | | | b567011 2010-09-23 | fixed some tests [Michael Kober]
| | | |/ / / /  
| | * | | | |   7a132a9 2010-09-23 | Merge branch 'master' into new_master [Viktor Klang]
| | |\ \ \ \ \  
| | | |/ / / /  
| | | * | | | b3b2dda 2010-09-23 | Modified the hbase storage backend dependencies to exclude sl4j [David Greco]
| | | * | | |   c0e3dc6 2010-09-23 | renamed the files and the names of the habse tests, the names now ends with Test [David Greco]
| | | |\ \ \ \  
| | | * | | | | f9c8af6 2010-09-23 | renamed the files and the names of the habse tests, the names now ends with Test [David Greco]
| | | * | | | | 1d9d849 2010-09-23 | Modified the hbase storage backend dependencies to exclude sl4j [David Greco]
| | | | |_|_|/  
| | | |/| | |   
| | * | | | |   38365da 2010-09-23 | Merge branch 'master' into new_master [Viktor Klang]
| | |\ \ \ \ \  
| | | | |/ / /  
| | | |/| | |   
| | | * | | | a6dd098 2010-09-23 | Removing log4j and making Jetty intransitive [Viktor Klang]
| | | |/ / /  
| | * | | |   1c61c40 2010-09-22 | Merge branch 'master' into new_master [Viktor Klang]
| | |\ \ \ \  
| | | |/ / /  
| | | * | | 476e810 2010-09-22 | Bumping Jersey to 1.3 [Viktor Klang]
| | | * | | 96ded85 2010-09-22 | renamed the files and the names of the habse tests, the names now ends with Test [David Greco]
| | | * | | 38978ab 2010-09-22 | Now the hbase persistent storage tests dont'run by default [David Greco]
| | * | | | 4efef68 2010-09-22 | Ported HBase to use new Uuids [Viktor Klang]
| | * | | |   aae0a1c 2010-09-22 | Merge branch 'new_uuid' into new_master [Viktor Klang]
| | |\ \ \ \  
| | | * | | | 6afad7a 2010-09-22 | Preparing to add UUIDs to RemoteServer as well [Viktor Klang]
| | | * | | |   a4b3ead 2010-09-21 | Merge with master [Viktor Klang]
| | | |\ \ \ \  
| | | | | |_|/  
| | | | |/| |   
| | | * | | |   7d7fdd7 2010-09-19 | Adding better guard in id vs uuid parsing of ActorComponent [Viktor Klang]
| | | |\ \ \ \  
| | | | * | | | a6cc67a 2010-09-19 | Its a wrap! [Viktor Klang]
| | | * | | | | 5a98ba6 2010-09-19 | Its a wrap! [Viktor Klang]
| | | |/ / / /  
| | | * | | | 8464fd5 2010-09-17 | Aaaaalmost there... [Viktor Klang]
| | | * | | |   f9203d9 2010-09-17 | Merge with master + update RemoteProtocol.proto [Viktor Klang]
| | | |\ \ \ \  
| | | * | | | | 475a29c 2010-08-31 | Initial UUID migration [Viktor Klang]
| | * | | | | |   fd2be7c 2010-09-22 | Merge branch 'master' into new_master [Viktor Klang]
| | |\ \ \ \ \ \  
| | | | |_|_|/ /  
| | | |/| | | |   
| | * | | | | | 1fdaf22 2010-09-22 | Adding poms [Viktor Klang]
| * | | | | | | 4ea4158 2010-09-24 | Changed file-based mailbox creation [Jonas Bonér]
| * | | | | | |   444cbb1 2010-09-22 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | | |/ / / / /  
| | |/| | | | |   
| | * | | | | | 0b665b8 2010-09-22 | Corrected a bug, now the hbase quorum is read correctly from the configuration [David Greco]
| | |/ / / / /  
| | * | | | | c1a0505 2010-09-22 | fixed TypedActorBeanDefinitionParserTest [Michael Kober]
| | * | | | | ddee617 2010-09-22 | fixed merge error in conf [Michael Kober]
| | * | | | | d4be120 2010-09-22 | fixed missing aop.xml in akka-typed-actor jar [Michael Kober]
| | * | | | |   6608961 2010-09-22 | Merge branch 'ticket423' [Michael Kober]
| | |\ \ \ \ \  
| | | * | | | | 62d1510 2010-09-22 | closing ticket423, implemented custom placeholder configurer [Michael Kober]
| | | | |_|/ /  
| | | |/| | |   
| * | | | | |   044f06d 2010-09-22 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 52b8edb 2010-09-21 | The getVectorStorageRangeFor of HbaseStorageBackend shouldn't make any defensive programming against out of bound indexes. Now all the tests are passing again. The HbaseTicket343Spec.scala tests were expecting exceptions with out of bound indexes [David Greco]
| | * | | | | 356d3eb 2010-09-21 | Some refactoring and management of edge cases in the  getVectorStorageRangeFor method [David Greco]
| | * | | | |   85d52cb 2010-09-21 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \  
| | | |/ / / /  
| | | * | | |   75d148c 2010-09-20 | merged branch ticket364 [Michael Kober]
| | | |\ \ \ \  
| | | | * | | | 025d76d 2010-09-17 | closing #364, serializiation for typed actor proxy ref [Michael Kober]
| | | * | | | | 362c930 2010-09-20 | Removing dead code [Viktor Klang]
| | * | | | | |   bf1fe43 2010-09-20 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \  
| | | |/ / / / /  
| | | * | | | | d2abefc 2010-09-20 | Folding 3 booleans into 1 reference, preparing for @volatile decimation [Viktor Klang]
| | | * | | | | b1462ad 2010-09-20 | Threw away old ThreadBasedDispatcher and replaced it with an EBEDD with 1 in core pool and 1 in max pool [Viktor Klang]
| | * | | | | | 5379913 2010-09-20 | Corrected a bug where I wasn't reading the zookeeper quorum configuration correctly [David Greco]
| | * | | | | | 11e53bf 2010-09-20 | Corrected a bug where I wasn't reading the zookeeper quorum configuration correctly [David Greco]
| | * | | | | |   fc9c072 2010-09-20 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \  
| | | |/ / / / /  
| | | * | | | |   16a7a3e 2010-09-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \  
| | | | * | | | | 7b8d2a6 2010-09-20 | fixed merge [Michael Kober]
| | | | * | | | |   df44189 2010-09-20 | Merge branch 'find-actor-by-uuid' [Michael Kober]
| | | | |\ \ \ \ \  
| | | | | * | | | | 60dd1b9 2010-09-20 | added possibility to register and find remote actors by uuid [Michael Kober]
| | | * | | | | | | 1d48b91 2010-09-20 | Reverting some of the dataflow tests [Viktor Klang]
| | | |/ / / / / /  
| | * | | | | | | aac5784 2010-09-20 | Implemented the start and finish semantic in the getMapStorageRangeFor method [David Greco]
| | * | | | | | |   2aea4eb 2010-09-20 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \ \  
| | | |/ / / / / /  
| | | * | | | | | 5f08f12 2010-09-20 | Adding the old tests for the DataFlowStream [Viktor Klang]
| | | * | | | | | 7897cad 2010-09-20 | Fixing varargs issue with Logger.warn [Viktor Klang]
| | | |/ / / / /  
| | * | | | | | ca3538d 2010-09-20 | Implemented the start and finish semantic in the getMapStorageRangeFor method [David Greco]
| | * | | | | |   2145b09 2010-09-20 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \  
| | | |/ / / / /  
| | * | | | | |   4a37900 2010-09-18 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \  
| | * | | | | | | 45add70 2010-09-17 | Added the ticket 343 test too [David Greco]
| | * | | | | | | 0aee908 2010-09-17 | Now all the tests used to pass with Mongo and Cassandra are passing [David Greco]
| | * | | | | | |   54666cb 2010-09-17 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | 176bfd2 2010-09-17 | Starting to work on the hbase storage backend for maps [David Greco]
| | * | | | | | | | 71a6360 2010-09-17 | Starting to work on the hbase storage backend for maps [David Greco]
| | * | | | | | | |   e11ad77 2010-09-17 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \ \ \  
| | | | |_|_|_|_|/ /  
| | | |/| | | | | |   
| | * | | | | | | | 9ab3b23 2010-09-17 | Implemented the Ref and the Vector backend apis [David Greco]
| | * | | | | | | |   d750aa3 2010-09-16 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \ \ \  
| | * | | | | | | | | 75a03cf 2010-09-16 | Corrected a problem merging with the upstream [David Greco]
| | * | | | | | | | |   2eeb301 2010-09-16 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \   cc66952 2010-09-15 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | 881f7ae 2010-09-15 | Start to work on the HbaseStorageBackend [David Greco]
| | * | | | | | | | | | | e58d603 2010-09-15 | Start to work on the HbaseStorageBackend [David Greco]
| | * | | | | | | | | | | fb2ba7e 2010-09-15 | working on the hbase integration [David Greco]
| | * | | | | | | | | | |   7475b85 2010-09-15 | Merge remote branch 'upstream/master' [David Greco]
| | |\ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | 9c477ed 2010-09-15 | Added a simple test showing how to use Hbase testing utilities [David Greco]
| | * | | | | | | | | | | | a14697e 2010-09-15 | Added a simple test showing how to use Hbase testing utilities [David Greco]
| | * | | | | | | | | | | | 0721937 2010-09-15 | Added a simple test showing how to use Hbase testing utilities [David Greco]
| | * | | | | | | | | | | |   848e0cb 2010-09-15 | Added a new project akka-persistence-hbase [David Greco]
| | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|_|_|_|_|_|/  
| | | |/| | | | | | | | | |   
| | * | | | | | | | | | | | 8172252 2010-09-15 | Added a new project akka-persistence-hbase [David Greco]
| * | | | | | | | | | | | | 9ea09c3 2010-09-21 | Refactored mailbox configuration [Jonas Bonér]
| | |_|_|_|_|_|_|_|_|/ / /  
| |/| | | | | | | | | | |   
| * | | | | | | | | | | | e90d5b1 2010-09-19 | Readded a bugfixed DataFlowStream [Jonas Bonér]
| | |_|_|_|_|_|_|_|/ / /  
| |/| | | | | | | | | |   
| * | | | | | | | | | | e48011c 2010-09-18 | Switching from OP_READ to OP_WRITE [Viktor Klang]
| * | | | | | | | | | | f225530 2010-09-18 | fixed ticket #435. Also made serialization of mailbox optional - default true [Debasish Ghosh]
| | |_|_|_|_|_|_|/ / /  
| |/| | | | | | | | |   
| * | | | | | | | | | bc2f7a9 2010-09-17 | Ticket #343 implementation done except for pop of PersistentVector [Debasish Ghosh]
| | |_|_|_|_|_|/ / /  
| |/| | | | | | | |   
| * | | | | | | | | 00356a1 2010-09-16 | Adding support for optional maxrestarts and withinTime, closing ticket #346 [Viktor Klang]
| * | | | | | | | |   95520e2 2010-09-16 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \   264bf4b 2010-09-16 | Merge branch 'master' of https://github.com/jboner/akka [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | d8b827c 2010-09-16 | Extended akka-sample-camel to include server-managed remote typed consumer actors. Minor refactorings. [Martin Krasser]
| | | |_|_|_|_|/ / / /  
| | |/| | | | | | | |   
| * | | | | | | | | | a37ef6c 2010-09-16 | Fixing #437 by adding "Remote" Future [Viktor Klang]
| | |/ / / / / / / /  
| |/| | | | | | | |   
| * | | | | | | | |   97c4dd2 2010-09-16 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|/ /  
| | |/| | | | | | |   
| | * | | | | | | |   a8f6ae8 2010-09-16 | Merge branch 'ticket434' [Michael Kober]
| | |\ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|/ /  
| | |/| | | | | | |   
| | | * | | | | | | 7fb3e51 2010-09-16 | closing ticket 434; added id to ActorInfoProtocol [Michael Kober]
| | | |/ / / / / /  
| | * | | | | | | 0b35666 2010-09-16 | fix for issue #436, new version of sjson jar [Debasish Ghosh]
| | |/ / / / / /  
| | * | | | | | 0952281 2010-09-16 | Resolve casbah time dependency from casbah snapshots repo [Peter Vlugter]
| | | |_|_|/ /  
| | |/| | | |   
| * | | | | | fec83b8 2010-09-16 | Closing #427 and #424 [Viktor Klang]
| * | | | | | 3d897d3 2010-09-16 | Make ExecutorBasedEventDrivenDispatcherActorSpec deterministic [Viktor Klang]
| |/ / / / /  
| * | | | | 6fb46d4 2010-09-15 | Closing #264, addign JavaAPI to DataFlowVariable [Viktor Klang]
| | |_|/ /  
| |/| | |   
| * | | | 42d9d6a 2010-09-15 | Updated akka-reference.conf with deadline [Viktor Klang]
| * | | | 496a8b6 2010-09-15 | Added support for throughput deadlines [Viktor Klang]
| | |/ /  
| |/| |   
| * | |   e976457 2010-09-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * | | 72737d5 2010-09-14 | fixed bug in PersistentSortedSet implemnetation of redis [Debasish Ghosh]
| * | | |   86a0348 2010-09-14 | Merge branch 'master' into ticket_419 [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| | * | | 4386c3c 2010-09-14 | disabled tests for redis and mongo to be run automatically since they need running servers [Debasish Ghosh]
| | * | |   5218fcb 2010-09-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \  
| | | * | | 395b029 2010-09-14 | The unborkening of master: The return of the Poms [Viktor Klang]
| | | |/ /  
| | * | | 94a3841 2010-09-14 | The unborkening of master: The return of the Poms [Viktor Klang]
| | |/ /  
| | * |   efd3287 2010-09-13 | Merge branch 'ticket194' [Michael Kober]
| | |\ \  
| | | * | 182885b 2010-09-13 | merged with master [Michael Kober]
| | | * | 1077719 2010-09-13 | merged with master [Michael Kober]
| | | * |   3d2af5f 2010-09-13 | merged with master [Michael Kober]
| | | |\ \  
| | | * | | 8bc2663 2010-09-13 | closing ticket #426 [Michael Kober]
| | | * | | a224d26 2010-09-09 | closing ticket 378 [Michael Kober]
| | | * | |   fa0db0d 2010-09-07 | Merge with upstream [Viktor Klang]
| | | |\ \ \  
| | | | * | | ba1ab2b 2010-09-06 | implemented server managed typed actor [Michael Kober]
| | | | * | | 0e9bac2 2010-09-06 | started working on ticket 194 [Michael Kober]
| | | * | | | 977f4e6 2010-09-07 | Removing boilerplate in ReflectiveAccess [Viktor Klang]
| | | * | | | e0b81ae 2010-09-07 | Fixing id/uuid misfortune [Viktor Klang]
| | * | | | |   698fbf9 2010-09-13 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| | |\ \ \ \ \  
| | | * | | | | f59bf05 2010-09-13 | Merge introduced old code [Viktor Klang]
| | | * | | | |   cd9c4de 2010-09-13 | Merge branch 'ticket_250' of github.com:jboner/akka into ticket_250 [Viktor Klang]
| | | |\ \ \ \ \  
| | | | * | | | | 6ae312f 2010-09-12 | Switching dispatching strategy to 1 runnable per mailbox and removing use of TransferQueue [Viktor Klang]
| | | * | | | | | 839c81d 2010-09-12 | Switching dispatching strategy to 1 runnable per mailbox and removing use of TransferQueue [Viktor Klang]
| | | |/ / / / /  
| | | * | | | |   03a54e1 2010-09-12 | Merge branch 'master' into ticket_250 [Viktor Klang]
| | | |\ \ \ \ \  
| | | * | | | | | acbcd9e 2010-09-12 | Take advantage of short-circuit to avoid lazy init if possible [Viktor Klang]
| | | * | | | | |   985882f 2010-09-12 | Merge remote branch 'origin/ticket_250' into ticket_250 [Viktor Klang]
| | | |\ \ \ \ \ \  
| | | | * \ \ \ \ \   2f89dd2 2010-09-12 | Resolved conflict [Viktor Klang]
| | | | |\ \ \ \ \ \  
| | | * | \ \ \ \ \ \   4ab6d33 2010-09-12 | Adding final declarations [Viktor Klang]
| | | |\ \ \ \ \ \ \ \  
| | | | |/ / / / / / /  
| | | |/| / / / / / /   
| | | | |/ / / / / /    
| | | | * | | | | |   48c1e13 2010-09-12 | Better latency [Viktor Klang]
| | | | |\ \ \ \ \ \  
| | | * | \ \ \ \ \ \   49f6b38 2010-09-12 | Improving latency in EBEDD [Viktor Klang]
| | | |\ \ \ \ \ \ \ \  
| | | | |/ / / / / / /  
| | | |/| / / / / / /   
| | | | |/ / / / / /    
| | | | * | | | | | 4f473d3 2010-09-12 | Safekeeping [Viktor Klang]
| | | * | | | | | | c3d66ed 2010-09-11 | 1 entry per mailbox at most [Viktor Klang]
| | | |/ / / / / /  
| | | * | | | | | 94ad3f9 2010-09-10 | Added more safeguards to the WorkStealers tests [Viktor Klang]
| | | * | | | | |   cc36786 2010-09-10 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \  
| | | | | |_|_|/ /  
| | | | |/| | | |   
| | | * | | | | | 158ea29 2010-09-10 | Massive refactoring of EBEDD and WorkStealer and basically everything... [Viktor Klang]
| | | * | | | | | 5fdaad4 2010-09-09 | Optimization started of EBEDD [Viktor Klang]
| | * | | | | | |   0d2ed3b 2010-09-13 | Merge branch 'branch-343' [Debasish Ghosh]
| | |\ \ \ \ \ \ \  
| | | |_|_|/ / / /  
| | |/| | | | | |   
| | | * | | | | | 43b86b9 2010-09-12 | refactoring for more type safety [Debasish Ghosh]
| | | * | | | | | 185c38c 2010-09-12 | all mongo update operations now use safely {} to pin connection at the driver level [Debasish Ghosh]
| | | * | | | | | 354e535 2010-09-11 | redis keys are no longer base64-ed. Though values are [Debasish Ghosh]
| | | * | | | | | 8d31ab7 2010-09-10 | changes for ticket #343. Test harness runs for both Redis and Mongo [Debasish Ghosh]
| | | * | | | | | 44c0d5b 2010-09-09 | Refactor mongodb module to confirm to Redis and Cassandra. Issue #430 [Debasish Ghosh]
| * | | | | | | | aae2efc 2010-09-13 | Added meta data to network protocol [Jonas Bonér]
| * | | | | | | | 2810aa5 2010-09-13 | Remove initTransactionalState, renamed init and shutdown [Viktor Klang]
| |/ / / / / / /  
| * | | | | | | e255f5f 2010-09-12 | Setting -1 as default mailbox capacity [Viktor Klang]
| | |_|/ / / /  
| |/| | | | |   
| * | | | | | 5f67da0 2010-09-10 | Removed logback config files from akka-actor and akka-remote and use only those in $AKKA_HOME/config (see also ticket #410). [Martin Krasser]
| * | | | | | bae879d 2010-09-09 | Added findValue to Index [Viktor Klang]
| * | | | | | 5df8dac 2010-09-09 | Moving the Atmosphere AkkaBroadcaster dispatcher to be shared [Viktor Klang]
| | |/ / / /  
| |/| | | |   
| * | | | | 37130ad 2010-09-09 | Added convenience method for push timeout on EBEDD [Viktor Klang]
| * | | | | c9ad9b5 2010-09-09 | ExecutorBasedEventDrivenDispatcher now works and unit tests are added [Viktor Klang]
| * | | | |   af73797 2010-09-09 | Merge branch 'master' into safe_mailboxes [Viktor Klang]
| |\ \ \ \ \  
| | * | | | | b57e048 2010-09-09 | Added comments and removed inverted logic [Viktor Klang]
| * | | | | |   1c57b02 2010-09-09 | Merge with master [Viktor Klang]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | f1a1755 2010-09-09 | Removing Reactor based dispatchers and closing #428 [Viktor Klang]
| | * | | | |   9c1cbff 2010-09-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \  
| | | |/ / / /  
| | | * | | | ba7801c 2010-09-08 | minor edits to scala test specs descriptors, fix up comments [rossputin]
| | * | | | | 7e0442d 2010-09-09 | Fixing #425 by retrieving the MODULE$ [Viktor Klang]
| | |/ / / /  
| * | | | | 45bf7c7 2010-09-08 | Added more comments for the mailboxfactory [Viktor Klang]
| * | | | |   fe461fd 2010-09-08 | Merge branch 'master' into safe_mailboxes [Viktor Klang]
| |\ \ \ \ \  
| | |/ / / /  
| | * | | | aab16ef 2010-09-08 | Optimization of Index [Viktor Klang]
| * | | | | 928fa63 2010-09-07 | Adding support for safe mailboxes [Viktor Klang]
| * | | | | c2b85ee 2010-09-07 | Removing erronous use of uuid and replaced with id [Viktor Klang]
| * | | | | 69ed8ae 2010-09-07 | Removing boilerplate in reflective access [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | |   747e07e 2010-09-07 | Merge remote branch 'origin/master' [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| | * | |   fb9d273 2010-09-06 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \  
| | * | | | 9a2163d 2010-09-06 | improved error reporting [Jonas Bonér]
| * | | | | fd480e9 2010-09-07 | Refactoring RemoteServer [Viktor Klang]
| * | | | | 4841996 2010-09-06 | Adding support for BoundedTransferQueue to EBEDD [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | | db5a8c1 2010-09-06 | Added javadocs for Function and Procedure [Viktor Klang]
| * | | | 9ffd618 2010-09-06 | Added Function and Procedure (Java API) + added them to Agent, closing #262 [Viktor Klang]
| * | | | 9fe5827 2010-09-06 | Added setAccessible(true) to circumvent security exceptions [Viktor Klang]
| * | | |   e66bb02 2010-09-06 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| | * | | 0a7c2c7 2010-09-06 | minor fix [Jonas Bonér]
| | * | |   165b22e 2010-09-06 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \  
| | * | | | 403dc2b 2010-09-06 | minor edits [Jonas Bonér]
| * | | | | 641e63a 2010-09-06 | Closing ticket #261 [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | |   58dc1f4 2010-09-06 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | | |/ /  
| | |/| |   
| | * | | 28f1949 2010-09-06 | fix: server initiated remote actors not found [Michael Kober]
| * | | | 6a1cb74 2010-09-06 | Closing #401 with a nice, brand new, multimap [Viktor Klang]
| * | | | 1fed6a2 2010-09-06 | Removing unused field [Viktor Klang]
| |/ / /  
| * | | 485aebb 2010-09-05 | redisclient support for Redis 2.0. Not fully backward compatible, since Redis 2.0 has some differences with 1.x [Debasish Ghosh]
| * | | 526a357 2010-09-04 | Removed LIFT_VERSION [Viktor Klang]
| * | | 1a6079d 2010-09-04 | Removing Lift sample project and deps (saving ~5MB of dist size [Viktor Klang]
| * | | 930a3af 2010-09-04 | Fixing Dispatcher config bug #422 [Viktor Klang]
| * | | 2286e96 2010-09-03 | Added support for UntypedLoadBalancer and UntypedDispatcher [Viktor Klang]
| * | |   42201a8 2010-09-03 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * | | 5199063 2010-09-02 | added config element for mailbox capacity, ticket 408 [Michael Kober]
| | |/ /  
| * | | f2d8651 2010-09-03 | Fixing ticket #420 [Viktor Klang]
| * | | 68a6319 2010-09-03 | Fixing mailboxSize for ThreadBasedDispatcher [Viktor Klang]
| |/ /  
| * | 8e1c3ac 2010-09-01 | Moved ActorSerialization to 'serialization' package [Jonas Bonér]
| * |   6c65023 2010-09-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \  
| | * | 817dd12 2010-09-01 | Optimization + less code [Viktor Klang]
| | * | 9368ddc 2010-09-01 | Added support hook for persistent mailboxes + cleanup and optimizations [Viktor Klang]
| | * |   021dd2d 2010-09-01 | Merge branch 'log-categories' [Michael Kober]
| | |\ \  
| | | * | f6b6bd3 2010-09-01 | added alias for log category warn [Michael Kober]
| | * | | 6bec082 2010-08-31 | Upgrading Multiverse to 0.6.1 [Viktor Klang]
| | | |/  
| | |/|   
| | * | a364ce1 2010-08-31 | Add possibility to set default cometSupport in akka.conf [Viktor Klang]
| | * | cd0d5d0 2010-08-31 | Fix ticket #415 + add Jetty dep [Viktor Klang]
| | * |   69822ae 2010-08-31 | Merge branch 'oldmaster' [Viktor Klang]
| | |\ \  
| | | * | 4df0b66 2010-08-31 | Increased the default timeout for ThreadBasedDispatcher to 10 seconds [Viktor Klang]
| | | * |   a7d7923 2010-08-31 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \  
| | | | |/  
| | | * | 09ab5a5 2010-08-30 | Moving Queues into akka-actor [Viktor Klang]
| | | * |   ec47355 2010-08-30 | Merge branch 'master' into transfer_queue [Viktor Klang]
| | | |\ \  
| | | * \ \   5e649e9 2010-08-30 | Merge branch 'master' of github.com:jboner/akka into transfer_queue [Viktor Klang]
| | | |\ \ \  
| | | * | | | 13e4ad4 2010-08-27 | Added boilerplate to improve BoundedTransferQueue performance [Viktor Klang]
| | | * | | |   ea19d19 2010-08-27 | Switched to mailbox instead of local queue for ThreadBasedDispatcher [Viktor Klang]
| | | |\ \ \ \  
| | | * | | | | 657f623 2010-08-26 | Changed ThreadBasedDispatcher from LinkedBlockingQueue to TransferQueue [Viktor Klang]
| | * | | | | | 15b4d51 2010-08-31 | Ripping out Grizzly and replacing it with Jetty [Viktor Klang]
| | | |_|_|_|/  
| | |/| | | |   
| * | | | | | d913f6d 2010-08-31 | Added all config options for STM to akka.conf [Jonas Bonér]
| |/ / / / /  
| * | | | | ea60588 2010-08-30 | Changed JtaModule to use structural typing instead of Field reflection, plus added a guard [Jonas Bonér]
| | |_|_|/  
| |/| | |   
| * | | | 8e884df 2010-08-30 | Updating Netty to 3.2.2.Final [Viktor Klang]
| * | | | 92c86e8 2010-08-30 | Fixing master [Viktor Klang]
| | |_|/  
| |/| |   
| * | |   4a339f8 2010-08-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * | | 76097ee1 2010-08-27 | remove logback.xml from akka-core jar and exclude logback-test.xml from distribution. [Martin Krasser]
| | * | | a23159f 2010-08-27 | Make sure dispatcher isnt changed on actor restart [Viktor Klang]
| | * | | 35cc621 2010-08-27 | Adding a guard to dispatcher_= in ActorRef [Viktor Klang]
| | | |/  
| | |/|   
| | * | d9384b9 2010-08-27 | Conserving memory usage per dispatcher [Viktor Klang]
| | |/  
| | *   1d96584 2010-08-26 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\  
| | | *   0134829 2010-08-26 | Merge branch 'master' of github.com:jboner/akka [Michael Kober]
| | | |\  
| | | * | 9d5b606 2010-08-26 | fixed resart of actor with thread based dispatcher [Michael Kober]
| | * | | 7448969 2010-08-26 | Changing source jar naming from src to sources [Viktor Klang]
| | | |/  
| | |/|   
| | * | badf7d5 2010-08-26 | Added more comments and made code more readable for the BoundedTransferQueue [Viktor Klang]
| | * | be1aba8 2010-08-26 | RemoteServer now notifies listeners on connect for non-ssl communication [Viktor Klang]
| | |/  
| | * deb8a1f 2010-08-25 | Constraining input [Viktor Klang]
| | * ab5dc41 2010-08-25 | Refining names [Viktor Klang]
| | * a03952f 2010-08-25 | Adding BoundedTransferQueue [Viktor Klang]
| | * f47a10e 2010-08-25 | Small refactor [Viktor Klang]
| | * c7b91a4 2010-08-24 | Adding some comments for the future [Viktor Klang]
| | * e4720d4 2010-08-24 | Reconnect now possible in RemoteClient [Viktor Klang]
| | * 8bc8370 2010-08-24 | Optimization of DataFlow + bugfix [Viktor Klang]
| | * e7ce753 2010-08-24 | Update sbt plugin [Peter Vlugter]
| | * 4ae02d8 2010-08-23 | Document and remove dead code, restructure tests [Viktor Klang]
| * | ee47eae 2010-08-28 | removed trailing whitespace [Jonas Bonér]
| * | fc70682 2010-08-28 | renamed cassandra storage-conf.xml [Jonas Bonér]
| * | 7586fcf 2010-08-28 | Completed refactoring into lightweight modules akka-actor akka-typed-actor and akka-remote [Jonas Bonér]
| * | c67b17a 2010-08-24 | splitted up akka-core into three modules; akka-actors, akka-typed-actors, akka-core [Jonas Bonér]
| * | b7b7948 2010-08-23 | minor reformatting [Jonas Bonér]
| |/  
| * be5160b 2010-08-23 | Some more dataflow cleanup [Viktor Klang]
| *   fbc0b22 2010-08-23 | Merge branch 'dataflow' [Viktor Klang]
| |\  
| | * 2db2df3 2010-08-23 | Refactor, optimize, remove non-working code [Viktor Klang]
| | *   6f3a9c6 2010-08-22 | Merge branch 'master' into dataflow [Viktor Klang]
| | |\  
| | * | eec6e38 2010-08-20 | One minute is shorter, and cleaned up blocking readers impl [Viktor Klang]
| | * |   84ea41a 2010-08-20 | Merge branch 'master' into dataflow [Viktor Klang]
| | |\ \  
| | * | | 40d382e 2010-08-20 | Added tests for DataFlow [Viktor Klang]
| | * | | 91f7191 2010-08-20 | Added lazy initalization of SSL engine to avoid interference [Viktor Klang]
| | * | | d825a58 2010-08-19 | Fixing bugs in DataFlowVariable and adding tests [Viktor Klang]
| * | | | 0f66fa0 2010-08-23 | Fixed deadlock in RemoteClient shutdown after reconnection timeout [Jonas Bonér]
| * | | | 56757e1 2010-08-23 | Updated version to 1.0-SNAPSHOT [Jonas Bonér]
| * | | | ea24fa2 2010-08-22 | Changed package name of FSM module to 'se.ss.a.a' plus name from 'Fsm' to 'FSM' [Jonas Bonér]
| | |_|/  
| |/| |   
| * | | 133b5f7 2010-08-21 | Release 0.10 (v0.10) [Jonas Bonér]
| * | | 89e6ec1 2010-08-21 | Enhanced the RemoteServer/RemoteClient listener API [Jonas Bonér]
| * | |   d255d82 2010-08-21 | Added missing events to RemoteServer Listener API [Jonas Bonér]
| |\ \ \  
| | * | | 689e8e9 2010-08-21 | Changed the RemoteClientLifeCycleEvent to carry a reference to the RemoteClient + dito for RemoteClientException [Jonas Bonér]
| * | | | c095faf 2010-08-21 | removed trailing whitespace [Jonas Bonér]
| * | | | 863d861 2010-08-21 | dos2unix [Jonas Bonér]
| * | | | fa954d6 2010-08-21 | Added mailboxCapacity to Dispatchers API + documented config better [Jonas Bonér]
| * | | | 5237cb9 2010-08-21 | Changed the RemoteClientLifeCycleEvent to carry a reference to the RemoteClient + dito for RemoteClientException [Jonas Bonér]
| |/ / /  
| * | |   fee0d1e 2010-08-21 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * | | f4e62e6 2010-08-21 | Update to Multiverse 0.6 final [Peter Vlugter]
| * | | | d7ecb6c 2010-08-21 | Added support for reconnection-time-window for RemoteClient, configurable through akka-reference.conf [Jonas Bonér]
| |/ / /  
| * | | fd69201 2010-08-21 | Added option to use a blocking mailbox with custom capacity [Jonas Bonér]
| * | | 60d16d9 2010-08-21 | Test for RequiresNew propagation [Peter Vlugter]
| * | | 2699b29 2010-08-21 | Rename explicitRetries to blockingAllowed [Peter Vlugter]
| * | | 9c438bd 2010-08-21 | Add transaction propagation level [Peter Vlugter]
| | |/  
| |/|   
| * |   8a92141 2010-08-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \  
| | * | 44edaef 2010-08-19 | fixed remote server name [Michael Kober]
| | * | ada1805 2010-08-19 | blade -> chopstick [momania]
| | * | 346800d 2010-08-19 | moved fsm spec to correct location [momania]
| | * |   728f846 2010-08-19 | Merge branch 'fsm' [momania]
| | |\ \  
| | | |/  
| | |/|   
| | | * 415bc08 2010-08-19 | Dining hakkers on fsm [momania]
| | | *   fd35b7a 2010-08-19 | Merge branch 'master' into fsm [momania]
| | | |\  
| | | * | 8a48fef 2010-07-20 | better matching reply value [momania]
| | | * | 8b40590 2010-07-20 | use ref for state- makes sense? [momania]
| | | * | 6485ba8 2010-07-20 | State refactor [momania]
| | | * | 9687bf8 2010-07-20 | State refactor [momania]
| | | * | f7d5315 2010-07-19 | move StateTimeout into Fsm [momania]
| | | * | 5eb62f2 2010-07-19 | foreach -> flatMap [momania]
| | | * | 6a82839 2010-07-19 | refactor fsm [momania]
| | | * | 00af83a 2010-07-19 | initial idea for FSM [momania]
| * | | | 6c4b866 2010-08-20 | Exit is bad mkay [Viktor Klang]
| * | | | 360b325 2010-08-20 | Added lazy initalization of SSL engine to avoid interference [Viktor Klang]
| |/ / /  
| * | | 63d0af3 2010-08-19 | Added more flexibility to ListenerManagement [Viktor Klang]
| * | |   24fc4da 2010-08-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | | |/  
| | |/|   
| | * |   7e59006 2010-08-19 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \  
| | * | | 51b22b2 2010-08-19 | Introduced uniquely identifiable, loggable base exception: AkkaException and made use of it throught the project [Jonas Bonér]
| * | | | fb2d777 2010-08-19 | Changing Listeners backing store to ConcurrentSkipListSet and changing signature of WithListeners(f) to (ActorRef) => Unit [Viktor Klang]
| | |/ /  
| |/| |   
| * | | 2cf35a2 2010-08-18 | Hard-off-switching SSL Remote Actors due to not production ready for 0.10 [Viktor Klang]
| * | | 8116bf8 2010-08-18 | Adding scheduling thats usable from TypedActor [Viktor Klang]
| |/ /  
| * |   04af409 2010-08-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \  
| | * \   06db9cc 2010-08-18 | Merge branch 'master' of github.com:jboner/akka [rossputin]
| | |\ \  
| | | * \   244a70f 2010-08-18 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | | |\ \  
| | | | * | 8f7204e 2010-08-18 | Adding lifecycle messages and listenability to RemoteServer [Viktor Klang]
| | | | * | 311d35c 2010-08-18 | Adding DiningHakkers as FSM example [Viktor Klang]
| | | * | | a3fe99b 2010-08-18 | Closes #398 Fix broken tests in akka-camel module [Martin Krasser]
| | * | | | 8c6afb5 2010-08-18 | add akka-init-script.sh to allArtifacts in AkkaProject [rossputin]
| * | | | | 164aa94 2010-08-18 | removed codefellow plugin [Jonas Bonér]
| | |_|/ /  
| |/| | |   
| * | | | e96d57e 2010-08-17 | Added a more Java-suitable, and less noisy become method [Viktor Klang]
| | |/ /  
| |/| |   
| * | |   8d2469b 2010-08-17 | Merge branch 'master' of github.com:jboner/akka [Jonas Boner]
| |\ \ \  
| | * | | 9d79eb1 2010-08-17 | Issue #388 Typeclass serialization of ActorRef/UntypedActor isn't Java-friendly : Added wrapper APIs for implicits. Also added test cases for serialization of UntypedActor [Debasish Ghosh]
| | * | |   325efd9 2010-08-16 | merged with master [Michael Kober]
| | |\ \ \  
| | | * | | 6ec0ebb 2010-08-16 | fixed properties for untyped actors [Michael Kober]
| | | |/ /  
| | * | |   0bda754 2010-08-16 | Merge branch 'master' of github.com:jboner/akka [Michael Kober]
| | |\ \ \  
| | | * | | c0812bd 2010-08-16 | Changed signature of ActorRegistry.find [Viktor Klang]
| | | |/ /  
| | * | | edccbb3 2010-08-16 | fixed properties for untyped actors [Michael Kober]
| | |/ /  
| * | | c49bf3a 2010-08-17 | Refactoring: TypedActor now extends Actor and is thereby a full citizen in the Akka actor-land [Jonas Boner]
| * | | e53d220 2010-08-16 | Return Future from TypedActor message send [Jonas Boner]
| |/ /  
| * | 19ac69f 2010-08-16 | merged with upstream [Jonas Boner]
| * |   565446d 2010-08-16 | Merge branch 'master' of github.com:jboner/akka [Jonas Boner]
| |\ \  
| | * | e5d1245 2010-08-16 | Added defaults to scan and debug in logback configuration [Viktor Klang]
| | * | 90ee6cb 2010-08-16 | Fixing logback config file locate [Viktor Klang]
| | * | 871e079 2010-08-16 | Migrated test to new API [Viktor Klang]
| | * | b0a31bb 2010-08-16 | Added a lot of docs for the Java API [Viktor Klang]
| | * |   4a4688a 2010-08-16 | Merge branch 'master' into java_actor [Viktor Klang]
| | |\ \  
| | | * \   ce98ad5 2010-08-16 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \  
| | | * | | b7e9bc4 2010-08-13 | Added support for pool factors and executor bounds [Viktor Klang]
| | * | | | c293420 2010-08-13 | Holy crap, it actually works! [Viktor Klang]
| | * | | | 1e9883d 2010-08-13 | Initial conversion of UntypedActor [Viktor Klang]
| | |/ / /  
| * | | | 01ea59c 2010-08-16 | Added shutdown of un-supervised Temporary that have crashed [Jonas Boner]
| * | | | c6dd7dd 2010-08-16 | minor edits [Jonas Boner]
| | |/ /  
| |/| |   
| * | |   612c308 2010-08-16 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * | | bea8f16 2010-08-16 | Some Java friendliness for STM [Peter Vlugter]
| * | | | 335c0d3 2010-08-16 | Fixed unessecary remote actor registration of sender reference [Jonas Bonér]
| |/ / /  
| * | | 8e48ace 2010-08-15 | Closes #393 Redesign CamelService singleton to be a CamelServiceManager [Martin Krasser]
| * | | 177801d 2010-08-14 | Cosmetic changes to akka-sample-camel [Martin Krasser]
| * | | 5bb7811 2010-08-14 | Closes #392 Support untyped Java actors as endpoint producer [Martin Krasser]
| * | |   2940801 2010-08-14 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \  
| | |/ /  
| | * | 854d7a4 2010-08-13 | Removing legacy dispatcher id [Viktor Klang]
| | * | 5295c48 2010-08-13 | Fix Atmosphere integration for the new dispatchers [Viktor Klang]
| | * | b05781b 2010-08-13 | Cleaned up code and verified tests [Viktor Klang]
| | * | cd529cd 2010-08-13 | Added utility method and another test [Viktor Klang]
| | * |   a5090b1 2010-08-13 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \  
| | | * | 5bdbfd7 2010-08-13 | fixed untyped actor parsing [Michael Kober]
| | | * | b269142 2010-08-13 | closing ticket198: support for thread based dispatcher in spring config [Michael Kober]
| | | * | 95d0b52 2010-08-13 | added thread based dispatcher config for untyped actors [Michael Kober]
| | | * | 0843807 2010-08-13 | Update for Ref changes [Peter Vlugter]
| | | * | 2d2d177 2010-08-13 | Small changes to Ref [Peter Vlugter]
| | | * | 621e133 2010-08-12 | Cosmetic [momania]
| | | * | b6f05de 2010-08-12 | Use static 'parseFrom' to create protobuf objects instead of creating a defaultInstance all the time. [momania]
| | | * |   bbb17e6 2010-08-12 | Merge branch 'rpc_amqp' [momania]
| | | |\ \  
| | | | * \   2b0e9a1 2010-08-12 | Merge branch 'master' of git-proxy:jboner/akka into rpc_amqp [momania]
| | | | |\ \  
| | | | * | | 072425e 2010-08-12 | disable tests again [momania]
| | | | * | | 50e5e5e 2010-08-12 | making it more easy to start string and protobuf base consumers, producers and rpc style [momania]
| | | | * | | 2956647 2010-08-12 | shutdown linked actors too when shutting down supervisor [momania]
| | | | * | | 1ecbae9 2010-08-12 | added shutdownAll to be able to kill the whole actor tree, incl the amqp supervisor [momania]
| | | | * | |   6b8e20d 2010-08-11 | Merge branch 'master' of git-proxy:jboner/akka into rpc_amqp [momania]
| | | | |\ \ \  
| | | | * | | | 6393a94 2010-08-11 | added async call with partial function callback to rpcclient [momania]
| | | | * | | | 93e8830 2010-08-11 | manual rejection of delivery (for now by making it fail until new rabbitmq version has basicReject) [momania]
| | | | * | | |   a97077a 2010-08-11 | Merge branch 'master' of git-proxy:jboner/akka into rpc_amqp [momania]
| | | | |\ \ \ \  
| | | | * | | | | 9815ef7 2010-08-10 | types seem to help the parameter declaration :S [momania]
| | | | * | | | | 4770846 2010-08-10 | add durablility and auto-delete with defaults to rpc and with passive = true for client [momania]
| | | | * | | | | 6a586d1 2010-08-09 | undo local repo settings (for the 25953467296th time :S ) [momania]
| | | | * | | | | 75c2c14 2010-08-09 | added optional routingkey and queuename to parameters [momania]
| | | | * | | | | b1fe483 2010-08-06 | remove rpcclient trait... [momania]
| | | | * | | | | 533319d 2010-08-06 | disable ampq tests [momania]
| | | | * | | | | be4be45 2010-08-06 | - moved all into package folder structure - added simple protobuf based rpc convenience [momania]
| | | * | | | | | f0b5d38 2010-08-12 | closing ticket 377, 376 and 200 [Michael Kober]
| | | * | | | | | bef2332 2010-08-12 | added config for WorkStealingDispatcher and HawtDispatcher; Tickets 200 and 377 [Michael Kober]
| | | * | | | | | 40a91f2 2010-08-11 | ported unit tests for spring config from java to scala, removed akka-spring-test-java [Michael Kober]
| | | | |_|_|/ /  
| | | |/| | | |   
| | | * | | | |   5ad9370 2010-08-12 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \ \  
| | | * | | | | | 82ec8b3 2010-08-12 | Fixed #305. Invoking 'stop' on client-managed remote actors does not shut down remote instance (but only local) [Jonas Bonér]
| | * | | | | | | 2428f93 2010-08-13 | Added tests are fixed some bugs [Viktor Klang]
| | * | | | | | | a293e16 2010-08-12 | Adding first support for config dispatchers [Viktor Klang]
| | | |/ / / / /  
| | |/| | | | |   
| | * | | | | |   66a7133 2010-08-12 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \  
| | | |/ / / / /  
| | | * | | | |   4dc1eae 2010-08-12 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \ \  
| | | * | | | | | 79b3b6d 2010-08-12 | Added tests for remotely supervised TypedActor [Jonas Bonér]
| | * | | | | | | 1ec811b 2010-08-12 | Add default DEBUG to test output [Viktor Klang]
| | | |/ / / / /  
| | |/| | | | |   
| | * | | | | | b5b6574 2010-08-12 | Allow core threads to time out in dispatchers [Viktor Klang]
| | * | | | | | cfa68f5 2010-08-12 | Moving logback-test.xml to /config [Viktor Klang]
| | |/ / / / /  
| | * | | | | a6a02de 2010-08-12 | Add actorOf with call-by-name for Java TypedActor [Jonas Bonér]
| | * | | | |   9dedfab 2010-08-12 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \  
| | * | | | | | b0b294a 2010-08-12 | Refactored Future API to make it more Java friendly [Jonas Bonér]
| * | | | | | | 292477c 2010-08-14 | Full Camel support for untyped and typed actors (both Java and Scala API). Closes #356, closes 357. [Martin Krasser]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | 8744ee5 2010-08-11 | Extra robustness for Logback [Viktor Klang]
| * | | | | | 79df750 2010-08-11 | Minor perf improvement in Ref [Viktor Klang]
| * | | | | | 3d6500f 2010-08-11 | Ported TransactorSpec to UntypedActor [Viktor Klang]
| * | | | | | 3861bea 2010-08-11 | Changing akka-init-script.sh to use logback [Viktor Klang]
| | |_|_|/ /  
| |/| | | |   
| * | | | |   b1d942b 2010-08-11 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \  
| | |/ / / /  
| | * | | | c4f4ddf 2010-08-11 | added init script [Jonas Bonér]
| | | |/ /  
| | |/| |   
| * | | | 89722c5 2010-08-11 | Switch to Logback! [Viktor Klang]
| * | | | 6efe4d0 2010-08-11 | Ported ReceiveTimeoutSpec to UntypedActor [Viktor Klang]
| * | | | 7be2daa 2010-08-11 | Ported ForwardActorSpec to UntypedActor [Viktor Klang]
| * | | |   910b61d 2010-08-11 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| | * | |   d7f6b28 2010-08-11 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \  
| | * | | | 864cc4c 2010-08-11 | Fixed issue in AMQP by not supervising a consumer handler that is already supervised [Jonas Bonér]
| | * | | | ee5195f 2010-08-10 | removed trailing whitespace [Jonas Bonér]
| | * | | | 9de0ffd 2010-08-10 | Converted tabs to spaces [Jonas Bonér]
| | * | | | 26dc435 2010-08-10 | Reformatting [Jonas Bonér]
| * | | | | 01f313c 2010-08-10 | Performance optimization? [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | | 4cbf8b5 2010-08-10 | Grouped JDMK modules [Viktor Klang]
| * | | |   a12b19c 2010-08-10 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| | * | |   cb1f0a2 2010-08-10 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \  
| | * | | | 1948918 2010-08-10 | Did some work on improving the Java API (UntypedActor) [Jonas Bonér]
| * | | | | 9beed3a 2010-08-10 | Reduce memory use per Actor [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | | 6c1d32d 2010-08-09 | Closing ticket #372, added tests [Viktor Klang]
| * | | |   cfd7033 2010-08-09 | Merge branch 'master' into ticket372 [Viktor Klang]
| |\ \ \ \  
| | * | | | d62fdcd 2010-08-09 | Fixing a boot sequence issue with RemoteNode [Viktor Klang]
| | * | | | 3ad5f34 2010-08-09 | Cleanup and Atmo+Lift version bump [Viktor Klang]
| | |/ / /  
| | * | | 6d41299 2010-08-09 | The unborkening [Viktor Klang]
| | * | | d99e566 2010-08-09 | Updated docs [Viktor Klang]
| | * | | 6f42eee 2010-08-09 | Removed if*-methods and improved performance for arg-less logging [Viktor Klang]
| | * | | d41f64d 2010-08-09 | Formatting [Viktor Klang]
| | * | | bc7326e 2010-08-09 | Closing ticket 370 [Viktor Klang]
| * | | | db842de 2010-08-06 | Fixing ticket 372 [Viktor Klang]
| |/ / /  
| * | |   afe820e 2010-08-06 | Merge branch 'master' into ticket337 [Viktor Klang]
| |\ \ \  
| | |/ /  
| | * | 09d7cc7 2010-08-06 | - forgot the api commit - disable tests again :S [momania]
| | * | f6d86ed 2010-08-06 | - move helper object actors in specs companion object to avoid clashes with the server spec (where the helpers have the same name) [momania]
| | * | 17e97ea 2010-08-06 | - made rpc handler reqular function instead of partial function - add queuename as optional parameter for rpc server (for i.e. loadbalancing purposes) [momania]
| | * |   aebdc77 2010-08-06 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | |\ \  
| | * \ \   7c33e7f 2010-08-03 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | |\ \ \  
| | * | | | 7a8caac 2010-07-27 | no need for the dummy tests anymore [momania]
| * | | | | 6c6d3d2 2010-08-06 | Closing ticket 337 [Viktor Klang]
| | |_|/ /  
| |/| | |   
| * | | | 88053cf 2010-08-05 | Added unit test to test for race-condition in ActorRegistry [Viktor Klang]
| * | | |   7f364df 2010-08-05 | Fixed race condition in ActorRegistry [Viktor Klang]
| |\ \ \ \  
| | * | | | e58e9b9 2010-08-04 | update run-akka script to use 2.8.0 final [rossputin]
| * | | | | c2a156d 2010-08-05 | Race condition should be patched now [Viktor Klang]
| |/ / / /  
| * | | | 5168bb5 2010-08-04 | Uncommenting SSL support [Viktor Klang]
| * | | | 573c0bf 2010-08-04 | Closing ticket 368 [Viktor Klang]
| * | | | 774424a 2010-08-03 | Closing ticket 367 [Viktor Klang]
| * | | | 80a325e 2010-08-03 | Closing ticket 355 [Viktor Klang]
| * | | |   54fb468 2010-08-03 | Merge branch 'ticket352' [Viktor Klang]
| |\ \ \ \  
| | * | | | f9750d8 2010-08-03 | Closing ticket 352 [Viktor Klang]
| | | |/ /  
| | |/| |   
| * | | |   2a1ec37 2010-08-03 | Merge with master [Viktor Klang]
| |\ \ \ \  
| | |/ / /  
| | * | |   5809a01 2010-08-02 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \  
| | | * | | 3e423ac 2010-08-02 | Ref extends Multiverse BasicRef (closes #253) [Peter Vlugter]
| | * | | | c892953 2010-08-02 | closes #366: CamelService should be a singleton [Martin Krasser]
| | |/ / /  
| | * | | f349714 2010-08-01 | Test cases for handling actor failures in Camel routes. [Martin Krasser]
| | * | | e5c1a45 2010-07-31 | formatting [Jonas Bonér]
| | * | | ae75e14 2010-07-31 | Removed TypedActor annotations and the method callbacks in the config [Jonas Bonér]
| | * | | d4ce436 2010-07-31 | Changed the Spring schema and the Camel endpoint names to the new typed-actor name [Jonas Bonér]
| | * | | ba68c1e 2010-07-30 | Removed imports not used [Jonas Bonér]
| | * | | ac77ce5 2010-07-30 | Restructured test folder structure [Jonas Boner]
| | * | |   cfa7dc0 2010-07-30 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \  
| | | * | | ea5ce25 2010-07-30 | removed trailing whitespace [Jonas Boner]
| | | * | | a4d246e 2010-07-30 | dos2unix [Jonas Boner]
| | | * | |   1817a9d 2010-07-30 | Merge branch 'master' of github.com:jboner/akka [Jonas Boner]
| | | |\ \ \  
| | | | * | | 10ffeb6 2010-07-29 | Fixing Comparable problem [Viktor Klang]
| | | * | | | 8c82e27 2010-07-30 | Added UntypedActor and UntypedActorRef (+ tests) to work with untyped MDB-style actors in Java. [Jonas Boner]
| | * | | | | 01c3be9 2010-07-30 | Fixed failing (and temporarily disabled) tests in akka-spring after refactoring from ActiveObject to TypedActor. [Martin Krasser]
| | | |/ / /  
| | |/| | |   
| | * | | | 2903613 2010-07-29 | removed trailing whitespace [Jonas Bonér]
| | * | | | b15d8b8 2010-07-29 | converted tabs to spaces [Jonas Bonér]
| | * | | |   1c7985f 2010-07-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \  
| | | * | | | cff8a78 2010-07-29 | upgraded sjson to 0.7 [Debasish Ghosh]
| | | |/ / /  
| | * | | | 78f1324 2010-07-29 | minor reformatting [Jonas Bonér]
| | |/ / /  
| | * | |   117eb3b 2010-07-28 | Merge branch 'wip-typed-actor-jboner' into master [Jonas Bonér]
| | |\ \ \  
| | | * | | d6b728d 2010-07-28 | Initial draft of UntypedActor for Java API [Jonas Bonér]
| | | * | | ed2d9d6 2010-07-28 | Implemented swapping TypedActor instance on restart [Jonas Bonér]
| | | * | | 918f0b3 2010-07-27 | TypedActor refactoring completed, all test pass except for some in the Spring module (commented them away for now). [Jonas Bonér]
| | | * | | e33e92c 2010-07-27 | Converted all TypedActor tests to interface-impl, code and tests compile [Jonas Bonér]
| | | * | | add7702 2010-07-26 | Added TypedActor and TypedTransactor base classes. Renamed ActiveObject factory object to TypedActor. Improved network protocol for TypedActor. Remote TypedActors now identified by UUID. [Jonas Bonér]
| | * | | |   684396b 2010-07-28 | merged with upstream [Jonas Bonér]
| | |\ \ \ \  
| | | |/ / /  
| | |/| | |   
| | | * | | 1bd2e6c 2010-07-28 | match readme to scaladoc in sample [rossputin]
| | | |/ /  
| | | * | 922259b 2010-07-24 | Upload patched camel-jetty-2.4.0.1 that fixes concurrency bug (will be officially released with Camel 2.5.0) [Martin Krasser]
| | | * | f62eb75 2010-07-23 | move into the new test dispach directory. [Hiram Chirino]
| | | * |   714a1c1 2010-07-23 | Merge branch 'master' of git://github.com/jboner/akka [Hiram Chirino]
| | | |\ \  
| | | | * | ff52aa9 2010-07-23 | re-arranged tests into folders/packages [momania]
| | | * | |   b49f432 2010-07-23 | Merge branch 'master' of git://github.com/jboner/akka [Hiram Chirino]
| | | |\ \ \  
| | | | |/ /  
| | | * | | f540ee1 2010-07-23 | update to the released version of hawtdispatch [Hiram Chirino]
| | | * | | 74043d3 2010-07-21 | Simplify the hawt dispatcher class name added a hawt dispatch echo server exampe. [Hiram Chirino]
| | | * | | 2ed5714 2010-07-21 | hawtdispatch dispatcher can now optionally use dispatch sources to agregate cross actor invocations [Hiram Chirino]
| | | * | | 9d1b18b 2010-07-21 | fixing HawtDispatchEventDrivenDispatcher so that it has at least one non-daemon thread while it's active [Hiram Chirino]
| | | * | | f4d6222 2010-07-21 | adding a HawtDispatch based message dispatcher [Hiram Chirino]
| | | * | | cc7da99 2010-07-21 | decoupled the mailbox implementation from the actor.  The implementation is now controled by dispatcher associated with the actor. [Hiram Chirino]
| | * | | |   3f0fba4 2010-07-26 | Merge branch 'ticket_345' [Jonas Bonér]
| | |\ \ \ \  
| | | * | | | 552ee56 2010-07-26 | Fixed broken tests for Active Objects + added logging to Scheduler + fixed problem with SchedulerSpec [Jonas Bonér]
| | | * | | | 8a2716d 2010-07-23 | cosmetic [momania]
| | | * | | | e91dc5c 2010-07-23 | - better restart strategy test - make sure actor stops when restart strategy maxes out - nicer patternmathing on lifecycle making sure lifecycle.get is never called anymore (sometimes gave nullpointer exceptions) - also applying the defaults in a nicer way [momania]
| | | * | | | 7288e83 2010-07-23 | proof restart strategy [momania]
| | * | | | |   f49dfa7 2010-07-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \  
| | | | |_|/ /  
| | | |/| | |   
| | | * | | | 2c3431a 2010-07-23 | clean end state [momania]
| | | |/ / /  
| | | * | | ec97e72 2010-07-23 | Test #307 - Proof schedule continues with retarted actor [momania]
| | | * | |   08d0d2c 2010-07-22 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | | |\ \ \  
| | | | * | | c668615 2010-07-22 | MongoDB based persistent Maps now use Mongo updates. Also upgraded mongo-java driver to 2.0 [Debasish Ghosh]
| | | | |/ /  
| | | * | | c0ae02c 2010-07-22 | WIP [momania]
| | * | | | 7ddc553 2010-07-23 | Now uses 'Duration' for all time properties in config [Jonas Bonér]
| | | |/ /  
| | |/| |   
| | * | |   bc29b0e 2010-07-21 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \  
| | | * \ \   30f6df4 2010-07-21 | Merge branch 'master' of github.com:jboner/akka [Heiko Seeberger]
| | | |\ \ \  
| | | | * | | 2e453dd 2010-07-21 | fix for idle client closing issues by redis server #338 and #340 [Debasish Ghosh]
| | | * | | |   d9c8a78 2010-07-21 | Merge branch '342-hseeberger' [Heiko Seeberger]
| | | |\ \ \ \  
| | | | * | | | 2310bcb 2010-07-21 | closes #342: Added parens to ActorRegistry.shutdownAll. [Heiko Seeberger]
| | | * | | | |   efe769d 2010-07-21 | Merge branch '341-hseeberger' [Heiko Seeberger]
| | | |\ \ \ \ \  
| | | | |/ / / /  
| | | |/| | | |   
| | | | * | | | 22e6be9 2010-07-21 | closes #341: Fixed O-S-G-i example. [Heiko Seeberger]
| | | |/ / / /  
| | * | | | | e7cedd0 2010-07-21 | HTTP Producer/Consumer concurrency test (ignored by default) [Martin Krasser]
| | * | | | | a63a2e3 2010-07-21 | Added example how to use JMS endpoints in standalone applications. [Martin Krasser]
| | * | | | | 909bdfe 2010-07-21 | Closes #333 Allow applications to wait for endpoints being activated [Martin Krasser]
| | | |/ / /  
| | |/| | |   
| | * | | |   650c6de 2010-07-21 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \ \  
| | | |/ / /  
| | | * | |   aae9506 2010-07-21 | Merge branch '31-hseeberger' [Heiko Seeberger]
| | | |\ \ \  
| | | | * | | 9f40458 2010-07-20 | closes #31: Some fixes to the O-S-G-i settings in SBT project file; also deleted superfluous bnd4sbt.jar in project/build/lib directory. [Heiko Seeberger]
| | | | * | |   13ce451 2010-07-20 | Merge branch 'master' into osgi [Heiko Seeberger]
| | | | |\ \ \  
| | | | | |/ /  
| | | | * | |   7dc2f85 2010-07-19 | Merge branch 'master' into osgi [Heiko Seeberger]
| | | | |\ \ \  
| | | | | | |/  
| | | | | |/|   
| | | | * | |   04ba5f1 2010-06-28 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | | |\ \ \  
| | | | * \ \ \   a464afe 2010-06-28 | Merge remote branch 'origin/osgi' into osgi [Roman Roelofsen]
| | | | |\ \ \ \  
| | | | | * | | | 7dfe392 2010-06-21 | OSGi work: Fixed plugin configuration => Added missing repo and module config for BND. [Heiko Seeberger]
| | | | * | | | | e24eb38 2010-06-28 | Removed pom.xml, not needed anymore [Roman Roelofsen]
| | | | * | | | |   1d6cb8e 2010-06-24 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \  
| | | | | |/ / / /  
| | | | |/| | | |   
| | | | * | | | | b72b5b4 2010-06-21 | OSGi work: Fixed packageAction for AkkaOSGiAssemblyProject (publish-local working now) and reverted to default artifactID (removed superfluous suffix '_osgi'). [Heiko Seeberger]
| | | | * | | | | 03b90a1 2010-06-21 | OSGi work: Switched to bnd4sbt 1.0.0.RC3, using projectVersion for exported packages now and fixed a merge bug. [Heiko Seeberger]
| | | | * | | | |   43136f7 2010-06-21 | Merge branch 'master' into osgi [Heiko Seeberger]
| | | | |\ \ \ \ \  
| | | | * \ \ \ \ \   53a2743 2010-06-18 | Merge remote branch 'origin/master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \  
| | | | * \ \ \ \ \ \   c1d6140 2010-06-18 | Merge remote branch 'akollegger/master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \  
| | | | | * | | | | | | b0f12f2 2010-06-17 | synced with jboner/master; decoupled AkkaWrapperProject; bumped bnd4sbt to 1.0.0.RC2 [Andreas Kollegger]
| | | | | * | | | | | |   2ce67bf 2010-06-17 | Merge branch 'master' of http://github.com/jboner/akka [Andreas Kollegger]
| | | | | |\ \ \ \ \ \ \  
| | | | | * | | | | | | | f8240a2 2010-06-08 | pulled wrappers into AkkaWrapperProject trait file; sjson, objenesis, dispatch-json, netty ok; multiverse is next [Andreas Kollegger]
| | | | | * | | | | | | | 691e344 2010-06-06 | initial implementation of OSGiWrapperProject, applied to jgroups dependency to make it OSGi-friendly [Andreas Kollegger]
| | | | | * | | | | | | |   b4ab04a 2010-06-06 | merged with master; changed renaming of artifacts to use override def artifactID [Andreas Kollegger]
| | | | | |\ \ \ \ \ \ \ \  
| | | | | * | | | | | | | | 6911c76 2010-06-06 | initial changes for OSGification: added bnd4sbt plugin, changed artifact naming to include _osgi [Andreas Kollegger]
| | | | * | | | | | | | | | 8d6642f 2010-06-17 | Started work on OSGi sample [Roman Roelofsen]
| | | | * | | | | | | | | |   c173c8b 2010-06-17 | Merge remote branch 'origin/master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \ \ \ \  
| | | | | | |_|/ / / / / / /  
| | | | | |/| | | | | | | |   
| | | | * | | | | | | | | | 36c830c 2010-06-17 | All bundles resolve! [Roman Roelofsen]
| | | | * | | | | | | | | | 1936695 2010-06-16 | Exclude transitive dependencies Ongoing work on finding the bundle list [Roman Roelofsen]
| | | | * | | | | | | | | | 6410473 2010-06-16 | Updated bnd4sbt plugin [Roman Roelofsen]
| | | | * | | | | | | | | |   56b972d 2010-06-16 | Merge remote branch 'origin/master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | a3baa7b 2010-06-16 | Basic OSGi stuff working. Need to exclude transitive dependencies from the bundle list. [Roman Roelofsen]
| | | | * | | | | | | | | | |   8bd9f35 2010-06-08 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | 94b87cf 2010-06-08 | Use more idiomatic way to add the assembly task [Roman Roelofsen]
| | | | * | | | | | | | | | | | 1a42676 2010-06-07 | Work in progress! Trying to find an alternative to mvn assembly [Roman Roelofsen]
| | | | * | | | | | | | | | | | db2dd57 2010-06-07 | Removed some dependencies since they will be provided by their own bundles [Roman Roelofsen]
| | | | * | | | | | | | | | | |   a085cfb 2010-06-07 | Merge remote branch 'origin/osgi' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | | | * \ \ \ \ \ \ \ \ \ \ \   46d0ccb 2010-03-06 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | | | c7c5455 2010-06-07 | Added dependencies-bundle. [Roman Roelofsen]
| | | | * | | | | | | | | | | | | |   27154f1 2010-06-07 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | | | | 341ab90 2010-06-07 | Removed Maven projects and added bnd4sbt [Roman Roelofsen]
| | | | * | | | | | | | | | | | | | |   0f1031e 2010-05-25 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | | | | | c16c6ba 2010-05-25 | changed karaf url [Roman Roelofsen]
| | | | * | | | | | | | | | | | | | | |   06322c8 2010-03-19 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | | | | | | f12484b 2010-03-12 | rewriting deployer in scala ... work in progress! [Roman Roelofsen]
| | | | * | | | | | | | | | | | | | | | | c09d64a 2010-03-05 | added akka-osgi module to parent pom [Roman Roelofsen]
| | | | * | | | | | | | | | | | | | | | |   35d33e3 2010-03-05 | Merge commit 'origin/osgi' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | | | |_|_|/ / / / / / / / / / / / /  
| | | | | |/| | | | | | | | | | | | | | |   
| | | | | * | | | | | | | | | | | | | | | e6c942a 2010-03-04 | Added OSGi proof of concept Very basic example Starting point to kick of discussions [Roman Roelofsen]
| | * | | | | | | | | | | | | | | | | | | 9e30e33 2010-07-21 | Remove misleading term 'non-blocking' from comments. [Martin Krasser]
| | |/ / / / / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | | | |   c9eccda 2010-07-20 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | | 70bf8b2 2010-07-20 | Adding become to Actor [Viktor Klang]
| | | | |_|_|_|_|_|_|_|_|_|_|_|_|_|_|_|/  
| | | |/| | | | | | | | | | | | | | | |   
| | | * | | | | | | | | | | | | | | | |   2d3a5e5 2010-07-20 | Merge branch '277-hseeberger' [Heiko Seeberger]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | | | | | | 995cab9 2010-07-20 | closes #277: Transformed all subprojects to use Dependencies object; also reworked Plugins.scala accordingly. [Heiko Seeberger]
| | | | * | | | | | | | | | | | | | | | |   9e201a3 2010-07-20 | Merge branch 'master' into 277-hseeberger [Heiko Seeberger]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | | | | |   
| | | * | | | | | | | | | | | | | | | | |   680128b 2010-07-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | | | 96533ee 2010-07-19 | Fixing case 334 [Viktor Klang]
| | | | |_|_|_|_|_|_|_|_|_|_|_|_|_|_|_|_|/  
| | | |/| | | | | | | | | | | | | | | | |   
| | | | | * | | | | | | | | | | | | | | | 02bf955 2010-07-20 | re #277: Created objects for repositories and dependencies and started transformig akka-core. [Heiko Seeberger]
| | | | |/ / / / / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | e289bac 2010-07-20 | Minor changes in akka-sample-camel [Martin Krasser]
| | | |/ / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | 64b249a 2010-07-19 | Remove listener from listener list before stopping the listener (avoids warning that stopped listener cannot be notified) [Martin Krasser]
| | |/ / / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | |   1310a98 2010-07-18 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   30efc7e 2010-07-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | | | | | 6b4e924 2010-07-17 | Fixing bug in ActorRegistry [Viktor Klang]
| | | * | | | | | | | | | | | | | | | | 5447520 2010-07-18 | Fixed bug when trying to abort an already committed CommitBarrier [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | 2cdfdb2 2010-07-18 | Fixed bug in using STM together with Active Objects [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | 3b20bc3 2010-07-18 | Completely redesigned Producer trait. [Martin Krasser]
| | | |/ / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | 79ea559 2010-07-17 | Added missing API documentation. [Martin Krasser]
| | * | | | | | | | | | | | | | | | |   2b53012 2010-07-17 | Merge commit 'remotes/origin/master' into 320-krasserm, resolve conflicts and compile errors. [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | c737e2e 2010-07-16 | And multiverse module config [Peter Vlugter]
| | | * | | | | | | | | | | | | | | | | e6e334d 2010-07-16 | Multiverse 0.6-SNAPSHOT again [Peter Vlugter]
| | | * | | | | | | | | | | | | | | | | 7039495 2010-07-16 | Updated ants sample [Peter Vlugter]
| | | * | | | | | | | | | | | | | | | | 1670b8a 2010-07-16 | Adding support for maxInactiveActivity [Viktor Klang]
| | | * | | | | | | | | | | | | | | | | b169bf1 2010-07-16 | Fixing case 286 [Viktor Klang]
| | | * | | | | | | | | | | | | | | | | 8fb0af3 2010-07-15 | Fixed race-condition in Cluster [Viktor Klang]
| | | |/ / / / / / / / / / / / / / / /  
| | | * | | | | | | | | | | | | | | |   4dabf49 2010-07-15 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | 58bead8 2010-07-15 | Upgraded to new fresh Multiverse with CountDownCommitBarrier bugfix [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | 580f8f7 2010-07-15 | Upgraded Akka to Scala 2.8.0 final, finally... [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | 69a26f4 2010-07-15 | Added Scala 2.8 final versions of SBinary and Configgy [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | |   6057b77 2010-07-15 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   333c040 2010-07-15 | Added support for MaximumNumberOfRestartsWithinTimeRangeReachedException(this, maxNrOfRetries, withinTimeRange, reason) [Jonas Bonér]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | | | a521385 2010-07-14 | Moved logging of actor crash exception that was by-passed/hidden by STM exception [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | | | 6c0503e 2010-07-14 | Changed Akka config file syntax to JSON-style instead of XML style Plus added missing test classes for ActiveObjectContextSpec [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | | | 48ec67d 2010-07-14 | Added ActorRef.receiveTimout to remote protocol and LocalActorRef serialization [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | | | 4be36cb 2010-07-14 | Removed 'reply' and 'reply_?' from Actor - now only usef 'self.reply' etc [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | | | 28faea7 2010-07-14 | Removed Java Active Object tests, not needed now that we have them ported to Scala in the akka-core module [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | | | 1f09e17 2010-07-14 | Fixed bug in Active Object restart, had no default life-cycle defined + added tests [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | | | 2541176 2010-07-14 | Added tests for ActiveObjectContext [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | | | 4d130d5 2010-07-14 | Fixed deadlock when Transactor is restarted in the middle of a transaction [Jonas Bonér]
| | | * | | | | | | | | | | | | | | | | | | b98cfd5 2010-07-13 | Fixed 3 bugs in Active Objects and Actor supervision + changed to use Multiverse tryJoinCommit + improved logging + added more tracing + various misc fixes [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | | 99f72f7 2010-07-16 | Non-blocking routing and transformation example with asynchronous HTTP request/reply [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | | | d7926fb 2010-07-16 | closes #320 (non-blocking routing engine), closes #335 (producer to forward results) [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | | | f63bd41 2010-07-16 | Do not download sources [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | | | d024b39 2010-07-16 | Remove Camel staging repo as Camel 2.4.0 can already be downloaded repo1. [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | | | 98fee72 2010-07-15 | Further tests [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | | | 78151e9 2010-07-15 | Fixed concurrency bug [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | | |   c24446e 2010-07-15 | Merge commit 'remotes/origin/master' into 320-krasserm [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | | | 11611a4 2010-07-15 | Camel's non-blocking routing engine now fully supported [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | | | 8a19a11 2010-07-13 | Further tests for non-blocking in-out message exchange with consumer actors. [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | | | ba75746 2010-07-13 | re #320 Non-blocking in-out message exchanges with actors [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | |   c7130f0 2010-07-15 | Merge remote branch 'origin/master' into wip-ssl-actors [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|_|/ / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | | |   8f056dd 2010-07-15 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|/ / / / / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | | | | | |   
| | | * | | | | | | | | | | | | | | | | | | d1688ba 2010-07-15 | redisclient & sjson jar - 2.8.0 version [Debasish Ghosh]
| | | | |/ / / / / / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | | 495e884 2010-07-15 | Close #336 [momania]
| | * | | | | | | | | | | | | | | | | | | 16755b6 2010-07-15 | disable tests [momania]
| | * | | | | | | | | | | | | | | | | | | 104a48e 2010-07-15 | - rpc typing and serialization - again [momania]
| | * | | | | | | | | | | | | | | | | | | e6c0620 2010-07-14 | rpc typing and serialization [momania]
| * | | | | | | | | | | | | | | | | | | | d748bd5 2010-07-15 | Initial code, ble to turn ssl on/off, not verified [Viktor Klang]
| * | | | | | | | | | | | | | | | | | | |   c9745a8 2010-07-14 | Merge branch 'master' into wip_141_SSL_enable_remote_actors [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | | 5a6783f 2010-07-14 | Laying the foundation for current-message-resend [Viktor Klang]
| | |/ / / / / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | | | | 7c34763 2010-07-14 | - make consumer restart when delegated handling actor fails - made single object to flag test enable/disable [momania]
| | * | | | | | | | | | | | | | | | | | 0f10d44 2010-07-14 | Test #328 [momania]
| | * | | | | | | | | | | | | | | | | | 273de9e 2010-07-14 | small refactor - use patternmatching better [momania]
| | * | | | | | | | | | | | | | | | | | abb1866 2010-07-12 | Closing ticket 294 [Viktor Klang]
| | * | | | | | | | | | | | | | | | | | 4717694 2010-07-12 | Switching ActorRegistry storage solution [Viktor Klang]
| | | |/ / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | c8f1a5e 2010-07-11 | added new jar for sjson for 2.8.RC7 [Debasish Ghosh]
| | * | | | | | | | | | | | | | | | | bbd246a 2010-07-11 | bug fix in redisclient, version upgraded to 1.4 [Debasish Ghosh]
| | * | | | | | | | | | | | | | | | | 1d66d1b 2010-07-11 | removed logging in cassandra [Jonas Boner]
| | * | | | | | | | | | | | | | | | |   4850f7a 2010-07-11 | Merge branch 'master' of github.com:jboner/akka [Jonas Boner]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   7c15a07 2010-07-08 | Merge branch 'amqp' [momania]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | | | |   
| | | | * | | | | | | | | | | | | | | | 6e0be37 2010-07-08 | cosmetic and disable the tests [momania]
| | | | * | | | | | | | | | | | | | | | 21314c0 2010-07-08 | pimped the rpc a bit more, using serializers [momania]
| | | | * | | | | | | | | | | | | | | | f36ad6c 2010-07-08 | added rpc server and unit test [momania]
| | | | * | | | | | | | | | | | | | | | fd6db03 2010-07-08 | - split up channel parameters into channel and exchange parameters - initial setup for rpc client [momania]
| | * | | | | | | | | | | | | | | | | | f586aac 2010-07-11 | Adding Ensime project file [Jonas Boner]
| * | | | | | | | | | | | | | | | | | |   e7ad0ce 2010-07-07 | Merge branch 'master' of github.com:jboner/akka into wip_141_SSL_enable_remote_actors [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | |   a265a1b 2010-07-07 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | | | |   
| | | * | | | | | | | | | | | | | | | | e1becf7 2010-07-07 | removed @PreDestroy functionality [Johan Rask]
| | | * | | | | | | | | | | | | | | | | 528500c 2010-07-07 | Added support for springs @PostConstruct and @PreDestroy [Johan Rask]
| | * | | | | | | | | | | | | | | | | | f7f98d3 2010-07-07 | Dropped akka.xsd, updated all spring XML configurations to use akka-0.10.xsd [Martin Krasser]
| | |/ / / / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | | | 9b3aed1 2010-07-07 | Closes #318: Race condition between ActorRef.cancelReceiveTimeout and ActorRegistry.shutdownAll [Martin Krasser]
| | * | | | | | | | | | | | | | | | | 003a44e 2010-07-06 | Minor change, overriding destroyInstance instead of destroy [Johan Rask]
| | * | | | | | | | | | | | | | | | | 3e7980a 2010-07-06 | #301 DI does not work in akka-spring when specifying an interface [Johan Rask]
| | * | | | | | | | | | | | | | | | | 7a155c7 2010-07-06 | cosmetic logging change [momania]
| | * | | | | | | | | | | | | | | | |   ce84b38 2010-07-06 | Merge branch 'master' of git@github.com:jboner/akka and resolve conflicts in akka-spring [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | 2deb9fa 2010-07-05 | #304 Fixed Support for ApplicationContextAware in akka-spring [Johan Rask]
| | | * | | | | | | | | | | | | | | | | da275f4 2010-07-05 | set emtpy parens back [momania]
| | | * | | | | | | | | | | | | | | | | 5baf86f 2010-07-05 | - moved receive timeout logic to ActorRef - receivetimeout now only inititiated when receiveTimeout property is set [momania]
| | * | | | | | | | | | | | | | | | | | fd9fbb1 2010-07-06 | closes #314 akka-spring to support active object lifecycle management closes #315 akka-spring to support configuration of shutdown callback method [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | 460dcfe 2010-07-05 | Tests for stopping active object endpoints; minor refactoring in ConsumerPublisher [Martin Krasser]
| | |/ / / / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | | | ae6bf7a 2010-07-04 | Added test subject description [Martin Krasser]
| | * | | | | | | | | | | | | | | | | 54229d8 2010-07-04 | Added comments. [Martin Krasser]
| | * | | | | | | | | | | | | | | | | 8b228b3 2010-07-04 | Tests for ActiveObject lifecycle [Martin Krasser]
| | * | | | | | | | | | | | | | | | |   61bc049 2010-07-04 | Resolved conflicts and compile errors after merging in master [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | e1dce96 2010-07-03 | Track stopping of Dispatcher actor [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | 9521461 2010-07-01 | re #297: Initial suport for shutting down routes to consumer active objects (both supervised and non-supervised). [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | bf45759 2010-07-01 | Additional remote consumer test [Martin Krasser]
| | * | | | | | | | | | | | | | | | | | 1408dbe 2010-07-01 | re #296: Initial support for active object lifecycle management [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | 9cd36f1 2010-04-26 | ... [Viktor Klang]
| * | | | | | | | | | | | | | | | | | | ca60132 2010-04-25 | Tests pass with Dummy SSL config! [Viktor Klang]
| * | | | | | | | | | | | | | | | | | | b976591 2010-04-25 | Added some Dummy SSL config to assist in proof-of-concept [Viktor Klang]
| * | | | | | | | | | | | | | | | | | | cf80225 2010-04-25 | Adding SSL code to RemoteServer [Viktor Klang]
| * | | | | | | | | | | | | | | | | | | cca1c2f 2010-04-25 | Initial code for SSL remote actors [Viktor Klang]
| | |/ / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | 4a25439 2010-07-04 | Fixed Issue #306: JSON serialization between remote actors is not transparent [Debasish Ghosh]
| * | | | | | | | | | | | | | | | | |   8ffef7c 2010-07-02 | Merge branch 'master' of github.com:jboner/akka [Heiko Seeberger]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   13c1374 2010-07-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | 7075672 2010-07-02 | Do not log to error when interception NotFoundException from Cassandra [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | |   e95cb71 2010-07-02 | Merge branch '290-hseeberger' [Heiko Seeberger]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|/ / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | 8a746a4 2010-07-02 | re #290: Added parens to no-parameter methods returning Unit in all subprojects. [Heiko Seeberger]
| | * | | | | | | | | | | | | | | | | | 0e117dc 2010-07-02 | re #290: Added parens to no-parameter methods returning Unit in rest of akka-core. [Heiko Seeberger]
| | * | | | | | | | | | | | | | | | | | 8617018 2010-07-02 | re #290: Added parens to no-parameter methods returning Unit in ActorRef. [Heiko Seeberger]
| |/ / / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | | 4baceb1 2010-07-02 | Fixing flaky tests [Viktor Klang]
| |/ / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | ab40b48 2010-07-02 | Added codefellow to the plugins embeddded repo and upgraded to 0.3 [Jonas Bonér]
| * | | | | | | | | | | | | | | | |   a8b3896 2010-07-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | 827ad5a 2010-07-02 |  - added dummy tests to make sure the test classes don't fail because of disabled tests, these tests need a local rabbitmq server running [momania]
| | * | | | | | | | | | | | | | | | |   1e5a85d 2010-07-02 | Merge branch 'master' of http://github.com/jboner/akka [momania]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | 91ec220 2010-07-02 | - moved deliveryHandler linking for consumer to the AMQP factory function - added the copyright comments [momania]
| | * | | | | | | | | | | | | | | | | | d794154 2010-07-02 | No need for disconnect after a shutdown error [momania]
| * | | | | | | | | | | | | | | | | | | 29cb951 2010-07-02 | Addde codefellow plugin jars to embedded repo [Jonas Bonér]
| | |/ / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | |   79954b1 2010-07-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | | |   53d682b 2010-07-02 | Merge branch 'master' of http://github.com/jboner/akka [momania]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | 6b48521 2010-07-02 | removed akka.conf [momania]
| | * | | | | | | | | | | | | | | | | | cb3ba69 2010-07-02 | Redesigned AMQP [momania]
| | * | | | | | | | | | | | | | | | | | cc9ca33 2010-07-02 | RabbitMQ to 1.8.0 [momania]
| * | | | | | | | | | | | | | | | | | | 4ec73a4 2010-07-02 | minor edits [Jonas Bonér]
| | |/ / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | 0d40ba0 2010-07-02 | Changed Akka to use IllegalActorStateException instead of IllegalStateException [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | a06af6b 2010-07-02 | Merged in patch with method to find actor by function predicate on the ActorRegistry [Jonas Bonér]
| * | | | | | | | | | | | | | | | | |   00ee156 2010-07-01 | Merge commit '02b816b893e1941b251a258b6403aa999c756954' [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | 02b816b 2010-07-01 | CodeFellow integration [Jonas Bonér]
| | |/ / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | 985e8e8 2010-07-01 | fixed bug in timeout handling that caused tests to fail [Jonas Bonér]
| * | | | | | | | | | | | | | | | |   0f7c442 2010-07-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | ac4cd8a 2010-07-02 | type class based actor serialization implemented [Debasish Ghosh]
| * | | | | | | | | | | | | | | | | | cce99d6 2010-07-01 | commented out failing tests [Jonas Bonér]
| * | | | | | | | | | | | | | | | | |   1d54fcd 2010-07-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | | |   ab11c9c 2010-07-01 | Merge branch 'master' of http://github.com/jboner/akka [momania]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | b177089 2010-07-01 | Fix ActiveObjectGuiceConfiguratorSpec. Wait is now longer than set timeout [momania]
| | * | | | | | | | | | | | | | | | | | 236925f 2010-07-01 | Added ReceiveTimeout behaviour [momania]
| * | | | | | | | | | | | | | | | | | | f74e0e5 2010-07-01 | Added CodeFellow to gitignore [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | |   1abe006 2010-07-01 | Merge commit '38e8bea3fe6a7e9fcc9c5f353124144739bdc234' [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|/ / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | 38e8bea 2010-06-29 | Fixed bug in fault handling of TEMPORARY Actors + ported all Active Object Java tests to Scala (using Java POJOs) [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | |   b0bdccf 2010-07-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | fe22944 2010-07-01 | #292 - Added scheduleOne and re-created unit tests [momania]
| | | |/ / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | 429ccc5 2010-07-01 | Removed unused catch for IllegalStateException [Jonas Bonér]
| |/ / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | 21dc177 2010-06-30 | Removed trailing whitespace [Jonas Bonér]
| * | | | | | | | | | | | | | | | | 1896713 2010-06-30 | Converted TAB to SPACE [Jonas Bonér]
| * | | | | | | | | | | | | | | | | b269048 2010-06-30 | Fixed bug in remote deserialization + fixed some failing tests + cleaned up and reorganized code [Jonas Bonér]
| * | | | | | | | | | | | | | | | | 03e1ac0 2010-06-29 | Fixed bug in fault handling of TEMPORARY Actors + ported all Active Object Java tests to Scala (using Java POJOs) [Jonas Bonér]
| |/ / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | 0d4845b 2010-06-28 | Added Java tests as Scala tests [Jonas Bonér]
| * | | | | | | | | | | | | | | | 11f6b61 2010-06-28 | Added AspectWerkz 2.2 to embedded-repo [Jonas Bonér]
| * | | | | | | | | | | | | | | | d950793 2010-06-28 | Upgraded to AspectWerkz 2.2 + merged in patch for using Actor.isDefinedAt for akka-patterns stuff [Jonas Bonér]
| | |_|_|_|_|_|_|_|_|_|_|_|_|_|/  
| |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | 04dfc5b 2010-06-27 | FP approach always makes one happy [Viktor Klang]
| * | | | | | | | | | | | | | | c2dc8db 2010-06-27 | Minor tidying [Viktor Klang]
| * | | | | | | | | | | | | | | d4da572 2010-06-27 | Fix for #286 [Viktor Klang]
| * | | | | | | | | | | | | | | 3c7639f 2010-06-26 | Updated to Netty 3.2.1.Final [Viktor Klang]
| * | | | | | | | | | | | | | | cbfa18d 2010-06-26 | Upgraded to Atmosphere 0.6 final [Viktor Klang]
| * | | | | | | | | | | | | | | 3aeebe3 2010-06-25 | Atmosphere bugfix [Viktor Klang]
| * | | | | | | | | | | | | | | 292f5dd 2010-06-24 | Tests for #289 [Martin Krasser]
| * | | | | | | | | | | | | | |   3568c37 2010-06-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|_|_|_|_|_|_|_|/  
| | |/| | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | 27e266b 2010-06-24 | Documentation added. [Martin Krasser]
| | * | | | | | | | | | | | | | 89d686c 2010-06-24 | Minor edits [Martin Krasser]
| | * | | | | | | | | | | | | |   d05af6f 2010-06-24 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | f885d32 2010-06-24 | closes #289: Support for <akka:camel-service> Spring configuration element [Martin Krasser]
| | * | | | | | | | | | | | | | | 1099a7d 2010-06-24 | Comment changed [Martin Krasser]
| * | | | | | | | | | | | | | | | a62fca3 2010-06-24 | Increased timeout in Transactor in STMSpec [Jonas Bonér]
| * | | | | | | | | | | | | | | | 20a53d4 2010-06-24 | Added serialization of actor mailbox [Jonas Bonér]
| | |/ / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | |   f5541a3 2010-06-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | f5dc369 2010-06-23 | Added test for verifying pre/post restart invocations [Johan Rask]
| | * | | | | | | | | | | | | | | 497edc6 2010-06-23 | Fixed #287,Old dispatcher settings are now copied to new dispatcher on restart [Johan Rask]
| | |/ / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | 35152cb 2010-06-23 | Updated sbt plugin [Peter Vlugter]
| | * | | | | | | | | | | | | | cc526f9 2010-06-22 | Added akka.conf values as defaults and removed lift dependency [Viktor Klang]
| * | | | | | | | | | | | | | | 1ad7f52 2010-06-23 | fixed mem-leak in Active Object + reorganized SerializableActor traits [Jonas Bonér]
| |/ / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | 00a8cd8 2010-06-22 | Added test for serializing stateless actor + made mailbox accessible [Jonas Bonér]
| * | | | | | | | | | | | | | 5ccd43b 2010-06-22 | Fixed bug with actor unregistration in ActorRegistry, now we are using a Set instead of a List and only the right instance is removed, not all as before [Jonas Bonér]
| * | | | | | | | | | | | | |   a78b1cc 2010-06-22 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | 15e2f86 2010-06-21 | Removed comments from test [Johan Rask]
| | * | | | | | | | | | | | | |   d78ee8d 2010-06-21 | Merge branch 'master' of github.com:jboner/akka [Johan Rask]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | 3891455 2010-06-21 | Added missing files [Johan Rask]
| * | | | | | | | | | | | | | | | 052746b 2010-06-22 | Protobuf deep actor serialization working and test passing [Jonas Bonér]
| | |/ / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | d9fc457 2010-06-21 | commented out failing spring test [Jonas Bonér]
| * | | | | | | | | | | | | | |   f2579b9 2010-06-21 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | |   0e39f45 2010-06-21 | Merge branch 'master' of github.com:jboner/akka [Johan Rask]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|_|_|_|_|_|_|_|/  
| | | |/| | | | | | | | | | | |   
| | | * | | | | | | | | | | | |   7a5403d 2010-06-21 | Merge branch '281-hseeberger' [Heiko Seeberger]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | | f1d4c9a 2010-06-21 | closes #281: Made all subprojects test after breaking changes introduced by removing the type parameter from ActorRef.!!. [Heiko Seeberger]
| | | | * | | | | | | | | | | | | 4bfde61 2010-06-21 | re #281: Made all subprojects compile  after breaking changes introduced by removing the type parameter from ActorRef.!!; test-compile still missing! [Heiko Seeberger]
| | | | * | | | | | | | | | | | | 88125a9 2010-06-21 | re #281: Made akka-core compile and test after breaking changes introduced by removing the type parameter from ActorRef.!!. [Heiko Seeberger]
| | | | * | | | | | | | | | | | | 0e70a5f 2010-06-21 | re #281: Added as[T] and asSilently[T] to Option[Any] via implicit conversions in object Actor. [Heiko Seeberger]
| | | | * | | | | | | | | | | | |   a69f0d4 2010-06-21 | Merge branch 'master' into 281-hseeberger [Heiko Seeberger]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | |   
| | | | * | | | | | | | | | | | |   969af8d 2010-06-19 | Merge branch 'master' into 281-hseeberger [Heiko Seeberger]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | | | 1e6a4c0 2010-06-18 | re #281: Removed type parameter from ActorRef.!! which now returns Option[Any] and added Helpers.narrow and Helpers.narrowSilently. [Heiko Seeberger]
| | | | | |_|_|_|_|_|_|_|/ / / / /  
| | | | |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | ff4de1b 2010-06-21 | When interfaces are used, target instances are now created correctly [Johan Rask]
| | * | | | | | | | | | | | | | | 15d1ded 2010-06-16 | Added support for scope and depdenency injection on target bean [Johan Rask]
| | |/ / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | be40cf6 2010-06-20 | Added more examples to akka-sample-camel [Martin Krasser]
| | * | | | | | | | | | | | | | 032a7b8 2010-06-20 | Changed return type of CamelService.load to CamelService [Martin Krasser]
| | * | | | | | | | | | | | | |   08681ee 2010-06-20 | Merge branch 'stm-pvlugter' [Peter Vlugter]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | 104c3e7 2010-06-20 | Some stm documentation changes [Peter Vlugter]
| | | * | | | | | | | | | | | | | f8da5e1 2010-06-20 | Improved transaction factory defaults [Peter Vlugter]
| | | * | | | | | | | | | | | | | 71fedd9 2010-06-20 | Removed isTransactionalityEnabled [Peter Vlugter]
| | | * | | | | | | | | | | | | | 18ed80b 2010-06-20 | Updated ants sample [Peter Vlugter]
| | | * | | | | | | | | | | | | | 3f7402c 2010-06-19 | Removing unused stm classes [Peter Vlugter]
| | | * | | | | | | | | | | | | | 12b83eb 2010-06-19 | Moved data flow to its own package [Peter Vlugter]
| | | * | | | | | | | | | | | | | 024d225 2010-06-18 | Using actor id for transaction family name [Peter Vlugter]
| | | * | | | | | | | | | | | | | 58be9f0 2010-06-18 | Updated imports to use stm package objects [Peter Vlugter]
| | | * | | | | | | | | | | | | | 5acd2fd 2010-06-18 | Added some documentation for stm [Peter Vlugter]
| | | * | | | | | | | | | | | | | 2e6a1d6 2010-06-17 | Added transactional package object - includes Multiverse data structures [Peter Vlugter]
| | | * | | | | | | | | | | | | | f8ca6b9 2010-06-14 | Added stm local and global package objects [Peter Vlugter]
| | | * | | | | | | | | | | | | | ba0b503 2010-06-14 | Removed some trailing whitespace [Peter Vlugter]
| | | * | | | | | | | | | | | | | 35743ee 2010-06-10 | Updated actor ref to use transaction factory [Peter Vlugter]
| | | * | | | | | | | | | | | | | ef4f525 2010-06-10 | Configurable TransactionFactory [Peter Vlugter]
| | | * | | | | | | | | | | | | | 9c026ad 2010-06-10 | Fixed import in ants sample for removed Vector class [Peter Vlugter]
| | | * | | | | | | | | | | | | | cb241a1 2010-06-10 | Added Transaction.Util with methods for transaction lifecycle and blocking [Peter Vlugter]
| | | * | | | | | | | | | | | | | f9e52b5 2010-06-10 | Removed unused stm config options from akka conf [Peter Vlugter]
| | | * | | | | | | | | | | | | | bb93d72 2010-06-10 | Removed some unused stm config options [Peter Vlugter]
| | | * | | | | | | | | | | | | | 8a46c5a 2010-06-10 | Added Duration utility class for working with j.u.c.TimeUnit [Peter Vlugter]
| | | * | | | | | | | | | | | | | 21a6021 2010-06-10 | Updated stm tests [Peter Vlugter]
| | | * | | | | | | | | | | | | | 3cb6e55 2010-06-10 | Using Scala library HashMap and Vector [Peter Vlugter]
| | | * | | | | | | | | | | | | | 77960d6 2010-06-10 | Removed TransactionalState and TransactionalRef [Peter Vlugter]
| | | * | | | | | | | | | | | | | 07f52e8 2010-06-10 | Removed for-comprehensions for transactions [Peter Vlugter]
| | | * | | | | | | | | | | | | | 3a1d888 2010-06-10 | Removed AtomicTemplate - new Java API will use Multiverse more directly [Peter Vlugter]
| | | * | | | | | | | | | | | | | ed81b02 2010-06-10 | Removed atomic0 - no longer used [Peter Vlugter]
| | | * | | | | | | | | | | | | | 8fc8246 2010-06-10 | Updated to Multiverse 0.6-SNAPSHOT [Peter Vlugter]
| | |/ / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | 5bfa786 2010-06-19 | Fixed bug with stm not being enabled by default when no AKKA_HOME is set. [Jonas Bonér]
| | * | | | | | | | | | | | | | 8f019ac 2010-06-19 | Enforce commons-codec version 1.4 for akka-core [Martin Krasser]
| | * | | | | | | | | | | | | | 64c6930 2010-06-19 | Producer trait with default implementation of Actor.receive [Martin Krasser]
| | | |/ / / / / / / / / / / /  
| | |/| | | | | | | | | | | |   
| * | | | | | | | | | | | | |   35ae277 2010-06-18 | Stateless and Stateful Actor serialization + Turned on class caching in Active Object [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / /  
| | * | | | | | | | | | | | | d1c0f0e 2010-06-14 | Added akka-sbt-plugin source [Peter Vlugter]
| | | |_|_|_|_|_|_|_|_|_|_|/  
| | |/| | | | | | | | | | |   
| * | | | | | | | | | | | | e86fa86 2010-06-18 | Fix for ticket #280 - Tests fail if there is no akka.conf set [Jonas Bonér]
| |/ / / / / / / / / / / /  
| * | | | | | | | | | | | deebd31 2010-06-18 | Fixed final issues in actor deep serialization; now Java and Protobuf support [Jonas Bonér]
| * | | | | | | | | | | | 8543902 2010-06-17 | Upgraded commons-codec to 1.4 [Jonas Bonér]
| * | | | | | | | | | | | 452f299 2010-06-16 | Serialization of Actor now complete (using Java serialization of actor instance) [Jonas Bonér]
| * | | | | | | | | | | | c9b7228 2010-06-15 | Added fromProtobufToLocalActorRef serialization, all old test passing [Jonas Bonér]
| * | | | | | | | | | | | 48750e9 2010-06-10 | Added SerializableActorSpec for testing deep actor serialization [Jonas Bonér]
| * | | | | | | | | | | | e73ad3c 2010-06-10 | Deep serialization of Actors now works [Jonas Bonér]
| * | | | | | | | | | | | 4c933b6 2010-06-10 | Added SerializableActor trait and friends [Jonas Bonér]
| * | | | | | | | | | | | 2a9db62 2010-06-10 | Upgraded existing code to new remote protocol, all tests pass [Jonas Bonér]
| | |_|_|_|_|_|_|_|/ / /  
| |/| | | | | | | | | |   
| * | | | | | | | | | | e81f175 2010-06-16 | Fixed problem with Scala REST sample [Jonas Bonér]
| |/ / / / / / / / / /  
| * | | | | | | | | | ee19365 2010-06-15 | Made AMQP UnregisterMessageConsumerListener public [Jonas Bonér]
| * | | | | | | | | | 8fe51dd 2010-06-15 | fixed problem with cassandra map storage in rest example [Jonas Bonér]
| * | | | | | | | | | 2ec2a36 2010-06-11 | Marked Multiverse dependency as intransitive [Peter Vlugter]
| * | | | | | | | | | ccf9e09 2010-06-11 | Redis persistence now handles serialized classes.Removed apis for increment / decrement atomically from Ref. Issue #267 fixed [Debasish Ghosh]
| * | | | | | | | | |   1a69773 2010-06-10 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | b6228ae 2010-06-10 | Added a isDefinedAt method on the ActorRef [Jonas Bonér]
| | * | | | | | | | | | 06296b2 2010-06-09 | Improved RemoteClient listener info [Jonas Bonér]
| | * | | | | | | | | |   40a90af 2010-06-08 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|_|/ / /  
| | | |/| | | | | | | |   
| | * | | | | | | | | | 14822e9 2010-06-08 | added redis test from debasish [Jonas Bonér]
| | * | | | | | | | | | bc8ff87 2010-06-08 | Added bench from akka-bench for convenience [Jonas Bonér]
| | * | | | | | | | | | ecc26c7 2010-06-08 | Fixed bug in setting sender ref + changed version to 0.10 [Jonas Bonér]
| * | | | | | | | | | | 5d34002 2010-06-10 | remote consumer tests [Martin Krasser]
| * | | | | | | | | | | f2c0a0d 2010-06-10 | restructured akka-sample-camel [Martin Krasser]
| * | | | | | | | | | | 9275e0b 2010-06-10 | tests for accessing active objects from Camel routes (ticket #266) [Martin Krasser]
| | |/ / / / / / / / /  
| |/| | | | | | | | |   
| * | | | | | | | | |   286568d 2010-06-08 | Merge commit 'remotes/origin/master' into 224-krasserm [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / /  
| | * | | | | | | | | cb62ce4 2010-06-08 | Bumped version to 0.10-SNAPSHOT [Jonas Bonér]
| | * | | | | | | | | 15ff45d 2010-06-07 | Added a method to get a List with all MessageInvocation in the Actor mailbox [Jonas Bonér]
| | * | | | | | | | | 5a29d59 2010-06-07 | Upgraded build.properties to 0.9.1 (v0.9.1) [Jonas Bonér]
| | * | | | | | | | | e36c1aa 2010-06-07 | Upgraded to version 0.9.1 (v.0.9.1) [Jonas Bonér]
| | | |_|_|_|/ / / /  
| | |/| | | | | | |   
| | * | | | | | | | ec3a466 2010-06-07 | Added reply methods to Actor trait + fixed race-condition in Actor.spawn [Jonas Bonér]
| | | |_|_|_|_|_|/  
| | |/| | | | | |   
| | * | | | | | | 28b54da 2010-06-06 | Removed legacy code [Viktor Klang]
| * | | | | | | | 493ebb6 2010-06-08 | Support for using ActiveObjectComponent without Camel service [Martin Krasser]
| * | | | | | | | 1463dc3 2010-06-07 | Extended documentation (active object support) [Martin Krasser]
| * | | | | | | | ed4303a 2010-06-06 | Added remote active object example [Martin Krasser]
| * | | | | | | |   46b7cc8 2010-06-06 | Merge remote branch 'remotes/origin/master' into 224-krasserm [Martin Krasser]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | |   c75d114 2010-06-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | 3925993 2010-06-05 | Tidying up more debug statements [Viktor Klang]
| | | * | | | | | | c83f80a 2010-06-05 | Tidying up debug statements [Viktor Klang]
| | | * | | | | | | 6a5cdab 2010-06-05 | Fixing Jersey classpath resource scanning [Viktor Klang]
| | * | | | | | | | 906c12a 2010-06-05 | Added methods to retreive children from a Supervisor [Jonas Bonér]
| | |/ / / / / / /  
| | * | | | | | | 276074d 2010-06-04 | Freezing Atmosphere dep [Viktor Klang]
| | * | | | | | |   c9ea05f 2010-06-04 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | 94d61d0 2010-06-02 | Cleanup and refactored code a bit and added javadoc to explain througput parameter in detail. [Jan Van Besien]
| | | * | | | | | | c268c89 2010-06-02 | formatting, comment fixup [rossputin]
| | | * | | | | | | e75a425 2010-06-02 | update README docs for chat sample [rossputin]
| | * | | | | | | | 43aecb6 2010-06-04 | Fixed bug in remote actors + improved scaladoc [Jonas Bonér]
| | |/ / / / / / /  
| * | | | | | | | 1ae80fc 2010-06-06 | Fixed wrong test description [Martin Krasser]
| * | | | | | | | 971b9ba 2010-06-04 | Initial tests for active object support [Martin Krasser]
| * | | | | | | | 914f877 2010-06-04 | make all classes/traits module-private that are not part of the public API [Martin Krasser]
| * | | | | | | | 3c47977 2010-06-03 | Cleaned main sources from target actor instance access. Minor cleanups. [Martin Krasser]
| * | | | | | | | 2c558e3 2010-06-03 | Dropped service package and moved contained classes one level up. [Martin Krasser]
| * | | | | | | | 06ac8de 2010-06-03 | Refactored tests to interact with actors only via message passing [Martin Krasser]
| * | | | | | | | 4683173 2010-06-03 | ActiveObjectComponent now written in Scala [Martin Krasser]
| * | | | | | | |   bd0343f 2010-06-02 | Merge commit 'remotes/origin/master' into 224-krasserm [Martin Krasser]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | | 02af674 2010-06-01 | Re-adding runnable Active Object Java tests, which all pass (v0.9) [Jonas Bonér]
| | * | | | | | |   a355751 2010-06-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | e14bbc3 2010-05-31 | Fixed Jersey dependency [Viktor Klang]
| | * | | | | | | | edfb8ae 2010-06-01 | Upgraded run script [Jonas Bonér]
| | * | | | | | | | 7ed3ad3 2010-06-01 | Removed trailing whitespace [Jonas Bonér]
| | * | | | | | | | ce684b8 2010-06-01 | Converted tabs to spaces [Jonas Bonér]
| | * | | | | | | | 31ef608 2010-06-01 | Added activemq-data to .gitignore [Jonas Bonér]
| | * | | | | | | | ba61e36 2010-06-01 | Removed redundant servlet spec API jar from dist manifest [Jonas Bonér]
| | * | | | | | | | a8b2253 2010-06-01 | Added guard for NULL inital values in Agent [Jonas Bonér]
| | * | | | | | | | 2245af7 2010-06-01 | Added assert for if message is NULL [Jonas Bonér]
| | * | | | | | | | dbad1f4 2010-06-01 | Removed MessageInvoker [Jonas Bonér]
| | * | | | | | | | d109255 2010-06-01 | Removed ActorMessageInvoker [Jonas Bonér]
| | * | | | | | | | 4c0d7ec 2010-06-01 | Fixed race condition in Agent + improved ScalaDoc [Jonas Bonér]
| | * | | | | | | | 33a1d35 2010-05-31 | Added convenience method to ActorRegistry [Jonas Bonér]
| | |/ / / / / / /  
| | * | | | | | | 17ac9b5 2010-05-31 | Refactored Java REST example to work with the new way of doing REST in Akka [Jonas Bonér]
| | | |_|_|_|_|/  
| | |/| | | | |   
| | * | | | | | d796617 2010-05-31 | Cleaned up 'Supervisor' code and ScalaDoc + renamed dispatcher throughput option in config to 'dispatcher.throughput' [Jonas Bonér]
| | * | | | | | 6249991 2010-05-31 | Renamed 'toProtocol' to 'toProtobuf' [Jonas Bonér]
| | * | | | | | 80543d9 2010-05-30 | Upgraded Atmosphere to 0.6-SNAPSHOT [Viktor Klang]
| | * | | | | |   767683c 2010-05-30 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| | |\ \ \ \ \ \  
| | | * | | | | | de805b2 2010-05-30 | Added test for tested Transactors [Jonas Bonér]
| | * | | | | | | 881a3cb 2010-05-30 | minor fix in test case [Debasish Ghosh]
| | |/ / / / / /  
| | * | | | | | 195fd4a 2010-05-30 | Upgraded ScalaTest to Scala 2.8.0.RC3 compat lib [Jonas Bonér]
| | * | | | | |   baed657 2010-05-30 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \  
| | * | | | | | | 4b03a99 2010-05-30 | Fixed bug in STM and Persistence integration: added trait Abortable and added abort methods to all Persistent datastructures and removed redundant errornous atomic block [Jonas Bonér]
| * | | | | | | | 1d41af6 2010-06-02 | Prepare merge with master [Martin Krasser]
| * | | | | | | | 3701be7 2010-06-01 | initial support for publishing ActiveObject methods at Camel endpoints [Martin Krasser]
| | |/ / / / / /  
| |/| | | | | |   
| * | | | | | | bd16162 2010-05-30 | Upgrade to Camel 2.3.0 [Martin Krasser]
| * | | | | | | 52f22df 2010-05-29 | Prepare for master merge [Viktor Klang]
| * | | | | | | 1eb7c14 2010-05-29 | Ported akka-sample-secure [Viktor Klang]
| * | | | | | |   96d24d2 2010-05-29 | Merge branch 'master' of github.com:jboner/akka into wip-akka-rest-fix [Viktor Klang]
| |\ \ \ \ \ \ \  
| | * \ \ \ \ \ \   d02dd49 2010-05-29 | Merge branch '247-hseeberger' [Heiko Seeberger]
| | |\ \ \ \ \ \ \  
| | | |/ / / / / /  
| | |/| | | | | |   
| | | * | | | | | ce79442 2010-05-29 | closes #247: Added all missing module configurations. [Heiko Seeberger]
| | | * | | | | |   9075937 2010-05-29 | Merge branch 'master' into 247-hseeberger [Heiko Seeberger]
| | | |\ \ \ \ \ \  
| | | |/ / / / / /  
| | |/| | | | | |   
| | * | | | | | | 22630c5 2010-05-29 | Upgraded to Protobuf 2.3.0 [Jonas Bonér]
| | * | | | | | |   3e314f9 2010-05-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | a5125ee 2010-05-28 | no need to start supervised actors [Martin Krasser]
| | * | | | | | | | a15dd30 2010-05-29 | Upgraded Configgy to one build for Scala 2.8 RC3 [Jonas Bonér]
| | * | | | | | | | 1dd8740 2010-05-28 | Fixed issue with CommitBarrier and its registered callbacks + Added compensating 'barrier.decParties' to each 'barrier.incParties' [Jonas Bonér]
| | | | * | | | | | d62a88d 2010-05-28 | re #247: Added module configuration for akka-persistence-cassandra. Attention: Necessary to delete .ivy2 directory! [Heiko Seeberger]
| | | | * | | | | | f5ca349 2010-05-28 | re #247: Removed all vals for repositories except for embeddedRepo. Introduced module configurations necessary for akka-core; other modules still missing. [Heiko Seeberger]
| * | | | | | | | | d728205 2010-05-29 | Ported samples rest scala to the new akka-http [Viktor Klang]
| * | | | | | | | |   5f405fb 2010-05-28 | Looks promising! [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | 8230e2c 2010-05-28 | Fixing sbt run (exclude slf4j 1.5.11) [Viktor Klang]
| | * | | | | | | | | c7000ce 2010-05-28 | ClassLoader issue [Viktor Klang]
| | | |/ / / / / / /  
| | |/| | | | | | |   
| * | | | | | | | |   2cf60ce 2010-05-28 | Merge branch 'master' of github.com:jboner/akka into wip-akka-rest-fix [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| | * | | | | | | | 3adcb61 2010-05-29 | checked in wrong jar: now pushing the correct one for sjson [Debasish Ghosh]
| | | |/ / / / / /  
| | |/| | | | | |   
| | * | | | | | | 31a588f 2010-05-28 | project file updated for redisclient for 2.8.0.RC3 [Debasish Ghosh]
| | * | | | | | | 445742a 2010-05-28 | redisclient upped to 2.8.0.RC3 [Debasish Ghosh]
| | * | | | | | | b9c9166 2010-05-28 | updated project file for sjson for 2.8.RC3 [Debasish Ghosh]
| | * | | | | | | db26ebb 2010-05-28 | added 2.8.0.RC3 for sjson jar [Debasish Ghosh]
| | |/ / / / / /  
| | * | | | | | 919b267 2010-05-28 | Switched Listeners impl from Agent to CopyOnWriteArraySet [Jonas Bonér]
| | * | | | | |   55d210c 2010-05-28 | Merge branch 'scala_2.8.RC3' [Jonas Bonér]
| | |\ \ \ \ \ \  
| | | * | | | | | de5e74c 2010-05-28 | Added Scala 2.8RC3 version of SBinary [Jonas Bonér]
| | | * | | | | | 29048a7 2010-05-28 | Upgraded to Scala 2.8.0 RC3 [Jonas Bonér]
| | * | | | | | |   4d2e6ee 2010-05-28 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | ffce95a 2010-05-28 | Fix of issue #235 [Jonas Bonér]
| | | |/ / / / / /  
| | |/| | | | | |   
| | * | | | | | |   94b5caf 2010-05-28 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | a1f0e58 2010-05-28 | Added senderFuture to ActiveObjectContext, eg. fixed issue #248 [Jonas Bonér]
| * | | | | | | | |   ad53bba 2010-05-28 | Merge branch 'master' of github.com:jboner/akka into wip-akka-rest-fix [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | | |_|/ / / / / /  
| | |/| | | | | | |   
| | * | | | | | | |   3d410b8 2010-05-28 | Merge branch 'master' of github.com:jboner/akka [rossputin]
| | |\ \ \ \ \ \ \ \  
| | | | |/ / / / / /  
| | | |/| | | | | |   
| | | * | | | | | | d175bd1 2010-05-28 | Correct fix for no logging on sbt run [Peter Vlugter]
| | | |/ / / / / /  
| | | * | | | | | d348c9b 2010-05-28 | Fixed issue #240: Supervised actors not started when starting supervisor [Jonas Bonér]
| | * | | | | | | b45c475 2010-05-28 | minor log message change for consistency [rossputin]
| | |/ / / / / /  
| | * | | | | | d04f69a 2010-05-28 | Fixed issue with AMQP module [Jonas Bonér]
| | * | | | | | 6d99341 2010-05-28 | Made 'sender' and 'senderFuture' in ActorRef public [Jonas Bonér]
| | * | | | | |   fe6fb1e 2010-05-28 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \  
| | | * | | | | | a9c47d0 2010-05-28 | Fix for no logging on sbt run (#241) [Peter Vlugter]
| | * | | | | | | a7ce4b2 2010-05-28 | Fixed issue with sender reference in Active Objects [Jonas Bonér]
| | |/ / / / / /  
| | * | | | | | 5a941c4 2010-05-27 | fixed publish-local-mvn [Michael Kober]
| | * | | | | | a7355a1 2010-05-27 | Added default dispatch.throughput value to akka-reference.conf [Peter Vlugter]
| | * | | | | | 4fde847 2010-05-27 | Configurable throughput for ExecutorBasedEventDrivenDispatcher (#187) [Peter Vlugter]
| | * | | | | | 9553b69 2010-05-27 | Updated to Multiverse 0.5.2 [Peter Vlugter]
| * | | | | | | f82d25d 2010-05-26 | Tweaking akka-reference.conf [Viktor Klang]
| * | | | | | | 01c4e51 2010-05-26 | Elaborated on classloader handling [Viktor Klang]
| * | | | | | |   92312a3 2010-05-26 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| | * | | | | |   721e1bf 2010-05-26 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \ \ \ \  
| | * | | | | | | fa66aae 2010-05-26 | Workaround temporary issue by starting supervised actors explicitly. [Martin Krasser]
| * | | | | | | | 461bec2 2010-05-25 | Initial attempt at fixing akka rest [Viktor Klang]
| | |/ / / / / /  
| |/| | | | | |   
| * | | | | | |   ab4c69e 2010-05-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | | |_|_|_|/ /  
| | |/| | | | |   
| | * | | | | | 6caaecf 2010-05-25 | implemented updateVectorStorageEntryFor in akka-persistence-mongo (issue #165) and upgraded mongo-java-driver to 1.4 [Debasish Ghosh]
| * | | | | | | 4a6d4d6 2010-05-25 | Added option to specify class loader when deserializing RemoteActorRef [Jonas Bonér]
| * | | | | | | 176bf48 2010-05-25 | Added option to specify class loader to load serialized classes in the RemoteClient + cleaned up RemoteClient and RemoteServer API in this regard [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 5736f92 2010-05-25 | Fixed issue #157 [Jonas Bonér]
| * | | | | |   87f0272 2010-05-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | 1ccd23d 2010-05-25 | fixed merge error [Michael Kober]
| * | | | | | | 92797f7 2010-05-25 | Fixed issue #156 and #166 [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 7e89a87 2010-05-25 | Changed order of peristence operations in Storage::commit, now clear is done first [Jonas Bonér]
| * | | | | |   4e35c84 2010-05-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | fbd971b 2010-05-25 | fixed merge error [Michael Kober]
| | * | | | | |   4e6ae68 2010-05-25 | Merge branch '221-sbt-publish' [Michael Kober]
| | |\ \ \ \ \ \  
| | | * | | | | | 5e751c5 2010-05-25 | added new task publish-local-mvn [Michael Kober]
| | | |/ / / / /  
| * | | | | | | 1a50409 2010-05-25 | Upgraded to Cassandra 0.6.1 [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 947657d 2010-05-25 | Upgraded to SBinary for Scala 2.8.0.RC2 [Jonas Bonér]
| * | | | | | 5bb8dbc 2010-05-25 | Fixed bug in Transaction.Local persistence management [Jonas Bonér]
| |/ / / / /  
| * | | | | 49c2202 2010-05-24 | Upgraded to 2.8.0.RC2-1.4-SNAPSHOT version of Redis Client [Jonas Bonér]
| * | | | | 4430f0a 2010-05-24 | Fixed wrong code rendering [Jonas Bonér]
| * | | | | d51c820 2010-05-24 | Added akka-sample-ants as a sample showcasing STM and Transactors [Jonas Bonér]
| * | | | |   3aaaf94 2010-05-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | a91948d 2010-05-24 | disabled tests for akka-persistence-redis to be run automatically [Debasish Ghosh]
| | * | | | | 40f7b57 2010-05-24 | updated redisclient to 2.8.0.RC2 [Debasish Ghosh]
| | * | | | | b69a5b6 2010-05-22 | Removed some LoC [Viktor Klang]
| * | | | | | d1c910c 2010-05-24 | Fixed bug in issue #211; Transaction.Global.atomic {...} management [Jonas Bonér]
| * | | | | | 1d5545a 2010-05-24 | Added failing test for issue #211; triggering CommitBarrierOpenException [Jonas Bonér]
| * | | | | | 6af4676 2010-05-24 | Updated pom.xml for Java test to 0.9 [Jonas Bonér]
| * | | | | | d8ce28c 2010-05-24 | Updated to JGroups 2.9.0.GA [Jonas Bonér]
| * | | | | | 39a9bef 2010-05-24 | Added ActiveObjectContext with sender reference [Jonas Bonér]
| |/ / / / /  
| * | | | |   7406d23 2010-05-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * \ \ \ \   453faa1 2010-05-22 | Merged with origin/master [Viktor Klang]
| | |\ \ \ \ \  
| | * | | | | | 4244f83 2010-05-22 | Switched to primes and !! + cleanup [Viktor Klang]
| * | | | | | | cf76aa1 2010-05-23 | Fixed regression bug in AMQP supervisor code [Jonas Bonér]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | 3687b6f 2010-05-21 | Removed trailing whitespace [Jonas Bonér]
| * | | | | |   a06f1ff 2010-05-21 | Merge branch 'scala_2.8.RC2' into rc2 [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | f6ec562 2010-05-21 | Upgraded to Scala RC2 version of ScalaTest, but still some problems [Jonas Bonér]
| | * | | | | | 4a26591 2010-05-20 | Port to Scala RC2. All compile, but tests fail on ScalaTest not being RC2 compatible [Jonas Bonér]
| * | | | | | | ad3b905 2010-05-21 | Add the possibility to start Akka kernel or use Akka as dependency JAR *without* setting AKKA_HOME or have an akka.conf defined somewhere. Also moved JGroupsClusterActor into akka-core and removed akka-cluster module [Jonas Bonér]
| * | | | | | | 3d1a782 2010-05-21 | Fixed issue #190: RemoteClient shutdown ends up in endless loop [Jonas Bonér]
| * | | | | | | a521759 2010-05-21 | Fixed regression in Scheduler [Jonas Bonér]
| * | | | | | |   730e176 2010-05-20 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | | |/ / / / /  
| | |/| | | | |   
| | * | | | | | a9debde 2010-05-20 | Added regressiontest for spawn [Viktor Klang]
| | * | | | | | 40113f8 2010-05-20 | Fixed cluster [Viktor Klang]
| | |/ / / / /  
| * | | | | | 47b4e2b 2010-05-20 | Fixed race-condition in creation and registration of RemoteServers [Jonas Bonér]
| |/ / / / /  
| * | | | | 656e65c 2010-05-19 | Fixed problem with ordering when invoking self.start from within Actor [Jonas Bonér]
| * | | | | b36dc5c 2010-05-19 | Re-introducing 'sender' and 'senderFuture' references. Now 'sender' is available both for !! and !!! message sends [Jonas Bonér]
| * | | | | feef59f 2010-05-18 | Added explicit nullification of all ActorRef references in Actor to make the Actor instance eligable for GC [Jonas Bonér]
| * | | | | 3277b2a 2010-05-18 | Fixed race-condition in Supervisor linking [Jonas Bonér]
| * | | | |   e5d97c6 2010-05-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * \ \ \ \   b95db67 2010-05-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \  
| * | \ \ \ \ \   646516e 2010-05-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| |/| / / / / /   
| | |/ / / / /    
| | * | | | | 6154102 2010-05-17 | Removing old, unused, dependencies [Viktor Klang]
| | * | | | | c21da14 2010-05-16 | Added Receive type [Viktor Klang]
| | * | | | | 3df7ae2 2010-05-16 | Took the liberty of adding the redisclient pom and changed the name of the jar [Viktor Klang]
| * | | | | | 854cdba 2010-05-18 | Fixed supervision bugs [Jonas Bonér]
| |/ / / / /  
| * | | | | 1cc28c8 2010-05-16 | Improved error handling and message for Config [Jonas Bonér]
| * | | | |   78d1651 2010-05-16 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | 3253d35 2010-05-15 | changed version of sjson to 0.5 [Debasish Ghosh]
| | * | | | | 989dd2a 2010-05-15 | changed redisclient version to 1.3 [Debasish Ghosh]
| | * | | | | 2f6f682 2010-05-12 | Allow applications to disable stream-caching (#202) [Martin Krasser]
| * | | | | |   c4b32e7 2010-05-16 | Merged with master and fixed last issues [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | eb50a1c 2010-05-12 | Fixed wrong instructions in sample-remote README [Jonas Bonér]
| | * | | | | 0b02d2b 2010-05-12 | Updated to Guice 2.0 [Jonas Bonér]
| | * | | | | 80ef9af 2010-05-11 | AKKA-192 - Upgrade slf4j to 1.6.0 [Viktor Klang]
| | * | | | | cbe7262 2010-05-10 | Upgraded to Netty 3.2.0-RC1 [Viktor Klang]
| | * | | | | cf4891f 2010-05-10 | Fixed potential stack overflow [Viktor Klang]
| | * | | | | 2e239f0 2010-05-10 | Fixing bug with !! and WorkStealing? [Viktor Klang]
| | * | | | | 16c03cc 2010-05-10 | Message API improvements [Martin Krasser]
| | * | | | | 81f0419 2010-05-10 | Changed the order for detecting akka.conf [Peter Vlugter]
| | * | | | | c3d8e85 2010-05-09 | Deactivate endpoints of stopped consumer actors (AKKA-183) [Martin Krasser]
| | * | | | | 7abb110 2010-05-08 | Switched newActor for actorOf [Viktor Klang]
| | * | | | | fbefcee 2010-05-08 | newActor(() => refactored [Viktor Klang]
| | * | | | | fcc1591 2010-05-08 | Refactored Actor [Viktor Klang]
| | * | | | | 17e5a12 2010-05-08 | Fixing the test [Viktor Klang]
| | * | | | | 70db2a5 2010-05-08 | Closing ticket 150 [Viktor Klang]
| * | | | | | 6b1012f 2010-05-16 | Added failing test to supervisor specs [Jonas Bonér]
| * | | | | | 98f3b76 2010-05-16 | Fixed final bug in remote protocol, now refactoring should (finally) be complete [Jonas Bonér]
| * | | | | | e2ee983 2010-05-16 | added lock util class [Jonas Bonér]
| * | | | | | b2b4b7d 2010-05-16 | Rewritten "home" address management and protocol, all test pass except 2 [Jonas Bonér]
| * | | | | | 21e6085 2010-05-13 | Refactored code into ActorRef, LocalActorRef and RemoteActorRef [Jonas Bonér]
| * | | | | | b1d9897 2010-05-12 | Added scaladoc [Jonas Bonér]
| * | | | | | 797d1cd 2010-05-11 | Splitted up Actor and ActorRef in their own files [Jonas Bonér]
| * | | | | | 0f797d9 2010-05-09 | Actor and ActorRef restructuring complete, still need to refactor tests [Jonas Bonér]
| * | | | | |   d09a6f4 2010-05-08 | Merge branch 'ActorRef-FaultTolerance' of git@github.com:jboner/akka into ActorRef-FaultTolerance [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | cbe87c8 2010-05-08 | Moved everything from Actor to ActorRef: akka-core compiles [Jonas Bonér]
| | |/ / / / /  
| * | | | | | 96f459a 2010-05-08 | Fixed Actor initialization problem with DynamicVariable initialied by ActorRef [Jonas Bonér]
| * | | | | | 072bbe4 2010-05-08 | Added isOrRemoteNode field to ActorRef [Jonas Bonér]
| * | | | | | 4de5302 2010-05-08 | Moved everything from Actor to ActorRef: akka-core compiles [Jonas Bonér]
| |/ / / / /  
| * | | | |   6da1b07 2010-05-07 | Merge branch 'ActorRefSerialization' [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | cb2e39c 2010-05-07 | Rewrite of remote protocol to use the new ActorRef protocol [Jonas Bonér]
| | * | | | |   f951d2c 2010-05-06 | Merge branch 'master' into ActorRefSerialization [Jonas Bonér]
| | |\ \ \ \ \  
| | * | | | | | e8cf790 2010-05-05 | converted tabs to spaces [Jonas Bonér]
| | * | | | | | 1f63a52 2010-05-05 | Add Protobuf serialization and deserialization of ActorID [Jonas Bonér]
| * | | | | | |   8bf320c 2010-05-07 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | |_|/ / / / /  
| |/| | | | | |   
| | * | | | | | 086aeed 2010-05-06 | Added Kerberos config [Viktor Klang]
| | * | | | | | a56ac7b 2010-05-06 | Added ScalaDoc for akka-patterns [Viktor Klang]
| | * | | | | | 3ce843e 2010-05-06 | Merged akka-utils and akka-java-utils into akka-core [Viktor Klang]
| | * | | | | |   3440891 2010-05-06 | Merge branch 'master' into multiverse-0.5 [Peter Vlugter]
| | |\ \ \ \ \ \  
| | | |/ / / / /  
| | * | | | | | 286921c 2010-05-06 | Updated to Multiverse 0.5 release [Peter Vlugter]
| | * | | | | | 805914e 2010-05-02 | Updated to Multiverse 0.5 [Peter Vlugter]
| * | | | | | | 84b8e64 2010-05-06 | Renamed ActorID to ActorRef [Jonas Bonér]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | c469c86 2010-05-05 | Cleanup and minor refactorings, improved documentation etc. [Jonas Bonér]
| * | | | | |   be5114e 2010-05-05 | Merged with master [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | d618570 2010-05-05 | Removed Serializable.Protobuf since it did not work, use direct Protobuf messages for remote messages instead [Jonas Bonér]
| | * | | | | | 34bfaa0 2010-05-05 | Renamed Reactor.scala to MessageHandling.scala [Jonas Bonér]
| | * | | | | |   53e1fbe 2010-05-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \  
| | | * | | | | | a6c566e 2010-05-03 | Split up Patterns.scala in different files [Viktor Klang]
| | | * | | | | | 251bd0f 2010-05-02 | Added start utility method [Viktor Klang]
| | * | | | | | | f98a479 2010-05-05 | Fixed remote actor protobuf message serialization problem + added tests [Jonas Bonér]
| | * | | | | | | 1d44740 2010-05-04 | Changed suffix on source JAR from -src to -sources [Jonas Bonér]
| | * | | | | | | 6cea56b 2010-05-04 | minor edits [Jonas Bonér]
| * | | | | | | | 8e33dfc 2010-05-04 | merged in akka-sample-remote [Jonas Bonér]
| * | | | | | | |   df479a4 2010-05-04 | Merge branch 'master' into actor-handle [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | | c38d293 2010-05-04 | Added sample module for remote actors [Jonas Bonér]
| | |/ / / / / /  
| * | | | | | | 232ec14 2010-05-03 | ActorID: now all test pass, mission accomplished, ready for master [Jonas Bonér]
| * | | | | | |   5e97d81 2010-05-03 | Merge branch 'master' into actor-handle [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| | * | | | | |   3c6e17a 2010-05-02 | Merge branch 'master' of git@github.com:jboner/akka into wip_restructure [Viktor Klang]
| | |\ \ \ \ \ \  
| | | |/ / / / /  
| | | * | | | | ec2a0bd 2010-05-02 | Fixed Ref initial value bug by removing laziness [Peter Vlugter]
| | | * | | | | 0fd629e 2010-05-02 | Added test for Ref initial value bug [Peter Vlugter]
| | * | | | | |   224d50a 2010-05-01 | Merge branch 'master' of git@github.com:jboner/akka into wip_restructure [Viktor Klang]
| | |\ \ \ \ \ \  
| | | |/ / / / /  
| | | * | | | | 8816c7f 2010-05-01 | Fixed problem with PersistentVector.slice : Issue #161 [Debasish Ghosh]
| | * | | | | | 17241e1 2010-04-29 | Moved Grizzly logic to Kernel and renamed it to EmbeddedAppServer [Viktor Klang]
| | * | | | | | 834f866 2010-04-29 | Moving akka-patterns into akka-core [Viktor Klang]
| | * | | | | | 46c491a 2010-04-29 | Consolidated akka-security, akka-rest, akka-comet and akka-servlet into akka-http [Viktor Klang]
| | * | | | | | d5e4523 2010-04-29 | Removed Shoal and moved jGroups to akka-cluster, packages remain intact [Viktor Klang]
| | |/ / / / /  
| * | | | | | 05f1107 2010-05-03 | ActorID: all tests passing except akka-camel [Jonas Bonér]
| * | | | | | 4be87a0 2010-05-03 | All tests compile [Jonas Bonér]
| * | | | | | 0b9c797 2010-05-03 | All modules are building now [Jonas Bonér]
| * | | | | |   a2931f1 2010-05-02 | Merge branch 'actor-handle' of git@github.com:jboner/akka into actor-handle [Jonas Bonér]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   a7c29f9 2010-05-02 | merged with upstream [Jonas Bonér]
| | |\ \ \ \ \ \  
| * | \ \ \ \ \ \   33ab6e1 2010-05-02 | Chat sample now compiles with newActor[TYPE] [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| |/| / / / / / /   
| | |/ / / / / /    
| | * | | | | |   388b52b 2010-05-02 | merged with upstream [Jonas Bonér]
| | |\ \ \ \ \ \  
| * | \ \ \ \ \ \   ae7f732 2010-05-02 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| |/| / / / / / /   
| | |/ / / / / /    
| | * | | | | | bac0db9 2010-05-01 | akka-core now compiles [Jonas Bonér]
| * | | | | | | d396961 2010-05-01 | akka-core now compiles [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 2ea646d 2010-04-30 | Mid ActorID refactoring [Jonas Bonér]
| * | | | | |   ebaead3 2010-04-27 | mid merge [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 2fb619b 2010-04-26 | Made ActiveObject non-advisable in AW terms [Jonas Bonér]
| | * | | | | 716229c 2010-04-25 | Added Future[T] as return type for await and awaitBlocking [Viktor Klang]
| | * | | | |   ef7e758 2010-04-24 | Added parameterized Futures [Viktor Klang]
| | |\ \ \ \ \  
| | | * | | | | 71f766f 2010-04-23 | Minor cleanup [Viktor Klang]
| | | * | | | |   d888395 2010-04-23 | Merge branch 'master' of git@github.com:jboner/akka into 151_parameterize_future [Viktor Klang]
| | | |\ \ \ \ \  
| | | * | | | | | e598c28 2010-04-23 | Initial parametrization [Viktor Klang]
| | * | | | | | | 12d3ce3 2010-04-24 | Added reply_? that discards messages if it cannot find reply target [Viktor Klang]
| | * | | | | | | 4ffa759 2010-04-24 | Added Listeners to akka-patterns [Viktor Klang]
| | | |/ / / / /  
| | |/| | | | |   
| | * | | | | | d7e327d 2010-04-22 | updated dependencies in pom [Michael Kober]
| | * | | | | | 70256e4 2010-04-22 | JTA: Added option to register "joinTransaction" function and which classes to NOT roll back on [Jonas Bonér]
| | * | | | | | 08f835e 2010-04-22 | Added StmConfigurationException [Jonas Bonér]
| | |/ / / / /  
| | * | | | | 70087d5 2010-04-21 | added scaladoc [Jonas Bonér]
| | * | | | | 68bb4af 2010-04-21 | Moved ActiveObjectConfiguration to ActiveObject.scala file [Jonas Bonér]
| | * | | | | 011898b 2010-04-21 | Made JTA Synchronization management generic and allowing more than one + refactoring [Jonas Bonér]
| | * | | | |   fb42965 2010-04-20 | Renamed to JTA.scala [Jonas Bonér]
| | |\ \ \ \ \  
| | | * | | | | fec67a8 2010-04-20 | Added STM Synchronization registration to JNDI TransactionSynchronizationRegistry [Jonas Bonér]
| | * | | | | | 1d32924 2010-04-20 | Added STM Synchronization registration to JNDI TransactionSynchronizationRegistry [Jonas Bonér]
| | |/ / / / /  
| | * | | | |   251899d 2010-04-20 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \  
| | | * \ \ \ \   db40f78 2010-04-20 | Merge branch 'master' of github.com:jboner/akka [Michael Kober]
| | | |\ \ \ \ \  
| | | * | | | | | fc23d02 2010-04-20 | fixed #154 added ActiveObjectConfiguration with fluent API [Michael Kober]
| | * | | | | | | b4c7158 2010-04-20 | Cleaned up JTA stuff [Jonas Bonér]
| | | |/ / / / /  
| | |/| | | | |   
| | * | | | | |   2604c2c 2010-04-20 | Merge branch 'master' into jta [Jonas Bonér]
| | |\ \ \ \ \ \  
| | | * | | | | | ee21af3 2010-04-20 | fix for Vector from Dean (ticket #155) [Peter Vlugter]
| | | * | | | | | 4a01336 2010-04-20 | added Dean's test for Vector bug (blowing up after 32 items) [Peter Vlugter]
| | | * | | | | | 75c1cf5 2010-04-19 | Removed jndi.properties [Viktor Klang]
| | | * | | | | |   070e005 2010-04-19 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \  
| | | | |/ / / / /  
| | | * | | | | | 51619a0 2010-04-15 | Removed Scala 2.8 deprecation warnings [Viktor Klang]
| | * | | | | | | 98d5c4a 2010-04-20 | Finalized the JTA support [Jonas Bonér]
| | * | | | | | | eb95fd8 2010-04-17 | added logging to jta detection [Jonas Bonér]
| | * | | | | | | d5da879 2010-04-17 | jta-enabled stm [Jonas Bonér]
| | * | | | | | | 3ce5ef8 2010-04-17 | upgraded to 0.9 [Jonas Bonér]
| | * | | | | | |   27c54de 2010-04-17 | Merge branch 'master' into jta [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | | | |/ / / / /  
| | | |/| | | | |   
| | | * | | | | | 584de09 2010-04-16 | added sbt plugin file [Jonas Bonér]
| | | * | | | | | a596fbb 2010-04-16 | Added Cassandra logging dependencies to the compile jars [Jonas Bonér]
| | | * | | | | | 5ab8135 2010-04-14 | updating TransactionalRef to be properly monadic [Peter Vlugter]
| | | * | | | | | 86ee7d8 2010-04-14 | tests for TransactionalRef in for comprehensions [Peter Vlugter]
| | | * | | | | | 3ac7acc 2010-04-14 | tests for TransactionalRef [Peter Vlugter]
| | | * | | | | | 5b0f267 2010-04-16 | converted tabs to spaces [Jonas Bonér]
| | | * | | | | | 379b6e2 2010-04-16 | Updated old scaladoc [Jonas Bonér]
| | | * | | | | |   19b53df 2010-04-15 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \ \ \  
| | | | |/ / / / /  
| | | | * | | | | fac08d2 2010-04-14 | update instructions for chat running chat sample [rossputin]
| | | | * | | | | 2abad7d 2010-04-14 | Redis client now implements pubsub. Also included a sample app for RedisPubSub in akka-samples [Debasish Ghosh]
| | | | * | | | |   ea0f4ef 2010-04-14 | Merge branch 'link-active-objects' [Michael Kober]
| | | | |\ \ \ \ \  
| | | | | * | | | | 4728c3c 2010-04-14 | implemented link/unlink for active objects [Michael Kober]
| | | | * | | | | |   a47ec96 2010-04-13 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | | |\ \ \ \ \ \  
| | | | * | | | | | | 90b2821 2010-04-11 | Documented replyTo [Viktor Klang]
| | | | * | | | | | | e348190 2010-04-11 | Moved a runtime error to compile time [Viktor Klang]
| | | | * | | | | | | 7883b73 2010-04-10 | Refactored _isEventBased into the MessageDispatcher [Viktor Klang]
| | | * | | | | | | | 4756490 2010-04-14 | Added AtomicTemplate to allow atomic blocks from Java code [Jonas Bonér]
| | | * | | | | | | | 4117d94 2010-04-14 | fixed bug with ignoring timeout in Java API [Jonas Bonér]
| | | | |/ / / / / /  
| | | |/| | | | | |   
| | * | | | | | | | dcaa743 2010-04-17 | added TransactionManagerDetector [Jonas Bonér]
| | * | | | | | | | d97df97 2010-04-08 | Added JTA module, monadic and higher-order functional API [Jonas Bonér]
| * | | | | | | | | 8c44240 2010-04-14 | added ActorRef [Jonas Bonér]
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | | a6b8483 2010-04-12 | added compile options [Jonas Bonér]
| * | | | | | | | bc49036 2010-04-12 | fixed bug in config file [Jonas Bonér]
| | |/ / / / / /  
| |/| | | | | |   
| * | | | | | | e523475 2010-04-10 | Readded more SBinary functionality [Viktor Klang]
| * | | | | | |   1a73d73 2010-04-09 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | | |/ / / / /  
| | |/| | | | |   
| | * | | | | | 10b1c6f 2010-04-09 | added test for supervised remote active object [Michael Kober]
| * | | | | | | ce73229 2010-04-09 | cleaned up remote tests + remvod akkaHome from sbt build file [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 988f16c 2010-04-09 | fixed bug in Agent.scala, fixed bug in RemoteClient.scala, fixed problem with tests [Jonas Bonér]
| * | | | | | 4604d22 2010-04-09 | Initial values possible for TransactionalRef, TransactionalMap, and TransactionalVector [Peter Vlugter]
| * | | | | | 3f7c1fe 2010-04-09 | Added alter method to TransactionalRef [Peter Vlugter]
| * | | | | | bdd7b9e 2010-04-09 | fix for HashTrie: apply and + now return HashTrie rather than Map [Peter Vlugter]
| * | | | | |   ed70b73 2010-04-08 | Merge branch 'master' of git@github.com:jboner/akka [Jan Kronquist]
| |\ \ \ \ \ \  
| | * | | | | | 2b8db32 2010-04-08 | improved scaladoc for Actor.scala [Jonas Bonér]
| | * | | | | | 34dee73 2010-04-08 | removed Actor.remoteActor factory method since it does not work [Jonas Bonér]
| | |/ / / / /  
| * | | | | | 08695c5 2010-04-08 | Started working on issue #121 Added actorFor to get the actor for an activeObject [Jan Kronquist]
| |/ / / / /  
| * | | | |   1b2451f 2010-04-07 | Merge branch 'master' of git@github.com:jboner/akka into sbt [Jonas Bonér]
| |\ \ \ \ \  
| | * \ \ \ \   0549fc0 2010-04-07 | Merge branch 'either_sender_future' [Viktor Klang]
| | |\ \ \ \ \  
| | | * | | | | 0f87564 2010-04-07 | Removed uglies [Viktor Klang]
| | | * | | | | 92d5f2c 2010-04-06 | Change sender and senderfuture to Either [Viktor Klang]
| * | | | | | | 675b2fb 2010-04-07 | Cleaned up sbt build file + upgraded to sbt 0.7.3 [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 7dfbb8f 2010-04-07 | Improved ScalaDoc in Actor [Jonas Bonér]
| * | | | | | cf2c0ea 2010-04-07 | added a method to retrieve the supervisor for an actor + a message Unlink to unlink himself [Jonas Bonér]
| * | | | | |   5c9c1ba 2010-04-07 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | 0368d9b 2010-04-07 | fixed @inittransactionalstate, updated pom for spring java tests [Michael Kober]
| * | | | | | | 4dee6cb 2010-04-07 | fixed bug in nested supervisors + added tests + added latch to agent tests [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 59e4b53 2010-04-07 | Fixed: Akka kernel now loads all jars wrapped up in the jars in the ./deploy dir [Jonas Bonér]
| * | | | | | c9f0a87 2010-04-06 | Added API to add listeners to subscribe to Error, Connect and Disconnect events on RemoteClient [Jonas Bonér]
| |/ / / / /  
| * | | | | d5f09f8 2010-04-06 | Added Logging trait back to Actor (v0.8.1) [Jonas Bonér]
| * | | | | 9c57c3b 2010-04-06 | Now doing a 'reply(..)' to remote sender after receiving a remote message through '!' works. Added tests. Also removed the Logging trait from Actor for lower memory footprint. [Jonas Bonér]
| * | | | |   85cb032 2010-04-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | 9037ac2 2010-04-05 | Rename file [Viktor Klang]
| | * | | | |   cb53385 2010-04-05 | Merged in akka-servlet [Viktor Klang]
| | |\ \ \ \ \  
| | * | | | | | dc89cd1 2010-04-05 | Changed module name, packagename and classnames :-) [Viktor Klang]
| | * | | | | | 736ace2 2010-04-05 | Created jxee module [Viktor Klang]
| * | | | | | | c57aea9 2010-04-05 | renamed tests from *Test -> *Spec [Jonas Bonér]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | 98f85a7 2010-04-05 | Improved scaladoc for Transaction [Jonas Bonér]
| * | | | | | c382e44 2010-04-05 | cleaned up packaging in samples to all be "sample.x" [Jonas Bonér]
| * | | | | | 0d95b09 2010-04-05 | Refactored STM API into Transaction.Global and Transaction.Local, fixes issues with "atomic" outside actors [Jonas Bonér]
| * | | | | |   fec271e 2010-04-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 092485e 2010-04-04 | fix for comments [rossputin]
| * | | | | | 5aa8c58 2010-04-04 | fixed broken "sbt dist" [Jonas Bonér]
| |/ / / / /  
| * | | | | fb77ee6 2010-04-03 | fixed bug with creating anonymous actor, renamed some anonymous actor factory methods [Jonas Bonér]
| * | | | | c580695 2010-04-02 | changed println -> log.info [Jonas Bonér]
| * | | | | 647bb9c 2010-03-17 | Added load balancer which prefers actors with small mailboxes (discussed on mailing list a while ago). [Jan Van Besien]
| * | | | |   c110b86 2010-04-02 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| |\ \ \ \ \  
| | * | | | | 7cab56d 2010-04-02 | simplified tests using CountDownLatch.await with a timeout by asserting the count reached zero in a single statement. [Jan Van Besien]
| * | | | | | 954ae00 2010-04-02 | new redisclient with support for clustering [Debasish Ghosh]
| |/ / / / /  
| * | | | |   74b192f 2010-04-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | eef1670 2010-04-01 | Minor cleanups and fixing super.unregister [Viktor Klang]
| | * | | | | 1e3b7eb 2010-04-01 | Improved unit test performance by replacing Thread.sleep with more clever approaches (CountDownLatch, BlockingQueue and others). Here and there Thread.sleep could also simply be removed. [Jan Van Besien]
| * | | | | | 7efef1c 2010-04-01 | cleaned up [Jonas Bonér]
| * | | | | | cff55f8 2010-04-01 | refactored build file [Jonas Bonér]
| |/ / / / /  
| * | | | | 5f4e8b8 2010-04-01 | release v0.8 (v0.8) [Jonas Bonér]
| * | | | |   6d36ac9 2010-04-01 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | 6ff1d09 2010-03-31 | updated copyright header [Jonas Bonér]
| | * | | | | d4d705e 2010-03-31 | updated Agent scaladoc with monadic examples [Jonas Bonér]
| | * | | | | ffeaac3 2010-03-31 | Agent is now monadic, added more tests to AgentTest [Jonas Bonér]
| | * | | | | 70b4d73 2010-03-31 | Gave the sbt deploy plugin richer API [Jonas Bonér]
| | * | | | | c16b42e 2010-03-31 | added missing scala-library.jar to dist and manifest.mf classpath [Jonas Bonér]
| | * | | | | 1a976fa 2010-03-31 | reverted back to sbt 0.7.1 [Jonas Bonér]
| | * | | | | 19879f3 2010-03-30 | Removed Actor.send function [Jonas Bonér]
| | * | | | |   7cf13c7 2010-03-30 | merged with upstream [Jonas Bonér]
| | |\ \ \ \ \  
| | * \ \ \ \ \   84ee350 2010-03-30 | merged with upstream [Jonas Bonér]
| | |\ \ \ \ \ \  
| | | * \ \ \ \ \   6732535 2010-03-30 | Merge branch '2.8-WIP' of git@github.com:jboner/akka into 2.8-WIP [Viktor Klang]
| | | |\ \ \ \ \ \  
| | | | * | | | | | 582edbe 2010-03-31 | upgraded redisclient to version 1.2: includes api name changes for conformance with redis server (earlier ones deprecated). Also an implementation of Deque that can be used for Durable Q in actors [Debasish Ghosh]
| | | * | | | | | | e17713b 2010-03-30 | Forward-ported bugfix in Security to 2.8-WIP [Viktor Klang]
| | | |/ / / / / /  
| | * | | | | | |   2c6a8ae 2010-03-30 | Merged with new Redis 1.2 code from master, does not compile since the redis-client is build with 2.7.7, need to get correct JAR [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | | |/ / / / / /  
| | |/| | | | | |   
| | * | | | | | |   1c5dc6e 2010-03-30 | merged with upstream [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | 10bc33f 2010-03-29 | Added missing + sign [viktorklang]
| | * | | | | | | | dc6e9a7 2010-03-30 | Updated version to 0.8x [Jonas Bonér]
| | * | | | | | | | 47c75c5 2010-03-30 | Rewrote distribution generation, now it also packages sources and docs [Jonas Bonér]
| | |/ / / / / / /  
| | * | | | | | | a3187d1 2010-03-29 | minor edit [Jonas Bonér]
| | * | | | | | | 20569b7 2010-03-29 | improved scaladoc [Jonas Bonér]
| | * | | | | | | 4dc46ce 2010-03-29 | removed usused code [Jonas Bonér]
| | * | | | | | | 24bb4e8 2010-03-29 | updated to commons-pool 1.5.4 [Jonas Bonér]
| | * | | | | | | 440f016 2010-03-29 | fixed all deprecations execept in grizzly code [Jonas Bonér]
| | * | | | | | | 5ec1144 2010-03-29 | fixed deprecation warnings in akka-core [Jonas Bonér]
| | * | | | | | | 733ce7d 2010-03-26 | fixed warning, usage of 2.8 features: default arguments and generated copy method. [Martin Krasser]
| | * | | | | | | 73c70ac 2010-03-25 | And we`re back! [Viktor Klang]
| | * | | | | | | 1c68af3 2010-03-25 | Bumped version [Viktor Klang]
| | * | | | | | | 7c7458d 2010-03-25 | Removing Redis waiting for 1.2-SNAPSHOT for 2.8-Beta1 [Viktor Klang]
| | * | | | | | |   f0d2b6c 2010-03-25 | Resolved conflicts [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | 6e97b15 2010-03-02 | upgraded redisclient jar to 1.1 [Debasish Ghosh]
| | * | | | | | | | 03a5cf7 2010-03-25 | compiles, tests and dists without Redis + samples [Viktor Klang]
| | * | | | | | | |   ec06b6d 2010-03-23 | Merged latest master, fighting missing deps [Viktor Klang]
| | |\ \ \ \ \ \ \ \  
| | * | | | | | | | | 5af2dc2 2010-03-03 | Toying with manifests [Viktor Klang]
| | * | | | | | | | |   f3e1c7b 2010-02-28 | Merge branch '2.8-WIP' of git@github.com:jboner/akka into 2.8-WIP [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / /  
| | | |/| | | | | | |   
| | | * | | | | | | | e8ae2d3 2010-02-23 | redis storage support ported to Scala 2.8.Beta1. New jar for redisclient for 2.8.Beta1 [Debasish Ghosh]
| | * | | | | | | | |   4154a35 2010-02-26 | Merge with master [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / /  
| | |/| | | | | | | |   
| | * | | | | | | | | b8a0d2f 2010-02-22 | Akka LIVES! [Viktor Klang]
| | * | | | | | | | | 6ded5f6 2010-02-21 | And now akka-core builds! [Viktor Klang]
| | * | | | | | | | | 05ac9d4 2010-02-20 | Working nine to five ... [Viktor Klang]
| | * | | | | | | | | 682d944 2010-02-20 | Updated more deps [Viktor Klang]
| | * | | | | | | | | 1134711 2010-02-20 | Deleted old version of configgy [Viktor Klang]
| | * | | | | | | | | e9e3920 2010-02-20 | Added new version of configgy [Viktor Klang]
| | * | | | | | | | | 1bb5382 2010-02-20 | Partial version updates [Viktor Klang]
| | * | | | | | | | |   29a532b 2010-02-19 | Merge branch 'master' into 2.8-WIP [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | f7db1ec 2010-01-20 | And the pom... [Viktor Klang]
| | * | | | | | | | | | a856877 2010-01-20 | Stashing away work so far [Viktor Klang]
| | * | | | | | | | | | 8516652 2010-01-18 | Updated dep versions [Viktor Klang]
| * | | | | | | | | | |   3ba2d43 2010-03-31 | Merge branch 'master' of git@github.com:janvanbesien/akka (v0.7.1) [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \   97411d1 2010-03-31 | Merge branch 'workstealing' [Jan Van Besien]
| | |\ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | 8f448b9 2010-03-31 | added jsr166x library from doug lea [Jan Van Besien]
| * | | | | | | | | | | | | c2280df 2010-03-31 | Added jsr166x to the embedded repo. Use jsr166x.ConcurrentLinkedDeque in stead of LinkedBlockingDeque as colletion for the actors mailbox [Jan Van Besien]
| * | | | | | | | | | | | |   999c073 2010-03-31 | Merge branch 'master' of git@github.com:jboner/akka into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / /  
| | | / / / / / / / / / / /   
| | |/ / / / / / / / / / /    
| |/| | | | | | | | | | |     
| | * | | | | | | | | | |   b066363 2010-03-31 | Merge branch 'spring-dispatcher' [Michael Kober]
| | |\ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|/ / / / /  
| | |/| | | | | | | | | |   
| | | * | | | | | | | | | 8ae90fd 2010-03-30 | added spring dispatcher configuration [Michael Kober]
| | | | |_|_|/ / / / / /  
| | | |/| | | | | | | |   
| * | | | | | | | | | |   f9acc69 2010-03-31 | Merge branch 'master' of git@github.com:jboner/akka into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / /  
| | * | | | | | | | | | 933a7c8 2010-03-30 | Fixed reported exception in Akka-Security [Viktor Klang]
| | * | | | | | | | | | 1ca95fc 2010-03-30 | Added missing dependency [Viktor Klang]
| | | |_|_|_|/ / / / /  
| | |/| | | | | | | |   
| * | | | | | | | | |   1f4ddfc 2010-03-30 | Merge branch 'master' of git@github.com:jboner/akka into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / /  
| | * | | | | | | | | 338eea6 2010-03-30 | upgraded redisclient to version 1.2: includes api name changes for conformance with redis server (earlier ones deprecated). Also an implementation of Deque that can be used for Durable Q in actors [Debasish Ghosh]
| | |/ / / / / / / /  
| * | | | | | | | | 901fcd8 2010-03-30 | renamed some variables for clarity [Jan Van Besien]
| * | | | | | | | | 308bf20 2010-03-30 | fixed name of dispatcher in log messages [Jan Van Besien]
| * | | | | | | | | 62cdb9f 2010-03-30 | use forward in stead of send when stealing work from another actor [Jan Van Besien]
| * | | | | | | | | 067cc73 2010-03-30 | fixed round robin work stealing algorithm [Jan Van Besien]
| * | | | | | | | | 1951577 2010-03-29 | javadoc and comments [Jan Van Besien]
| * | | | | | | | | 75aad9d 2010-03-29 | fix [Jan Van Besien]
| * | | | | | | | | e7f4f41 2010-03-29 | minor refactoring of the round robin work stealing algorithm [Jan Van Besien]
| * | | | | | | | | cd6d27c 2010-03-29 | Simplified the round robin scheme [Jan Van Besien]
| * | | | | | | | |   5e31558 2010-03-29 | Merge commit 'upstream/master' into workstealing Implemented a simple round robin schema for the work stealing dispatcher [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| | * | | | | | | | e33c586 2010-03-22 | force projects to use higher versions of redundant libs instead of only excluding them later. This ensures that unit tests use the same libraries that are included into the distribution. [Martin Krasser]
| | * | | | | | | | 081e944 2010-03-22 | fixed bug in REST module (v0.7) [Jonas Bonér]
| | * | | | | | | | 9f5b2d8 2010-03-21 | upgrading to multiverse 0.4 [Jonas Bonér]
| | * | | | | | | | 639bfa7 2010-03-21 | Exclusion of redundant dependencies from distribution. [Martin Krasser]
| | * | | | | | | | 3297439 2010-03-20 | Upgrade of akka-sample-camel to spring-jms 3.0 [Martin Krasser]
| | * | | | | | | | aef25b7 2010-03-20 | Fixing akka-rest breakage from Configurator.getInstance [Viktor Klang]
| | * | | | | | | | 7f7a048 2010-03-20 | upgraded to 0.7 [Jonas Bonér]
| | * | | | | | | | dedf6c0 2010-03-20 | converted tabs to spaces [Jonas Bonér]
| | * | | | | | | | 1e4904e 2010-03-20 | Documented ActorRegistry and stablelized subscription API [Jonas Bonér]
| | * | | | | | | | f187f68 2010-03-20 | Cleaned up build file [Jonas Bonér]
| | * | | | | | | |   0085065 2010-03-20 | merged in the spring branch [Jonas Bonér]
| | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | efcd736 2010-03-17 | added integration tests [Michael Kober]
| | | * | | | | | | | 163c028 2010-03-17 | added integration tests, spring 3.0.1 and sbt [Michael Kober]
| | | * | | | | | | |   6dba173 2010-03-15 | merged master into spring [Michael Kober]
| | | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | | 5fb3a51 2010-03-15 | removed old source files [Michael Kober]
| | | * | | | | | | | |   79c48fd 2010-03-14 | pulled and merged [Michael Kober]
| | | |\ \ \ \ \ \ \ \ \  
| | | | * \ \ \ \ \ \ \ \   63387fb 2010-01-02 | merged with master [Jonas Bonér]
| | | | |\ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | 0eee35c 2010-01-02 | Added tests to Spring module, currently failing [Jonas Bonér]
| | | | * | | | | | | | | | 70b0aa2 2010-01-02 | Cleaned up Spring interceptor and helpers [Jonas Bonér]
| | | | * | | | | | | | | | 0d65b62 2010-01-01 | updated spring module pom to latest akka module layout. [Jonas Bonér]
| | | | * | | | | | | | | |   fd83fae 2009-12-31 | Merge branch 'master' of git://github.com/staffanfransson/akka into spring [Jonas Bonér]
| | | | |\ \ \ \ \ \ \ \ \ \  
| | | | | * | | | | | | | | | 23cbe7e 2009-12-02 | modified pom.xml to include akka-spring [Staffan Fransson]
| | | | | * | | | | | | | | | a1c81d9 2009-12-02 | Added contructor to Dispatcher and AspectInit [Staffan Fransson]
| | | | | * | | | | | | | | | cd94340 2009-12-02 | Added akka-spring [Staffan Fransson]
| | | * | | | | | | | | | | | 9533b54 2010-03-14 | initial version of spring custom namespace [Michael Kober]
| | | * | | | | | | | | | | | 4afd6a9 2010-01-02 | Added tests to Spring module, currently failing [Jonas Bonér]
| | | * | | | | | | | | | | | 25a8863 2010-01-02 | Cleaned up Spring interceptor and helpers [Jonas Bonér]
| | | * | | | | | | | | | | | 632a6b9 2010-01-01 | updated spring module pom to latest akka module layout. [Jonas Bonér]
| | | * | | | | | | | | | | | b6ec8ef 2009-12-02 | modified pom.xml to include akka-spring [Staffan Fransson]
| | | * | | | | | | | | | | | 9b76bcf 2009-12-02 | Added contructor to Dispatcher and AspectInit [Staffan Fransson]
| | | * | | | | | | | | | | | 84a344f 2009-12-02 | Added akka-spring [Staffan Fransson]
| | * | | | | | | | | | | | | c021724 2010-03-20 | added line count script [Jonas Bonér]
| | * | | | | | | | | | | | | 7b7013c 2010-03-20 | Improved Agent doc [Jonas Bonér]
| | * | | | | | | | | | | | |   3259ef4 2010-03-20 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | 56e733b 2010-03-20 | Extension/rewriting of remaining unit and functional tests [Martin Krasser]
| | | * | | | | | | | | | | | | 1c215c6 2010-03-20 | traits for configuring producer behaviour [Martin Krasser]
| | | * | | | | | | | | | | | | aea05a1 2010-03-19 | Extension/rewrite of CamelService unit and functional tests [Martin Krasser]
| | | | |_|_|_|_|_|_|_|_|_|/ /  
| | | |/| | | | | | | | | | |   
| | * | | | | | | | | | | | | 3c29ced 2010-03-20 | Added tests to AgentTest and cleaned up Agent [Jonas Bonér]
| | * | | | | | | | | | | | | b2eeffe 2010-03-18 | Fixed problem with Agent, now tests pass [Jonas Bonér]
| | * | | | | | | | | | | | | e9df98c 2010-03-18 | Changed Supervisors actor map to hold a list of actors per class entry [Jonas Bonér]
| | * | | | | | | | | | | | | 3e106a4 2010-03-17 | tabs -> spaces [Jonas Bonér]
| * | | | | | | | | | | | | | 673bb2b 2010-03-19 | Don't allow two different actors (different types) to share the same work stealing dispatcher. Added unit test. [Jan Van Besien]
| * | | | | | | | | | | | | |   84b7566 2010-03-19 | Merge branch 'master' into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / /  
| | |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | | 088d085 2010-03-19 | camel-cometd example disabled [Martin Krasser]
| | * | | | | | | | | | | | | a96c6c4 2010-03-19 | Fix for InstantiationException on Kernel startup [Martin Krasser]
| | * | | | | | | | | | | | | 50b8f55 2010-03-18 | Fixed issue with file URL to embedded repository on Windows. [Martin Krasser]
| | * | | | | | | | | | | | |   92b71dc 2010-03-18 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | 0e9be44 2010-03-18 | extension/rewrite of actor component unit and functional tests [Martin Krasser]
| * | | | | | | | | | | | | | |   7a04709 2010-03-18 | Merge branch 'master' into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | e065817 2010-03-18 | added new jar 1.2-SNAPSHOT for redisclient [Debasish Ghosh]
| | * | | | | | | | | | | | | | b8c594e 2010-03-18 | added support for Redis based SortedSet persistence in Akka transactors [Debasish Ghosh]
| | | |/ / / / / / / / / / / /  
| | |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | | 8646c1f 2010-03-17 | Refactored Serializer [Jonas Bonér]
| | * | | | | | | | | | | | | 01ea070 2010-03-17 | reformatted patterns code [Jonas Bonér]
| | * | | | | | | | | | | | |   4f761d5 2010-03-17 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | 63c6db3 2010-03-17 | Fixed typo in docs. [Jonas Bonér]
| | | * | | | | | | | | | | | | 0736613 2010-03-17 | Updated how to run the sample docs. [Jonas Bonér]
| | | * | | | | | | | | | | | | 3ed20bd 2010-03-17 | Updated README with new running procedure [Jonas Bonér]
| | * | | | | | | | | | | | | | 7104076 2010-03-17 | Created an alias to TransactionalRef; Ref [Jonas Bonér]
| | |/ / / / / / / / / / / / /  
| | * | | | | | | | | | | | | d40ee7d 2010-03-17 | Changed Chat sample to use server-managed remote actors + changed the how-to-run-sample doc. [Jonas Bonér]
| * | | | | | | | | | | | | | f3fc74e 2010-03-17 | only allow actors of the same type to be registered with a work stealing dispatcher. [Jan Van Besien]
| * | | | | | | | | | | | | | 399cbde 2010-03-17 | when searching for a thief, only consider thiefs with empty mailboxes. [Jan Van Besien]
| * | | | | | | | | | | | | |   6c16b7b 2010-03-17 | Merge branch 'master' into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / /  
| | * | | | | | | | | | | | | 33bbcab 2010-03-17 | Made "sbt publish" publish artifacts to local Maven repo [Jonas Bonér]
| * | | | | | | | | | | | | |   31c92e2 2010-03-17 | Merge branch 'master' into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / /  
| | * | | | | | | | | | | | | d9967c9 2010-03-17 | moved akka.annotation._ to akka.actor.annotation._ to be merged in with akka-core OSGi bundle [Jonas Bonér]
| | |/ / / / / / / / / / / /  
| | * | | | | | | | | | | |   c03e639 2010-03-17 | Merged in Camel branch [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | 8baab8b 2010-03-16 | Minor syntax edits [Jonas Bonér]
| | | * | | | | | | | | | | | 98efbb1 2010-03-16 | akka-camel added to manifest classpath. All examples enabled. [Martin Krasser]
| | | * | | | | | | | | | | | d849116 2010-03-16 | Move to sbt [Martin Krasser]
| | | * | | | | | | | | | | |   9ce5e80 2010-03-15 | initial resolution of conflicts after merge with master [Martin Krasser]
| | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | | | |_|_|_|/ / / / / / /  
| | | | |/| | | | | | | | | |   
| | | * | | | | | | | | | | | 70b6ad9 2010-03-15 | prepare merge with master [Martin Krasser]
| | | * | | | | | | | | | | | 066deef 2010-03-14 | publish/subscribe examples using jms and cometd [Martin Krasser]
| | | * | | | | | | | | | | | acab532 2010-03-11 | support for remote actors, consumer actor publishing at any time [Martin Krasser]
| | | * | | | | | | | | | | | 2a49a6c 2010-03-08 | error handling enhancements [Martin Krasser]
| | | * | | | | | | | | | | | 48ef898 2010-03-06 | performance improvement [Martin Krasser]
| | | * | | | | | | | | | | | cd60822 2010-03-06 | Added lifecycle methods to CamelService [Martin Krasser]
| | | * | | | | | | | | | | | ffe16b9 2010-03-06 | Fixed mess-up of previous commit (rollback changes to akka.iml), CamelService companion object for standalone applications to create their own CamelService instances [Martin Krasser]
| | | * | | | | | | | | | | | d39e39d 2010-03-06 | CamelService companion object for standalone applications to create their own CamelService instances [Martin Krasser]
| | | * | | | | | | | | | | |   cb8b184 2010-03-06 | Merge branch 'master' into camel [Martin Krasser]
| | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | | | |_|_|_|_|_|_|_|_|_|/  
| | | | |/| | | | | | | | | |   
| | | * | | | | | | | | | | | dd43d78 2010-03-05 | fixed compile errors after merging with master [Martin Krasser]
| | | * | | | | | | | | | | |   f75b1eb 2010-03-05 | Merge remote branch 'remotes/origin/master' into camel [Martin Krasser]
| | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | 77fb7f7 2010-03-05 | Producer trait for producing messages to Camel endpoints (sync/async, oneway/twoway), Immutable representation of Camel message, consumer/producer examples, refactorings/improvements/cleanups. [Martin Krasser]
| | | * | | | | | | | | | | | | 92946a9 2010-03-01 | use immutable messages for communication with actors [Martin Krasser]
| | | * | | | | | | | | | | | |   97c02c4 2010-03-01 | Merge branch 'camel' of github.com:jboner/akka into camel [Martin Krasser]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * \ \ \ \ \ \ \ \ \ \ \ \   fde7742 2010-03-01 | merge branch 'remotes/origin/master' into camel; resolved conflicts in ActorRegistry.scala and ActorRegistryTest.scala; removed initial, commented-out test class. [Martin Krasser]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | | | 7589121 2010-03-01 | changed actor URI format, cleanup unit tests. [Martin Krasser]
| | | | * | | | | | | | | | | | | | edaad3a 2010-02-28 | Fixed actor deregistration-by-id issue and added ActorRegistry unit test. [Martin Krasser]
| | | | * | | | | | | | | | | | | |   8620fb8 2010-02-27 | Merge branch 'master' into camel [Martin Krasser]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | | | |_|_|_|_|_|_|_|_|_|/ / /  
| | | | | |/| | | | | | | | | | | |   
| | | * | | | | | | | | | | | | | |   10d7c7b 2010-02-27 | Merge branch 'master' into camel [Martin Krasser]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | | |/ / / / / / / / / / / / /  
| | | | |/| | | | | | | | | | | | |   
| | | * | | | | | | | | | | | | | |   c8e4860 2010-02-26 | Merge branch 'master' into camel [Martin Krasser]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |_|/ / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | |   
| | | * | | | | | | | | | | | | | | bb202d4 2010-02-25 | initial camel integration (early-access, see also http://doc.akkasource.org/Camel) [Martin Krasser]
| | | | |_|_|_|_|_|_|_|_|_|_|/ / /  
| | | |/| | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | |   cc4b696 2010-03-16 | Merge branch 'jans_dispatcher_changes' [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \ \ \ \ \ \   491d25b 2010-03-16 | Merge branch 'dispatcherimprovements' of git@github.com:jboner/akka into jans_dispatcher_changes [Jonas Bonér]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   ec08ab2 2010-03-14 | merged [Jonas Bonér]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | 34973c8 2010-03-14 | dispatcher speed improvements [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | 4cc51cb 2010-03-16 | Added the run_akka.sh script [Viktor Klang]
| | * | | | | | | | | | | | | | | | | | dcb1645 2010-03-16 | Removed dead code [Viktor Klang]
| | | |_|_|_|_|_|_|_|_|/ / / / / / / /  
| | |/| | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | |   8c06a2e 2010-03-16 | Merge branch 'dispatcherimprovements' into workstealing. Also applied the same improvements on the work stealing dispatcher. [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|/ / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | a269dc4 2010-03-16 | Fixed bug which allowed messages to be "missed" if they arrived after looping through the mailbox, but before releasing the lock. [Jan Van Besien]
| | * | | | | | | | | | | | | | | | |   61a241c 2010-03-15 | Merge branch 'master' into dispatcherimprovements [Jan Van Besien]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / / /  
| | | | | / / / / / / / / / / / / / /   
| | | |_|/ / / / / / / / / / / / / /    
| | |/| | | | | | | | | | | | | | |     
| | | * | | | | | | | | | | | | | | 8bfa3f4 2010-03-15 | OS-specific substring search in paths (fixes 'sbt dist' issue on Windows) [Martin Krasser]
| | * | | | | | | | | | | | | | | |   7dad9ab 2010-03-10 | Merge commit 'upstream/master' into dispatcherimprovements Fixed conflict in actor.scala [Jan Van Besien]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | 2bafccb 2010-03-10 | fixed layout [Jan Van Besien]
| | * | | | | | | | | | | | | | | | | a36411c 2010-03-04 | only unlock if locked. [Jan Van Besien]
| | * | | | | | | | | | | | | | | | | 77b4455 2010-03-04 | remove println's in test [Jan Van Besien]
| | * | | | | | | | | | | | | | | | | a14b104 2010-03-04 | Release the lock when done dispatching. [Jan Van Besien]
| | * | | | | | | | | | | | | | | | | 6825980 2010-03-04 | Improved event driven dispatcher by not scheduling a task for dispatching when another is already busy. [Jan Van Besien]
| | | |_|_|_|_|_|_|_|_|_|_|_|_|_|_|/  
| | |/| | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | 5694a1a 2010-03-14 | don't just steal one message, but continue as long as there are more messages available. [Jan Van Besien]
| * | | | | | | | | | | | | | | | |   02229c9 2010-03-13 | Merge branch 'master' into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|/ / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | 40997c4 2010-03-13 | Revert to Atmosphere 0.5.4 because of issue in 0.6-SNAPSHOT [Viktor Klang]
| | * | | | | | | | | | | | | | | | 5dacc2b 2010-03-13 | Fixed deprecation warning [Viktor Klang]
| | * | | | | | | | | | | | | | | | 4bc10fb 2010-03-13 | Return 408 is authentication times out [Viktor Klang]
| | * | | | | | | | | | | | | | | | b9a59b5 2010-03-13 | Fixing container detection for SBT console mode [Viktor Klang]
| | | |_|/ / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | 6b6d4a9 2010-03-13 | cleanup, added documentation. [Jan Van Besien]
| * | | | | | | | | | | | | | | | 9e796de 2010-03-13 | switched from "work stealing" implementation to "work donating". Needs more testing, cleanup and documentation but looks promissing. [Jan Van Besien]
| * | | | | | | | | | | | | | | |   aab222e 2010-03-11 | Merge branch 'master' into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | |   e519d86 2010-03-11 | merged with upstream [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | 6e77cea 2010-03-11 | removed changes.xml (online instead) [Jonas Bonér]
| | * | | | | | | | | | | | | | |   4c76fe0 2010-03-11 | merged osgi-refactoring and sbt branch [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | 3f03939 2010-03-10 | Renamed packages in the whole project to be OSGi-friendly, A LOT of breaking changes [Jonas Bonér]
| | * | | | | | | | | | | | | | | | ea7d486 2010-03-10 | Added maven artifact publishing to sbt build [Jonas Bonér]
| | * | | | | | | | | | | | | | | | b8e2f48 2010-03-10 | fixed warnins in PerformanceTest [Jonas Bonér]
| | * | | | | | | | | | | | | | | | eb0c08e 2010-03-10 | Finalized SBT packaging task, now Akka is fully ported to SBT [Jonas Bonér]
| | * | | | | | | | | | | | | | | | e39b65a 2010-03-09 | added final tasks (package up distribution and executable JAR) to SBT build [Jonas Bonér]
| | * | | | | | | | | | | | | | | | 9c5676e 2010-03-07 | added assembly task and dist task to package distribution [Jonas Bonér]
| | * | | | | | | | | | | | | | | | 3e3b9f5 2010-03-07 | added java fun tests back to sbt project [Jonas Bonér]
| | * | | | | | | | | | | | | | | |   b7ed47e 2010-03-07 | merged sbt branch with master [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | b43793f 2010-03-05 | added test filter to filter away all tests that end with Spec [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | 6cafadf 2010-03-05 | cleaned up buildfile [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | d60af95 2010-03-02 | remove pom files [peter hausel]
| | * | | | | | | | | | | | | | | | | a668b5e 2010-03-02 | added remaining projects [peter hausel]
| | * | | | | | | | | | | | | | | | | 4c1e69b 2010-03-02 | new master parent [peter hausel]
| | * | | | | | | | | | | | | | | | | b97b456 2010-03-02 | second phase [peter hausel]
| | * | | | | | | | | | | | | | | | | 1eb6477 2010-03-01 | initial sbt support [peter hausel]
| | | |_|_|/ / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | |   3bc00ef 2010-03-10 | Merge commit 'upstream/master' into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|/ / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | 07ee50a 2010-03-10 | remove redundant method in tests [ross.mcdonald]
| | | |_|_|_|_|_|_|_|_|/ / / / / /  
| | |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | 57919cc 2010-03-10 | added todo [Jan Van Besien]
| * | | | | | | | | | | | | | | | 1c70063 2010-03-10 | use Actor.forward(...) when redistributing work. [Jan Van Besien]
| * | | | | | | | | | | | | | | |   0d9338b 2010-03-09 | Merge commit 'upstream/master' into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | b430ff1 2010-03-09 | added atomic increment and decrement in RedisStorageBackend [Debasish Ghosh]
| | * | | | | | | | | | | | | | | f37e44b 2010-03-08 | fix classloader error when starting AKKA as a library in jetty (fixes http://www.assembla.com/spaces/akka/tickets/129 ) [Eckart Hertzler]
| | * | | | | | | | | | | | | | | 703ed87 2010-03-08 | prevent Exception when shutting down cluster [Eckart Hertzler]
| | * | | | | | | | | | | | | | | 8524468 2010-03-07 | Cleanup of onLoad [Viktor Klang]
| | * | | | | | | | | | | | | | |   e3db213 2010-03-07 | Merge branch 'master' into ticket_136 [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | d162c77 2010-03-07 | Added documentation for all methods of the Cluster trait [Viktor Klang]
| | * | | | | | | | | | | | | | | | cada63a 2010-03-07 | Making it possile to turn cluster on/off in config [Viktor Klang]
| | * | | | | | | | | | | | | | | |   0db2900 2010-03-07 | Merge branch 'master' into ticket_136 [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / /  
| | | * | | | | | | | | | | | | | | 7cd2a08 2010-03-07 | Revert change to RemoteServer port [Viktor Klang]
| | | | |_|/ / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | 36584d8 2010-03-07 | Should do the trick [Viktor Klang]
| | |/ / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | 7837640 2010-03-07 | fixed bug in using akka as dep jar in app server [Jonas Bonér]
| | * | | | | | | | | | | | | | 7bc63a4 2010-03-06 | update docs, and comments [ross.mcdonald]
| | | |_|_|_|_|_|_|/ / / / / /  
| | |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | | 9c339a8 2010-03-05 | Default-enabling JGroups [Viktor Klang]
| | | |_|_|_|_|_|/ / / / / /  
| | |/| | | | | | | | | | |   
| | * | | | | | | | | | | | 02fe5ae 2010-03-05 | do not include *QSpec.java for testing [Martin Krasser]
| | | |/ / / / / / / / / /  
| | |/| | | | | | | | | |   
| | * | | | | | | | | | | 57009b1 2010-03-05 | removed log.trace that gave bad perf [Jonas Bonér]
| | * | | | | | | | | | |   453d516 2010-03-05 | merged with master [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|_|_|_|_|/  
| | | |/| | | | | | | | |   
| | * | | | | | | | | | | efa0cc0 2010-03-05 | Fixed last persistence issues with new STM, all test pass [Jonas Bonér]
| | * | | | | | | | | | | 36c0266 2010-03-04 | Redis tests now passes with new STM + misc minor changes to Cluster [Jonas Bonér]
| | * | | | | | | | | | |   73a0648 2010-03-03 | merged with upstream [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \ \   5696320 2010-03-01 | merged with upstream [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | 21c53c2 2010-02-23 | Upgraded to Multiverse 0.4 and its 2PC CommitBarriers, all tests pass [Jonas Bonér]
| | * | | | | | | | | | | | | 28c6cc7 2010-02-23 | renamed actor api [Jonas Bonér]
| | * | | | | | | | | | | | | 87f66d0 2010-02-22 | upgraded to multiverse 0.4-SNAPSHOT [Jonas Bonér]
| | * | | | | | | | | | | | | 93f2fe0 2010-02-18 | updated to 0.4 multiverse [Jonas Bonér]
| * | | | | | | | | | | | | | 3c18a5b 2010-03-09 | enhanced test such that it uses the same actor type as slow and fast actor [Jan Van Besien]
| * | | | | | | | | | | | | | 698f704 2010-03-07 | Improved work stealing algorithm such that work is stolen only after having processed at least all our own outstanding messages. [Jan Van Besien]
| * | | | | | | | | | | | | | 2880a63 2010-03-07 | Documentation and some cleanup. [Jan Van Besien]
| * | | | | | | | | | | | | | d58b82b 2010-03-05 | removed some logging and todo comments. [Jan Van Besien]
| * | | | | | | | | | | | | |   1ef14ea 2010-03-05 | Merge commit 'upstream/master' into workstealing [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|/ / / / / / / / / /  
| | |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | | 8091b6c 2010-03-04 | Fixing a bug in JGroupsClusterActor [Viktor Klang]
| | | |_|_|/ / / / / / / / /  
| | |/| | | | | | | | | | |   
| * | | | | | | | | | | | | 05969e2 2010-03-04 | fixed differences with upstream master. [Jan Van Besien]
| * | | | | | | | | | | | | 086a28a 2010-03-04 | Merged with dispatcher improvements. Cleanup unit tests. [Jan Van Besien]
| * | | | | | | | | | | | | c7bed40 2010-03-04 | Conflicts: 	akka-core/src/main/scala/actor/Actor.scala [Jan Van Besien]
| * | | | | | | | | | | | | b52ed9b 2010-03-04 | added todo [Jan Van Besien]
| * | | | | | | | | | | | |   e708741 2010-03-04 | Merge commit 'upstream/master' [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / /  
| | * | | | | | | | | | | | 259b6c2 2010-03-03 | shutdown (and unbind) Remote Server even if the remoteServerThread is not alive [Eckart Hertzler]
| | | |_|/ / / / / / / / /  
| | |/| | | | | | | | | |   
| * | | | | | | | | | | | 3442677 2010-03-03 | Had to remove the withLock method, otherwize java.lang.AbstractMethodError at runtime. The work stealing now actually works and gives a real improvement. Actors seem to be stealing work multiple times (going back and forth between actors) though... might need to tweak that. [Jan Van Besien]
| * | | | | | | | | | | | 389004c 2010-03-03 | replaced synchronization in actor with explicit lock. Use tryLock in the dispatcher to give up immediately when the lock is already held. [Jan Van Besien]
| * | | | | | | | | | | | b04e4a4 2010-03-03 | added documentation about the intended thread safety guarantees of the isDispatching flag. [Jan Van Besien]
| * | | | | | | | | | | | 1eec870 2010-03-03 | Forgot these files... seems I have to get use to git a little still ;-) [Jan Van Besien]
| * | | | | | | | | | | | 4ee9078 2010-03-03 | first version of the work stealing idea. Added a dispatcher which considers all actors dispatched in that dispatcher part of the same pool of actors. Added a test to verify that a fast actor steals work from a slower actor. [Jan Van Besien]
| |/ / / / / / / / / / /  
| * | | | | | | | | | | d46504f 2010-03-03 | Had to revert back to synchronizing on actor when processing mailbox in dispatcher [Jonas Bonér]
| * | | | | | | | | | |   11cc8f2 2010-03-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \  
| | |_|/ / / / / / / / /  
| |/| | | | | | | | | |   
| | * | | | | | | | | | 36a9665 2010-03-02 | upgraded version in pom to 1.1 [Debasish Ghosh]
| | * | | | | | | | | |   3017f2c 2010-03-02 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| | |\ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | 9cfefa0 2010-03-02 | Fix for link(..) [Viktor Klang]
| | | | |_|_|_|/ / / / /  
| | | |/| | | | | | | |   
| | * | | | | | | | | | 10aaf55 2010-03-02 | upgraded redisclient to 1.1 - api changes, refactorings [Debasish Ghosh]
| | |/ / / / / / / / /  
| * | | | | | | | | | f3a457d 2010-03-01 | improved perf with 25 % + renamed FutureResult -> Future + Added lightweight future factory method [Jonas Bonér]
| |/ / / / / / / / /  
| * | | | | | | | | f571c07 2010-02-28 | ActorRegistry: now based on ConcurrentHashMap, now have extensive tests, now has actorFor(uuid): Option[Actor] [Jonas Bonér]
| * | | | | | | | | 9cea01d 2010-02-28 | fixed bug in aspect registry [Jonas Bonér]
| | |_|_|/ / / / /  
| |/| | | | | | |   
| * | | | | | | | 8718f5b 2010-02-26 | fixed bug with init of tx datastructs + changed actor id management [Jonas Bonér]
| | |_|/ / / / /  
| |/| | | | | |   
| * | | | | | |   32ec3b6 2010-02-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | * \ \ \ \ \ \   c52b0b0 2010-02-22 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | 009b65c 2010-02-21 | added plain english aliases for methods in CassandraSession [Eckart Hertzler]
| | | | |/ / / / /  
| | | |/| | | | |   
| | * | | | | | | 0d83c79 2010-02-22 | Cleanup [Viktor Klang]
| | |/ / / / / /  
| | * | | | | | 1093451 2010-02-19 | transactional storage access has to be through lazy vals: changed in Redis test cases [Debasish Ghosh]
| * | | | | | | 3edbc16 2010-02-23 | Added "def !!!: Future" to Actor + Futures.* with util methods [Jonas Bonér]
| * | | | | | | 4e8611f 2010-02-19 | added auto shutdown of "spawn" [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 421e87b 2010-02-18 | fixed bug with "spawn" [Jonas Bonér]
| |/ / / / /  
| * | | | |   f762be6 2010-02-17 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | f97ebfa 2010-02-17 |  [Jonas Bonér]
| * | | | | | 671ed5b 2010-02-17 | added check that transactional ref is only touched within a transaction [Jonas Bonér]
| |/ / / / /  
| * | | | |   7d7518b 2010-02-17 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | a73b66a 2010-02-17 | upgrade cassandra to 0.5.0 [Eckart Hertzler]
| * | | | | | d76d69f 2010-02-17 | added possibility to register a remote actor by explicit handle id [Jonas Bonér]
| |/ / / / /  
| * | | | |   963d76b 2010-02-17 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | a6806b7 2010-02-17 | remove old and unused 'storage-format' config element for cassandra storage [Eckart Hertzler]
| | * | | | | db1d963 2010-02-17 | fixed bug in Serializer API, added a sample test case for Serializer, added a new jar for sjson to embedded_repo [Debasish Ghosh]
| | * | | | | 91b954a 2010-02-16 | Added foreach to Cluster [Viktor Klang]
| | * | | | | 2597cb2 2010-02-16 | Restructure loader to accommodate booting from a container [Viktor Klang]
| * | | | | | d8636b4 2010-02-17 | added sample for new server-initated remote actors [Jonas Bonér]
| * | | | | | ea6274b 2010-02-16 | fixed failing tests [Jonas Bonér]
| * | | | | | 7628831 2010-02-16 | added some methods to the AspectRegistry [Jonas Bonér]
| * | | | | | 49a1d93 2010-02-16 | Added support for server-initiated remote actors with clients getting a dummy handle to the remote actor [Jonas Bonér]
| |/ / / / /  
| * | | | | 16d887f 2010-02-16 | Deployment class loader now inhertits from system class loader [Jonas Bonér]
| * | | | | dd939a0 2010-02-15 | converted tabs to spaces [Jonas Bonér]
| * | | | |   96ef70d 2010-02-15 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | c368d24 2010-02-15 | fixed some readme typo's [ross.mcdonald]
| * | | | | | c0bbcc7 2010-02-15 | Added clean automatic shutdown of RemoteClient, based on reference counting + fixed bug in shutdown of RemoteClient [Jonas Bonér]
| |/ / / / /  
| * | | | | 8829cea 2010-02-13 | Merged patterns code into module [Viktor Klang]
| * | | | | a92a90b 2010-02-13 | Added akka-patterns module [Viktor Klang]
| * | | | | 8ffed99 2010-02-12 | Moving to actor-based broadcasting, atmosphere 0.5.2 [Viktor Klang]
| * | | | |   5fa544c 2010-02-12 | Merge branch 'master' into wip-comet [Viktor Klang]
| |\ \ \ \ \  
| | * | | | | d99526f 2010-02-10 | upgrade version in akka.conf and Config.scala to 0.7-SNAPSHOT [Eckart Hertzler]
| | * | | | | ac2df50 2010-02-10 | upgrade akka version in pom to 0.7-SNAPSHOT [Eckart Hertzler]
| * | | | | | 1cc11ee 2010-02-06 | Tweaking impl [Viktor Klang]
| * | | | | |   3db0a18 2010-02-06 | Updated deps [Viktor Klang]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 9ca13d3 2010-02-06 | Upgraded Atmosphere and Jersey to 1.1.5 and 0.5.1 respectively [Viktor Klang]
| | * | | | | 42094cf 2010-02-04 | upgraded sjson to 0.4 [debasishg]
| * | | | | |   d4d0fe3 2010-02-03 | Merge with master [Viktor Klang]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 7dac0dc 2010-02-03 | Now requiring Cluster to be started and shut down manually when used outside of the Kernel [Viktor Klang]
| | * | | | | 4a14eb5 2010-02-03 |  Switched to basing it on JerseyBroadcaster for now, plus setting the correct ID [Viktor Klang]
| * | | | | | aee8d54 2010-02-02 | Tweaks [Viktor Klang]
| * | | | | | fad9610 2010-02-02 | Cleaned up cluster instantiation [Viktor Klang]
| * | | | | | 4d2afd2 2010-02-02 | Initial cleanup [Viktor Klang]
| |/ / / / /  
| * | | | | d26eb22 2010-01-30 | Updated Akka to use JerseySimpleBroadcaster via AkkaBroadcaster [Viktor Klang]
| * | | | | d9fb984 2010-01-28 | Merged enforcers [Viktor Klang]
| * | | | | 7e4bd90 2010-01-28 | Added more documentation [Viktor Klang]
| * | | | | 97c2723 2010-01-28 | Added more conf-possibilities and documentation [Viktor Klang]
| * | | | | 5d6fc7a 2010-01-28 | Added the Buildr Buildfile [Viktor Klang]
| * | | | | 45de505 2010-01-28 | Removed WS and de-commented enforcer [Viktor Klang]
| * | | | | 3be43c4 2010-01-27 | Shoal will boot, but have to add jars to cp manually cause of signing of jar vs. shade [Viktor Klang]
| * | | | | 6d6ceda 2010-01-27 | Created BasicClusterActor [Viktor Klang]
| * | | | | 95420bc 2010-01-27 | Compiles... :) [Viktor Klang]
| * | | | | 134e5e2 2010-01-24 | Added enforcer of AKKA_HOME [Viktor Klang]
| * | | | |   9df1922 2010-01-20 | merge with master [Viktor Klang]
| |\ \ \ \ \  
| | * | | | | 96d52ec 2010-01-18 | Minor code refresh [Viktor Klang]
| | * | | | | 00c71a6 2010-01-18 | Updated deps [Viktor Klang]
| | | |_|_|/  
| | |/| | |   
| * | | | | 5b949d8 2010-01-20 | Tidied sjson deps [Viktor Klang]
| * | | | | 6624709 2010-01-20 | Deactored Sender [Viktor Klang]
| |/ / / /  
| * | | | 937963f 2010-01-16 | Updated bio [Viktor Klang]
| * | | | a84f8c6 2010-01-16 | Should use the frozen jars right? [Viktor Klang]
| * | | |   83bc2eb 2010-01-16 | Merge branch 'cluster_restructure' [Viktor Klang]
| |\ \ \ \  
| | * | | | f2ef37c 2010-01-14 | Cleanup [Viktor Klang]
| | * | | |   ff0308d 2010-01-14 | Merge branch 'master' into cluster_restructure [Viktor Klang]
| | |\ \ \ \  
| | * | | | | b3f0fd7 2010-01-11 | Added Shoal and Tribes to cluster pom [Viktor Klang]
| | * | | | | 8dc1def 2010-01-11 | Added modules for Shoal and Tribes [Viktor Klang]
| | * | | | | 46441e0 2010-01-11 | Moved the cluster impls to their own modules [Viktor Klang]
| * | | | | | e6f5e9e 2010-01-16 | Actor now uses default contact address for makeRemote [Viktor Klang]
| | |/ / / /  
| |/| | | |   
| * | | | | 596c576 2010-01-13 | Queue storage is only implemented in Redis. Base trait throws UnsupportedOperationException [debasishg]
| |/ / / /  
| * | | | 9f12d05 2010-01-11 | Implemented persistent transactional queue with Redis backend [debasishg]
| * | | | 15d38ed 2010-01-09 | Added some FIXMEs for 2.8 migration [Viktor Klang]
| * | | | 2f9ef88 2010-01-06 | Added docs [Viktor Klang]
| * | | | fcc6fc7 2010-01-05 | renamed shutdown tests to spec (v0.6) [Jonas Bonér]
| * | | | dd59d32 2010-01-05 | dos2unix formatting [Jonas Bonér]
| * | | | a0fc43b 2010-01-05 | Added test for Actor shutdown, RemoteServer shutdown and Cluster shutdown [Jonas Bonér]
| * | | | c0b1168 2010-01-05 | Updated pom.xml files to new dedicated Atmosphere and Jersey JARs [Jonas Bonér]
| * | | |   d01276f 2010-01-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| | * | | | 5b19a3d 2010-01-04 | Comet fixed! JFA FTW! [Viktor Klang]
| * | | | |   f910e75 2010-01-04 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | |/ / / /  
| | * | | | 3a8b257 2010-01-04 | Fixed redisclient pom and embedded repo path [Viktor Klang]
| | * | | | d61fe7f 2010-01-04 | jndi.properties, now in jar [Viktor Klang]
| | * | | | 72f7fc3 2010-01-03 | Typo broke auth [Viktor Klang]
| * | | | | 28041ec 2010-01-04 | Fixed issue with shutting down cluster correctly + Improved chat sample README [Jonas Bonér]
| |/ / / /  
| * | | | 6b4bcee 2010-01-03 | added pretty print to chat sample [Jonas Bonér]
| | |_|/  
| |/| |   
| * | | 46e6a78 2010-01-02 | changed README [Jonas Bonér]
| * | | cc8377e 2010-01-02 | Restructured persistence modules into its own submodule [Jonas Bonér]
| * | | 9b84364 2010-01-02 | removed unecessary parent pom directive [Jonas Bonér]
| * | | 6e803d6 2010-01-02 | moved all samples into its own subproject [Jonas Bonér]
| * | | ce3315b 2010-01-02 | Fixed bug with not shutting down remote node cluster correctly [Jonas Bonér]
| * | | cb019e9 2010-01-02 | Fixed bug in shutdown management of global event-based dispatcher [Jonas Bonér]
| |/ /  
| * | cc0517d 2009-12-31 | added postRestart to RedisChatStorage [Jonas Bonér]
| * |   4815b42 2009-12-31 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \  
| | * | 4dbc410 2009-12-31 | fixed bug in 'actor' methods [Jonas Bonér]
| * | | e3bf142 2009-12-31 | fixed bug in 'actor' methods [Jonas Bonér]
| |/ /  
| * | 066cd04 2009-12-30 | refactored chat sample [Jonas Bonér]
| * | 0c445b8 2009-12-30 | refactored chat server [Jonas Bonér]
| * | 1f71e5d 2009-12-30 | Added test for forward of !! messages + Added StaticChannelPipeline for RemoteClient [Jonas Bonér]
| * |   835428e 2009-12-30 | removed tracing [Jonas Bonér]
| |\ \  
| | * | c78e24e 2009-12-29 | Fixing ticket 89 [Viktor Klang]
| * | | c4a78fb 2009-12-30 | Added registration of remote actors in declarative supervisor config + Fixed bug in remote client reconnect + Added Redis as backend for Chat sample + Added UUID utility + Misc minor other fixes [Jonas Bonér]
| |/ /  
| * | fb98c64 2009-12-29 | Fixed bug in RemoteClient reconnect, now works flawlessly + Added option to declaratively configure an Actor to be remote [Jonas Bonér]
| * | e09eaea 2009-12-29 | renamed Redis test from *Test to *Spec + removed requirement to link Actor only after start + refactored Chat sample to use mixin composition of Actor [Jonas Bonér]
| * | fc0ee72 2009-12-29 | upgraded sjson to 0.3 to handle json serialization of classes loaded through an externally specified classloader [debasishg]
| * | 202d552 2009-12-28 | fixed shutdown bug [Jonas Bonér]
| * | 0e2aaae 2009-12-28 | added README how to run the chat server sample [Jonas Bonér]
| * |   ca5d218 2009-12-28 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \  
| | * | 9811819 2009-12-28 | added redis module for persistence in parent pom [debasishg]
| * | | 41045ea 2009-12-28 | Enhanced sample chat application [Jonas Bonér]
| * | | cf3f399 2009-12-27 | added new chat server sample [Jonas Bonér]
| * | | 2db71d7 2009-12-27 | Now forward works with !! + added possibility to set a ClassLoader for the Serializer.* classes [Jonas Bonér]
| * | | e58002d 2009-12-27 | removed scaladoc [Jonas Bonér]
| * | | ef7dec4 2009-12-27 | Updated copyright header [Jonas Bonér]
| |/ /  
| * | b36bacb 2009-12-27 | fixed misc FIXMEs and TODOs [Jonas Bonér]
| * | be00b09 2009-12-27 | changed order of config elements [Jonas Bonér]
| * | 0ab13d5 2009-12-26 | Upgraded to RabbitMQ 1.7.0 [Jonas Bonér]
| * | e3ceab0 2009-12-26 | added implicit transaction family name for the atomic { .. } blocks + changed implicit sender argument to Option[Actor] (transparent change) [Jonas Bonér]
| * | ed233e4 2009-12-26 | renamed ..comet.AkkaCometServlet to ..comet.AkkaServlet [Jonas Bonér]
| * |   6005657 2009-12-26 | Merge branch 'Christmas_restructure' [Viktor Klang]
| |\ \  
| | * | c97c887 2009-12-26 | Adding docs [Viktor Klang]
| | * |   3233da1 2009-12-26 | Merge branch 'master' into Christmas_restructure [Viktor Klang]
| | |\ \  
| | * \ \   524e3df 2009-12-24 | Merge branch 'master' into Christmas_restructure [Viktor Klang]
| | |\ \ \  
| | * | | | 3cef5d8 2009-12-24 | Some renaming and some comments [Viktor Klang]
| | * | | | b7b36c2 2009-12-24 | Additional tidying [Viktor Klang]
| | * | | | d037f2a 2009-12-24 | Cleaned up the code [Viktor Klang]
| | * | | | b249e3a 2009-12-24 | Got it working! [Viktor Klang]
| | * | | | 999d287 2009-12-23 | Tweaking [Viktor Klang]
| | * | | | 512134b 2009-12-23 | Experimenting with Comet cluster support [Viktor Klang]
| | * | | | 6ff1e15 2009-12-22 | Forgot to add the Main class [Viktor Klang]
| | * | | | 4ae3341 2009-12-22 | Added Kernel class for web kernel [Viktor Klang]
| | * | | | b7a3ba1 2009-12-22 | Added possibility to use Kernel as j2ee context listener [Viktor Klang]
| | * | | |   f973498 2009-12-22 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \  
| | * | | | | 82cbafa 2009-12-22 | Christmas cleaning [Viktor Klang]
| * | | | | | df58e50 2009-12-26 | added tests for actor.forward [Jonas Bonér]
| | |_|_|/ /  
| |/| | | |   
| * | | | |   67adfd8 2009-12-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| | | |_|/ /  
| | |/| | |   
| | * | | | 2a82894 2009-12-24 | small typo to catch up with docs [ross.mcdonald]
| | * | | | f7f4063 2009-12-24 | changed default port for redis server [debasishg]
| | * | | | c9ac8da 2009-12-24 | added redis backend storage for akka transactors [debasishg]
| | | |/ /  
| | |/| |   
| * | | | 2489773 2009-12-25 | Added durable and auto-delete to AMQP [Jonas Bonér]
| |/ / /  
| * | | 44cf578 2009-12-22 | pre/postRestart now takes a Throwable as arg [Jonas Bonér]
| |/ /  
| * | 30be53a 2009-12-22 | fixed problem in aop.xml [Jonas Bonér]
| * |   eca9ab8 2009-12-22 | merged [Jonas Bonér]
| |\ \  
| | * | 0639926 2009-12-21 | reverted back to working pom files [Jonas Bonér]
| * | | 1d52915 2009-12-21 | cleaned up pom.xml files [Jonas Bonér]
| |/ /  
| * | 9b245c3 2009-12-21 | removed dbDispatch from embedded repo [Jonas Bonér]
| * | 47966db 2009-12-21 | forgot to add Cluster.scala [Jonas Bonér]
| * | 40053db 2009-12-21 | moved Cluster into akka-core + updated dbDispatch jars [Jonas Bonér]
| * |   3e3a86c 2009-12-21 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \  
| | * | 1d694d7 2009-12-18 | Updated conf aswell [Viktor Klang]
| | * | 93cdf1f 2009-12-18 | Moved Cluster package [Viktor Klang]
| | * |   3f91c3a 2009-12-18 | Merge with Atmosphere0.5 [Viktor Klang]
| | |\ \  
| | | * \   13b2e5e 2009-12-18 | merged with master [Viktor Klang]
| | | |\ \  
| | | * | | 648dcf7 2009-12-15 | Isn´t needed [Viktor Klang]
| | | * | |   e5c276e 2009-12-15 | Merge branch 'master' into Atmosphere0.5 [Viktor Klang]
| | | |\ \ \  
| | | | * \ \   69bdce1 2009-12-15 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | | |\ \ \  
| | | * | | | | 1e10ab2 2009-12-13 | removed wrongly added module [Viktor Klang]
| | | * | | | | 5d95b76 2009-12-13 | Merged with master and refined API [Viktor Klang]
| | | * | | | |   a2f5e51 2009-12-13 | Merge branch 'master' into Atmosphere0.5 [Viktor Klang]
| | | |\ \ \ \ \  
| | | | |/ / / /  
| | | * | | | | 2fbd0d0 2009-12-13 | Upgrading to latest Atmosphere API [Viktor Klang]
| | | * | | | | b0ed75d 2009-12-09 | Fixing comet support [Viktor Klang]
| | | * | | | | 2883a37 2009-12-09 | Upgraded API for Jersey and Atmosphere [Viktor Klang]
| | | * | | | |   53e85db 2009-12-09 | Merge branch 'master' into Atmosphere0.5 [Viktor Klang]
| | | |\ \ \ \ \  
| | | | | |_|_|/  
| | | | |/| | |   
| | | * | | | |   4d63c48 2009-12-03 | Merge branch 'master' into Atmosphere0.5 [Viktor Klang]
| | | |\ \ \ \ \  
| | * | \ \ \ \ \   50589bf 2009-12-18 | Merge branch 'Cluster' of git@github.com:jboner/akka into Cluster [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | * \ \ \ \ \ \   0225126 2009-12-18 | merged master [Viktor Klang]
| | | |\ \ \ \ \ \ \  
| | | | | |_|_|_|_|/  
| | | | |/| | | | |   
| | | | * | | | | |   4b294ce 2009-12-17 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | | |\ \ \ \ \ \  
| | | | * | | | | | | 63edb5b 2009-12-17 | re-adding NodeWriter [Viktor Klang]
| | | | * | | | | | |   3d5d6cd 2009-12-17 | merge with master [Viktor Klang]
| | | | |\ \ \ \ \ \ \  
| | | | * \ \ \ \ \ \ \   b086954 2009-12-16 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | | |\ \ \ \ \ \ \ \  
| | | | * | | | | | | | | 6803f46 2009-12-16 | Fixing Jersey resources shading [Viktor Klang]
| | * | | | | | | | | | | 684e8e2 2009-12-18 | Merging [Viktor Klang]
| | |/ / / / / / / / / /  
| | * | | | | | | | | | 518c449 2009-12-15 | Removed boring API method [Viktor Klang]
| | * | | | | | | | | | be22ab6 2009-12-14 | Added ask-back [Viktor Klang]
| | * | | | | | | | | | d3cf21e 2009-12-14 | fixed the API, bugs etc [Viktor Klang]
| | * | | | | | | | | | 50b5769 2009-12-14 | minor formatting edits [Jonas Bonér]
| | * | | | | | | | | |   c2edbaa 2009-12-14 | Merge branch 'Cluster' of git@github.com:jboner/akka into Cluster [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | 1da198d 2009-12-13 | A better solution for comet conflict resolve [Viktor Klang]
| | | * | | | | | | | | | 5d9a8c5 2009-12-13 | Updated to latest Atmosphere API [Viktor Klang]
| | | * | | | | | | | | | e8ace9e 2009-12-13 | Minor tweaks [Viktor Klang]
| | | * | | | | | | | | | 3d72244 2009-12-13 | Adding more comments [Viktor Klang]
| | | * | | | | | | | | | 3fcbd8f 2009-12-13 | Excluding self node from member list [Viktor Klang]
| | | * | | | | | | | | | 6bb993d 2009-12-13 |  Added additional logging and did some slight tweaks. [Viktor Klang]
| | | * | | | | | | | | |   be9713d 2009-12-13 | Merge branch 'master' into Cluster [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \ \  
| | | | | |_|_|_|_|_|_|/ /  
| | | | |/| | | | | | | |   
| | | | * | | | | | | | |   372562c 2009-12-13 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | | |\ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | a21dcc8 2009-12-09 | Adding the cluster module skeleton [Viktor Klang]
| | | | * | | | | | | | | |   594ff7c 2009-12-09 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | | |\ \ \ \ \ \ \ \ \ \  
| | | | | |_|_|_|_|_|/ / / /  
| | | | |/| | | | | | | / /   
| | | | | | |_|_|_|_|_|/ /    
| | | | | |/| | | | | | |     
| | | | * | | | | | | | | 037c3d7 2009-12-02 | Tweaked Jersey version [Viktor Klang]
| | | | * | | | | | | | |   00acc63 2009-12-02 | Fixed deps [Viktor Klang]
| | | | |\ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | 6090de6 2009-12-02 | Fixed JErsey broadcaster issue [Viktor Klang]
| | | | * | | | | | | | | | a78cea6 2009-12-02 | Added version [Viktor Klang]
| | | | * | | | | | | | | |   4519348 2009-12-02 | Merge commit 'origin/master' into Atmosphere0.5 [Viktor Klang]
| | | | |\ \ \ \ \ \ \ \ \ \  
| | | | * \ \ \ \ \ \ \ \ \ \   00dc992 2009-11-26 | Merge branch 'master' into Atmosphere5.0 [Viktor Klang]
| | | | |\ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | fd277f7 2009-11-25 | Atmosphere5.0 [Viktor Klang]
| | | * | | | | | | | | | | | | d3e7e5b 2009-12-13 | Ack, fixing the conf [Viktor Klang]
| | | * | | | | | | | | | | | | c6455cb 2009-12-13 | Sprinkling extra output for debugging [Viktor Klang]
| | | * | | | | | | | | | | | | 5ec2ab1 2009-12-12 | Working on one node anyways... [Viktor Klang]
| | | * | | | | | | | | | | | | 76ed3d6 2009-12-12 | Hooked the clustering into RemoteServer [Viktor Klang]
| | | * | | | | | | | | | | | | 7ab1b30 2009-12-12 | Tweaked logging [Viktor Klang]
| | | * | | | | | | | | | | | | 1cd01e7 2009-12-12 | Moved Cluster to akka-actors [Viktor Klang]
| | | * | | | | | | | | | | | | 18b2945 2009-12-12 | Moved cluster into akka-actor [Viktor Klang]
| | | * | | | | | | | | | | | | 4cf7aaa 2009-12-12 | Tidying some code [Viktor Klang]
| | | * | | | | | | | | | | | | d89b30c 2009-12-12 | Atleast compiles [Viktor Klang]
| | | * | | | | | | | | | | | | d73409f 2009-12-09 | Updated conf docs [Viktor Klang]
| | | * | | | | | | | | | | | | 33e7bc2 2009-12-09 | Create and link new cluster module [Viktor Klang]
| | | | |_|_|_|/ / / / / / / /  
| | | |/| | | | | | | | | | |   
| * | | | | | | | | | | | | | df54024 2009-12-21 | minor reformatting [Jonas Bonér]
| * | | | | | | | | | | | | |   9857d7d 2009-12-18 | merged in teigen's persistence structure refactoring [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \ \ \ \   34876f9 2009-12-17 | Merge branch 'master' of git@github.com:teigen/akka into ticket_82 [Jon-Anders Teigen]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|_|_|_|_|_|/ / /  
| | | |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | | | ea1eb23 2009-12-17 | #82 - Split up persistence module into a module per backend storage [Jon-Anders Teigen]
| | * | | | | | | | | | | | | | 73e27e2 2009-12-17 | #82 - Split up persistence module into a module per backend storage [Jon-Anders Teigen]
| | | |_|_|_|_|_|_|_|_|_|/ / /  
| | |/| | | | | | | | | | | |   
| * | | | | | | | | | | | | | dfb4564 2009-12-18 | renamed akka-actor to akka-core [Jonas Bonér]
| | |/ / / / / / / / / / / /  
| |/| | | | | | | | | | | |   
| * | | | | | | | | | | | | 3af46e2 2009-12-17 | fixed broken h2-lzf jar [Jonas Bonér]
| * | | | | | | | | | | | | cfc509a 2009-12-17 | upgraded many dependencies and removed some in embedded-repo that are in public repos now [Jonas Bonér]
| |/ / / / / / / / / / / /  
| * | | | | | | | | | | | d9e88e5 2009-12-17 | Removed MessageBodyWriter causing problems + added a Compression class with support for LZF compression and uncomression + added new flag to Actor defining if actor is currently dead [Jonas Bonér]
| | |_|_|_|_|_|_|_|/ / /  
| |/| | | | | | | | | |   
| * | | | | | | | | | | 1508848 2009-12-16 | renamed 'nio' package to 'remote' [Jonas Bonér]
| | |_|_|_|_|_|_|/ / /  
| |/| | | | | | | | |   
| * | | | | | | | | | 5cefd20 2009-12-15 | fixed broken runtime name of threads + added Transactor trait to some samples [Jonas Bonér]
| * | | | | | | | | | 5cf3414 2009-12-15 | minor edits [Jonas Bonér]
| * | | | | | | | | | 85d73b5 2009-12-15 | Moved {AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy} from 'actor' to 'config' [Jonas Bonér]
| | |_|_|_|_|_|_|_|/  
| |/| | | | | | | |   
| * | | | | | | | | a740e66 2009-12-15 | cleaned up kernel module pom.xml [Jonas Bonér]
| * | | | | | | | | 9d430d8 2009-12-15 | updated changes.xml [Jonas Bonér]
| * | | | | | | | | 444c1a0 2009-12-15 | added test timeout [Jonas Bonér]
| * | | | | | | | | c38c011 2009-12-15 | Fixed bug in event-driven dispatcher + fixed bug in makeRemote when run on a remote instance [Jonas Bonér]
| * | | | | | | | | 9b9bee3 2009-12-15 | Fixed bug with starting actors twice in supervisor + moved init method in actor into isRunning block + ported DataFlow module to akka actors [Jonas Bonér]
| * | | | | | | | | 04ef279 2009-12-14 | - added remote actor reply changes [Mikael Högqvist]
| * | | | | | | | |   0d2e905 2009-12-14 | Merge branch 'remotereply' [Mikael Högqvist]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | ea8963e 2009-12-14 | - Support for implicit sender with remote actors (fixes Issue #71) - The RemoteServer and RemoteClient was modified to support a clean shutdown when testing using multiple remote servers [Mikael Högqvist]
| | |/ / / / / / / /  
| * | | | | | | | | e58128f 2009-12-14 | add a jersey MessageBodyWriter that serializes scala lists to JSON arrays [Eckart Hertzler]
| * | | | | | | | | 6c4d05b 2009-12-14 | removed the Init(config) life-cycle message and the config parameters to pre/postRestart instead calling init right after start has been invoked for doing post start initialization [Jonas Bonér]
| |/ / / / / / / /  
| * | | | | | | | 3de15e3 2009-12-14 | fixed bug in dispatcher [Jonas Bonér]
| | |_|_|_|_|/ /  
| |/| | | | | |   
| * | | | | | |   6ab4b48 2009-12-13 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| | * | | | | | 045ed42 2009-12-09 | Upgraded MongoDB Java driver to 1.0 and fixed API incompatibilities [debasishg]
| * | | | | | | af0ac79 2009-12-13 | removed fork-join scheduler [Jonas Bonér]
| * | | | | | | 416bad0 2009-12-13 | Rewrote new executor based event-driven dispatcher to use actor-specific mailboxes [Jonas Bonér]
| * | | | | | | e35e958 2009-12-11 | Rewrote the dispatcher APIs and internals, now event-based dispatchers are 10x faster and much faster than Scala Actors. Added Executor and ForkJoin based dispatchers. Added a bunch of dispatcher tests. Added performance test [Jonas Bonér]
| * | | | | | | de5735a 2009-12-11 | refactored dispatcher invocation API [Jonas Bonér]
| * | | | | | | 94277df 2009-12-11 | added forward method to Actor, which forwards the message and maintains the original sender [Jonas Bonér]
| |/ / / / / /  
| * | | | | | 2af6adc 2009-12-08 | fixed actor bug related to hashcode [Jonas Bonér]
| * | | | | | ab71f38 2009-12-08 | fixed bug in storing user defined Init(config) in Actor [Jonas Bonér]
| * | | | | | 7f4d19b 2009-12-08 | changed actor message type from AnyRef to Any [Jonas Bonér]
| * | | | | | 7a8b70c 2009-12-07 | added memory footprint test + added shutdown method to Kernel + added ActorRegistry.shutdownAll to shut down all actors [Jonas Bonér]
| * | | | | |   49f4163 2009-12-07 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | | |_|_|_|/  
| | |/| | | |   
| | * | | | | e8a178e 2009-12-03 | Upgrading to Grizzly 1.9.18-i [Viktor Klang]
| * | | | | |   963f3f3 2009-12-07 | merged after reimpl of persistence API [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | e6222c7 2009-12-05 | refactoring of persistence implementation and its api [Jonas Bonér]
| * | | | | | | 143ba23 2009-12-05 | fixed bug in anon actor [Jonas Bonér]
| | |/ / / / /  
| |/| | | | |   
| * | | | | |   cee884d 2009-12-03 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| |/| | | | /   
| | | |_|_|/    
| | |/| | |     
| | * | | | 718eac0 2009-12-02 | Added jersey.version and atmosphere.version and fixed jersey broadcaster bug [Viktor Klang]
| | | |_|/  
| | |/| |   
| * | | | 96d393b 2009-12-03 | minor reformatting [jboner]
| * | | | 1b07d8b 2009-12-02 | fixed bug in start/spawnLink, now atomic [jboner]
| |/ / /  
| * | | 2bec672 2009-12-02 | removed unused jars in embedded repo, added to changes.xml [jboner]
| * | | 5c33398 2009-12-02 | fixed type in rabbitmq pom file in embedded repo [jboner]
| * | | c89d1ec 2009-12-01 | added memory footprint test [jboner]
| * | | 741a3c2 2009-11-30 | Added trapExceptions to declarative supervisor configuration [jboner]
| * | | 174ca6a 2009-11-30 | Fixed issue #35: @transactionrequired as config element in declarative config [jboner]
| * | |   0492607 2009-11-30 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\ \ \  
| | * | | 968896e 2009-11-30 | typos in modified actor [ross.mcdonald]
| * | | | 8bb3706 2009-11-30 | edit of logging [jboner]
| |/ / /  
| * | | ee0c6c6 2009-11-30 | added PersistentMap.newMap(id) and PersistinteMap.getMap(id) for Map, Vector and Ref [jboner]
| | |/  
| |/|   
| * | 4ad156d 2009-11-26 | shaped up scaladoc for transaction [jboner]
| * | 9c9490b 2009-11-26 | improved anonymous actor and atomic block syntax [jboner]
| * |   2453351 2009-11-25 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\ \  
| | * | 793741d 2009-11-25 | fixed MongoDB tests and fixed bug in transaction handling with PersistentMap [debasishg]
| * | | 8174225 2009-11-25 | Upgraded to latest Mulitverse SNAPSHOT [jboner]
| |/ /  
| * | 1b21fe6 2009-11-25 | Addded reference count for dispatcher to allow shutdown and GC of event-driven actors using the global event-driven dispatcher [jboner]
| * | 4dbd3f6 2009-11-25 | renamed RemoteServerNode -> RemoteNode [jboner]
| * | f9c9a66 2009-11-24 | removed unused dependencies [jboner]
| * | 93200be 2009-11-24 | changed remote server API to allow creating multiple servers (RemoteServer) or one (RemoteServerNode), also added a shutdown method [jboner]
| * | d1d3788 2009-11-24 | cleaned up and fixed broken error logging [jboner]
| * | b0db0b4 2009-11-23 | reverted back to original mongodb test, still failing though [jboner]
| * | 8f55ec6 2009-11-23 | Fixed problem with implicit sender + updated changes.xml [jboner]
| * | 26f97f4 2009-11-22 | cleaned up logging and error reporting [jboner]
| * | 39dcb0f 2009-11-22 | added support for LZF compression [jboner]
| * | 08cf576 2009-11-22 | added compression level config options [jboner]
| * | 7e842bd 2009-11-21 | Added zlib compression to remote actors [jboner]
| * | 8109b00 2009-11-21 | Fixed issue #46: Remote Actor should be defined by target class and UUID [jboner]
| * | 11a38c7 2009-11-21 | Cleaned up the Actor and Supervisor classes. Added implicit sender to actor ! methods, works with 'sender' field and 'reply' [jboner]
| * | b1c1c07 2009-11-20 | added stop method to actor [jboner]
| * | e9f4f9a 2009-11-20 | removed the .idea dirr [jboner]
| * | bb7c5f3 2009-11-20 | cleaned up supervisor and actor api, breaking changes [jboner]
| |/  
| * 17a8a7b 2009-11-19 | added eclipse files to .gitignore [jboner]
| *   17ad3e6 2009-11-19 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\  
| | * 1e56c6d 2009-11-18 | update package of AkkaServlet in the sample's web.xml after the refactoring of AkkaServlet [Eckart Hertzler]
| | *   817660a 2009-11-18 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | |\  
| | * | 1ef733d 2009-11-18 | Unbr0ked the comet support loading [Viktor Klang]
| * | | 83cc322 2009-11-19 | upgraded to Protobuf 2.2 and Netty 3.2-ALPHA [jboner]
| | |/  
| |/|   
| * | 888ffff 2009-11-18 | fixed bug in remote server [jboner]
| * | d7ac449 2009-11-17 | changed trapExit from Boolean to "trapExit = List(classOf[..], classOf[..])" + cleaned up security code [jboner]
| * | 495adb7 2009-11-17 | added .idea project files [jboner]
| * | 83c107d 2009-11-17 | removed idea project files [jboner]
| * |   3f08ed6 2009-11-16 | Merge branch 'master' of git@github.com:jboner/akka into dev [jboner]
| |\ \  
| | |/  
| | * e421eed 2009-11-14 | Added support for CometSupport parametrization [Viktor Klang]
| | * 06c412b 2009-11-12 | Updated Atmosphere deps [Viktor Klang]
| * | 12eda5a 2009-11-16 | added system property settings for max Multiverse speed [jboner]
| |/  
| *   28eb24d 2009-11-12 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\  
| | * 9819c02 2009-11-11 | fixed typo in comment [ross.mcdonald]
| * | c4e24b0 2009-11-12 | added changes to changes.xml [jboner]
| * | 82bcd47 2009-11-11 | fixed potential memory leak with temporary actors [jboner]
| * | 7dee21a 2009-11-11 | removed transient life-cycle and restart-within-time attribute [jboner]
| * |   765a11f 2009-11-09 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\ \  
| | |/  
| | * bc9da7b 2009-11-07 | Fixing master [Viktor Klang]
| | * 3ae77c8 2009-11-05 | bring lift dependencies into embedded repo [ross.mcdonald]
| | * e96da8f 2009-11-04 | rename our embedded repo lift dependencies [ross.mcdonald]
| | * aabeb29 2009-11-04 | bring lift 1.1-SNAPSHOT into the embedded repo [ross.mcdonald]
| | * 4a5ba56 2009-11-03 | minor change to comments [ross.mcdonald]
| * | 5b3f6bf 2009-11-04 | added lightweight actor syntax + fixed STM/persistence issue [jboner]
| |/  
| * 5f4df01 2009-11-02 | added monadic api to the transaction [jboner]
| * 927936d 2009-11-02 | added the ability to kill another actor [jboner]
| * 317c5c9 2009-11-02 | added support for finding actor by id in the actor registry + made senderFuture available to user code [jboner]
| * 59bb232 2009-10-30 | refactored and cleaned up [jboner]
| * 14fa1ac 2009-10-30 | Changed the Cassandra consistency level semantics to fit with new 0.4 nomenclature [jboner]
| * 75b1d37 2009-10-28 | renamed lifeCycleConfig to lifeCycle + fixed AMQP bug/isses [jboner]
| * 3d7eecb 2009-10-28 | cleaned up actor field access modifiers and prefixed internal fields with _ to avoid name clashes [jboner]
| * ffbe3fb 2009-10-28 | Improved AMQP module code [jboner]
| * d19a471 2009-10-27 | removed transparent serialization/deserialization on AMQP module [jboner]
| * 10ff3b9 2009-10-27 | changed AMQP messages access modifiers [jboner]
| * 8bd0209 2009-10-27 | Made the AMQP message consumer listener aware of if its is using a already defined queue or not [jboner]
| * cd7cb7a 2009-10-27 | upgrading to lift 1.1 snapshot [jboner]
| * 0a169fb 2009-10-27 | Added possibility of sending reply messages directly by sending them to the AMQP.Consumer [jboner]
| * 322a048 2009-10-27 | fixing compile errors due to api changes in multiverse [Jon-Anders Teigen]
| * a5feb95 2009-10-26 | added scaladoc for all modules in the ./doc directory [jboner]
| * 3c6c961 2009-10-26 | upgraded scaladoc module [jboner]
| *   904cf0e 2009-10-26 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\  
| | * 8cbc98a 2009-10-26 | tests can now be run with out explicitly defining AKKA_HOME [Eckart Hertzler]
| | * ca112a3 2009-10-25 | bump lift sample akka version [ross.mcdonald]
| | * 1db43e7 2009-10-24 | test cases for basic authentication actor added [Eckart Hertzler]
| * | 2f5127c 2009-10-26 | fixed issue with needing AKKA_HOME to run tests [jboner]
| * | 7790924 2009-10-26 | migrated over to ScalaTest 1.0 [jboner]
| |/  
| * d7a1944 2009-10-24 | messed with the config file [jboner]
| *   aec743e 2009-10-24 | merged with updated security sample [jboner]
| |\  
| | * e270cd9 2009-10-23 | remove old commas [ross.mcdonald]
| | * 90d7123 2009-10-23 | updated FQN of sample basic authentication service [Eckart Hertzler]
| | *   c9008f6 2009-10-23 | Merge branch 'master' of git://github.com/jboner/akka [Eckart Hertzler]
| | |\  
| | | * 433abb5 2009-10-23 | Updated FQN of Security module [Viktor Klang]
| | * | fcd5c67 2009-10-23 | add missing @DenyAll annotation [Eckart Hertzler]
| | |/  
| | *   02c6085 2009-10-23 | Merge branch 'master' of git://github.com/jboner/akka [Eckart Hertzler]
| | |\  
| | * | 09a196b 2009-10-22 | added a sample webapp for the security actors including examples for all authentication actors [Eckart Hertzler]
| * | | 472e479 2009-10-24 | upgraded to multiverse 0.3-SNAPSHOT + enriched the AMQP API [jboner]
| | |/  
| |/|   
| * | b876aa5 2009-10-22 | added API for creating and binding new queues to existing AMQP producer/consumer [jboner]
| * | 19c6521 2009-10-22 | commented out failing lift-samples module [jboner]
| * | c440d89 2009-10-22 | Added reconnection handler and config to RemoteClient [jboner]
| * | c2b659f 2009-10-21 | fixed wrong timeout semantics in actor [jboner]
| |/  
| * 58a0ec2 2009-10-21 | AMQP: added API for creating and deleting new queues for a producer and consumer [jboner]
| *   b4f2a71 2009-10-20 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\  
| | * 6921d98 2009-10-19 | Fix NullpointerException in the BasicAuth actor when called without "Authorization" header [Eckart Hertzler]
| | * c5a021e 2009-10-18 | fix a bug in the retrieval of resource level role annotation [Eckart Hertzler]
| | * 6d81f1f 2009-10-18 | Added Kerberos/SPNEGO Authentication for REST Actors [Eckart Hertzler]
| | * 425710c 2009-09-16 | Fixed misspelled XML namespace in pom. Removed twitter scala-json dependency from pom. [Odd Moller]
| * | 9f21618 2009-10-20 | commented out the persistence tests [jboner]
| * | 5ac805b 2009-10-20 | fixed SJSON bug in Mongo [jboner]
| * |   00b606b 2009-10-19 | merged with master head [jboner]
| |\ \  
| | |/  
| | * 858b219 2009-10-16 | added wrong config by mistake [jboner]
| | * 3da2d61 2009-10-14 | added NOOP serializer + fixed wrong servlet name in web.xml [jboner]
| | *   2baa653 2009-10-14 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| | |\  
| | | *   e72fcbf 2009-10-14 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | |\  
| | | * | 9df0350 2009-10-14 | Changed to only exclude jars [Viktor Klang]
| | | * | 9caf635 2009-10-13 | Added webroot [Viktor Klang]
| | * | | bcbfa84 2009-10-14 | fixed bug with using ThreadBasedDispatcher + added tests for dispatchers [jboner]
| | * | | 0f68232 2009-10-13 | changed persistent structures names [jboner]
| | | |/  
| | |/|   
| | * | 617d1a9 2009-10-13 | fixed broken remote server api [jboner]
| | * | b9262d5 2009-10-13 | fixed remote server bug [jboner]
| | |/  
| | * 169ea22 2009-10-12 | Fitted the Atmosphere Chat example onto Akka [Viktor Klang]
| | * 56dc6ea 2009-10-12 | Refactored Atmosphere support to be container agnostic + fixed a couple of NPEs [Viktor Klang]
| | *   50820db 2009-10-11 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| | |\  
| | | * e29d4b4 2009-10-11 | enhanced the RemoteServer API [jboner]
| | * | 0009c1a 2009-10-11 | enhanced the RemoteServer API [jboner]
| | |/  
| * | 3898138 2009-10-19 | upgraded to cassandra 0.4.1 [jboner]
| * | a944a03 2009-10-19 | refactored Dispatchers + made Supervisor private[akka] [jboner]
| * | 67f6a66 2009-10-19 | fixed sample problem [jboner]
| * | 887d3af 2009-10-17 | removed println [jboner]
| * |   e82998f 2009-10-17 | finalized new STM with Multiverse backend + cleaned up Active Object config and factory classes [jboner]
| |\ \  
| | |/  
| | * b047557 2009-10-08 | upgraded dependencies [Viktor Klang]
| | * a2fce53 2009-10-06 | upgraded sjson jar to 0.2 [debasishg]
| | * 64b7e59 2009-09-26 | Removed bad conf [Viktor Klang]
| * | 6728b23 2009-10-08 | renamed methods for or-else [jboner]
| * | 03fa295 2009-10-08 | stm cleanup and refactoring [jboner]
| * | eb919aa 2009-10-08 | refactored and renamed AMQP code, refactored STM, fixed persistence bugs, renamed reactor package to dispatch, added programmatic API for RemoteServer [jboner]
| * | 046fa21 2009-10-06 | fixed a bunch of persistence bugs [jboner]
| * | fe6c025 2009-10-01 | migrated storage over to cassandra 0.4 [jboner]
| * | 7a05e17 2009-09-30 | upgraded to Cassandra 0.4.0 [jboner]
| * | db57c16 2009-09-30 | moved the STM Ref to correct package [jboner]
| * | 3971bdf 2009-09-24 | adapted tests to the new STM and tx datastructures [jboner]
| * |   7a74669 2009-09-23 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\ \  
| | |/  
| | * 1d93e1e 2009-09-22 | Switched to Shade, upgraded Atmosphere, synched libs [Viktor Klang]
| | * ce0e919 2009-09-21 | Added scala-json to the embedded repo [Viktor Klang]
| * | 1690931 2009-09-23 | added camel tests [jboner]
| * | 5b2d720 2009-09-23 | added @inittransactionalstate [jboner]
| * | 98bdd93 2009-09-23 | added init tx state hook for active objects, rewrote mongodb test [jboner]
| * | 1bce709 2009-09-18 | fixed mongodb test issues [jboner]
| * |   1968dc1 2009-09-17 | merged multiverse STM rewrite with master [jboner]
| |\ \  
| | |/  
| | * 8dfc485 2009-09-12 | Changed title to Akka Transactors [jboner]
* | | b42417a 2011-04-05 | Added scripts for removing files from git's history [Jonas Bonér]
* | | 76173e3 2011-04-05 | replaced event handler with println in first tutorial [Jonas Bonér]
* | | a4a4395 2011-04-04 | Update supervisor spec [Peter Vlugter]
* | | 832568a 2011-04-04 | Add delay in typed actor lifecycle test [Peter Vlugter]
* | | 4b82c41 2011-04-04 | Specify objenesis repo directly [Peter Vlugter]
* | | cfa1c5d 2011-04-03 | Add basic documentation to Futures.{sequence,traverse} [Derek Williams]
* | | 1ef38c8 2011-04-02 | Add tests for Futures.{sequence,traverse} [Derek Williams]
* | | 7219f8e 2011-04-02 | Make sure there is no type error when used from a val [Derek Williams]
* | | bfa96ef 2011-04-02 | Bumping SBT version to 0.7.6-RC0 to fix jline problem with sbt console [Viktor Klang]
* | | c14c01a 2011-04-02 | Adding Pi2, fixing router shutdown in Pi, cleaning up the generation of workers in Pi [Viktor Klang]
* | | 64d620c 2011-04-02 | Simplified the Master/Worker interaction in first tutorial [Jonas Bonér]
* | | 5303f9f 2011-04-02 | Added the tutorial projects to the akka core project file [Jonas Bonér]
* | | 80bce15 2011-04-02 | Added missing project file for pi tutorial [Jonas Bonér]
* | | 03914a2 2011-04-01 | rewrote algo for Pi calculation to use 'map' instead of 'for-yield' [Jonas Bonér]
* | |   4ba2296 2011-04-01 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \  
| * | | 89e842b 2011-04-01 | Fix after removal of akka-sbt-plugin [Derek Williams]
| * | |   5faabc3 2011-04-01 | Merge branch 'master' into ticket-622 [Derek Williams]
| |\ \ \  
| * \ \ \   bef05fe 2011-03-30 | Merge remote-tracking branch 'origin/master' into ticket-622 [Derek Williams]
| |\ \ \ \  
| * | | | | 918b4f2 2011-03-29 | Move akka-sbt-plugin to akka-modules [Derek Williams]
* | | | | | 1eea91a 2011-04-01 | Changed Iterator to take immutable.Seq instead of mutable.Seq. Also changed Pi tutorial to use Vector instead of Array [Jonas Bonér]
| |_|/ / /  
|/| | | |   
* | | | | d859a9c 2011-04-01 | Added some comments and scaladoc to Pi tutorial sample [Jonas Bonér]
* | | | | 0149cb3 2011-04-01 | Changed logging level in ReflectiveAccess and set the default Akka log level to INFO [Jonas Bonér]
* | | | | d97b8fb 2011-04-01 | Added Routing.Broadcast message and handling to be able to broadcast a message to all the actors a load-balancer represents [Jonas Bonér]
* | | | | 384332d 2011-04-01 | removed JARs added by mistake [Jonas Bonér]
* | | | | 8e7c212 2011-04-01 | Fixed bug with not shutting down remote event handler listener properly [Jonas Bonér]
* | | | |   8ad0f07 2011-04-01 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| * | | | | e7c1325 2011-04-01 | Adding Kill message, synonymous with Restart(new ActorKilledException(msg)) [Viktor Klang]
* | | | | | 827c678 2011-04-01 | Changed *Iterator to take a Seq instead of a List [Jonas Bonér]
* | | | | | 8f4dcfe 2011-04-01 | Added first tutorial based on Scala and SBT [Jonas Bonér]
|/ / / / /  
* | | | |   b475c88 2011-03-31 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| * | | | | 72dfe47 2011-03-31 | Should should be must, should must be must [Peter Vlugter]
| * | | | | 44385d1 2011-03-31 | Rework the tests in actor/actor [Peter Vlugter]
| | |/ / /  
| |/| | |   
* | | | | cdbde3f 2011-03-31 | Added comment about broken TypedActor remoting behavior [Jonas Bonér]
|/ / / /  
* | | | 804812b 2011-03-31 | Add general mechanism for excluding tests [Peter Vlugter]
* | | |   511263c 2011-03-30 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * | | | fef54b0 2011-03-30 | More test timing adjustments [Peter Vlugter]
| * | | | de2566e 2011-03-30 | Multiply test timing in ActorModelSpec [Peter Vlugter]
| * | | | fa8809a 2011-03-30 | Multiply test timing in FSMTimingSpec [Peter Vlugter]
| * | | | 4f0c22c 2011-03-30 | Add some testing times to FSM tests (for Jenkins) [Peter Vlugter]
| * | | | 4ee194f 2011-03-29 | Adding -optimise to the compile options [Viktor Klang]
| * | | | 8bc017f 2011-03-29 | Temporarily disabling send-time-work-redistribution until I can devise a good way of avoiding a worst-case-stack-overflow [Viktor Klang]
* | | | | 2868b33 2011-03-30 | improved scaladoc in Future [Jonas Bonér]
* | | | | 3d529e8 2011-03-30 | Added check to ensure that messages are not null. Also cleaned up misc code [Jonas Bonér]
* | | | | f88a7cd 2011-03-30 | added some methods to the TypedActor context and deprecated all methods starting with 'get*' [Jonas Bonér]
|/ / / /  
* | | |   b617fcf 2011-03-29 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * | | | 3d4463f 2011-03-29 | AspectWerkz license changed to Apache 2 [Jonas Bonér]
| |/ / /  
* | | | dcd49bd 2011-03-29 | Added configuration to define capacity to the remote client buffer messages on failure to send [Jonas Bonér]
|/ / /  
* | | ec76287 2011-03-29 | Update to sbt 0.7.5.RC1 [Peter Vlugter]
* | |   4610723 2011-03-29 | Merge branch 'master' into wip-2.9.0 [Peter Vlugter]
|\ \ \  
| * \ \   ca78c55 2011-03-29 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| * | | | c70a37f 2011-03-29 | Removing warninit from project def [Viktor Klang]
* | | | |   93b92dd 2011-03-28 | Merge branch 'master' into wip-2.9.0 [Peter Vlugter]
|\ \ \ \ \  
| | |/ / /  
| |/| | |   
| * | | |   6ece561 2011-03-28 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| | * \ \ \   e251684 2011-03-28 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \  
| * | \ \ \ \   9cdf58a 2011-03-28 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| |/| / / / /   
| | |/ / / /    
| | * | | |   a640e43 2011-03-28 | Merge branch 'wip_resend_message_on_remote_failure' [Jonas Bonér]
| | |\ \ \ \  
| | | |/ / /  
| | |/| | |   
| * | | | |   0412ae4 2011-03-28 | Renamed config option for remote client retry message send. [Jonas Bonér]
| |\ \ \ \ \  
| | |/ / / /  
| |/| / / /   
| | |/ / /    
| | * | | 2538f57 2011-03-28 | Add sudo directly to network tests [Peter Vlugter]
| | * | | 28e4a23 2011-03-28 | Add system property to enable network failure tests [Peter Vlugter]
| | * | | cf80e6a 2011-03-27 | 1. Added config option to enable/disable the remote client transaction log for resending failed messages. 2. Swallows exceptions on appending to transaction log and do not complete the Future matching the message. [Jonas Bonér]
| | * | | e6c658f 2011-03-25 | Changed UnknownRemoteException to CannotInstantiateRemoteExceptionDueToRemoteProtocolParsingErrorException - should be more clear now. [Jonas Bonér]
| | * | | 0d23cf1 2011-03-25 | added script to simulate network failure scenarios and restore original settings [Jonas Bonér]
| | * | | 5a9bfe9 2011-03-25 | 1. Fixed issues with remote message tx log. 2. Added trait for network failure testing that supports 'TCP RST', 'TCP DENY' and message throttling/delay. 3. Added test for the remote transaction log. Both for TCP RST and TCP DENY. [Jonas Bonér]
| | * | | 331b5c7 2011-03-25 | Added accessor for pending messages [Jonas Bonér]
| | * | |   4578dd2 2011-03-24 | merged with upstream [Jonas Bonér]
| | |\ \ \  
| | | * | | a92fd83 2011-03-24 | 1. Added a 'pending-messages' tx log for all pending messages that are not yet delivered to the remote host, this tx log is retried upon successful remote client reconnect. 2. Fixed broken code in UnparsableException and renamed it to UnknownRemoteException. [Jonas Bonér]
| | * | | | f2ebd7a 2011-03-24 | 1. Added a 'pending-messages' tx log for all pending messages that are not yet delivered to the remote host, this tx log is retried upon successful remote client reconnect. 2. Fixed broken code in UnparsableException and renamed it to UnknownRemoteException. [Jonas Bonér]
| | |/ / /  
| | * | | 6449fa9 2011-03-24 | refactored remote event handler and added deregistration of it on remote shutdown [Jonas Bonér]
| | * | | cb0f14a 2011-03-24 | Added a remote event handler that pipes remote server and client events to the standard EventHandler system [Jonas Bonér]
* | | | | d6dc4c9 2011-03-28 | Update plugins [Peter Vlugter]
* | | | | eb1b071 2011-03-28 | Re-enable ants sample [Peter Vlugter]
* | | | |   05903a4 2011-03-28 | Merge branch 'master' into wip-2.9.0 [Peter Vlugter]
|\ \ \ \ \  
| |/ / / /  
| * | | | f853c05 2011-03-27 | Potential fix for #723 [Viktor Klang]
* | | | | 3f468f6 2011-03-26 | Upgrading to Scala 2.9.0-RC1 [Viktor Klang]
* | | | |   0c6cefb 2011-03-26 | Merging in master [Viktor Klang]
|\ \ \ \ \  
| |/ / / /  
| * | | |   65a6953 2011-03-26 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| |\ \ \ \  
| | * | | | fca98aa 2011-03-25 | Removing race in isDefinedAt and in apply, closing ticket #722 [Viktor Klang]
| * | | | | a34e9d0 2011-03-26 | changed version of sjson to 0.10 [Debasish Ghosh]
| |/ / / /  
| * | | | 865566a 2011-03-25 | Closing ticket #721, shutting down the VM if theres a broken config supplied [Viktor Klang]
| * | | | 85bd43f 2011-03-25 | Add a couple more test timing adjustments [Peter Vlugter]
| * | | | fb8625c 2011-03-25 | Replace sleep with latch in valueWithin test [Peter Vlugter]
| * | | | d8c07d0 2011-03-25 | Introduce testing time factor (for Jenkins builds) [Peter Vlugter]
| * | | | 6e5284c 2011-03-25 | Fix race in ActorModelSpec [Peter Vlugter]
| * | | | b4021e6 2011-03-25 | Catch possible actor init exceptions [Peter Vlugter]
| * | | | 110a07a 2011-03-25 | Fix race with PoisonPill [Peter Vlugter]
| * | | | 83d355a 2011-03-24 | Fixing order-of-initialization-bug [Viktor Klang]
| |/ / /  
| * | |   03ad1ac 2011-03-24 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \  
| | * \ \   c22a240 2011-03-23 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \  
| | * | | | 518e23b 2011-03-23 | Moving Initializer to akka-kernel, add manually for other uses, removing ListWriter, changing akka-http to depend on akka-actor instead of akka-remote, closing ticket #716 [Viktor Klang]
| * | | | | 472577a 2011-03-24 | moved slf4j to 'akka.event.slf4j' [Jonas Bonér]
| * | | | | 529dd3d 2011-03-23 | added error reporting to the ReflectiveAccess object [Jonas Bonér]
| | |/ / /  
| |/| | |   
| * | | |   a8991ac 2011-03-23 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| | |/ / /  
| | * | | dfbc694 2011-03-23 | Adding synchronous writes to NettyRemoteSupport [Viktor Klang]
| | * | | 1747f21 2011-03-23 | Removing printlns [Viktor Klang]
| | * | |   7efe2dc 2011-03-23 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \  
| | * | | | db1b50a 2011-03-23 | Adding OrderedMemoryAwareThreadPoolExecutor with an ExecutionHandler to the NettyRemoteServer [Viktor Klang]
| * | | | | 6429086 2011-03-23 | Moved EventHandler to 'akka.event' plus added 'error' method without exception param [Jonas Bonér]
| | |/ / /  
| |/| | |   
| * | | | 74f7d47 2011-03-23 | Remove akka-specific transaction and hooks [Peter Vlugter]
| |/ / /  
| * | | f79d5c4 2011-03-23 | Deprecating the current impl of DataFlowVariable [Viktor Klang]
| * | | 6f560a7 2011-03-22 | Switched to FutureTimeoutException for Future.apply/Future.get [Viktor Klang]
| * | |   9ed3bb2 2011-03-22 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * \ \   26654d3 2011-03-22 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \  
| | * | | | 417695f 2011-03-22 | Added SLF4 module with Logging trait and Event Handler [Jonas Bonér]
| * | | | | e23ba6e 2011-03-22 | Adding Java API for reduce, fold, apply and firstCompletedOf, adding << and apply() to CompletableFuture + a lot of docs [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | | 331f3e6 2011-03-22 | Renaming resultWithin to valueWithin, awaitResult to awaitValue to aling the naming, and then deprecating the blocking methods in Futures [Viktor Klang]
| * | | | f63024b 2011-03-22 | Switching AlreadyCompletedFuture to always be expired, good for GC eligibility etc [Viktor Klang]
* | | | | 1d8c954 2011-03-22 | Updating to Scala 2.9.0, SJSON still needs to be released for 2.9.0-SNAPSHOT tho [Viktor Klang]
|/ / / /  
* | | |   967f81c 2011-03-22 | Merge branch '667-krasserm' [Martin Krasser]
|\ \ \ \  
| * | | | 63be885 2011-03-17 | Preliminary upgrade to latest Camel development snapshot. [Martin Krasser]
* | | | | 161f683 2011-03-21 | Minor optimization for getClassFor and added some comments [Viktor Klang]
| |/ / /  
|/| | |   
* | | | 24981f6 2011-03-21 | Fixing classloader priority loading [Viktor Klang]
* | | |   99492ae 2011-03-21 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \  
| * | | | 54b1963 2011-03-20 | Prevent throwables thrown in futures from disrupting the rest of the system. Fixes #710 [Derek Williams]
| * | | | 5573b59 2011-03-20 | added test cases for Java serialization of actors in course of documenting the stuff in the wiki [Debasish Ghosh]
* | | | | e33eefa 2011-03-20 | Rewriting getClassFor to do a fall-back approach, first test the specified classloader, then test the current threads context loader, then try the ReflectiveAccess` classloader and the Class.forName [Viktor Klang]
|/ / / /  
* | | |   e5bd97f 2011-03-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \  
| * \ \ \   233044f 2011-03-19 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| * | | | | c27ee29 2011-03-19 | added event handler logging + minor reformatting and cleanup [Jonas Bonér]
| * | | | | 2ecebc5 2011-03-19 | removed some println [Jonas Bonér]
| * | | | | 299f865 2011-03-18 | Fixed bug with restarting supervised supervisor that had done linking in constructor + Changed all calls to EventHandler to use direct 'error' and 'warning' methods for improved performance [Jonas Bonér]
* | | | | | c520104 2011-03-19 | Giving a 1s time window for the requested change to occur [Viktor Klang]
| |/ / / /  
|/| | | |   
* | | | |   25660a9 2011-03-18 | Resolving conflict [Viktor Klang]
|\ \ \ \ \  
| |/ / / /  
| * | | | 9e3e3ef 2011-03-18 | Added hierarchical event handler level to generic event publishing [Jonas Bonér]
* | | | | d738674 2011-03-18 | Switching to PoisonPill to shut down Per-Session actors, and restructuring some Future-code to avoid wasteful object creation [Viktor Klang]
* | | | | 44c7ed6 2011-03-18 | Removing 2 vars from Future, and adding some ScalaDoc [Viktor Klang]
* | | | | d903498 2011-03-18 | Making thread transient in Event and adding WTF comment [Viktor Klang]
|/ / / /  
* | | |   2752d7a 2011-03-18 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \  
| * | | | f1de4dc 2011-03-18 | Fix for event handler levels [Peter Vlugter]
* | | | | efb2855 2011-03-18 |  Removing verbose type annotation [Viktor Klang]
* | | | | 76f058e 2011-03-18 | Fixing stall issue in remote pipeline [Viktor Klang]
* | | | | 12b91ed 2011-03-18 | Reducing overhead and locking involved in Futures.fold and Futures.reduce [Viktor Klang]
* | | | |   5a399e8 2011-03-17 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| |/ / / /  
| * | | |   0604964 2011-03-17 | Merge branch 'wip-CallingThreadDispatcher' [Roland Kuhn]
| |\ \ \ \  
| | * | | | 18a6046 2011-03-17 | make FSMTimingSpec more deterministic [Roland Kuhn]
| | * | | | d20d85c 2011-03-17 | ignore VIM swap files (and clean up previous accident) [Roland Kuhn]
| | * | | | 2693a35 2011-03-06 | add locking to CTD-mbox [Roland Kuhn]
| | * | | | 2deb47f 2011-03-06 | add test to ActorModelSpec [Roland Kuhn]
| | * | | | 50b2c14 2011-03-05 | create akka-testkit subproject [Roland Kuhn]
| | * | | | 872c44c 2011-02-20 | first shot at CallingThreadDispatcher [Roland Kuhn]
* | | | | | d935965 2011-03-16 | Making sure that theres no allocation for ActorRef.invoke() [Viktor Klang]
* | | | | | f1654a3 2011-03-16 | Adding yet another comment to ActorPool [Viktor Klang]
* | | | | | 1093c21 2011-03-16 | Faster than Derek! Changing completeWith(Future) to be lazy and not eager [Viktor Klang]
* | | | | | 98c35ec 2011-03-16 | Added some more comments to ActorPool [Viktor Klang]
* | | | | | 04a72b6 2011-03-16 | ActorPool code cleanup, fixing some qmarks and some minor defects [Viktor Klang]
|/ / / / /  
* | | | | d9ca436 2011-03-16 | Restructuring some methods in ActorPool, and switch to PoisonPill for postStop cleanup, to let workers finish their tasks before shutting down [Viktor Klang]
* | | | | ab15ac0 2011-03-16 | Refactoring, reformatting and fixes to ActorPool, including ticket 705 [Viktor Klang]
* | | | | 67e1cb2 2011-03-16 | Fixing #706 [Viktor Klang]
* | | | | 44659d2 2011-03-16 | Just saved 3 allocations per Actor instance [Viktor Klang]
* | | | | 83748ad 2011-03-15 | Switching to unfair locking [Viktor Klang]
* | | | | ec42d71 2011-03-15 | Adding a test for ticket 703 [Viktor Klang]
* | | | | 8256672 2011-03-15 | No, seriously, fixing ticket #703 [Viktor Klang]
* | | | | 8baa62b 2011-03-14 | Upgrading the fix for overloading and TypedActors [Viktor Klang]
* | | | | 7f3a727 2011-03-14 | Moving AkkaLoader from akka.servlet in akka-http to akka.util, closing ticket #701 [Viktor Klang]
* | | | | ac51509 2011-03-14 | Removign leftover debug statement. My bad. [Viktor Klang]
* | | | |   0899fa4 2011-03-14 | Merge with master [Viktor Klang]
|\ \ \ \ \  
| * | | | | 0ec4d18 2011-03-14 | changed event handler dispatcher name [Jonas Bonér]
| * | | | | 8264ba8 2011-03-14 | Added generic event handler [Jonas Bonér]
| * | | | |   978ec62 2011-03-14 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| * | | | | | f8ce3d5 2011-03-14 | Changed API for EventHandler and added support for log levels [Jonas Bonér]
* | | | | | | 2c1ea69 2011-03-14 | Fixing ticket #703 and reformatting Pool.scala [Viktor Klang]
* | | | | | | 5629281 2011-03-14 | Adding a unit test for ticket 552, but havent solved the ticket [Viktor Klang]
* | | | | | | 2a390d3 2011-03-14 | All tests pass, might actually have solved the typed actor method resolution issue [Viktor Klang]
* | | | | | | cce0fa8 2011-03-14 | Pulling out _resolveMethod_ from NettyRemoteSupport and moving it into ReflectiveAccess [Viktor Klang]
* | | | | | | b5b46aa 2011-03-14 | Potential fix for the remote dispatch of TypedActor methods when overloading is used. [Viktor Klang]
* | | | | | | 49338dc 2011-03-14 | Fixing ReadTimeoutException, and implement proper shutdown after timeout [Viktor Klang]
| |/ / / / /  
|/| | | | |   
* | | | | |   af97128 2011-03-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| | |_|/ / /  
| |/| | | |   
| * | | | |   bfb5fe9 2011-03-13 | Merge branch '647-krasserm' [Martin Krasser]
| |\ \ \ \ \  
| | * | | | | 1759150 2011-03-07 | Dropped dependency to AspectInitRegistry and usage of internal registry in TypedActorComponent [Martin Krasser]
* | | | | | | 669a2b8 2011-03-14 | Reverting change to SynchronousQueue [Viktor Klang]
* | | | | | | dfca401 2011-03-14 | Revert "Switching ThreadBasedDispatcher to use SynchronousQueue since only one actor should be in it" [Viktor Klang]
|/ / / / / /  
* | | | | | 36a612e 2011-03-11 | Switching ThreadBasedDispatcher to use SynchronousQueue since only one actor should be in it [Viktor Klang]
* | | | | |   5ba947c 2011-03-11 | Merge branch 'future-covariant' [Derek Williams]
|\ \ \ \ \ \  
| * | | | | | 2cdfa43 2011-03-11 | Improve Future API when using UntypedActors, and add overloads for Java API [Derek Williams]
* | | | | | | 1053202 2011-03-11 | Optimization for the mostly used mailbox, switch to non-blocking queue [Viktor Klang]
* | | | | | | 0d740db 2011-03-11 | Beefed up the concurrency level for the mailbox tests [Viktor Klang]
* | | | | | | a743dcf 2011-03-11 | Adding a rather untested BoundedBlockingQueue to wrap PriorityQueue for BoundedPriorityMessageQueue [Viktor Klang]
|/ / / / / /  
* | | | | | 5d3b669 2011-03-10 | Deprecating client-managed TypedActor [Viktor Klang]
* | | | | | e588a2c 2011-03-10 | Deprecating Client-managed remote actors [Viktor Klang]
* | | | | | 00dea71 2011-03-10 | Commented out the BoundedPriorityMailbox, since it wasn´t bounded, and broke out the mailbox logic into PriorityMailbox [Viktor Klang]
* | | | | | 0b5858f 2011-03-09 | Adding PriorityExecutorBasedEventDrivenDispatcher [Viktor Klang]
* | | | | | 42cfe27 2011-03-09 | Adding unbounded and bounded MessageQueues based on PriorityBlockingQueue [Viktor Klang]
* | | | | | 9f4144c 2011-03-09 | Changing order as to avoid DNS lookup in worst-case scenario [Viktor Klang]
* | | | | |   6d3a28d 2011-03-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| * \ \ \ \ \   f0bc68d 2011-03-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \  
* | \ \ \ \ \ \   1301e30 2011-03-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
|/| / / / / / /   
| |/ / / / / /    
| * | | | | | 671712a 2011-03-09 | Add future and await to agent [Peter Vlugter]
| | |/ / / /  
| |/| | | |   
* | | | | | f8e9c61 2011-03-09 | Removing legacy, non-functional, SSL support from akka-remote [Viktor Klang]
|/ / / / /  
* | | | | 39caa29 2011-03-09 | Fix problems with config lists and default config [Peter Vlugter]
* | | | | 9a0c103 2011-03-08 | Removing the use of embedded-repo and deleting it, closing ticket #623 [Viktor Klang]
* | | | |   5f05cc3 2011-03-08 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| * | | | | 45342b4 2011-03-08 | Adding ModuleConfiguration for net.debasishg [Viktor Klang]
* | | | | | ef69b5c 2011-03-08 | Adding ModuleConfiguration for net.debasishg [Viktor Klang]
|/ / / / /  
* | | | | 93470f3 2011-03-08 | Removing ssl options since SSL isnt ready yet [Viktor Klang]
* | | | | 28bce69 2011-03-08 | closes #689: All properties from the configuration file are unit-tested now. [Heiko Seeberger]
* | | | | ebc8ccb 2011-03-08 | re #689: Verified config for akka-stm. [Heiko Seeberger]
* | | | | 95c2b04 2011-03-08 | re #689: Verified config for akka-remote. [Heiko Seeberger]
* | | | | 9d63964 2011-03-08 | re #689: Verified config for akka-http. [Heiko Seeberger]
* | | | | 031b5c2 2011-03-08 | re #689: Verified config for akka-actor. [Heiko Seeberger]
* | | | |   3130025 2011-03-08 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| * | | | | a4fbc02 2011-03-08 | Reduce config footprint [Peter Vlugter]
* | | | | | e75439d 2011-03-08 | Removing SBinary artifacts from embedded repo [Viktor Klang]
|/ / / / /  
* | | | | 513e5de 2011-03-08 | Removing support for SBinary as per #686 [Viktor Klang]
* | | | | 7b2e2a6 2011-03-08 | Removing dead code [Viktor Klang]
* | | | | 28090e2 2011-03-08 | Reverting fix for due to design flaw in *ByUuid [Viktor Klang]
* | | | | 41864b2 2011-03-07 | Remove uneeded parameter [Derek Williams]
* | | | | 442ab0f 2011-03-07 | Fix calls to EventHandler [Derek Williams]
* | | | |   0a7122d 2011-03-07 | Merge branch 'master' into derekjw-future-dispatch [Derek Williams]
|\ \ \ \ \  
| * | | | | 25ce71f 2011-03-07 | Fixing #655: Stopping all actors connected to remote server on shutdown [Viktor Klang]
| * | | | | 8d2dc68 2011-03-07 | Removing non-needed jersey module configuration [Viktor Klang]
| * | | | |   f5a03ff 2011-03-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \  
| | * \ \ \ \   0aa197d 2011-03-07 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \  
| | | * \ \ \ \   af5892c 2011-03-07 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | | |\ \ \ \ \  
| | | | |/ / / /  
| | * | | | | |   bc5449d 2011-03-07 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \  
| | | |/ / / / /  
| | |/| / / / /   
| | | |/ / / /    
| | * | | | | 2168e86 2011-03-07 | Changed event handler config to a list of the FQN of listeners [Jonas Bonér]
| * | | | | | 1c15e26 2011-03-07 | Moving most of the Jersey and Jetty deps into MicroKernel (akka-modules), closing #593 [Viktor Klang]
| | |/ / / /  
| |/| | | |   
| * | | | | 9b71830 2011-03-06 | Tweaking AkkaException [Viktor Klang]
| * | | | | f8b9a73 2011-03-06 | Adding export of the embedded uuid lib to the OSGi manifest [Viktor Klang]
| * | | | | 2d0fa75 2011-03-05 | reverting changes to avoid breaking serialization [Viktor Klang]
| * | | | | a1218b6 2011-03-05 |  Speeding up remote tests by removing superfluous Thread.sleep [Viktor Klang]
| * | | | | 0375e78 2011-03-05 | Removed some superfluous code [Viktor Klang]
| * | | | | 28e2941 2011-03-05 | Add Future GC comment [Viktor Klang]
| * | | | | 3bd83e5 2011-03-05 | Adding support for clean exit of remote server [Viktor Klang]
| * | | | | fd6c879 2011-03-05 | Updating the Remote protocol to support control messages [Viktor Klang]
| * | | | | 9e95143 2011-03-04 | Adding support for MessageDispatcherConfigurator, which means that you can configure homegrown dispatchers in akka.conf [Viktor Klang]
| * | | | | 12dfba8 2011-03-05 | Fixed #675 : preStart() is called twice when creating new instance of TypedActor [Debasish Ghosh]
| * | | | | 48d06de 2011-03-05 | fixed repo of scalatest which was incorrectly pointing to ScalaToolsSnapshot [Debasish Ghosh]
| |/ / / /  
* | | | | 949dbff 2011-03-04 | Use scalatools release repo for scalatest [Derek Williams]
* | | | |   15449e9 2011-03-04 | Merge branch 'master' into derekjw-future-dispatch [Derek Williams]
|\ \ \ \ \  
| |/ / / /  
| * | | | 53c824d 2011-03-04 | Remove logback config [Peter Vlugter]
| * | | |   0b2508b 2011-03-04 | merged with upstream [Jonas Bonér]
| |\ \ \ \  
| * | | | | e845835 2011-03-04 | reverted tests supported 2.9.0 to 2.8.1 [Jonas Bonér]
| * | | | |   5789a36 2011-03-04 | Merge branch '0deps', remote branch 'origin' into 0deps [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | 465e064 2011-03-04 | Update Java STM API to include STM utils [Peter Vlugter]
| * | | | | | af22be7 2011-03-04 | reverting back to 2.8.1 [Jonas Bonér]
* | | | | | | ccd68bf 2011-03-03 | Cleaner exception matching in tests [Derek Williams]
* | | | | | |   eb4de86 2011-03-03 | Merge remote-tracking branch 'origin/0deps' into 0deps-future-dispatch [Derek Williams]
|\ \ \ \ \ \ \  
| | |_|/ / / /  
| |/| | | | |   
| * | | | | | c4fa953 2011-03-03 | Removing shutdownLinkedActors, making linkedActors and getLinkedActors public, fixing checkinit problem in AkkaException [Viktor Klang]
| * | | | | |   044ea59 2011-03-03 | Merge remote branch 'origin/0deps' into 0deps [Viktor Klang]
| |\ \ \ \ \ \  
| | * | | | | | 282e245 2011-03-03 | Remove configgy from embedded repo [Peter Vlugter]
| | |/ / / / /  
| * | | | | | 1767624 2011-03-03 | Removing Thrift jars [Viktor Klang]
| |/ / / / /  
| * | | | | 7fb6958 2011-03-03 | Incorporate configgy with some renaming and stripping down [Peter Vlugter]
| * | | | | 9dc3bbc 2011-03-02 | Add configgy sources under akka package [Peter Vlugter]
* | | | | | 1a671c2 2011-03-02 | remove debugging line from test [Derek Williams]
* | | | | | 9a05770 2011-03-02 | Fix test after merge [Derek Williams]
* | | | | |   48a41cf 2011-03-02 | Merge remote-tracking branch 'origin/0deps' into 0deps-future-dispatch [Derek Williams]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | | 283a5b4 2011-03-02 | Updating to Scala 2.9.0 and enabling -optimise and -Xcheckinit [Viktor Klang]
| * | | | |   d8c556b 2011-03-02 | Merge remote branch 'origin/0deps' into 0deps [Viktor Klang]
| |\ \ \ \ \  
| | * \ \ \ \   9acb511 2011-03-02 | merged with upstream [Jonas Bonér]
| | |\ \ \ \ \  
| | * | | | | | 793ad9a 2011-03-02 | Renamed to EventHandler and added 'info, debug, warning and error' [Jonas Bonér]
| | * | | | | |   a41fd15 2011-03-02 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \  
| | | | |/ / / /  
| | | |/| | | |   
| | * | | | | | c4e2d73 2011-03-02 | Added the ErrorHandler notifications to all try-catch blocks [Jonas Bonér]
| | * | | | | | 2127e80 2011-03-01 | Added ErrorHandler and a default listener which prints error logging to STDOUT [Jonas Bonér]
| | * | | | | | 67ac8a5 2011-02-28 | tabs to spaces [Jonas Bonér]
| | * | | | | | 9354f07 2011-02-28 | Removed logging [Jonas Bonér]
| * | | | | | | 1e72fbf 2011-03-02 | Potential fix for #672 [Viktor Klang]
| | |_|/ / / /  
| |/| | | | |   
| * | | | | | 7582b1e 2011-03-02 | Upding Jackson to 1.7 and commons-io to 2.0.1 [Viktor Klang]
| * | | | | | b0b15fd 2011-03-02 | Removing wasteful guarding in spawn/*Link methods [Viktor Klang]
| * | | | | | dc52add 2011-03-02 | Removing 4 unused dependencies [Viktor Klang]
| * | | | | | bbc390c 2011-03-02 | Removing old versions of Configgy [Viktor Klang]
| * | | | | | 407b631 2011-03-02 | Embedding the Uuid lib, deleting it from the embedded repo and dropping the jsr166z.jar [Viktor Klang]
| * | | | | | c9b699d 2011-03-02 | Rework of WorkStealer done, also, removal of DB Dispatch [Viktor Klang]
| * | | | | |   c371876 2011-03-02 | Merge branch 'master' of github.com:jboner/akka into 0deps [Viktor Klang]
| |\ \ \ \ \ \  
| | | |/ / / /  
| | |/| | | |   
| * | | | | |   689e3bd 2011-02-27 | Merge branch 'master' into 0deps [Viktor Klang]
| |\ \ \ \ \ \  
| | | |/ / / /  
| | |/| | | |   
| * | | | | | 5373b0b 2011-02-27 | Optimizing for bestcase when sending an actor a message [Viktor Klang]
| * | | | | | 2fea981 2011-02-27 | Removing logging from EBEDD [Viktor Klang]
| * | | | | | 4a232b1 2011-02-27 | Removing HawtDispatch, the old WorkStealing dispatcher, replace old workstealer with new workstealer based on EBEDD, and remove jsr166x dependency, only 3 more deps to go until 0 deps for akka-actor [Viktor Klang]
* | | | | | |   0a35d14 2011-03-01 | Merge branch 'master' into derekjw-future-dispatch [Derek Williams]
|\ \ \ \ \ \ \  
| | |_|/ / / /  
| |/| | | | |   
| * | | | | | 632848d 2011-03-01 | update Buncher to make it more generic [Roland Kuhn]
| * | | | | |   994963a 2011-03-01 | Merge branch 'master' into 669-krasserm [Martin Krasser]
| |\ \ \ \ \ \  
| | * | | | | | 1bac62e 2011-03-01 | now using sjson without scalaz dependency [Debasish Ghosh]
| | | |/ / / /  
| | |/| | | |   
| * | | | | | a76bc26 2011-03-01 | Reset currentMessage if InterruptedException is thrown [Martin Krasser]
| * | | | | | 20ccd02 2011-03-01 | Ensure proper cleanup even if postStop throws an exception. [Martin Krasser]
| * | | | | | 2aa8cb1 2011-03-01 | Support self.reply in preRestart and postStop after exception in receive. Closes #669 [Martin Krasser]
| |/ / / / /  
| * | | | |   359a63c 2011-02-26 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| |\ \ \ \ \  
| * | | | | | 35dae7f 2011-02-26 | removed sjson from embedded_repo. Now available from scala-tools. Upgraded sjson version to 0.9 which compiles on Scala 2.8.1 [Debasish Ghosh]
* | | | | | | 9d3bc38 2011-03-01 | Add first test of Future in Java [Derek Williams]
* | | | | | | 3de14d2 2011-02-28 | Add java friendly methods [Derek Williams]
* | | | | | | e1c3431 2011-02-28 | Reverse listeners before executing them [Derek Williams]
* | | | | | | 92a858e 2011-02-28 | Add locking in dispatchFuture [Derek Williams]
* | | | | | | 4bd00bc 2011-02-28 | Add friendlier method of starting a Future [Derek Williams]
* | | | | | | ac2462f 2011-02-28 | move unneeded test outside of if statement [Derek Williams]
* | | | | | | d74dc4b 2011-02-28 | Add low priority implicit for the default dispatcher [Derek Williams]
* | | | | | | 3ef23c7 2011-02-28 | no need to hold onto the lock to throw the exception [Derek Williams]
* | | | | | | 70cc5b7 2011-02-28 | Revert lock bypass. move lock calls outside of try block [Derek Williams]
* | | | | | | 2409d98 2011-02-27 | bypass lock when not needed [Derek Williams]
* | | | | | | 390fd42 2011-02-26 | removed sjson from embedded_repo. Now available from scala-tools. Upgraded sjson version to 0.9 which compiles on Scala 2.8.1 [Debasish Ghosh]
* | | | | | | 81ce1a1 2011-02-25 | Reorder Futures.future params to take better advantage of default values [Derek Williams]
* | | | | | | 3c70224 2011-02-25 | Reorder Futures.future params to take better advantage of default values [Derek Williams]
* | | | | | | a62f065 2011-02-25 | Can't share uuid lists [Derek Williams]
* | | | | | |   261bd23 2011-02-25 | Merge branch 'master' into derekjw-future-dispatch [Derek Williams]
|\ \ \ \ \ \ \  
| | |/ / / / /  
| |/| | | | |   
| * | | | | | 60dbbfc 2011-02-25 | Fix for timeout not being inherited from the builder future [Derek Williams]
| | |/ / / /  
| |/| | | |   
* | | | | | f459b16 2011-02-25 | Run independent futures on the dispatcher directly [Derek Williams]
|/ / / / /  
* | | | | 837124c 2011-02-25 | Specialized traverse and sequence methods for Traversable[Future[A]] => Future[Traversable[A]] [Derek Williams]
|/ / / /  
* | | | 98f897c 2011-02-23 | document some methods on Future [Derek Williams]
* | | | 3c99a83 2011-02-24 | Removing method that shouldve been removed in 1.0: startLinkRemote [Viktor Klang]
* | | | 56a387e 2011-02-24 | Removing method that shouldve been removed in 1.0: startLinkRemote [Viktor Klang]
* | | |   8fd982b 2011-02-23 | Merge branch 'derekjw-future' [Derek Williams]
|\ \ \ \  
| * | | | 67eae37 2011-02-22 | Reduce allocations [Derek Williams]
| * | | |   71a4e0c 2011-02-22 | Merge branch 'master' into derekjw-future [Derek Williams]
| |\ \ \ \  
| * | | | | 88c1a7a 2011-02-22 | Add test for folding futures by composing [Derek Williams]
| * | | | | 2ee5d9f 2011-02-22 | Add test for composing futures [Derek Williams]
| * | | | | bbe2a3b 2011-02-21 | add Future.filter for use in for comprehensions [Derek Williams]
| * | | | | cc1755f 2011-02-21 | Add methods to Future for map, flatMap, and foreach [Derek Williams]
* | | | | | ba8c187 2011-02-23 | Fixing bug in ifOffYield [Viktor Klang]
| |/ / / /  
|/| | | |   
* | | | | 169d97e 2011-02-22 | Fixing a regression in Actor [Viktor Klang]
* | | | |   e2fc947 2011-02-22 | Merge branch 'wip-ebedd-tune' [Viktor Klang]
|\ \ \ \ \  
| |/ / / /  
|/| | | |   
| * | | | 994736f 2011-02-21 | Added some minor migration comments for Scala 2.9.0 [Viktor Klang]
| * | | | fb27cad 2011-02-20 | Added a couple of final declarations on methods and reduced volatile reads [Viktor Klang]
| * | | | 77a406d 2011-02-15 | Manual inlining and indentation [Viktor Klang]
| * | | | a17e9c2 2011-02-15 | Lowering overhead for receiving messages [Viktor Klang]
| * | | |   86e6ffc 2011-02-14 | Merge branch 'master' of github.com:jboner/akka into wip-ebedd-tune [Viktor Klang]
| |\ \ \ \  
| * | | | | 0d75541 2011-02-14 | Spellchecking and elided a try-block [Viktor Klang]
| * | | | | 5cae50d 2011-02-14 | Removing conditional scheduling [Viktor Klang]
| * | | | | b5f9991 2011-02-14 | Possible optimization for EBEDD [Viktor Klang]
* | | | | |   6ed7b4f 2011-02-15 | Merge branch 'master' of github.com:jboner/akka [Garrick Evans]
|\ \ \ \ \ \  
| * | | | | | 92ddaac 2011-02-16 | Update to Multiverse 0.6.2 [Peter Vlugter]
* | | | | | | aeab748 2011-02-15 | ticket 634; adds filters to respond to raw pressure functions; updated test spec [Garrick Evans]
|/ / / / / /  
* | | | | |   b5b50ae 2011-02-14 | Merge branch 'master' of github.com:jboner/akka [Derek Williams]
|\ \ \ \ \ \  
| | |/ / / /  
| |/| | | |   
| * | | | | ce5ad5e 2011-02-14 | Adding support for PoisonPill [Viktor Klang]
* | | | | | 2d8f03a 2011-02-14 | Small change to better take advantage of latest Future changes [Derek Williams]
|/ / / / /  
* | | | | e7ad2a9 2011-02-13 | Add Future.receive(pf: PartialFunction[Any,Unit]), closes #636 [Derek Williams]
* | | | |   7437209 2011-02-13 | Merge branch '661-derekjw' [Derek Williams]
|\ \ \ \ \  
| |/ / / /  
|/| | | |   
| * | | | 1e09ea8 2011-02-13 | Refactoring based on Viktor's suggestions [Derek Williams]
| * | | | 94c4546 2011-02-12 | Allow specifying the timeunit of a Future's timeout. The compiler should also no longer store the timeout field since it is not referenced in any methods anymore [Derek Williams]
| * | | | 58359e0 2011-02-12 | Add method on Future to await and return the result. Works like resultWithin, but does not need an explicit timeout. [Derek Williams]
| * | | | 6285ad1 2011-02-12 | move repeated code to it's own method, replace loop with tailrec [Derek Williams]
| * | | | c9449a0 2011-02-12 | Rename completeWithValue to complete [Derek Williams]
| * | | | b625b56 2011-02-11 | Throw an exception if Future.await is called on an expired and uncompleted Future. Ref #659 [Derek Williams]
| * | | | db79e2b 2011-02-11 | Use an Option[Either[Throwable, T]] to hold the value of a Future [Derek Williams]
| |/ / /  
* | | | 97b26b6 2011-02-12 | fix tabs; remove debugging log line [Garrick Evans]
* | | | ec2dfe4 2011-02-12 | ticket 664 - update continuation handling to (re)support updating timeout [Garrick Evans]
|/ / /  
* | |   44cdd03 2011-02-11 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| * | | 73322f8 2011-02-11 | Update scalatest to version 1.3, closes #663 [Derek Williams]
* | | | d7ae45d 2011-02-11 | Potential fix for race-condition in RemoteClient [Viktor Klang]
|/ / /  
* | | 58ef109 2011-02-09 | Fixing neglected configuration in WorkStealer [Viktor Klang]
* | |   dd02d40 2011-02-08 | Merge branch 'master' of github.com:jboner/akka [Garrick Evans]
|\ \ \  
| * \ \   0c90597 2011-02-08 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| * | | | 4f9f7d2 2011-02-08 | API improvements to Futures and some code cleanup [Viktor Klang]
| * | | | 9f21b22 2011-02-08 | Fixing ticket #652 - Reaping expired futures [Viktor Klang]
* | | | | 9834f41 2011-02-08 | changed pass criteria for testBoundedCapacityActorPoolWithMailboxPressure to account for more capacity additions [Garrick Evans]
| |/ / /  
|/| | |   
* | | | 98128d2 2011-02-08 | Exclude samples and sbt plugin from parent pom [Peter Vlugter]
* | | | c8f528c 2011-02-08 | Fix publish release to include parent poms correctly [Peter Vlugter]
|/ / /  
* | | 69c402a 2011-02-07 | Fixing ticket #645 adding support for resultWithin on Future [Viktor Klang]
* | | 810f6cf 2011-02-07 | Fixing #648 Adding support for configuring Netty backlog in akka config [Viktor Klang]
* | | 6a93610 2011-02-04 | Fix for local actor ref home address [Peter Vlugter]
* | | ad6498f 2011-02-03 | Adding Java API for ReceiveTimeout [Viktor Klang]
* | |   d68960f 2011-02-01 | Merge branch 'master' of github.com:jboner/akka [Garrick Evans]
|\ \ \  
| * | | cd6f27d 2011-02-02 | Disable -optimise and -Xcheckinit compiler options [Peter Vlugter]
* | | | dfa1876 2011-02-01 | ticket #634 - add actor pool. initial version with unit tests [Garrick Evans]
|/ / /  
* | | 09b7b1f 2011-02-01 | Enable compile options in sub projects [Peter Vlugter]
* | | 588b726 2011-01-31 | Fixing a possible race-condition in netty [Viktor Klang]
* | | baafbee 2011-01-28 | Changing to getPathInfo instead of getRequestURI for Mist [Viktor Klang]
* | | b2b5113 2011-01-26 | Porting the tests from wip-628-629 [Viktor Klang]
* | | 54280de 2011-01-25 | Potential fix for #628 and #629 [Viktor Klang]
* | |   0b2e821 2011-01-26 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \  
| * | | 2dd205d 2011-01-25 | Potential fix for #628 and #629 [Viktor Klang]
* | | | 1dfaebd 2011-01-25 | Potential fix for #628 and #629 [Viktor Klang]
|/ / /  
* | | 98c4173 2011-01-24 | Added support for empty inputs for fold and reduce on Future [Viktor Klang]
* | | d20411f 2011-01-24 | Refining signatures on fold and reduce [Viktor Klang]
* | | ff1785d 2011-01-24 | Added Futures.reduce plus tests [Viktor Klang]
* | | 873e8b8 2011-01-24 | Adding unit tests to Futures.fold [Viktor Klang]
* | | c4620b9 2011-01-24 | Adding docs to Futures.fold [Viktor Klang]
* | | 181a57e 2011-01-24 | Adding fold to Futures and fixed a potential memory leak in Future [Viktor Klang]
* | |   adff63b 2011-01-22 | Merge branch 'master' of github.com:jboner/akka [Derek Williams]
|\ \ \  
| * | | 6a00c8b 2011-01-22 | Upgrade hawtdispatch to 1.1 [Hiram Chirino]
* | | | 5b9bbe2 2011-01-22 | Use correct config keys. Fixes #624 [Derek Williams]
|/ / /  
* | | 04b3ae4 2011-01-22 | Fix dist building [Peter Vlugter]
* | | cc6c315 2011-01-21 | Adding Odds project enhancements [Viktor Klang]
* | |   f84f60e 2011-01-21 | Merge branch 'master' of github.com:jboner/akka into newmaster [Viktor Klang]
|\ \ \  
| * | | c39328e 2011-01-21 | Add release scripts [Peter Vlugter]
| * | | 2bc84f5 2011-01-21 | Add build-release task [Peter Vlugter]
* | | | 75fed86 2011-01-20 | Making MessageInvocation a case class [Viktor Klang]
* | | | c30f442 2011-01-20 | Removing durable mailboxes from akka [Viktor Klang]
|/ / /  
* | | 4b45ca9 2011-01-18 | Reverting lazy addition on repos [Viktor Klang]
* | | daa113d 2011-01-18 | Fixing ticket #614 [Viktor Klang]
* | | c37c4e4 2011-01-17 | Switching to Peters cleaner solution [Viktor Klang]
* | | 3fb403f 2011-01-17 | Allowing forwards where no sender of the message can be found. [Viktor Klang]
* | | 5fccedb 2011-01-11 | Added test for failing TypedActor with method 'String hello(String s)' [Jonas Bonér]
* | | 2ef094f 2011-01-11 | Fixed some TypedActor tests [Jonas Bonér]
* | | 39b1b95 2011-01-08 | Fixing ticket #608 [Viktor Klang]
* | | 604e9ae 2011-01-08 | Update dependencies in sbt plugin [Peter Vlugter]
* | | df2e511 2011-01-05 | Making optimizeLocal public [Viktor Klang]
* | | 21171f2 2011-01-05 | Adding more start methods for RemoteSupport because of Java, and added BeanProperty on some events [Viktor Klang]
* | | 7159634 2011-01-05 | Minor code cleanup and deprecations etc [Viktor Klang]
* | | 2c613d9 2011-01-05 | Changed URI to akka.io [Jonas Bonér]
* | | f0e9732 2011-01-05 | Fixing ticket #603 [Viktor Klang]
* | |   fff4109 2011-01-04 | Merge branch 'remote_deluxe' [Viktor Klang]
|\ \ \  
| * | | 18c8098 2011-01-04 | Minor typed actor lookup cleanup [Viktor Klang]
| * | | dbe6f20 2011-01-04 | Removing ActorRegistry object, UntypedActor object, introducing akka.actor.Actors for the Java API [Viktor Klang]
| * | |   435de7b 2011-01-03 | Merge with master [Viktor Klang]
| |\ \ \  
| * | | | 00840c8 2011-01-03 | Adding support for non-delivery notifications on server-side as well + more code cleanup [Viktor Klang]
| * | | | 7a0e8a8 2011-01-03 | Major code clanup, switched from nested ifs to match statements etc [Viktor Klang]
| * | | | a61e591 2011-01-03 | Putting the Netty-stuff in akka.remote.netty and disposing of RemoteClient and RemoteServer [Viktor Klang]
| * | | | 8e522f4 2011-01-03 | Removing PassiveRemoteClient because of architectural problems [Viktor Klang]
| * | | | 63a182a 2011-01-01 | Added lock downgrades and fixed unlocking ordering [Viktor Klang]
| * | | | d5095be 2011-01-01 | Minor code cleanup [Viktor Klang]
| * | | | f679dd0 2011-01-01 | Added support for passive connections in Netty remoting, closing ticket #507 [Viktor Klang]
| * | | | 718f831 2010-12-30 | Adding support for failed messages to be notified to listeners, this closes ticket #587 [Viktor Klang]
| * | | | 4994b13 2010-12-29 | Removed if statement because it looked ugly [Viktor Klang]
| * | | | 960e161 2010-12-29 | Fixing #586 and #588 and adding support for reconnect and shutdown of individual clients [Viktor Klang]
| * | | | b51a4fe 2010-12-29 | Minor refactoring to ActorRegistry [Viktor Klang]
| * | | | 236eece 2010-12-29 | Moving shared remote classes into RemoteInterface [Viktor Klang]
| * | | | c120589 2010-12-29 | Changed wording in the unoptimized local scoped spec [Viktor Klang]
| * | | | 0a6567e 2010-12-29 | Adding tests for optimize local scoped and non-optimized local scoped [Viktor Klang]
| * | | | 83c8bb7 2010-12-29 | Moved all actorOf-methods from Actor to ActorRegistry and deprecated the forwarders in Actor [Viktor Klang]
| * | | | 9309c98 2010-12-28 | Fixing erronous test [Viktor Klang]
| * | | | d0f94b9 2010-12-28 | Adding additional tests [Viktor Klang]
| * | | | 0f87bd8 2010-12-28 | Adding example in test to show how to test remotely using only one registry [Viktor Klang]
| * | | |   9ccac82 2010-12-27 | Merged with current master [Viktor Klang]
| |\ \ \ \  
| * | | | | a1d0243 2010-12-22 | WIP [Viktor Klang]
| * | | | | c20aab0 2010-12-21 | All tests passing, still some work to be done though, but thank God for all tests being green ;) [Viktor Klang]
| * | | | | 1fa105f 2010-12-20 | Removing redundant call to ActorRegistry-register [Viktor Klang]
| * | | | | 6edfb7d 2010-12-20 | Reverted to using LocalActorRefs for client-managed actors to get supervision working, more migrated tests [Viktor Klang]
| * | | | |   dc15562 2010-12-20 | Merged with release_1_0_RC1 plus fixed some tests [Viktor Klang]
| |\ \ \ \ \  
| | * | | | | 8f6074d 2010-12-20 | Making sure RemoteActorRef.loader is passed into RemoteClient, also adding volatile flag to classloader in Serializer to make sure changes are propagated crossthreads [Viktor Klang]
| | * | | | | cb2054f 2010-12-20 | Giving all remote messages their own uuid, reusing actorInfo.uuid for futures, closing ticket 580 [Viktor Klang]
| | * | | | | 091bb41 2010-12-20 | Adding debug log of parse exception in parseException [Viktor Klang]
| | * | | | | 04a7a07 2010-12-20 | Adding UnparsableException and make sure that non-recreateable exceptions dont mess up the pipeline [Viktor Klang]
| | * | | | | 65a55a7 2010-12-19 | Give modified configgy a unique version and add a link to the source repository [Derek Williams]
| | * | | | | 9547d26 2010-12-20 | Refine transactor doNothing (fixes #582) [Peter Vlugter]
| | * | | | | 6920464 2010-12-18 | Backport from master, add new Configgy version with logging removed [Derek Williams]
| | * | | | | 65a6f3b 2010-12-16 | Update group id in sbt plugin [Peter Vlugter]
| * | | | | | 44933e9 2010-12-17 | Commented out many of the remote tests while I am porting [Viktor Klang]
| * | | | | | 8becbad 2010-12-17 | Fixing a lot of stuff and starting to port unit tests [Viktor Klang]
| * | | | | | 5f651c7 2010-12-15 | Got API in place now and RemoteServer/Client/Node etc purged. Need to get test-compile to work so I can start testing the new stuff... [Viktor Klang]
| * | | | | | 74f5445 2010-12-14 | Switch to a match instead of a not-so-cute if [Viktor Klang]
| * | | | | | c89ea0a 2010-12-14 | First shot at re-doing akka-remote [Viktor Klang]
| |/ / / / /  
| * | | | | a1117c6 2010-12-14 | Fixing a glitch in the API [Viktor Klang]
* | | | | |   cb3135c 2011-01-04 | Merge branch 'testkit' [Roland Kuhn]
|\ \ \ \ \ \  
| |_|_|/ / /  
|/| | | | |   
| * | | | | be655aa 2011-01-04 | fix up indentation [Roland Kuhn]
| * | | | |   b680c61 2011-01-04 | Merge branch 'testkit' of git-proxy:jboner/akka into testkit [momania]
| |\ \ \ \ \  
| | * | | | | 639b141 2011-01-03 | also test custom whenUnhandled fall-through [Roland Kuhn]
| | * | | | |   12d4942 2011-01-03 | merge Irmo's changes and add test case for whenUnhandled [Roland Kuhn]
| | |\ \ \ \ \  
| | * | | | | | 094b11c 2011-01-03 | remove one more allocation in hot path [Roland Kuhn]
| | * | | | | | 817395f 2011-01-03 | change indentation to 2 spaces [Roland Kuhn]
| * | | | | | | dc1fe99 2011-01-04 | small adjustment to the example, showing the correct use of the startWith and initialize [momania]
| * | | | | | | dfd1896 2011-01-03 | wrap initial sending of state to transition listener in CurrentState object with fsm actor ref [momania]
| * | | | | | | c66bbb5 2011-01-03 | add fsm self actor ref to external transition message [momania]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | 5094e87 2011-01-03 | Move handleEvent var declaration _after_ handleEventDefault val declaration. Using a val before defining it causes nullpointer exceptions... [momania]
| * | | | | | 61b4ded 2011-01-03 | Removed generic typed classes that are only used in the FSM itself from the companion object and put back in the typed FSM again so they will take same types. [momania]
| * | | | | | 80ac75a 2011-01-03 | fix tests [momania]
| * | | | | | 9f66471 2011-01-03 | - make transition handler a function taking old and new state avoiding the default use of the transition class - only create transition class when transition listeners are subscribed [momania]
| * | | | | | 33a628a 2011-01-03 | stop the timers (if any) while terminating [momania]
| |/ / / / /  
| * | | | | 227f2bb 2011-01-01 | convert test to WordSpec with MustMatchers [Roland Kuhn]
| * | | | | 91e210e 2011-01-01 | fix fallout of Duration changes in STM tests [Roland Kuhn]
| * | | | | 6a67274 2010-12-31 | make TestKit assertions nicer / improve Duration [Roland Kuhn]
| * | | | | da03c05 2010-12-31 | remove unnecessary allocations in hot paths [Roland Kuhn]
| * | | | | a45fc95 2010-12-30 | flesh out FSMTimingSpec [Roland Kuhn]
| * | | | | 68c0f7c 2010-12-29 | add first usage of TestKit [Roland Kuhn]
| * | | | | e83ef89 2010-12-29 | code cleanup (thanks, Viktor and Irmo) [Roland Kuhn]
| * | | | | ab10f6c 2010-12-28 | revamp TestKit (with documentation) [Roland Kuhn]
| * | | | | b868c90 2010-12-28 | Improve Duration classes [Roland Kuhn]
| * | | | | 4838121 2010-12-28 | add facility for changing stateTimeout dynamically [Roland Kuhn]
| * | | | | 6a8b0e1 2010-12-26 | first sketch of basic TestKit architecture [Roland Kuhn]
| * | | | |   b04851b 2010-12-26 | Merge remote branch 'origin/fsmrk2' into testkit [Roland Kuhn]
| |\ \ \ \ \  
| | * | | | | d175191 2010-12-24 | improved test - test for intial state on transition call back and use initialize function from FSM to kick of the machine. [momania]
| | * | | | | 4ba3ed6 2010-12-24 | wrap stop reason in stop even with current state, so state can be referenced in onTermination call for cleanup reasons etc [momania]
| | * | | | | 993b60a 2010-12-20 | add minutes and hours to Duration [Roland Kuhn]
| | * | | | | 63617e1 2010-12-20 | add user documentation comments to FSM [Roland Kuhn]
| | * | | | | acee86f 2010-12-20 | - merge in transition callback handling - renamed notifying -> onTransition - updated dining hakkers example and unit test - moved buncher to fsm sample project [momania]
| | * | | | | 716aab2 2010-12-19 | change ff=unix [Roland Kuhn]
| | * | | | | ec5be9b 2010-12-19 | improvements on FSM [Roland Kuhn]
| * | | | | | bdeef69 2010-12-26 | revamp akka.util.Duration [Roland Kuhn]
* | | | | | | 0bdb5d5 2010-12-30 | Adding possibility to set id for TypedActor [Viktor Klang]
* | | | | | | 953d155 2010-12-28 | Fixed logging glitch in ReflectiveAccess [Viktor Klang]
* | | | | | | ef9c01e 2010-12-28 | Making EmbeddedAppServer work without AKKA_HOME [Viktor Klang]
| |_|_|/ / /  
|/| | | | |   
* | | | | | 9d5c917 2010-12-27 | Fixing ticket 594 [Viktor Klang]
* | | | | |   eabdeec 2010-12-27 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | | e8fcdd6 2010-12-22 | Updated the copyright header to 2009-2011 [Jonas Bonér]
* | | | | | ccde5b4 2010-12-22 | Removing not needed dependencies [Viktor Klang]
|/ / / / /  
* | | | | 8a5fa56 2010-12-22 | Closing ticket 541 [Viktor Klang]
* | | | | fc3b125 2010-12-22 | removed trailing spaces [Jonas Bonér]
* | | | | cd27298 2010-12-22 | Enriched TypedActorContext [Jonas Bonér]
* | | | |   04c27b4 2010-12-22 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| * | | | | 5c241ae 2010-12-22 | Throw runtime exception for @Coordinated when used with non-void methods [Peter Vlugter]
* | | | | | 11eb304 2010-12-22 | changed config element name [Jonas Bonér]
|/ / / / /  
* | | | |   251878b 2010-12-21 | changed config for JMX enabling [Jonas Bonér]
|\ \ \ \ \  
| * | | | | 754dcc2 2010-12-21 | added option to turn on/off JMX browsing of the configuration [Jonas Bonér]
* | | | | | 224d4dc 2010-12-21 | added option to turn on/off JMX browsing of the configuration [Jonas Bonér]
|/ / / / /  
* | | | | 5e29b86 2010-12-21 | removed persistence stuff from config [Jonas Bonér]
* | | | |   8dd5768 2010-12-21 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| * | | | | f8703a8 2010-12-21 | Closing ticket #585 [Viktor Klang]
| * | | | | 06f230e 2010-12-20 | Making sure RemoteActorRef.loader is passed into RemoteClient, also adding volatile flag to classloader in Serializer to make sure changes are propagated crossthreads [Viktor Klang]
| * | | | | 5624e6d 2010-12-20 | Giving all remote messages their own uuid, reusing actorInfo.uuid for futures, closing ticket 580 [Viktor Klang]
| * | | | | dbd8a60 2010-12-20 | Adding debug log of parse exception in parseException [Viktor Klang]
| * | | | | e481adb 2010-12-20 | Adding UnparsableException and make sure that non-recreateable exceptions dont mess up the pipeline [Viktor Klang]
| * | | | | 169975e 2010-12-19 | Give modified configgy a unique version and add a link to the source repository [Derek Williams]
| * | | | | bbb089f 2010-12-20 | Refine transactor doNothing (fixes #582) [Peter Vlugter]
| |/ / / /  
| * | | | aa38fa9 2010-12-18 | Remove workaround since Configgy has logging removed [Derek Williams]
| * | | | a6f1f7f 2010-12-18 | Use new configgy [Derek Williams]
| * | | | 3926e4b 2010-12-18 | New Configgy version with logging removed [Derek Williams]
* | | | | f177c9f 2010-12-21 | Fixed bug with not setting homeAddress in RemoteActorRef [Jonas Bonér]
|/ / / /  
* | | | fb45478 2010-12-16 | Update group id in sbt plugin [Peter Vlugter]
* | | | a0abeb8 2010-12-14 | Fixing a glitch in the API [Viktor Klang]
* | | | 8f4db98 2010-12-13 | Bumping version to 1.1-SNAPSHOT [Viktor Klang]
* | | |   0e0daeb 2010-12-13 | Merge branch 'release_1_0_RC1' [Viktor Klang]
|\ \ \ \  
| |/ / /  
| * | | 18b9782 2010-12-13 | Changing versions to 1.0-RC2-SNAPSHOT [Viktor Klang]
| * | |   1b454c9 2010-12-10 | Merge branch 'release_1_0_RC1' of github.com:jboner/akka into release_1_0_RC1 [Jonas Bonér]
| |\ \ \  
| * | | | a3ecb23 2010-12-10 | fixed bug in which the default configuration timeout was always overridden by 5000L [Jonas Bonér]
| * | | | 96af414 2010-12-07 | applied McPom [Jonas Bonér]
* | | | |   1e84f7f 2010-12-10 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| * \ \ \ \   e985ee9 2010-12-09 | Merge branch 'master' of github.com:jboner/akka into ticket-538 [ticktock]
| |\ \ \ \ \  
| | * \ \ \ \   120829d 2010-12-08 | Merge branch 'release_1_0_RC1' [Viktor Klang]
| | |\ \ \ \ \  
| | | | |/ / /  
| | | |/| | |   
| | | * | | | b19c219 2010-12-08 | Adding McPom [Viktor Klang]
| | | |/ / /  
| | | * | | 09381f8 2010-12-01 | changed access modifier for RemoteServer.serverFor [Jonas Bonér]
| | | * | |   979c426 2010-11-30 | Merge branch 'release_1_0_RC1' of github.com:jboner/akka into release_1_0_RC1 [Jonas Bonér]
| | | |\ \ \  
| | | * | | | 5a48980 2010-11-30 | renamed DurableMailboxType to DurableMailbox [Jonas Bonér]
| * | | | | |   693e91d 2010-11-28 | merged module move refactor from master [ticktock]
| |\ \ \ \ \ \  
| * \ \ \ \ \ \   5d3646b 2010-11-22 | Merge branch 'master' of github.com:jboner/akka into ticket-538 [ticktock]
| |\ \ \ \ \ \ \  
| * | | | | | | | 855919b 2010-11-22 | Move persistent commit to a pre-commit handler [ticktock]
| * | | | | | | | c63d021 2010-11-19 | factor out redundant code [ticktock]
| * | | | | | | | 825d9cb 2010-11-19 | cleanup from wip merge [ticktock]
| * | | | | | | | 8137adc 2010-11-19 | check reference equality when registering a persistent datastructure, and fail if one is already there with a different reference [ticktock]
| * | | | | | | | cad793c 2010-11-18 | added retries to persistent state commits, and restructured the storage api to provide management over the number of instances of persistent datastructures [ticktock]
| * | | | | | | |   9f6f72e 2010-11-18 | merge wip [ticktock]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | 7724444 2010-11-18 | add TX retries, and add a helpful error logging in ReflectiveAccess [ticktock]
| | * | | | | | | | 7551809 2010-11-18 | most of the work for retry-able persistence commits and managing the instances of persistent data structures [ticktock]
| | * | | | | | | | a7939ee 2010-11-16 | wip [ticktock]
| | * | | | | | | | f278780 2010-11-15 | wip [ticktock]
| | * | | | | | | | 7467af1 2010-11-15 | wip [ticktock]
| | * | | | | | | | b9409b1 2010-11-15 | wip [ticktock]
| | * | | | | | | |   0be5f3a 2010-11-15 | Merge branch 'master' into wip-ticktock-persistent-transactor [ticktock]
| | |\ \ \ \ \ \ \ \  
| | * | | | | | | | | 6f7fed8 2010-11-15 | retry only failed/not executed Ops if the starabe backend is not transactional [ticktock]
| | * | | | | | | | |   38ec6f2 2010-11-15 | Merge branch 'master' of https://github.com/jboner/akka into wip-ticktock-persistent-transactor [ticktock]
| | |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | 620ac89 2010-11-15 | initial sketch [ticktock]
* | | | | | | | | | | | f261093 2010-12-10 | fixed bug in which the default configuration timeout was always overridden by 5000L [Jonas Bonér]
| |_|_|_|_|_|/ / / / /  
|/| | | | | | | | | |   
* | | | | | | | | | | 6ad4267 2010-12-01 | Instructions on how to run the sample [Jonas Bonér]
* | | | | | | | | | |   05a8209 2010-12-01 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \ \ \   9b273f9 2010-12-01 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \  
| | |_|_|_|_|_|_|_|/ / /  
| |/| | | | | | | | | |   
| * | | | | | | | | | | 30f73c7 2010-11-30 | Fixing SLF4J logging lib switch, insane API FTL [Viktor Klang]
| | |_|_|_|_|_|_|/ / /  
| |/| | | | | | | | |   
* | | | | | | | | | | bf4ed2b 2010-12-01 | changed access modifier for RemoteServer.serverFor [Jonas Bonér]
| |/ / / / / / / / /  
|/| | | | | | | | |   
* | | | | | | | | |   1aed36b 2010-11-30 | Merge branch 'master' of github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / /  
| * | | | | | | | | ec1d0e4 2010-11-29 | Adding Java API for per session remote actors, closing ticket #561 [Viktor Klang]
| | |_|_|_|_|/ / /  
| |/| | | | | | |   
* | | | | | | | | e369113 2010-11-30 | renamed DurableMailboxType to DurableMailbox [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | aa47e66 2010-11-26 | bumped version to 1.0-RC1 [Jonas Bonér]
* | | | | | | | ea5cd05 2010-11-26 | Switched AkkaLoader to use Switch instead of volatile boolean [Viktor Klang]
* | | | | | | |   d0a2956 2010-11-25 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \   cfd12fe 2010-11-25 | Merge branch 'master' of github.com:jboner/akka [momania]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | e248f8e 2010-11-25 | - added local maven repo as repo for libs so publishing works - added databinder repo again: needed for publish to work [momania]
* | | | | | | | | | c76ff14 2010-11-25 | Moving all message typed besides Transition into FSM object [Viktor Klang]
| |/ / / / / / / /  
|/| | | | | | | |   
* | | | | | | | | 4acee62 2010-11-25 | Adding AkkaRestServlet that will provide the same functionality as the AkkaServlet - Atmosphere [Viktor Klang]
* | | | | | | | |   6121a80 2010-11-25 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \ \  
| |/ / / / / / / /  
| * | | | | | | | 6cf7cc2 2010-11-24 | removed trailing whitespace [Jonas Bonér]
| * | | | | | | | ba258e6 2010-11-24 | tabs to spaces [Jonas Bonér]
| * | | | | | | | 1b37b9d 2010-11-24 | removed dataflow stream for good [Jonas Bonér]
| * | | | | | | | 96011a0 2010-11-24 | renamed dataflow variable file [Jonas Bonér]
| * | | | | | | |   2ba20cf 2010-11-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | 900b90c 2010-11-24 | uncommented the dataflowstream tests and they all pass [Jonas Bonér]
* | | | | | | | | |   268c411 2010-11-24 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| |/| | | | | | | |   
| * | | | | | | | |   9b602c4 2010-11-24 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| |\ \ \ \ \ \ \ \ \  
| * | | | | | | | | | 774dd97 2010-11-24 | - re-add fsm samples - removed ton of whitespaces from the project definition [momania]
* | | | | | | | | | | 22a970a 2010-11-24 | Making HotSwap stacking not be the default [Viktor Klang]
| |/ / / / / / / / /  
|/| | | | | | | | |   
* | | | | | | | | | 08e5ee5 2010-11-24 | Removing unused imports [Viktor Klang]
* | | | | | | | | |   73531cb 2010-11-24 | Merge with master [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| |/| | | | | | | |   
| * | | | | | | | |   ebdfd56 2010-11-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| | * | | | | | | | 3c71c4a 2010-11-24 | - fix race condition with timeout - improved stopping mechanism - renamed 'until' to 'forMax' for less confusion - ability to specif timeunit for timeout [momania]
| * | | | | | | | | f21a83e 2010-11-24 | added effect to java api [Jonas Bonér]
| |/ / / / / / / /  
* | | | | | | | | 9a01010 2010-11-24 | Fixing silly error plus fixing bug in remtoe session actors [Viktor Klang]
* | | | | | | | | 10dacf7 2010-11-24 | Fixing %d for logging into {} [Viktor Klang]
* | | | | | | | | bc1ae78 2010-11-24 | Fixing all %s into {} for logging [Viktor Klang]
* | | | | | | | | 08abc15 2010-11-24 | Switching to raw SLF4J on internals [Viktor Klang]
|/ / / / / / / /  
* | | | | | | | 5d436e3 2010-11-23 | cleaned up project file [Jonas Bonér]
* | | | | | | | f97d04f 2010-11-23 | Separated core from modules, moved modules to akka-modules repository [Jonas Bonér]
* | | | | | | | 7f03582 2010-11-23 | Closing #555 [Viktor Klang]
* | | | | | | |   6d94622 2010-11-23 | Mist now integrated in master [Viktor Klang]
|\ \ \ \ \ \ \ \  
| * | | | | | | | 31d5d7c 2010-11-23 | Moving Mist into almost one file, changing Servlet3.0 into a Provided jar and adding an experimental Filter [Viktor Klang]
| * | | | | | | |   1bca241 2010-11-23 | Merge with master [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | e802b33 2010-11-22 | Minor code tweaks, removing Atmosphere, awaiting some tests then ready for master [Viktor Klang]
| * | | | | | | | |   dcbdfcc 2010-11-22 | Merge branch 'master' into mist [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| * | | | | | | | | | f99d91c 2010-11-21 | added back old 2nd sample (http (mist)) [Garrick Evans]
| * | | | | | | | | | 542bc29 2010-11-21 | restore project and ref config to pre jetty-8 states [Garrick Evans]
| * | | | | | | | | |   737b120 2010-11-20 | Merge branch 'wip-mist-http-garrick' of github.com:jboner/akka into wip-mist-http-garrick [Garrick Evans]
| |\ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | 5bb5cfd 2010-11-20 | removing odd git-added folder. [Garrick Evans]
| | * | | | | | | | | | b2b4686 2010-11-20 | merge master to branch [Garrick Evans]
| | * | | | | | | | | | 61c957e 2010-11-20 | hacking in servlet 3.0 support using embedded jetty-8 (remove atmo, hbase, volde to get around jar mismatch); wip [Garrick Evans]
| | * | | | | | | | | | 6176f70 2010-11-09 | most of the refactoring done and jetty is working again (need to check updating timeouts, etc); servlet 3.0 impl next [Garrick Evans]
| | * | | | | | | | | | 25c17d5 2010-11-08 | refactoring WIP - doesn't build; added servlet 3.0 api jar from glassfish to proj dep [Garrick Evans]
| | * | | | | | | | | | 9136e62 2010-11-08 | adding back (mist) http work in a new branch. misitfy was too stale. this is WIP - trying to support both SAPI 3.0 and Jetty Continuations at once [Garrick Evans]
| * | | | | | | | | | | adf8b63 2010-11-20 | fixing a screwy merge from master... readding files git deleted for some unknown reason [Garrick Evans]
| * | | | | | | | | | | 1a23d36 2010-11-20 | removing odd git-added folder. [Garrick Evans]
| * | | | | | | | | | | 4a9fd4e 2010-11-20 | merge master to branch [Garrick Evans]
| * | | | | | | | | | | c313e22 2010-11-20 | hacking in servlet 3.0 support using embedded jetty-8 (remove atmo, hbase, volde to get around jar mismatch); wip [Garrick Evans]
| * | | | | | | | | | | 0108b84 2010-11-09 | most of the refactoring done and jetty is working again (need to check updating timeouts, etc); servlet 3.0 impl next [Garrick Evans]
| * | | | | | | | | | | c3acad0 2010-11-08 | refactoring WIP - doesn't build; added servlet 3.0 api jar from glassfish to proj dep [Garrick Evans]
| * | | | | | | | | | | 31be60d 2010-11-08 | adding back (mist) http work in a new branch. misitfy was too stale. this is WIP - trying to support both SAPI 3.0 and Jetty Continuations at once [Garrick Evans]
* | | | | | | | | | | | a347bc3 2010-11-23 | Switching to SBT 0.7.5.RC0 and now we can drop the ubly AkkaDeployClassLoader [Viktor Klang]
* | | | | | | | | | | | 62f312c 2010-11-23 | upgraded to single jar aspectwerkz [Jonas Bonér]
| |_|_|/ / / / / / / /  
|/| | | | | | | | | |   
* | | | | | | | | | | 4c4fe3c 2010-11-23 | Upgrade to Scala 2.8.1 [Jonas Bonér]
* | | | | | | | | | | 7ee8acc 2010-11-23 | Fixed problem with message toString is not lazily evaluated in RemoteClient [Jonas Bonér]
* | | | | | | | | | |   6188e70 2010-11-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \  
| | |_|_|_|_|_|_|/ / /  
| |/| | | | | | | | |   
| * | | | | | | | | | 1178f15 2010-11-23 | Disable cross paths on parent projects as well [Peter Vlugter]
| * | | | | | | | | | 629334b 2010-11-23 | Remove scala version from dist paths - fixes #549 [Peter Vlugter]
| | |_|/ / / / / / /  
| |/| | | | | | | |   
* | | | | | | | | |   a7ef1da 2010-11-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / /  
| * | | | | | | | |   3ca9d57 2010-11-22 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | 1bc4bc0 2010-11-22 | Fixed bug in ActorRegistry getting typed actor by manifest [Jonas Bonér]
| * | | | | | | | | | 47246b2 2010-11-22 | Merging in Actor per Session + fixing blocking problem with remote typed actors with Future response types [Viktor Klang]
| * | | | | | | | | |   b6c6698 2010-11-22 | Merge branch 'master' of https://github.com/paulpach/akka into paulpach-master [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \   3724cfe 2010-11-20 | Merge branch 'master' of git://github.com/jboner/akka [Paul Pacheco]
| | |\ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | e5fdbaa 2010-11-20 | tests pass [Paul Pacheco]
| | * | | | | | | | | | | 2803766 2010-11-19 | Cleaned up some semicolons Test now compiles (but does not pass) [Paul Pacheco]
| | * | | | | | | | | | | a605ac4 2010-11-18 | Refatored createActor, separate unit tests cleanup according to viktor's suggestions [Paul Pacheco]
| | * | | | | | | | | | |   5f073e2 2010-11-18 | Merge branch 'master' of git://github.com/jboner/akka [Paul Pacheco]
| | |\ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|/ / / / / /  
| | | |/| | | | | | | | |   
| | * | | | | | | | | | | 126ea2c 2010-11-18 | refactored the createActor function to make it easier to understand and remove the return statements; [Paul Pacheco]
| | * | | | | | | | | | | 8c35885 2010-11-18 | Cleaned up patch as suggested by Vicktor [Paul Pacheco]
| | * | | | | | | | | | | 16640eb 2010-11-14 | Added remote typed session actors, along with unit tests [Paul Pacheco]
| | * | | | | | | | | | | 376d1c9 2010-11-14 | Added server initiated remote untyped session actors now you can register a factory function and whenever a new session starts, the actor will be created and started. When the client disconnects, the actor will be stopped. The client works the same as any other untyped remote server managed actor. [Paul Pacheco]
| | * | | | | | | | | | |   26f23ac 2010-11-14 | Merge branch 'master' of git://github.com/jboner/akka into session-actors [Paul Pacheco]
| | |\ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|_|/ / / /  
| | | |/| | | | | | | | |   
| | * | | | | | | | | | | 67cf378 2010-11-14 | Added interface for registering session actors, and adding unit test (which is failing now) [Paul Pacheco]
* | | | | | | | | | | | | ada24c7 2010-11-22 | Removed reflective coupling to akka cloud [Jonas Bonér]
* | | | | | | | | | | | | 1ee3a54 2010-11-22 | Fixed issues with config - Ticket #535 [Jonas Bonér]
* | | | | | | | | | | | | 80adb71 2010-11-22 | Fixed bug in ActorRegistry getting typed actor by manifest [Jonas Bonér]
| |_|_|_|_|/ / / / / / /  
|/| | | | | | | | | | |   
* | | | | | | | | | | | 5820286 2010-11-22 | fixed wrong path in voldermort tests - now test are passing again [Jonas Bonér]
* | | | | | | | | | | | 062db26 2010-11-22 | Disable cross paths for publishing without Scala version [Peter Vlugter]
|/ / / / / / / / / / /  
* | | | | | | | | | |   5ab5180 2010-11-21 | Merge with master [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | 6ae1d1e 2010-11-21 | Ticket #506 closed, caused by REPL [Viktor Klang]
* | | | | | | | | | | | f4f77cd 2010-11-21 | Ticket #506 closed, caused by REPL [Viktor Klang]
|/ / / / / / / / / / /  
* | | | | | | | | | | ac1e981 2010-11-21 | Moving dispatcher volatile field from ActorRef to LocalActorRef [Viktor Klang]
* | | | | | | | | | | e6c02cc 2010-11-21 | Fixing ticket #533 by adding get/set LifeCycle in ActorRef [Viktor Klang]
* | | | | | | | | | | 136f76b 2010-11-21 | Fixing groupID for SBT plugin and change url to akka homepage [Viktor Klang]
| |_|_|_|/ / / / / /  
|/| | | | | | | | |   
* | | | | | | | | | b527a28 2010-11-20 | Adding a Java API for Channel, and adding some docs, have updated wiki, closing #536 [Viktor Klang]
| |_|_|/ / / / / /  
|/| | | | | | | |   
* | | | | | | | | 7040ef0 2010-11-20 | Added a root akka folder for source files for the docs to work properly, closing ticket #541 [Viktor Klang]
* | | | | | | | | 8ecba6d 2010-11-20 | Changing signature for HotSwap to include self-reference, closing ticket #540 [Viktor Klang]
* | | | | | | | | 13a735a 2010-11-20 | Changing artifact IDs so they dont include scala version no, closing ticket #529 [Viktor Klang]
| |_|/ / / / / /  
|/| | | | | | |   
* | | | | | | | 0e9aa2d 2010-11-18 | Making register and unregister of ActorRegistry private [Viktor Klang]
* | | | | | | | d2c9da0 2010-11-18 | Change to akka-actor rather than akka-remote as default in sbt plugin [Peter Vlugter]
* | | | | | | | 120664f 2010-11-18 | Change to akka-actor rather than akka-remote as default in sbt plugin [Peter Vlugter]
* | | | | | | | 0f75cea 2010-11-16 | redis tests should not run by default [Debasish Ghosh]
* | | | | | | | 3bf6d1d 2010-11-16 | fixed ticket #531 - Fix RedisStorage add() method in Java API : added Java test case akka-persistence/akka-persistence-redis/src/test/java/akka/persistence/redis/RedisStorageTests.java [Debasish Ghosh]
| |_|_|_|/ / /  
|/| | | | | |   
* | | | | | | 7825253 2010-11-15 | fix ticket-532 [ticktock]
| |/ / / / /  
|/| | | | |   
* | | | | | a808aff 2010-11-14 | Fixing ticket #530 [Viktor Klang]
* | | | | | 47a0cf4 2010-11-14 | Update redis test after rebase [Peter Vlugter]
* | | | | | a86237b 2010-11-14 | Remove disabled src in stm module [Peter Vlugter]
* | | | | | 8a857b1 2010-11-14 | Move transactor.typed to other packages [Peter Vlugter]
* | | | | | 70361e2 2010-11-14 | Update agent and agent spec [Peter Vlugter]
* | | | | | 3b87e82 2010-11-13 | Add Atomically for transactor Java API [Peter Vlugter]
* | | | | | baa181f 2010-11-13 | Add untyped coordinated example to be used in docs [Peter Vlugter]
* | | | | | 2cc572e 2010-11-13 | Use coordinated.await in test [Peter Vlugter]
* | | | | | c0a3437 2010-11-13 | Add coordinated transactions for typed actors [Peter Vlugter]
* | | | | | 49c2575 2010-11-13 | Update new redis tests [Peter Vlugter]
* | | | | | db6d90d 2010-11-12 | Add untyped transactor [Peter Vlugter]
* | | | | | 4cdc46c 2010-11-09 | Add Java API for coordinated transactions [Peter Vlugter]
* | | | | | 2cbd033 2010-11-09 | Move Transactor and Coordinated to akka.transactor package [Peter Vlugter]
* | | | | | 8d5be78 2010-11-09 | Some tidy up [Peter Vlugter]
* | | | | | c17cbf7 2010-11-09 | Add reworked Agent [Peter Vlugter]
* | | | | | d59806c 2010-11-07 | Update stm scaladoc [Peter Vlugter]
* | | | | | e6e8f34 2010-11-07 | Add new transactor based on new coordinated transactions [Peter Vlugter]
* | | | | | cda2468 2010-11-06 | Add new mechanism for coordinated transactions [Peter Vlugter]
* | | | | | 2b79caa 2010-11-06 | Reworked stm with no global/local [Peter Vlugter]
* | | | | | 9012847 2010-11-05 | First pass on separating stm into its own module [Peter Vlugter]
* | | | | |   73c0f31 2010-11-13 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| * | | | | | 53afae0 2010-11-13 | Fixed Issue 528 - RedisPersistentRef should not throw in case of missing key [Debasish Ghosh]
| * | | | | | adea050 2010-11-13 | Implemented addition of entries with same score through zrange - updated test cases [Debasish Ghosh]
| | |_|/ / /  
| |/| | | |   
| * | | | | 1c686c9 2010-11-13 | Ensure unique scores for redis sorted set test [Peter Vlugter]
| * | | | |   b786d35 2010-11-12 | closing ticket 518 [ticktock]
| |\ \ \ \ \  
| | * | | | | c90b331 2010-11-12 | minor simplification [momania]
| | * | | | |   3646f9e 2010-11-12 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | |\ \ \ \ \  
| | * | | | | | 396d661 2010-11-12 | - add possibility to specify channel prefetch side for consumer [momania]
| | * | | | | |   47ac667 2010-11-08 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | |\ \ \ \ \ \  
| | * | | | | | | dce0e81 2010-11-08 | - improved RPC, adding 'poolsize' and direct queue capabilities - add redelivery property to delivery [momania]
| * | | | | | | |   0970097 2010-11-12 | Merge branch 'ticket-518' of https://github.com/jboner/akka into ticket-518 [ticktock]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | a1d1640 2010-11-11 | fix source of compiler warnings [ticktock]
| * | | | | | | | | da97047 2010-11-12 | clean up some code [ticktock]
| |/ / / / / / / /  
| * | | | | | | | 91cc372 2010-11-11 | finished enabling batch puts and gets in simpledb [ticktock]
| * | | | | | | |   774596e 2010-11-11 | Merge branch 'master' of https://github.com/jboner/akka into ticket-518 [ticktock]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | 4fe3187 2010-11-10 | cassandra, riak, memcached, voldemort working, still need to enable bulk gets and puts in simpledb [ticktock]
| * | | | | | | | | 0eea188 2010-11-10 | first pass at refactor, common access working (cassandra) now to KVAccess [ticktock]
| | |_|_|_|/ / / /  
| |/| | | | | | |   
* | | | | | | | |   7f3e653 2010-11-12 | Merge branch 'remove-cluster' [Viktor Klang]
|\ \ \ \ \ \ \ \ \  
| |_|_|_|_|/ / / /  
|/| | | | | | | |   
| * | | | | | | | bb855ed 2010-11-12 | Removing legacy code for 1.0 [Viktor Klang]
* | | | | | | | |   d45962a 2010-11-12 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \   c9abb47 2010-11-12 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| |\ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| * | | | | | | | | 6832b2d 2010-11-12 | updated test case to ensure that sorted sets have diff scores [Debasish Ghosh]
| | |_|/ / / / / /  
| |/| | | | | | |   
* | | | | | | | | 9898655 2010-11-12 | Adding configurable default dispatcher timeout and re-instating awaitEither [Viktor Klang]
* | | | | | | | | 49d9151 2010-11-12 | Adding Futures.firstCompleteOf to allow for composability [Viktor Klang]
* | | | | | | | | 4ccd860 2010-11-12 | Replacing awaitOne with a listener based approach [Viktor Klang]
* | | | | | | | | 249f141 2010-11-12 | Adding support for onComplete listeners to Future [Viktor Klang]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | | 9df923d 2010-11-11 | Fixing ticket #519 [Viktor Klang]
* | | | | | | | a0cc5d3 2010-11-11 | Removing pointless synchroniation [Viktor Klang]
* | | | | | | | 05ecb14 2010-11-11 | Fixing #522 [Viktor Klang]
* | | | | | | | 08fd01c 2010-11-11 | Fixing ticket #524 [Viktor Klang]
|/ / / / / / /  
* | | | | | | efa5cf2 2010-11-11 | Fix for Ticket 513 : Implement snapshot based persistence control in SortedSet [Debasish Ghosh]
|/ / / / / /  
* | | | | |   5dff296 2010-11-10 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| * \ \ \ \ \   cdd0c10 2010-11-09 | Merging support of amazon simpledb as a persistence backend [ticktock]
| |\ \ \ \ \ \  
| * | | | | | | bab4b9d 2010-11-09 | test max key and value sizes [ticktock]
| * | | | | | |   4999956 2010-11-08 | merged master [ticktock]
| |\ \ \ \ \ \ \  
| * | | | | | | | 32fc345 2010-11-08 | working simpledb backend, not the fastest thing in the world [ticktock]
| * | | | | | | | 01f64b1 2010-11-08 | change var names for aws keys [ticktock]
| * | | | | | | | c56b9e7 2010-11-08 | switching to aws-java-sdk, for consistent reads (Apache 2 licenced) finished impl [ticktock]
| * | | | | | | | 934928d 2010-11-08 | renaming dep [ticktock]
| * | | | | | | | 4e966be 2010-11-07 | wip for simpledb backend [ticktock]
| | |_|_|/ / / /  
| |/| | | | | |   
* | | | | | | |   fc57549 2010-11-10 | Merge branch 'master' of https://github.com/paulpach/akka [Viktor Klang]
|\ \ \ \ \ \ \ \  
| * | | | | | | | 4dd5ad5 2010-11-09 | Check that either implementation or ref are specified [Paul Pacheco]
* | | | | | | | |   0538472 2010-11-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \ \  
| | |_|_|/ / / / /  
| |/| | | | | | |   
| * | | | | | | |   70e9dc9 2010-11-09 | Merge branch '473-krasserm' [Martin Krasser]
| |\ \ \ \ \ \ \ \  
| | |_|_|/ / / / /  
| |/| | | | | | |   
| | * | | | | | | 5550e74 2010-11-09 | Customizing routes to typed consumer actors (Scala and Java API) and refactorings. [Martin Krasser]
| | * | | | | | | 1913b32 2010-11-08 | Java API for customizing routes to consumer actors [Martin Krasser]
| | * | | | | | | 3962c56 2010-11-05 | Initial support for customizing routes to consumer actors. [Martin Krasser]
* | | | | | | | |   f6abd71 2010-11-09 | Merge branch 'master' of https://github.com/paulpach/akka into paulpach-master [Viktor Klang]
|\ \ \ \ \ \ \ \ \  
| |/ / / / / / / /  
|/| | / / / / / /   
| | |/ / / / / /    
| |/| | | | | |     
| * | | | | | | fc9c833 2010-11-07 | Add a ref="..." attribute to untyped-actor and typed-actor so that akka can use spring created beans [Paul Pacheco]
* | | | | | | |   4066515 2010-11-08 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| | |_|_|_|/ / /  
| |/| | | | | |   
| * | | | | | | 7668813 2010-11-08 | Closing ticket 476 - verify licenses [Viktor Klang]
* | | | | | | |   c8eae71 2010-11-08 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | 5b1dc62 2010-11-08 | Fixing optimistic sleep in actor model spec [Viktor Klang]
| | |_|/ / / /  
| |/| | | | |   
| * | | | | |   f252efe 2010-11-07 | Tweaking the encoding of map keys so that there is no possibility of stomping on the key used to hold the map keyset [ticktock]
| |\ \ \ \ \ \  
| | * | | | | | ced628a 2010-11-08 | Update sbt plugin [Peter Vlugter]
| | * | | | | | 75e5266 2010-11-07 | Adding support for user-controlled action for unmatched messages [Viktor Klang]
| * | | | | | | 2944ca5 2010-11-07 | Tweaking the encoding of map keys so that there is no possibility of stomping on the key used to hold the maps keyset [ticktock]
| |/ / / / / /  
| * | | | | | 96419d6 2010-11-06 | formatting [ticktock]
| * | | | | | 1381ac7 2010-11-06 | realized that there are other MIT deps in akka, re-enabling [ticktock]
| * | | | | | e9e64f8 2010-11-06 | commenting out memcached support pending license question [ticktock]
| * | | | | | 9d6a093 2010-11-06 | freeing up a few more bytes for memcached keys, reserving a slightly less common key to hold map keysets [ticktock]
| * | | | | | e88bfcd 2010-11-05 | updating akka-reference.conf and switching to KetamaConnectionFactory for spymemcached [ticktock]
| * | | | | |   7884333 2010-11-05 | Closing ticket-29 memcached protocol support for persistence backend. tested against memcached and membase. [ticktock]
| |\ \ \ \ \ \  
| | * | | | | | 752a261 2010-11-05 | refactored test - lazy persistent vector doesn't seem to work anymore [Debasish Ghosh]
| | * | | | | | f466afb 2010-11-05 | changed implementation of PersistentQueue so that it's now thread-safe [Debasish Ghosh]
| | * | | | | |   362e98d 2010-11-04 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \  
| | | * \ \ \ \ \   b631dd4 2010-11-04 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \  
| | | | |/ / / / /  
| | * | | | | | |   b15fe0f 2010-11-04 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | |/ / / / / /  
| | |/| / / / / /   
| | | |/ / / / /    
| | * | | | | | 1c2c0b2 2010-11-04 | Fixing issue with turning off secure cookies [Viktor Klang]
| * | | | | | | bd70f74 2010-11-03 | merged master [ticktock]
| * | | | | | |   f13519b 2010-11-03 | merged master [ticktock]
| |\ \ \ \ \ \ \  
| | | |/ / / / /  
| | |/| | | | |   
| * | | | | | | 1c76e4a 2010-11-03 | Cleanup and wait/retry on futures returned from spymemcached appropriately [ticktock]
| * | | | | | | 538813b 2010-11-01 | Memcached Storage Backend [ticktock]
* | | | | | | | a43009e 2010-11-08 | Fixed maven groupId and some other minor stuff [Jonas Bonér]
| |/ / / / / /  
|/| | | | | |   
* | | | | | | f198b96 2010-11-02 | Made remote message frame size configurable [Jonas Bonér]
* | | | | | |   841bc78 2010-11-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| | |/ / / / /  
| |/| | | | |   
| * | | | | | 53523ff 2010-11-02 | Fixing #491 and lots of tiny optimizations [Viktor Klang]
| | |/ / / /  
| |/| | | |   
* | | | | |   cbca588 2010-11-02 | merged with upstream [Jonas Bonér]
|\ \ \ \ \ \  
| * | | | | | ade1123 2010-11-02 | Merging of RemoteRequest and RemoteReply protocols completed [Jonas Bonér]
| * | | | | | 19d86c8 2010-10-28 | mid prococol refactoring [Jonas Bonér]
* | | | | | | 8e9ab0d 2010-10-31 | formatting [Jonas Bonér]
| |/ / / / /  
|/| | | | |   
* | | | | |   2095f50 2010-10-31 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \  
| * | | | | | 071d428 2010-10-30 | Switched to server managed for Supervisor config [Viktor Klang]
| * | | | | | 19d082f 2010-10-29 | Fixing ticket #498 [Viktor Klang]
| * | | | | |   a0b08e7 2010-10-29 | Merge with master [Viktor Klang]
| |\ \ \ \ \ \  
| | | |/ / / /  
| | |/| | | |   
| * | | | | | 92fce8a 2010-10-29 | Fixing ticket #481, sorry for rewriting stuff. May God have mercy. [Viktor Klang]
* | | | | | | 17fa581 2010-10-31 | Added remote client info to remote server life-cycle events [Jonas Bonér]
| |/ / / / /  
|/| | | | |   
* | | | | | c589c4f 2010-10-29 | removed trailing spaces [Jonas Bonér]
* | | | | |   bf4dbd1 2010-10-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | | 0906bb5 2010-10-29 | Cleaned up shutdown hook code and increased readability [Viktor Klang]
| * | | | | 2975192 2010-10-29 | Adding shutdown hook that clears logging levels registered by Configgy, closing ticket 486 [Viktor Klang]
| * | | | |   18a5088 2010-10-29 | Merge branch '458-krasserm' [Martin Krasser]
| |\ \ \ \ \  
| | * | | | | a2a27a1 2010-10-29 | Remove Camel staging repo [Martin Krasser]
| | * | | | | 01b1a0e 2010-10-29 | Fixed compile error after resolving merge conflict [Martin Krasser]
| | * | | | |   b85ac98 2010-10-29 | Merge remote branch 'remotes/origin/master' into 458-krasserm and resolved conflict in akka-camel/src/main/scala/CamelService.scala [Martin Krasser]
| | |\ \ \ \ \  
| | | | |/ / /  
| | | |/| | |   
| | * | | | | aa586ea 2010-10-26 | Upgrade to Camel 2.5 release candidate 2 [Martin Krasser]
| | * | | | | dcde837 2010-10-24 | Use a cached JMS ConnectionFactory. [Martin Krasser]
| | * | | | |   dee1743 2010-10-22 | Merge branch 'master' into 458-krasserm [Martin Krasser]
| | |\ \ \ \ \  
| | * | | | | | 561cdfc 2010-10-19 | Upgrade to Camel 2.5 release candidate leaving ActiveMQ at version 5.3.2 because of https://issues.apache.org/activemq/browse/AMQ-2935 [Martin Krasser]
| | * | | | | | a1b29e8 2010-10-16 | Added missing Consumer trait to example actor [Martin Krasser]
| | * | | | | | 513e9c5 2010-10-15 | Improve Java API to wait for endpoint activation/deactivation. Closes #472 [Martin Krasser]
| | * | | | | | 96b8b45 2010-10-15 | Improve API to wait for endpoint activation/deactivation. Closes #472 [Martin Krasser]
| | * | | | | | 769786d 2010-10-14 | Upgrade to Camel 2.5-SNAPSHOT, Jetty 7.1.6.v20100715 and ActiveMQ 5.4.1 [Martin Krasser]
* | | | | | | | 7745173 2010-10-29 | Changed default remote port from 9999 to 2552 (AKKA) :-) [Jonas Bonér]
|/ / / / / / /  
* | | | | | |   175317f 2010-10-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | 44a1e40 2010-10-28 | Refactored a CommonStorageBackend out of the KVBackend, tweaked the CassandraBackend to extend it, and tweaked the Vold and Riak backends to use the updated KVBackend [ticktock]
| * | | | | | |   221666f 2010-10-28 | Merge branch 'master' of https://github.com/jboner/akka into ticket-438 [ticktock]
| |\ \ \ \ \ \ \  
| | | |_|/ / / /  
| | |/| | | | |   
| | * | | | | |   8ab1f9b 2010-10-28 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | |\ \ \ \ \ \  
| | * | | | | | | 62cb147 2010-10-28 | More sugar on the syntax [momania]
| * | | | | | | |   297658a 2010-10-28 | merged master after the package rename changeset [ticktock]
| |\ \ \ \ \ \ \ \  
| | | |/ / / / / /  
| | |/| | | | | |   
| | * | | | | | | fbcc749 2010-10-28 | Optimization, 2 less allocs and 1 less field in actorref [Viktor Klang]
| | * | | | | | | 35cd9f4 2010-10-28 | Bumping Jackson version to 1.4.3 [Viktor Klang]
| | * | | | | | |   41b5fd2 2010-10-28 | Merge with master [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | | |_|_|/ / /  
| | | |/| | | | |   
| | * | | | | | | 370e612 2010-10-26 | Fixing Akka Camel with the new package [Viktor Klang]
| | * | | | | | | 473b66c 2010-10-26 | Fixing missing renames of se.scalablesolutions [Viktor Klang]
| | * | | | | | | 680ee7d 2010-10-26 | BREAKAGE: switching from se.scalablesolutions.akka to akka for all packages [Viktor Klang]
| * | | | | | | | a9a3bf8 2010-10-26 | Adding PersistentQueue to CassandraStorage [ticktock]
| * | | | | | | | 62ccb56 2010-10-26 | refactored KVStorageBackend to also work with Cassandra, refactored CassandraStorageBackend to use KVStorageBackend, so Cassandra backend is now fully compliant with the test specs and supports PersistentQueue and Vector.pop [ticktock]
| * | | | | | | | d3af697 2010-10-25 | Refactoring KVStoragebackend such that it is possible to create a Cassandra based impl [ticktock]
| * | | | | | | | 4752475 2010-10-25 | adding compatibility tests for cassandra (failing currently) and refactor KVStorageBackend to make some functionality easier to get at [ticktock]
| * | | | | | | | 3706ef0 2010-10-25 | Making PersistentVector.pop required, removed support for it being optional [ticktock]
| * | | | | | | | 9b392fa 2010-10-24 | updating common tests so that impls that dont support pop wont fail [ticktock]
| * | | | | | | |   6a55e0c 2010-10-24 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | 6e45e69 2010-10-24 | Finished off adding vector.pop as an optional operation [ticktock]
| * | | | | | | | | a0195ef 2010-10-24 | initial tests of vector backend remove [ticktock]
| * | | | | | | | | c16f083 2010-10-22 | Initial frontend code to support vector pop, and KVStorageBackend changes to put the scaffolding in place to support this [ticktock]
* | | | | | | | | | 18b7465 2010-10-28 | Added untrusted-mode for remote server which disallows client-managed remote actors and al lifecycle messages [Jonas Bonér]
| |_|_|/ / / / / /  
|/| | | | | | | |   
* | | | | | | | |   f6547b1 2010-10-27 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| | |_|_|/ / / / /  
| |/| | | | | | |   
| * | | | | | | |   ba03634 2010-10-27 | Merge branch 'master' into fsm [unknown]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | 847e0c7 2010-10-27 | polishing up code [imn]
| * | | | | | | | | efb99fc 2010-10-27 | use nice case objects for the states :-) [imn]
| * | | | | | | | | b0d0b27 2010-10-26 | refactoring the FSM part [imn]
| | |_|_|/ / / / /  
| |/| | | | | | |   
* | | | | | | | | 4d58db2 2010-10-27 | Improved secure cookie generation script [Jonas Bonér]
* | | | | | | | | 429675e 2010-10-26 | converted tabs to spaces [Jonas Bonér]
* | | | | | | | | 73902d8 2010-10-26 | Changed the script to spit out a full akka.conf file with the secure cookie [Jonas Bonér]
* | | | | | | | |   68ad593 2010-10-26 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | | 07866a4 2010-10-26 | Adding possibility to take naps between scans for finished future, closing ticket #449 [Viktor Klang]
| * | | | | | | | 58bc55c 2010-10-26 | Added support for remote agent [Viktor Klang]
* | | | | | | | |   52f5e5e 2010-10-26 | Completed Erlang-style cookie handshake between RemoteClient and RemoteServer [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| |/ / / / / / / /  
| * | | | | | | | b9110d6 2010-10-26 | Switching to non-SSL repo for jBoss [Viktor Klang]
| |/ / / / / / /  
* | | | | | | | cbc1011 2010-10-26 | Added Erlang-style secure cookie authentication for remote client/server [Jonas Bonér]
* | | | | | | |   00feb8a 2010-10-26 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | 2979159 2010-10-25 | Fixing a cranky compiler whine on a match statement [Viktor Klang]
| * | | | | | | f11d339 2010-10-25 | Making ThreadBasedDispatcher Unbounded if no capacity specced and fix a possible mem leak in it [Viktor Klang]
| * | | | | | | b0001ea 2010-10-25 | Handling Interrupts for ThreadBasedDispatcher, EBEDD and EBEDWSD [Viktor Klang]
| * | | | | | |   6a118f8 2010-10-25 | Merge branch 'wip-rework_dispatcher_config' [Viktor Klang]
| |\ \ \ \ \ \ \  
| | * \ \ \ \ \ \   2e1019a 2010-10-25 | Merge branch 'master' of github.com:jboner/akka into wip-rework_dispatcher_config [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | 79ea0f8 2010-10-25 | Adding a flooding test to reproduce error reported by user [Viktor Klang]
| | * | | | | | | | dc958f6 2010-10-25 | Added the ActorModel specification to HawtDispatcher and EBEDWSD [Viktor Klang]
| | * | | | | | | | af1f4e9 2010-10-25 | Added tests for suspend/resume [Viktor Klang]
| | * | | | | | | | ac241e3 2010-10-25 | Added test for dispatcher parallelism [Viktor Klang]
| | * | | | | | | | 74df2a8 2010-10-25 | Adding test harness for ActorModel (Dispatcher), work-in-progress [Viktor Klang]
| | * | | | | | | |   d694f85 2010-10-25 | Merge branch 'master' of github.com:jboner/akka into wip-rework_dispatcher_config [Viktor Klang]
| | |\ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \   6b7c2fc 2010-10-25 | Merge branch 'master' into wip-rework_dispatcher_config [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \  
| | | | |_|_|/ / / / /  
| | | |/| | | | | | |   
| | * | | | | | | | | 3beb1a5 2010-10-25 | Removed boilerplate, added final optmization [Viktor Klang]
| | * | | | | | | | | ed9ec3f 2010-10-25 | Rewrote timed shutdown facility, causes less than 5% overhead now [Viktor Klang]
| | * | | | | | | | | 67ac857 2010-10-24 | Naïve implementation of timeout completed [Viktor Klang]
| | * | | | | | | | | d187c22 2010-10-24 | Renamed stopAllLinkedActors to stopAllAttachedActors [Viktor Klang]
| | * | | | | | | | | dbd2db6 2010-10-24 | Moved active flag into MessageDispatcher and let it handle the callbacks, also fixed race in DataFlowSpec [Viktor Klang]
| | * | | | | | | | | b80fb90 2010-10-24 | Fixing race-conditions, now works albeit inefficiently when adding/removing actors rapidly [Viktor Klang]
| | * | | | | | | | | 594efe9 2010-10-24 | Removing unused code and the isShutdown method [Viktor Klang]
| | * | | | | | | | | f767215 2010-10-24 | Tests green, config basically in place, need to work on start/stop semantics and countdowns [Viktor Klang]
| | * | | | | | | | |   3e286cc 2010-10-23 | Merge branch 'master' of github.com:jboner/akka into wip-rework_dispatcher_config [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|/ / /  
| | | |/| | | | | | |   
| | * | | | | | | | | a0b8d6c 2010-10-22 | WIP [Viktor Klang]
| | | |_|_|_|/ / / /  
| | |/| | | | | | |   
| * | | | | | | | | d80dcfb 2010-10-25 | Updating Netty to 3.2.3, closing ticket #495 [Viktor Klang]
| | |_|_|_|/ / / /  
| |/| | | | | | |   
| * | | | | | | | e1fa9eb 2010-10-25 | added more tests and fixed corner case to TypedActor Option return value [Viktor Klang]
| * | | | | | | | 4bbe8f7 2010-10-25 | Closing ticket #471 [Viktor Klang]
| | |_|_|/ / / /  
| |/| | | | | |   
| * | | | | | | dd7a062 2010-10-25 | Closing ticket #460 [Viktor Klang]
| | |_|/ / / /  
| |/| | | | |   
| * | | | | | bd62b54 2010-10-25 | Fixing #492 [Viktor Klang]
| | |/ / / /  
| |/| | | |   
| * | | | |   58df0dc 2010-10-22 | Merge branch '479-krasserm' [Martin Krasser]
| |\ \ \ \ \  
| | |/ / / /  
| |/| | | |   
| | * | | | d1753b8 2010-10-21 | Closes #479. Do not register listeners when CamelService is turned off by configuration [Martin Krasser]
* | | | | | 903ce01 2010-10-26 | Fixed bug in startLink and friends + Added cryptographically secure cookie generator [Jonas Bonér]
|/ / / / /  
* | | | | 4a8b933 2010-10-21 | Final tweaks to common KVStorageBackend factored out of Riak and Voldemort backends [ticktock]
* | | | | c546f00 2010-10-21 | Voldemort Tests now working as well as Riak [ticktock]
* | | | | aa4a522 2010-10-21 | riak working, all vold tests work individually, just not in sequence [ticktock]
* | | | | f1f4392 2010-10-20 | refactoring complete, vold tests still acting up [ticktock]
* | | | | 7f6a282 2010-10-21 | Improving SupervisorConfig for Java [Viktor Klang]
|/ / / /  
* | | | a6d3b0b 2010-10-21 | Changes publication from sourcess to sources [Viktor Klang]
* | | |   d25d83d 2010-10-21 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * \ \ \   ce72e58 2010-10-20 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| |\ \ \ \  
| | * | | | b197259 2010-10-20 | Reducing object creation per ActorRef + removed unsafe concurrent publication [Viktor Klang]
| * | | | | 14f1126 2010-10-20 | remove usage of 'actor' function [momania]
| |/ / / /  
| * | | | aa90413 2010-10-20 | fix for the fix for #480 : new version of redisclient [Debasish Ghosh]
| * | | | 2e46c3e 2010-10-19 | fix for issue #480 Regression multibulk replies redis client with a new version of redisclient [Debasish Ghosh]
| * | | | ddfb15e 2010-10-19 | Added Java API constructor to supervision configuration [Viktor Klang]
| * | | | 470cd00 2010-10-19 | Refining Supervision API and remove AllForOne, OneForOne and replace with AllForOneStrategy, OneForOneStrategy etc [Viktor Klang]
| * | | | 3af056f 2010-10-19 | Moved Faulthandling into Supvervision [Viktor Klang]
| * | | | 31aa8f7 2010-10-18 | Refactored declarative supervision, removed ScalaConfig and JavaConfig, moved things around [Viktor Klang]
| * | | | 6697578 2010-10-18 | Removing local caching of actor self fields [Viktor Klang]
| * | | |   eee0d32 2010-10-15 | Merge branch 'master' of https://github.com/jboner/akka [ticktock]
| |\ \ \ \  
| | * | | | b4ef705 2010-10-15 | Closing #456 [Viktor Klang]
| * | | | | 31620a4 2010-10-15 | adding default riak config to akka-reference.conf [ticktock]
| |/ / / /  
| * | | | b8acb06 2010-10-15 | final tweaks before pushing to master [ticktock]
| * | | |   b15f417 2010-10-15 | merging master [ticktock]
| |\ \ \ \  
| * \ \ \ \   fbddef7 2010-10-15 | Merge with master [Viktor Klang]
| |\ \ \ \ \  
| * | | | | | 7b465bc 2010-10-14 | added fork of riak-java-pb-client to embedded repo, udpated backend to use new code therein, and all tests pass [ticktock]
| * | | | | | 38ed24a 2010-10-13 | fix an inconsistency [ticktock]
| * | | | | | 75ecbb5 2010-10-13 | Initial Port of the Voldemort Backend to Riak [ticktock]
| * | | | | | 6ae5abf 2010-10-12 | First pass at Riak Backend [ticktock]
| * | | | | | bf22792 2010-10-09 | Initial Scaffold of Riak Module [ticktock]
* | | | | | | 50bb069 2010-10-21 | Made Format serializers serializable [Jonas Bonér]
| |_|/ / / /  
|/| | | | |   
* | | | | | b929fd1 2010-10-15 | Added Java API for Supervise [Viktor Klang]
| |/ / / /  
|/| | | |   
* | | | | 56ddc82 2010-10-14 | Closing ticket #469 [Viktor Klang]
| |/ / /  
|/| | |   
* | | | b019914 2010-10-13 | Removed duplicate code [Viktor Klang]
* | | |   06d49cc 2010-10-12 | Merge branch 'master' into Kahlen-master [Viktor Klang]
|\ \ \ \  
| * \ \ \   76d646e 2010-10-12 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \  
| * | | | | 5d6b3c8 2010-10-12 | Improvements to actor-component API docs [Martin Krasser]
* | | | | | f71a248 2010-10-12 | Merging in CouchDB support [Viktor Klang]
* | | | | |   4e49a64 2010-10-12 | Merge branch 'master' of http://github.com/Kahlen/akka into Kahlen-master [Viktor Klang]
|\ \ \ \ \ \  
| |_|/ / / /  
|/| | | | |   
| * | | | | e617000 2010-10-06 | completed!! [Kahlen]
| * | | | | e58f07b 2010-10-06 | merge with yllan's commit [Kahlen]
| * | | | |   039c707 2010-10-06 | Merge branch 'couchdb' of http://github.com/yllan/akka into couchdb [Kahlen]
| |\ \ \ \ \  
| | * | | | | 49a3bd7 2010-10-06 | Copied the actor spec from mongo and voldemort. [Yung-Luen Lan]
| | * | | | | 50255af 2010-10-06 | clean up db for actor test. [Yung-Luen Lan]
| | * | | | | fe6ec6c 2010-10-06 | Add actor spec (but didn't pass) [Yung-Luen Lan]
| | * | | | | abe5f64 2010-10-06 | Add tags to gitignore. [Yung-Luen Lan]
| * | | | | | 7efba6a 2010-10-06 | Merge my stashed code for removeMapStorageFor [Kahlen]
| |/ / / / /  
| * | | | |   212f268 2010-10-06 | Merge branch 'master' of http://github.com/jboner/akka into couchdb [Yung-Luen Lan]
| |\ \ \ \ \  
| * \ \ \ \ \   6b20ed2 2010-10-05 | Merge branch 'couchdb' of http://github.com/Kahlen/akka into couchdb [Yung-Luen Lan]
| |\ \ \ \ \ \  
| | * | | | | | ed47beb 2010-10-05 | my first commit [Kahlen Lin]
| * | | | | | | 04871d8 2010-10-05 | Add couchdb support [Yung-Luen Lan]
| |/ / / / / /  
| * | | | | |   32f4a63 2010-10-04 | Merge branch 'master' of http://github.com/jboner/akka [Yung-Luen Lan]
| |\ \ \ \ \ \  
| * | | | | | | bad99fd 2010-10-04 | Add couch db plugable persistence module scheme. [Yung-Luen Lan]
* | | | | | | |   8b55407 2010-10-12 | Merge branch 'ticket462' [Viktor Klang]
|\ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \   9c73270 2010-10-12 | Merge branch 'master' of github.com:jboner/akka into ticket462 [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| | | |_|_|/ / / /  
| | |/| | | | | |   
| * | | | | | | | db6f951 2010-10-11 | Switching to volatile int instead of AtomicInteger until ticket 384 is done [Viktor Klang]
| * | | | | | | | ecb0f22 2010-10-11 | Tuned test to work, also fixed a bug in the restart logic [Viktor Klang]
| * | | | | | | | a001552 2010-10-11 | Rewrote restart code, resetting restarts outside tiem window etc [Viktor Klang]
| * | | | | | | | 7fd6ba8 2010-10-11 | Initial attempt at suspend/resume [Viktor Klang]
* | | | | | | | | 9dd962c 2010-10-12 | Fixing #467 [Viktor Klang]
* | | | | | | | | 5fd0779 2010-10-12 | Adding implicit dispatcher to spawn [Viktor Klang]
* | | | | | | | | 9eb3f80 2010-10-12 | Removing anonymous actor methods as per discussion on ML [Viktor Klang]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | |   f0d581e 2010-10-11 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | 389a588 2010-10-11 | Switching to Switch and restructuring some EBEDD code [Viktor Klang]
| * | | | | | | e0f1690 2010-10-11 | Switching to Switch for EBEDWSD active status [Viktor Klang]
| * | | | | | | 1ce0c7c 2010-10-11 | Fixing performance regression [Viktor Klang]
| * | | | | | | d534971 2010-10-11 | Fixed akka-jta bug and added tests [Viktor Klang]
| * | | | | | |   cf86ae2 2010-10-10 | Merge branch 'ticket257' [Viktor Klang]
| |\ \ \ \ \ \ \  
| | * | | | | | | f1d2bae 2010-10-10 | Switched to JavaConversion wrappers [Viktor Klang]
| | * | | | | | | adbfdf7 2010-10-07 | added java API for PersistentMap, PersistentVector [Michael Kober]
| * | | | | | | |   aa82abd 2010-10-10 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | | |_|_|_|/ / /  
| | |/| | | | | |   
| * | | | | | | | 97e043b 2010-10-10 | Removed errornous method in Future [Jonas Bonér]
* | | | | | | | | 8022fa3 2010-10-11 | Dynamic message routing to actors. Closes #465 [Martin Krasser]
* | | | | | | | | c443453 2010-10-11 | Refactorings [Martin Krasser]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | |   698524d 2010-10-09 | Merge branch '457-krasserm' [Martin Krasser]
|\ \ \ \ \ \ \ \  
| * | | | | | | | 2a602a5 2010-10-09 | Tests for Message Java API [Martin Krasser]
| * | | | | | | | 617478e 2010-10-09 | Java API for Message and Failure classes [Martin Krasser]
* | | | | | | | | efe1aea 2010-10-09 | Folding 3 volatiles into 1, all transactor-based stuff [Viktor Klang]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | |   75ff6f3 2010-10-09 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| * | | | | | | | bdeaa74 2010-10-08 | Removed all allocations from the canRestart-method [Viktor Klang]
| * | | | | | | |   b61fd12 2010-10-08 | Merge branch 'master' of http://github.com/andreypopp/akka into andreypopp-master [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \   8ff29b6 2010-10-08 | Merge branch 'master' of http://github.com/jboner/akka [Andrey Popp]
| | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | fa2268a 2010-10-08 | after merge cleanup [momania]
| | | * | | | | | | |   53bde24 2010-10-08 | Merge branch 'master' into amqp [momania]
| | | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | | 31e74bf 2010-10-08 | - Finshed up java api for RPC - Made case objects 'java compatible' via getInstance function - Added RPC examples in java examplesession [momania]
| | | * | | | | | | | | 65e96e7 2010-10-08 | - made channel and connection callback java compatible [momania]
| | | * | | | | | | | | 06ec4ce 2010-10-08 | - changed exchange types to case classes for java compatibility - made java api for string and protobuf convenience producers/consumers - implemented string and protobuf java api examples [momania]
| | | * | | | | | | | | c6470b0 2010-09-24 | initial take on java examples [momania]
| | | * | | | | | | | | 489db00 2010-09-24 | add test filter to the amqp project [momania]
| | | * | | | | | | | | bd8e677 2010-09-24 | wait a bit longer than the deadline... so test always works... [momania]
| | | * | | | | | | | | 0583392 2010-09-24 | renamed tests to support integration test selection via sbt [momania]
| | | * | | | | | | | |   a6aea44 2010-09-24 | Merge branch 'master' into amqp [momania]
| | | |\ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | 3dad2ce 2010-09-24 | back to original project settings :S [momania]
| | | * | | | | | | | | | 974c22a 2010-09-23 | Disable test before push [momania]
| | | * | | | | | | | | | 3b6fef8 2010-09-23 | Make tests pass again... [momania]
| | | * | | | | | | | | | 5df4303 2010-09-23 | Updated test to changes in api [momania]
| | | * | | | | | | | | | f4b6fb4 2010-09-23 | - Adding java api to AMQP module - Reorg of params, especially declaration attributes and exhange name/params [momania]
| | * | | | | | | | | | | 9d18fac 2010-10-08 | Rework restart strategy restart decision. [Andrey Popp]
| | * | | | | | | | | | | e3a8f9b 2010-10-08 | Add more specs for restart strategy params. [Andrey Popp]
| | | |_|_|_|_|_|_|/ / /  
| | |/| | | | | | | | |   
| * | | | | | | | | | | 54c5ddf 2010-10-08 | Switching to a more accurate approach that involves no locking and no thread locals [Viktor Klang]
| | |_|_|/ / / / / / /  
| |/| | | | | | | | |   
| * | | | | | | | | | 69b856e 2010-10-08 | Serialization of RemoteActorRef unborked [Viktor Klang]
| * | | | | | | | | | 0831d12 2010-10-08 | Removing isInInitialization, reading that from actorRefInCreation, -1 volatile field per actorref [Viktor Klang]
| * | | | | | | | | | 90831a9 2010-10-08 | Removing linkedActorsAsList, switching _linkedActors to be a volatile lazy val instead of Option with lazy semantics [Viktor Klang]
| * | | | | | | | | |   c045dd1 2010-10-08 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | 579526d 2010-10-04 | register client managed remote actors by uuid [Michael Kober]
| | | |_|_|_|/ / / / /  
| | |/| | | | | | | |   
| * | | | | | | | | | 9976121 2010-10-08 | Changed != SHUTDOWN to == RUNNING [Viktor Klang]
| * | | | | | | | | | a43fcb0 2010-10-08 | -1 volatile field in ActorRef, trapExit is migrated into faultHandler [Viktor Klang]
| |/ / / / / / / / /  
| * | | | | | | | |   2080f5b 2010-10-07 | Merge remote branch 'remotes/origin/master' into java-api [Martin Krasser]
| |\ \ \ \ \ \ \ \ \  
| | |_|_|_|/ / / / /  
| |/| | | | | | | |   
| | * | | | | | | | 43f5e7a 2010-10-07 | Fixing bug where ReceiveTimeout wasn´t turned off on actorref.stop [Viktor Klang]
| | * | | | | | | |   7bacfea 2010-10-07 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | cec86cc 2010-10-07 | fix:ensure that typed actor module is enabled in typed actor methods [Michael Kober]
| | * | | | | | | | | 78c0b81 2010-10-07 | Fixing UUID remote request bug [Viktor Klang]
| | |/ / / / / / / /  
| * | | | | | | | | 80d377a 2010-10-07 | Tests for Java API support [Martin Krasser]
| * | | | | | | | | cacba81 2010-10-07 | Moved Java API support to japi package. [Martin Krasser]
| * | | | | | | | | f3cd17f 2010-10-06 | Minor reformattings [Martin Krasser]
| * | | | | | | | | beb77b1 2010-10-06 | Java API for CamelServiceManager and CamelContextManager (refactorings) [Martin Krasser]
| * | | | | | | | | 77d5f39 2010-10-05 | Java API for CamelServiceManager and CamelContextManager (usage of JavaAPI.Option) [Martin Krasser]
| * | | | | | | | | 353d01c 2010-10-05 | CamelServiceManager.service returns Option[CamelService] (Scala API) CamelServiceManager.getService() returns Option[CamelService] (Java API) Re #457 [Martin Krasser]
| | |_|_|_|_|/ / /  
| |/| | | | | | |   
* | | | | | | | | 88ac683 2010-10-08 | Added serialization of 'hotswap' stack + tests [Jonas Bonér]
* | | | | | | | | e274d6c 2010-10-08 | Made 'hotswap' a Stack instead of Option + addded 'RevertHotSwap' and 'unbecome' + added tests for pushing and popping the hotswap stack [Jonas Bonér]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | | ac08784 2010-10-06 | Upgraded to Scala 1.2 final. [Jonas Bonér]
* | | | | | | |   7bf7cb4 2010-10-06 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| | |/ / / / / /  
| |/| | | | | |   
| * | | | | | | 9e561ec 2010-10-05 | Removed pointless check for N/A as Actor ID [Viktor Klang]
| * | | | | | | 1586fd7 2010-10-05 | Added some more methods to Index, as well as added return-types for put and remove as well as restructured some of the code [Viktor Klang]
| * | | | | | | d3ffd41 2010-10-05 | Removing more boilerplate from AkkaServlet [Viktor Klang]
| * | | | | | |   4490355 2010-10-05 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \  
| | * | | | | | | 0fc3f2f 2010-10-04 | porting a ticket 450 change over [ticktock]
| | * | | | | | |   bfd647a 2010-10-04 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| | |\ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \   6f18873 2010-10-03 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| | |\ \ \ \ \ \ \ \  
| | | | |/ / / / / /  
| | | |/| | | | | |   
| | * | | | | | | | 97a5c05 2010-10-01 | Added tests of proper null handling for Ref,Vector,Map,Queue and voldemort impl/tweak [ticktock]
| | * | | | | | | | 3411ad6 2010-09-30 | two more stub tests in Vector Spec [ticktock]
| | * | | | | | | | f8d77e0 2010-09-30 | More VectorStorageBackend tests plus an abstract Ticket343Test with a working VoldemortImpl [ticktock]
| | * | | | | | | | 8140700 2010-09-30 | Map Spec [ticktock]
| | * | | | | | | | 984de30 2010-09-30 | Moved implicit Ordering(ArraySeq[Byte]) to a new PersistentMapBinary companion object  and created an implicit Ordering(Array[Byte]) that can be used on the backends too [ticktock]
| | * | | | | | | |   578b9df 2010-09-30 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| | |\ \ \ \ \ \ \ \  
| | | | |_|_|_|/ / /  
| | | |/| | | | | |   
| | * | | | | | | | 7c2c550 2010-09-29 | Initial QueueStorageBackend Spec [ticktock]
| | * | | | | | | |   131d201 2010-09-29 | Merge branch 'master' of github.com:jboner/akka into ticket-443 [ticktock]
| | |\ \ \ \ \ \ \ \  
| | * | | | | | | | | 46f1f97 2010-09-29 | Initial QueueStorageBackend Spec [ticktock]
| | * | | | | | | | | 77bda9f 2010-09-28 | Initial Spec for MapStorageBackend [ticktock]
| | * | | | | | | | | 2218198 2010-09-28 | Persistence Compatibility Test Harness and Voldemort Implementation [ticktock]
| | * | | | | | | | | b234bd6 2010-09-28 | Initial Sketch of Persistence Compatibility Tests [ticktock]
| | * | | | | | | | | 2cb5faf 2010-09-27 | Initial PersistentRef spec [ticktock]
| * | | | | | | | | | 281a7c4 2010-10-05 | Cleaned up code and added more comments [Viktor Klang]
| | |_|_|_|/ / / / /  
| |/| | | | | | | |   
| * | | | | | | | | 3a0babf 2010-10-04 | Fixing ReceiveTimeout as per #446, now need to do: self.receiveTimeout = None to shut it off [Viktor Klang]
| * | | | | | | | | 8525f18 2010-10-04 | Ensure that at most 1 CometSupport is created per servlet. and remove boiler [Viktor Klang]
* | | | | | | | | | 02f5116 2010-10-06 | Upgraded to AspectWerkz 2.2.2 with new fix for Scala load-time weaving [Jonas Bonér]
* | | | | | | | | | 13ee448 2010-10-04 | Changed ReflectiveAccess to work with enterprise module [Jonas Bonér]
|/ / / / / / / / /  
* | | | | | | | | 5091f0a 2010-10-04 | Creating a Main object for Akka-http [Viktor Klang]
* | | | | | | | | 29a901b 2010-10-04 | Moving EmbeddedAppServer to akka-http and closing #451 [Viktor Klang]
* | | | | | | | | ea5f214 2010-10-04 | Fixing ticket #450, lifeCycle = Permanent => boilerplate reduction [Viktor Klang]
| |_|_|/ / / / /  
|/| | | | | | |   
* | | | | | | |   a210777 2010-10-02 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \   8be9a33 2010-10-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | 001e811 2010-10-02 | Added hasListener [Jonas Bonér]
| | |_|_|/ / / / /  
| |/| | | | | | |   
* | | | | | | | | 3a76f5a 2010-10-02 | Minor code cleanup of config file load [Viktor Klang]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | |   0446ac2 2010-10-02 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | |   bf33856 2010-09-30 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | * \ \ \ \ \ \   ca70416 2010-09-30 | merged ticket444 [Michael Kober]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | 25d6677 2010-09-28 | closing ticket 444, moved RemoteActorSet to ActorRegistry [Michael Kober]
| * | | | | | | | |   71aac38 2010-09-30 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| | * | | | | | | | 26dc090 2010-09-30 | fixed test [Michael Kober]
| | * | | | | | | |   a850810 2010-09-30 | merged master [Michael Kober]
| | |\ \ \ \ \ \ \ \  
| | * | | | | | | | | 212f721 2010-09-28 | closing ticket441, implemented typed actor methods for ActorRegistry [Michael Kober]
| * | | | | | | | | | 251b417 2010-09-30 | minor edit [Jonas Bonér]
| | |/ / / / / / / /  
| |/| | | | | | | |   
| * | | | | | | | | 0e79d48 2010-09-30 | CamelService can now be turned off by configuration. Closes #447 [Martin Krasser]
| | |_|_|/ / / / /  
| |/| | | | | | |   
| * | | | | | | |   5aaecc4 2010-09-29 | Merge branch 'ticket440' [Michael Kober]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | 7138505 2010-09-29 | added Java API [Michael Kober]
| | * | | | | | | | ddb6d9e 2010-09-29 | closing ticket440, implemented typed actor with constructor args [Michael Kober]
* | | | | | | | | | 19df3b2 2010-10-02 | Changing order of priority for akka.config and adding option to specify a mode [Viktor Klang]
* | | | | | | | | | 6928661 2010-10-02 | Updating Atmosphere to 0.6.2 and switching to using SimpleBroadcaster [Viktor Klang]
* | | | | | | | | | c9ca948 2010-09-29 | Changing impl of ReflectiveAccess to log to debug [Viktor Klang]
|/ / / / / / / / /  
* | | | | | | | | 7af91bc 2010-09-29 | new version of redisclient containing a redis based persistent deque [Debasish Ghosh]
* | | | | | | | |   de215c1 2010-09-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| * | | | | | | | | 6e8b23e 2010-09-29 | refactoring to remove compiler warnings reported by Viktor [Debasish Ghosh]
| |/ / / / / / / /  
* | | | | | | | | 954a11b 2010-09-29 | Refactored ExecutableMailbox to make it accessible for other implementations [Jonas Bonér]
* | | | | | | | |   e4a29cb 2010-09-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| |/ / / / / / / /  
| * | | | | | | | 9434404 2010-09-28 | Removing runActorInitialization volatile field, replace with isRunning check [Viktor Klang]
| * | | | | | | | 6af2a52 2010-09-28 | Removing isDeserialize volatile field since it doesn´t seem to have any use [Viktor Klang]
| * | | | | | | | d876887 2010-09-28 | Removing classloader field (volatile) from LocalActorRef, wasn´t used [Viktor Klang]
| | |/ / / / / /  
| |/| | | | | |   
| * | | | | | | b0e9941 2010-09-28 | Replacing use of == null and != null for Scala [Viktor Klang]
| * | | | | | | 8bedc2b 2010-09-28 | Fixing compiler issue that caused problems when compiling with JDT [Viktor Klang]
| | |/ / / / /  
| |/| | | | |   
| * | | | | |   72d8859 2010-09-27 | Merge branch 'master' of github.com:jboner/akka [ticktock]
| |\ \ \ \ \ \  
| | * | | | | | a47ce5b 2010-09-27 | Fixing ticket 413 [Viktor Klang]
| | |/ / / / /  
| * | | | | | ae2716c 2010-09-27 | Finished off Queue API [ticktock]
| * | | | | | 8c67399 2010-09-27 | Further Queue Impl [ticktock]
| * | | | | |   b714003 2010-09-27 | Merge branch 'master' of https://github.com/jboner/akka [ticktock]
| |\ \ \ \ \ \  
| | |/ / / / /  
| * | | | | |   47401a9 2010-09-25 | Merge branch 'master' of github.com:jboner/akka [ticktock]
| |\ \ \ \ \ \  
| * | | | | | | 191ff4c 2010-09-25 | Made dequeue operation retriable in case of errors, switched from Seq to Stream for queue removal [ticktock]
| * | | | | | | e6a0cf5 2010-09-24 | more queue implementation [ticktock]
* | | | | | | |   05bc749 2010-09-27 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| | |_|/ / / / /  
| |/| | | | | |   
| * | | | | | |   078d11b 2010-09-26 | Merge branch 'ticket322' [Michael Kober]
| |\ \ \ \ \ \ \  
| | * | | | | | | 2c969fa 2010-09-24 | closing ticket322 [Michael Kober]
* | | | | | | | | 11c1bb3 2010-09-27 | Support for more durable mailboxes [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | |   ecad8fe 2010-09-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| | |_|/ / / / /  
| |/| | | | | |   
| * | | | | | | 71a56e9 2010-09-25 | Small change in the config file [David Greco]
| | |/ / / / /  
| |/| | | | |   
* | | | | | |   4f25ffb 2010-09-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| |/ / / / / /  
| * | | | | | 49efebc 2010-09-24 | Refactor to utilize only one voldemort store per datastructure type [ticktock]
| * | | | | |   33e491e 2010-09-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
* | \ \ \ \ \ \   771d370 2010-09-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
|/| / / / / / /   
| |/ / / / / /    
| * | | | | |   6ca5e37 2010-09-24 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   a17837e 2010-09-24 | Merge remote branch 'ticktock/master' [ticktock]
| | |\ \ \ \ \ \  
| | | |/ / / / /  
| | |/| | | | |   
| | | * | | | | 0a62106 2010-09-23 | More Queue impl [ticktock]
| | | * | | | | ffcd6b3 2010-09-23 | Refactoring Vector to only use 1 voldemort store, and setting up for implementing Queue [ticktock]
| | | | |/ / /  
| | | |/| | |   
| * | | | | | c2462dd 2010-09-24 | API-docs improvements. [Martin Krasser]
| |/ / / / /  
| * | | | |   d3744e6 2010-09-24 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \  
| | * | | | | a1f35fa 2010-09-24 | reducing boilerplate imports with package objects [Debasish Ghosh]
| * | | | | | 4f578b5 2010-09-24 | Only execute tests matching *Test by default in akka-camel and akka-sample-camel. Rename stress tests in akka-sample-camel to *TestStress. [Martin Krasser]
| * | | | | | c58a8c7 2010-09-24 | Only execute tests matching *Test by default in akka-camel and akka-sample-camel. Rename stress tests in akka-sample-camel to *TestStress. [Martin Krasser]
| * | | | | | 1505c3a 2010-09-24 | Organized imports [Martin Krasser]
| |/ / / / /  
| * | | | | 7606dda 2010-09-24 | Renamed two akka-camel tests from *Spec to *Test [Martin Krasser]
| * | | | | b7005bc 2010-09-24 | Aligned the hbase test to the new mechanism for optionally running integration tests [David Greco]
| * | | | | 3e43f6c 2010-09-24 | Aligned the hbase test to the new mechanism for optionally running integration tests [David Greco]
| * | | | | 83b2450 2010-09-24 | Aligned the hbase test to the new mechanism for optionally running integration tests [David Greco]
| * | | | | b76c766 2010-09-24 | Aligned the hbase test to the new mechanism for optionally running integration tests [David Greco]
| |/ / / /  
| * | | |   09a1f54 2010-09-23 | Merge with master [Viktor Klang]
| |\ \ \ \  
| | * | | | 7d2d9e1 2010-09-23 | Corrected the optional run of the hbase tests [David Greco]
| * | | | | c5e2fac 2010-09-23 | Added support for having integration tests and stresstest optionally enabled [Viktor Klang]
| |/ / / /  
| * | | |   6846c0c 2010-09-23 | Merge branch 'master' of github.com:jboner/akka [David Greco]
| |\ \ \ \  
| | * \ \ \   39b8648 2010-09-23 | Merge branch 'serialization-dg-wip' [Debasish Ghosh]
| | |\ \ \ \  
| | | * | | | 175dcd8 2010-09-23 | removed unnecessary imports [Debasish Ghosh]
| | | * | | | 59a2881 2010-09-22 | Integrated sjson type class based serialization into Akka - some backward incompatible changes there [Debasish Ghosh]
| * | | | | | 31f0194 2010-09-23 | Now the hbase tests don't spit out too much logs, made the running of the hbase tests optional [David Greco]
| |/ / / / /  
| * | | | |   2a2bdde 2010-09-23 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \  
| | * \ \ \ \   e1a7944 2010-09-23 | Merging with ticktock [Viktor Klang]
| | |\ \ \ \ \  
| * | | | | | | a539313 2010-09-23 | Re-adding voldemort [Viktor Klang]
| * | | | | | |   919cdf2 2010-09-23 | Merging with ticktock [Viktor Klang]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| |/| / / / / /   
| | |/ / / / /    
| | * | | | | 264225a 2010-09-23 | Removing BDB as a test-runtime dependency [ticktock]
| * | | | | | a9b5899 2010-09-23 | Temporarily removing voldemort module pending license resolution [Viktor Klang]
| * | | | | |   e2ac6bb 2010-09-23 | Adding Voldemort persistence plugin [Viktor Klang]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 5abf5cd 2010-09-21 | making the persistent data sturctures non lazy in the ActorTest made things work...hmm seems strange though [ticktock]
| | * | | | | 81b61a2 2010-09-21 | Adding a direct test of PersistentRef, since after merging master over, something is blowing up there with the Actor tests [ticktock]
| | * | | | | ed7cfe2 2010-09-21 | adding sjson as a test dependency to voldemort persistence [ticktock]
| | * | | | |   617eac2 2010-09-21 | merge master of jboner/akka [ticktock]
| | |\ \ \ \ \  
| | | |/ / / /  
| | * | | | | aa69604 2010-09-20 | provide better voldemort configuration support, and defaults definition in akka-reference.conf, and made the backend more easily testable [ticktock]
| | * | | | | 4afdbf3 2010-09-20 | provide better voldemort configuration support, and defaults definition in akka-reference.conf, and made the backend more easily testable [ticktock]
| | * | | | | c2295bb 2010-09-20 | fixing the formatting damage I did [ticktock]
| | * | | | | e9cb289 2010-09-16 | sorted set hand serialization and working actor test [ticktock]
| | * | | | | f69d1b7 2010-09-15 | tests of PersistentRef,Map,Vector StorageBackend working [ticktock]
| | * | | | | 364ad7a 2010-09-15 | more tests, working on map api [ticktock]
| | * | | | | e617b13 2010-09-15 | Initial tests working with bdb backed voldemort, [ticktock]
| | * | | | | 46c24fd 2010-09-15 | switched voldemort to log4j-over-slf4j [ticktock]
| | * | | | | b181612 2010-09-15 | finished ref map vector and some initial test scaffolding [ticktock]
| | * | | | | 1de3c3d 2010-09-14 | Initial PersistentMap backend [ticktock]
| | * | | | | 16f21b9 2010-09-14 | initial structures [ticktock]
| * | | | | | 3016cf6 2010-09-23 | Removing registeredInRemoteNodeDuringSerialization [Viktor Klang]
| * | | | | | 893f621 2010-09-23 | Removing the running of HBase tests [Viktor Klang]
| * | | | | |   e169a46 2010-09-23 | Merge with master [Viktor Klang]
| |\ \ \ \ \ \  
| | * \ \ \ \ \   1e460d9 2010-09-23 | Merge branch 'fix-remote-test' [Michael Kober]
| | |\ \ \ \ \ \  
| | | * | | | | | 14c02ce 2010-09-23 | fixed some tests [Michael Kober]
| | * | | | | | | 3783442 2010-09-23 | fixed some tests [Michael Kober]
| | |/ / / / / /  
| * | | | | | |   e433955 2010-09-23 | Merge branch 'master' into new_master [Viktor Klang]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| | * | | | | | dff036b 2010-09-23 | Modified the hbase storage backend dependencies to exclude sl4j [David Greco]
| | * | | | | |   05a5f8c 2010-09-23 | renamed the files and the names of the habse tests, the names now ends with Test [David Greco]
| | |\ \ \ \ \ \  
| | * | | | | | | a2fc40b 2010-09-23 | renamed the files and the names of the habse tests, the names now ends with Test [David Greco]
| | * | | | | | | b36c5bc 2010-09-23 | Modified the hbase storage backend dependencies to exclude sl4j [David Greco]
| | | |_|_|/ / /  
| | |/| | | | |   
| * | | | | | |   a057b29 2010-09-23 | Merge branch 'master' into new_master [Viktor Klang]
| |\ \ \ \ \ \ \  
| | | |/ / / / /  
| | |/| | | | |   
| | * | | | | | 2769221 2010-09-23 | Removing log4j and making Jetty intransitive [Viktor Klang]
| | |/ / / / /  
| * | | | | |   b533892 2010-09-22 | Merge branch 'master' into new_master [Viktor Klang]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | | 3847957 2010-09-22 | Bumping Jersey to 1.3 [Viktor Klang]
| | * | | | | 6d1a999 2010-09-22 | renamed the files and the names of the habse tests, the names now ends with Test [David Greco]
| | * | | | | 471a051 2010-09-22 | Now the hbase persistent storage tests dont'run by default [David Greco]
| * | | | | | 626fec2 2010-09-22 | Ported HBase to use new Uuids [Viktor Klang]
| * | | | | |   364ea91 2010-09-22 | Merge branch 'new_uuid' into new_master [Viktor Klang]
| |\ \ \ \ \ \  
| | * | | | | | 4f0bb01 2010-09-22 | Preparing to add UUIDs to RemoteServer as well [Viktor Klang]
| | * | | | | |   386ffad 2010-09-21 | Merge with master [Viktor Klang]
| | |\ \ \ \ \ \  
| | | | |_|/ / /  
| | | |/| | | |   
| | * | | | | |   db1efa9 2010-09-19 | Adding better guard in id vs uuid parsing of ActorComponent [Viktor Klang]
| | |\ \ \ \ \ \  
| | | * | | | | | a1c0bd5 2010-09-19 | Its a wrap! [Viktor Klang]
| | * | | | | | | dfa637b 2010-09-19 | Its a wrap! [Viktor Klang]
| | |/ / / / / /  
| | * | | | | | 551f25a 2010-09-17 | Aaaaalmost there... [Viktor Klang]
| | * | | | | |   12aedd9 2010-09-17 | Merge with master + update RemoteProtocol.proto [Viktor Klang]
| | |\ \ \ \ \ \  
| | * | | | | | | 3f507fb 2010-08-31 | Initial UUID migration [Viktor Klang]
| * | | | | | | |   859a3b8 2010-09-22 | Merge branch 'master' into new_master [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| | | |_|_|/ / / /  
| | |/| | | | | |   
| * | | | | | | | 8555e00 2010-09-22 | Adding poms [Viktor Klang]
* | | | | | | | | 0e03f0c 2010-09-24 | Changed file-based mailbox creation [Jonas Bonér]
* | | | | | | | |   21b1eb4 2010-09-22 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | | d652d0c 2010-09-22 | Corrected a bug, now the hbase quorum is read correctly from the configuration [David Greco]
| |/ / / / / / /  
| * | | | | | | 75ab1f9 2010-09-22 | fixed TypedActorBeanDefinitionParserTest [Michael Kober]
| * | | | | | | 5a74789 2010-09-22 | fixed merge error in conf [Michael Kober]
| * | | | | | | 00949a2 2010-09-22 | fixed missing aop.xml in akka-typed-actor jar [Michael Kober]
| * | | | | | |   9d4aceb 2010-09-22 | Merge branch 'ticket423' [Michael Kober]
| |\ \ \ \ \ \ \  
| | * | | | | | | 3838411 2010-09-22 | closing ticket423, implemented custom placeholder configurer [Michael Kober]
| | | |_|/ / / /  
| | |/| | | | |   
* | | | | | | |   d10e181 2010-09-22 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | 9fe7e77 2010-09-21 | The getVectorStorageRangeFor of HbaseStorageBackend shouldn't make any defensive programming against out of bound indexes. Now all the tests are passing again. The HbaseTicket343Spec.scala tests were expecting exceptions with out of bound indexes [David Greco]
| * | | | | | | 3b34bd2 2010-09-21 | Some refactoring and management of edge cases in the  getVectorStorageRangeFor method [David Greco]
| * | | | | | |   050a6ae 2010-09-21 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| | * | | | | |   48250c2 2010-09-20 | merged branch ticket364 [Michael Kober]
| | |\ \ \ \ \ \  
| | | * | | | | | b78658a 2010-09-17 | closing #364, serializiation for typed actor proxy ref [Michael Kober]
| | * | | | | | | bc4a7f6 2010-09-20 | Removing dead code [Viktor Klang]
| * | | | | | | |   9648ef9 2010-09-20 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | | 35a160d 2010-09-20 | Folding 3 booleans into 1 reference, preparing for @volatile decimation [Viktor Klang]
| | * | | | | | | 56e6a0d 2010-09-20 | Threw away old ThreadBasedDispatcher and replaced it with an EBEDD with 1 in core pool and 1 in max pool [Viktor Klang]
| * | | | | | | | d702a62 2010-09-20 | Corrected a bug where I wasn't reading the zookeeper quorum configuration correctly [David Greco]
| * | | | | | | | 63e782d 2010-09-20 | Corrected a bug where I wasn't reading the zookeeper quorum configuration correctly [David Greco]
| * | | | | | | |   2fa01bc 2010-09-20 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | |   413dc37 2010-09-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | 8a6c524 2010-09-20 | fixed merge [Michael Kober]
| | | * | | | | | |   28eb6d1 2010-09-20 | Merge branch 'find-actor-by-uuid' [Michael Kober]
| | | |\ \ \ \ \ \ \  
| | | | * | | | | | | 2334710 2010-09-20 | added possibility to register and find remote actors by uuid [Michael Kober]
| | * | | | | | | | | 004e34f 2010-09-20 | Reverting some of the dataflow tests [Viktor Klang]
| | |/ / / / / / / /  
| * | | | | | | | | 0b44436 2010-09-20 | Implemented the start and finish semantic in the getMapStorageRangeFor method [David Greco]
| * | | | | | | | |   1eff139 2010-09-20 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| | * | | | | | | | 0dfc810 2010-09-20 | Adding the old tests for the DataFlowStream [Viktor Klang]
| | * | | | | | | | 08d8d78 2010-09-20 | Fixing varargs issue with Logger.warn [Viktor Klang]
| | |/ / / / / / /  
| * | | | | | | | 7b59a33 2010-09-20 | Implemented the start and finish semantic in the getMapStorageRangeFor method [David Greco]
| * | | | | | | |   60244ea 2010-09-20 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| * | | | | | | |   9316e2b 2010-09-18 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | 9f6cb7f 2010-09-17 | Added the ticket 343 test too [David Greco]
| * | | | | | | | | eb81dcf 2010-09-17 | Now all the tests used to pass with Mongo and Cassandra are passing [David Greco]
| * | | | | | | | |   876a023 2010-09-17 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \ \  
| * | | | | | | | | | bd4106e 2010-09-17 | Starting to work on the hbase storage backend for maps [David Greco]
| * | | | | | | | | | a737ef6 2010-09-17 | Starting to work on the hbase storage backend for maps [David Greco]
| * | | | | | | | | |   a4482f7 2010-09-17 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|/ / / /  
| | |/| | | | | | | |   
| * | | | | | | | | | cb830ed 2010-09-17 | Implemented the Ref and the Vector backend apis [David Greco]
| * | | | | | | | | |   633ba6e 2010-09-16 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | 1703e58 2010-09-16 | Corrected a problem merging with the upstream [David Greco]
| * | | | | | | | | | |   7261f21 2010-09-16 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \ \ \ \   324a76b 2010-09-15 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | 3f83c1d 2010-09-15 | Start to work on the HbaseStorageBackend [David Greco]
| * | | | | | | | | | | | | 4e5f288 2010-09-15 | Start to work on the HbaseStorageBackend [David Greco]
| * | | | | | | | | | | | | b98ecef 2010-09-15 | working on the hbase integration [David Greco]
| * | | | | | | | | | | | |   f7ae1ff 2010-09-15 | Merge remote branch 'upstream/master' [David Greco]
| |\ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | ed6084f 2010-09-15 | Added a simple test showing how to use Hbase testing utilities [David Greco]
| * | | | | | | | | | | | | | 0541140 2010-09-15 | Added a simple test showing how to use Hbase testing utilities [David Greco]
| * | | | | | | | | | | | | | a13c839 2010-09-15 | Added a simple test showing how to use Hbase testing utilities [David Greco]
| * | | | | | | | | | | | | |   e40a72b 2010-09-15 | Added a new project akka-persistence-hbase [David Greco]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|_|_|_|_|/ / /  
| | |/| | | | | | | | | | | |   
| * | | | | | | | | | | | | | c3411e9 2010-09-15 | Added a new project akka-persistence-hbase [David Greco]
* | | | | | | | | | | | | | | 76939fd 2010-09-21 | Refactored mailbox configuration [Jonas Bonér]
| |_|_|_|_|_|_|_|_|/ / / / /  
|/| | | | | | | | | | | | |   
* | | | | | | | | | | | | | e488b79 2010-09-19 | Readded a bugfixed DataFlowStream [Jonas Bonér]
| |_|_|_|_|_|_|_|/ / / / /  
|/| | | | | | | | | | | |   
* | | | | | | | | | | | | a8f88a7 2010-09-18 | Switching from OP_READ to OP_WRITE [Viktor Klang]
* | | | | | | | | | | | | a39ce10 2010-09-18 | fixed ticket #435. Also made serialization of mailbox optional - default true [Debasish Ghosh]
| |_|_|_|_|_|_|/ / / / /  
|/| | | | | | | | | | |   
* | | | | | | | | | | | 14b371b 2010-09-17 | Ticket #343 implementation done except for pop of PersistentVector [Debasish Ghosh]
| |_|_|_|_|_|/ / / / /  
|/| | | | | | | | | |   
* | | | | | | | | | | 8e8a9c7 2010-09-16 | Adding support for optional maxrestarts and withinTime, closing ticket #346 [Viktor Klang]
* | | | | | | | | | |   b924647 2010-09-16 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \ \ \   4cb0082 2010-09-16 | Merge branch 'master' of https://github.com/jboner/akka [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | adc1092 2010-09-16 | Extended akka-sample-camel to include server-managed remote typed consumer actors. Minor refactorings. [Martin Krasser]
| | |_|_|_|_|/ / / / / /  
| |/| | | | | | | | | |   
* | | | | | | | | | | | 971ebf4 2010-09-16 | Fixing #437 by adding "Remote" Future [Viktor Klang]
| |/ / / / / / / / / /  
|/| | | | | | | | | |   
* | | | | | | | | | |   1960241 2010-09-16 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \ \  
| | |_|_|_|_|_|/ / / /  
| |/| | | | | | | | |   
| * | | | | | | | | |   e6974cc 2010-09-16 | Merge branch 'ticket434' [Michael Kober]
| |\ \ \ \ \ \ \ \ \ \  
| | |_|_|_|_|_|/ / / /  
| |/| | | | | | | | |   
| | * | | | | | | | | e77f07c 2010-09-16 | closing ticket 434; added id to ActorInfoProtocol [Michael Kober]
| | |/ / / / / / / /  
| * | | | | | | | | 8d15870 2010-09-16 | fix for issue #436, new version of sjson jar [Debasish Ghosh]
| |/ / / / / / / /  
| * | | | | | | | ff9c24a 2010-09-16 | Resolve casbah time dependency from casbah snapshots repo [Peter Vlugter]
| | |_|_|/ / / /  
| |/| | | | | |   
* | | | | | | | b410df5 2010-09-16 | Closing #427 and #424 [Viktor Klang]
* | | | | | | | 8acfb5f 2010-09-16 | Make ExecutorBasedEventDrivenDispatcherActorSpec deterministic [Viktor Klang]
|/ / / / / / /  
* | | | | | | 8a36b24 2010-09-15 | Closing #264, addign JavaAPI to DataFlowVariable [Viktor Klang]
| |_|/ / / /  
|/| | | | |   
* | | | | | 9b85da6 2010-09-15 | Updated akka-reference.conf with deadline [Viktor Klang]
* | | | | | ce2730f 2010-09-15 | Added support for throughput deadlines [Viktor Klang]
| |/ / / /  
|/| | | |   
* | | | |   dd62dbc 2010-09-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| * | | | | 1ef3049 2010-09-14 | fixed bug in PersistentSortedSet implemnetation of redis [Debasish Ghosh]
* | | | | |   e43d9b2 2010-09-14 | Merge branch 'master' into ticket_419 [Viktor Klang]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | | d6f0996 2010-09-14 | disabled tests for redis and mongo to be run automatically since they need running servers [Debasish Ghosh]
| * | | | |   4ec896b 2010-09-14 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \  
| | * | | | | d24d9bc 2010-09-14 | The unborkening of master: The return of the Poms [Viktor Klang]
| | |/ / / /  
| * | | | | 46904f0 2010-09-14 | The unborkening of master: The return of the Poms [Viktor Klang]
| |/ / / /  
| * | | |   8d96c42 2010-09-13 | Merge branch 'ticket194' [Michael Kober]
| |\ \ \ \  
| | * | | | b5b08e2 2010-09-13 | merged with master [Michael Kober]
| | * | | | 4d036ed 2010-09-13 | merged with master [Michael Kober]
| | * | | |   aa906bf 2010-09-13 | merged with master [Michael Kober]
| | |\ \ \ \  
| | * | | | | 5a1e8f5 2010-09-13 | closing ticket #426 [Michael Kober]
| | * | | | | bfb6129 2010-09-09 | closing ticket 378 [Michael Kober]
| | * | | | |   8886de1 2010-09-07 | Merge with upstream [Viktor Klang]
| | |\ \ \ \ \  
| | | * | | | | ec61c29 2010-09-06 | implemented server managed typed actor [Michael Kober]
| | | * | | | | 60d4010 2010-09-06 | started working on ticket 194 [Michael Kober]
| | * | | | | | 40a6605 2010-09-07 | Removing boilerplate in ReflectiveAccess [Viktor Klang]
| | * | | | | | 869549c 2010-09-07 | Fixing id/uuid misfortune [Viktor Klang]
| * | | | | | |   61c9b3d 2010-09-13 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| |\ \ \ \ \ \ \  
| | * | | | | | | fd25f0e 2010-09-13 | Merge introduced old code [Viktor Klang]
| | * | | | | | |   fb655dd 2010-09-13 | Merge branch 'ticket_250' of github.com:jboner/akka into ticket_250 [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | * | | | | | | 9a0448a 2010-09-12 | Switching dispatching strategy to 1 runnable per mailbox and removing use of TransferQueue [Viktor Klang]
| | * | | | | | | | 5a39c3b 2010-09-12 | Switching dispatching strategy to 1 runnable per mailbox and removing use of TransferQueue [Viktor Klang]
| | |/ / / / / / /  
| | * | | | | | |   8b6895c 2010-09-12 | Merge branch 'master' into ticket_250 [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | 7ba4817 2010-09-12 | Take advantage of short-circuit to avoid lazy init if possible [Viktor Klang]
| | * | | | | | | |   981afff 2010-09-12 | Merge remote branch 'origin/ticket_250' into ticket_250 [Viktor Klang]
| | |\ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \   e613418 2010-09-12 | Resolved conflict [Viktor Klang]
| | | |\ \ \ \ \ \ \ \  
| | * | \ \ \ \ \ \ \ \   1381102 2010-09-12 | Adding final declarations [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / /  
| | |/| / / / / / / / /   
| | | |/ / / / / / / /    
| | | * | | | | | | |   eade9d7 2010-09-12 | Better latency [Viktor Klang]
| | | |\ \ \ \ \ \ \ \  
| | * | \ \ \ \ \ \ \ \   0386194 2010-09-12 | Improving latency in EBEDD [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / /  
| | |/| / / / / / / / /   
| | | |/ / / / / / / /    
| | | * | | | | | | | a5c5efc 2010-09-12 | Safekeeping [Viktor Klang]
| | * | | | | | | | | a13ebc5 2010-09-11 | 1 entry per mailbox at most [Viktor Klang]
| | |/ / / / / / / /  
| | * | | | | | | | f20e0ee 2010-09-10 | Added more safeguards to the WorkStealers tests [Viktor Klang]
| | * | | | | | | |   d7cfe2b 2010-09-10 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \ \  
| | | | |_|_|/ / / /  
| | | |/| | | | | |   
| | * | | | | | | | 77bdedc 2010-09-10 | Massive refactoring of EBEDD and WorkStealer and basically everything... [Viktor Klang]
| | * | | | | | | | 2c0ebf2 2010-09-09 | Optimization started of EBEDD [Viktor Klang]
| * | | | | | | | |   4e294fa 2010-09-13 | Merge branch 'branch-343' [Debasish Ghosh]
| |\ \ \ \ \ \ \ \ \  
| | |_|_|/ / / / / /  
| |/| | | | | | | |   
| | * | | | | | | | b8a3522 2010-09-12 | refactoring for more type safety [Debasish Ghosh]
| | * | | | | | | | 155f4d8 2010-09-12 | all mongo update operations now use safely {} to pin connection at the driver level [Debasish Ghosh]
| | * | | | | | | | 5a2529b 2010-09-11 | redis keys are no longer base64-ed. Though values are [Debasish Ghosh]
| | * | | | | | | | 8494f8b 2010-09-10 | changes for ticket #343. Test harness runs for both Redis and Mongo [Debasish Ghosh]
| | * | | | | | | | 59ca2b4 2010-09-09 | Refactor mongodb module to confirm to Redis and Cassandra. Issue #430 [Debasish Ghosh]
* | | | | | | | | | 31cd0c5 2010-09-13 | Added meta data to network protocol [Jonas Bonér]
* | | | | | | | | | ceff8bd 2010-09-13 | Remove initTransactionalState, renamed init and shutdown [Viktor Klang]
|/ / / / / / / / /  
* | | | | | | | | c7b555f 2010-09-12 | Setting -1 as default mailbox capacity [Viktor Klang]
| |_|/ / / / / /  
|/| | | | | | |   
* | | | | | | | 63d0f43 2010-09-10 | Removed logback config files from akka-actor and akka-remote and use only those in $AKKA_HOME/config (see also ticket #410). [Martin Krasser]
* | | | | | | | bae292a 2010-09-09 | Added findValue to Index [Viktor Klang]
* | | | | | | | db0ff24 2010-09-09 | Moving the Atmosphere AkkaBroadcaster dispatcher to be shared [Viktor Klang]
| |/ / / / / /  
|/| | | | | |   
* | | | | | | 922cf1d 2010-09-09 | Added convenience method for push timeout on EBEDD [Viktor Klang]
* | | | | | | aab2cd6 2010-09-09 | ExecutorBasedEventDrivenDispatcher now works and unit tests are added [Viktor Klang]
* | | | | | |   28ad821 2010-09-09 | Merge branch 'master' into safe_mailboxes [Viktor Klang]
|\ \ \ \ \ \ \  
| * | | | | | | 51dfc02 2010-09-09 | Added comments and removed inverted logic [Viktor Klang]
* | | | | | | |   63a6884 2010-09-09 | Merge with master [Viktor Klang]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | 81d9bff 2010-09-09 | Removing Reactor based dispatchers and closing #428 [Viktor Klang]
| * | | | | | |   93160d8 2010-09-09 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \  
| | |/ / / / / /  
| | * | | | | | 8110892 2010-09-08 | minor edits to scala test specs descriptors, fix up comments [rossputin]
| * | | | | | | f9ce258 2010-09-09 | Fixing #425 by retrieving the MODULE$ [Viktor Klang]
| |/ / / / / /  
* | | | | | | 8414bf3 2010-09-08 | Added more comments for the mailboxfactory [Viktor Klang]
* | | | | | |   3b195d5 2010-09-08 | Merge branch 'master' into safe_mailboxes [Viktor Klang]
|\ \ \ \ \ \ \  
| |/ / / / / /  
| * | | | | | eff7aea 2010-09-08 | Optimization of Index [Viktor Klang]
* | | | | | | 652927a 2010-09-07 | Adding support for safe mailboxes [Viktor Klang]
* | | | | | | 0085dd6 2010-09-07 | Removing erronous use of uuid and replaced with id [Viktor Klang]
* | | | | | | 456fd07 2010-09-07 | Removing boilerplate in reflective access [Viktor Klang]
| |/ / / / /  
|/| | | | |   
* | | | | |   63dbdd6 2010-09-07 | Merge remote branch 'origin/master' [Viktor Klang]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | |   f0079cb 2010-09-06 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| * | | | | | 81e37cd 2010-09-06 | improved error reporting [Jonas Bonér]
* | | | | | | ed1e45e 2010-09-07 | Refactoring RemoteServer [Viktor Klang]
* | | | | | | e492487 2010-09-06 | Adding support for BoundedTransferQueue to EBEDD [Viktor Klang]
| |/ / / / /  
|/| | | | |   
* | | | | | 4599f1c 2010-09-06 | Added javadocs for Function and Procedure [Viktor Klang]
* | | | | | 99ebdcb 2010-09-06 | Added Function and Procedure (Java API) + added them to Agent, closing #262 [Viktor Klang]
* | | | | | 5521c32 2010-09-06 | Added setAccessible(true) to circumvent security exceptions [Viktor Klang]
* | | | | |   ff18d0e 2010-09-06 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | | ac8874c 2010-09-06 | minor fix [Jonas Bonér]
| * | | | |   4e47869 2010-09-06 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| * | | | | | dbbb638 2010-09-06 | minor edits [Jonas Bonér]
* | | | | | | c7f7c1d 2010-09-06 | Closing ticket #261 [Viktor Klang]
| |/ / / / /  
|/| | | | |   
* | | | | |   246741a 2010-09-06 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| | |/ / / /  
| |/| | | |   
| * | | | | 2478e5d 2010-09-06 | fix: server initiated remote actors not found [Michael Kober]
* | | | | | 199aca0 2010-09-06 | Closing #401 with a nice, brand new, multimap [Viktor Klang]
* | | | | | 28eb3b2 2010-09-06 | Removing unused field [Viktor Klang]
|/ / / / /  
* | | | | 3d43330 2010-09-05 | redisclient support for Redis 2.0. Not fully backward compatible, since Redis 2.0 has some differences with 1.x [Debasish Ghosh]
* | | | | e7b0c4f 2010-09-04 | Removed LIFT_VERSION [Viktor Klang]
* | | | | 1b97387 2010-09-04 | Removing Lift sample project and deps (saving ~5MB of dist size [Viktor Klang]
* | | | | b752c9f 2010-09-04 | Fixing Dispatcher config bug #422 [Viktor Klang]
* | | | | 33319bc 2010-09-03 | Added support for UntypedLoadBalancer and UntypedDispatcher [Viktor Klang]
* | | | |   236a434 2010-09-03 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| * | | | | b861002 2010-09-02 | added config element for mailbox capacity, ticket 408 [Michael Kober]
| |/ / / /  
* | | | | bca39cb 2010-09-03 | Fixing ticket #420 [Viktor Klang]
* | | | | bd798ab 2010-09-03 | Fixing mailboxSize for ThreadBasedDispatcher [Viktor Klang]
|/ / / /  
* | | | 3268b17 2010-09-01 | Moved ActorSerialization to 'serialization' package [Jonas Bonér]
* | | |   16eeb26 2010-09-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * | | | 7790fdb 2010-09-01 | Optimization + less code [Viktor Klang]
| * | | | e1ed8a6 2010-09-01 | Added support hook for persistent mailboxes + cleanup and optimizations [Viktor Klang]
| * | | |   176770c 2010-09-01 | Merge branch 'log-categories' [Michael Kober]
| |\ \ \ \  
| | * | | | b22b721 2010-09-01 | added alias for log category warn [Michael Kober]
| * | | | | d12c9ba 2010-08-31 | Upgrading Multiverse to 0.6.1 [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | | 17143b9 2010-08-31 | Add possibility to set default cometSupport in akka.conf [Viktor Klang]
| * | | | 1ff5933 2010-08-31 | Fix ticket #415 + add Jetty dep [Viktor Klang]
| * | | |   1ed2d30 2010-08-31 | Merge branch 'oldmaster' [Viktor Klang]
| |\ \ \ \  
| | * | | | f610ac4 2010-08-31 | Increased the default timeout for ThreadBasedDispatcher to 10 seconds [Viktor Klang]
| | * | | |   2d12672 2010-08-31 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \  
| | | |/ / /  
| | * | | | 8419136 2010-08-30 | Moving Queues into akka-actor [Viktor Klang]
| | * | | |   4b67d3e 2010-08-30 | Merge branch 'master' into transfer_queue [Viktor Klang]
| | |\ \ \ \  
| | * \ \ \ \   2344692 2010-08-30 | Merge branch 'master' of github.com:jboner/akka into transfer_queue [Viktor Klang]
| | |\ \ \ \ \  
| | * | | | | | 0077a14 2010-08-27 | Added boilerplate to improve BoundedTransferQueue performance [Viktor Klang]
| | * | | | | |   f013267 2010-08-27 | Switched to mailbox instead of local queue for ThreadBasedDispatcher [Viktor Klang]
| | |\ \ \ \ \ \  
| | * | | | | | | 56b9c30 2010-08-26 | Changed ThreadBasedDispatcher from LinkedBlockingQueue to TransferQueue [Viktor Klang]
| * | | | | | | | 27fe14e 2010-08-31 | Ripping out Grizzly and replacing it with Jetty [Viktor Klang]
| | |_|_|_|/ / /  
| |/| | | | | |   
* | | | | | | | 7cd9d38 2010-08-31 | Added all config options for STM to akka.conf [Jonas Bonér]
|/ / / / / / /  
* | | | | | | b4b0ffd 2010-08-30 | Changed JtaModule to use structural typing instead of Field reflection, plus added a guard [Jonas Bonér]
| |_|_|/ / /  
|/| | | | |   
* | | | | | 293a49d 2010-08-30 | Updating Netty to 3.2.2.Final [Viktor Klang]
* | | | | | bd13afd 2010-08-30 | Fixing master [Viktor Klang]
| |_|/ / /  
|/| | | |   
* | | | |   be32e80 2010-08-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| * | | | | 447eb0a 2010-08-27 | remove logback.xml from akka-core jar and exclude logback-test.xml from distribution. [Martin Krasser]
| * | | | | a6bf0c0 2010-08-27 | Make sure dispatcher isnt changed on actor restart [Viktor Klang]
| * | | | | aab4724 2010-08-27 | Adding a guard to dispatcher_= in ActorRef [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | | 065ae05 2010-08-27 | Conserving memory usage per dispatcher [Viktor Klang]
| |/ / /  
| * | |   affd86f 2010-08-26 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| | * \ \   edb0c4f 2010-08-26 | Merge branch 'master' of github.com:jboner/akka [Michael Kober]
| | |\ \ \  
| | * | | | 8926c36 2010-08-26 | fixed resart of actor with thread based dispatcher [Michael Kober]
| * | | | | 3a4355c 2010-08-26 | Changing source jar naming from src to sources [Viktor Klang]
| | |/ / /  
| |/| | |   
| * | | | 7b56315 2010-08-26 | Added more comments and made code more readable for the BoundedTransferQueue [Viktor Klang]
| * | | | 48dca79 2010-08-26 | RemoteServer now notifies listeners on connect for non-ssl communication [Viktor Klang]
| |/ / /  
| * | | 5d85d87 2010-08-25 | Constraining input [Viktor Klang]
| * | | c288afa 2010-08-25 | Refining names [Viktor Klang]
| * | | 957e184 2010-08-25 | Adding BoundedTransferQueue [Viktor Klang]
| * | | 6de4697 2010-08-25 | Small refactor [Viktor Klang]
| * | | 4302426 2010-08-24 | Adding some comments for the future [Viktor Klang]
| * | | 57cdccd 2010-08-24 | Reconnect now possible in RemoteClient [Viktor Klang]
| * | | 4b7bf0d 2010-08-24 | Optimization of DataFlow + bugfix [Viktor Klang]
| * | | 30b3627 2010-08-24 | Update sbt plugin [Peter Vlugter]
| * | | 603a3d8 2010-08-23 | Document and remove dead code, restructure tests [Viktor Klang]
* | | | 24494a3 2010-08-28 | removed trailing whitespace [Jonas Bonér]
* | | | 0d5862a 2010-08-28 | renamed cassandra storage-conf.xml [Jonas Bonér]
* | | | da91cac 2010-08-28 | Completed refactoring into lightweight modules akka-actor akka-typed-actor and akka-remote [Jonas Bonér]
* | | | adaf5d4 2010-08-24 | splitted up akka-core into three modules; akka-actors, akka-typed-actors, akka-core [Jonas Bonér]
* | | | 0e899bb 2010-08-23 | minor reformatting [Jonas Bonér]
|/ / /  
* | | 110780d 2010-08-23 | Some more dataflow cleanup [Viktor Klang]
* | |   03a557d 2010-08-23 | Merge branch 'dataflow' [Viktor Klang]
|\ \ \  
| * | | e7efcf4 2010-08-23 | Refactor, optimize, remove non-working code [Viktor Klang]
| * | |   d05a24c 2010-08-22 | Merge branch 'master' into dataflow [Viktor Klang]
| |\ \ \  
| * | | | b84a673 2010-08-20 | One minute is shorter, and cleaned up blocking readers impl [Viktor Klang]
| * | | |   0869b8b 2010-08-20 | Merge branch 'master' into dataflow [Viktor Klang]
| |\ \ \ \  
| * | | | | 67b61da 2010-08-20 | Added tests for DataFlow [Viktor Klang]
| * | | | | 367dbf9 2010-08-20 | Added lazy initalization of SSL engine to avoid interference [Viktor Klang]
| * | | | | 5ade70d 2010-08-19 | Fixing bugs in DataFlowVariable and adding tests [Viktor Klang]
* | | | | | 5154235 2010-08-23 | Fixed deadlock in RemoteClient shutdown after reconnection timeout [Jonas Bonér]
* | | | | | 55766c2 2010-08-23 | Updated version to 1.0-SNAPSHOT [Jonas Bonér]
* | | | | | e887a93 2010-08-22 | Changed package name of FSM module to 'se.ss.a.a' plus name from 'Fsm' to 'FSM' [Jonas Bonér]
| |_|/ / /  
|/| | | |   
* | | | | e2ce27e 2010-08-21 | Release 0.10 [Jonas Bonér]
* | | | | 2d20294 2010-08-21 | Enhanced the RemoteServer/RemoteClient listener API [Jonas Bonér]
* | | | |   70d61b1 2010-08-21 | Added missing events to RemoteServer Listener API [Jonas Bonér]
|\ \ \ \ \  
| * | | | | 1a4601d 2010-08-21 | Changed the RemoteClientLifeCycleEvent to carry a reference to the RemoteClient + dito for RemoteClientException [Jonas Bonér]
* | | | | | 0e8096d 2010-08-21 | removed trailing whitespace [Jonas Bonér]
* | | | | | ca38bc9 2010-08-21 | dos2unix [Jonas Bonér]
* | | | | | 0ba8599 2010-08-21 | Added mailboxCapacity to Dispatchers API + documented config better [Jonas Bonér]
* | | | | | 7d57395 2010-08-21 | Changed the RemoteClientLifeCycleEvent to carry a reference to the RemoteClient + dito for RemoteClientException [Jonas Bonér]
|/ / / / /  
* | | | |   eeff076 2010-08-21 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| * | | | | 96b3be5 2010-08-21 | Update to Multiverse 0.6 final [Peter Vlugter]
* | | | | | ba13414 2010-08-21 | Added support for reconnection-time-window for RemoteClient, configurable through akka-reference.conf [Jonas Bonér]
|/ / / / /  
* | | | | 9c4d28d 2010-08-21 | Added option to use a blocking mailbox with custom capacity [Jonas Bonér]
* | | | | 9007c77 2010-08-21 | Test for RequiresNew propagation [Peter Vlugter]
* | | | | ee09693 2010-08-21 | Rename explicitRetries to blockingAllowed [Peter Vlugter]
* | | | | 930f85a 2010-08-21 | Add transaction propagation level [Peter Vlugter]
| |/ / /  
|/| | |   
* | | |   a1ae775 2010-08-20 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \  
| * | | | 7b5f078 2010-08-19 | fixed remote server name [Michael Kober]
| * | | | e269146 2010-08-19 | blade -> chopstick [momania]
| * | | | 5cd7a7b 2010-08-19 | moved fsm spec to correct location [momania]
| * | | |   7d69661 2010-08-19 | Merge branch 'fsm' [momania]
| |\ \ \ \  
| | |/ / /  
| |/| | |   
| | * | | 0f26ccd 2010-08-19 | Dining hakkers on fsm [momania]
| | * | |   29677ad 2010-08-19 | Merge branch 'master' into fsm [momania]
| | |\ \ \  
| | * | | | 17f37df 2010-07-20 | better matching reply value [momania]
| | * | | | 73106f6 2010-07-20 | use ref for state- makes sense? [momania]
| | * | | | c52c549 2010-07-20 | State refactor [momania]
| | * | | | 770f74f 2010-07-20 | State refactor [momania]
| | * | | | dcd182e 2010-07-19 | move StateTimeout into Fsm [momania]
| | * | | | 8a75065 2010-07-19 | foreach -> flatMap [momania]
| | * | | | 2af5fda 2010-07-19 | refactor fsm [momania]
| | * | | | b5bf62d 2010-07-19 | initial idea for FSM [momania]
* | | | | | 1e2fe00 2010-08-20 | Exit is bad mkay [Viktor Klang]
* | | | | | 9c6e833 2010-08-20 | Added lazy initalization of SSL engine to avoid interference [Viktor Klang]
|/ / / / /  
* | | | | ea2f7bb 2010-08-19 | Added more flexibility to ListenerManagement [Viktor Klang]
* | | | |   d82a804 2010-08-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \  
| | |/ / /  
| |/| | |   
| * | | |   49fd82f 2010-08-19 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \  
| * | | | | ac0a9e8 2010-08-19 | Introduced uniquely identifiable, loggable base exception: AkkaException and made use of it throught the project [Jonas Bonér]
* | | | | | c45454f 2010-08-19 | Changing Listeners backing store to ConcurrentSkipListSet and changing signature of WithListeners(f) to (ActorRef) => Unit [Viktor Klang]
| |/ / / /  
|/| | | |   
* | | | | 4073a1e 2010-08-18 | Hard-off-switching SSL Remote Actors due to not production ready for 0.10 [Viktor Klang]
* | | | | de79fa1 2010-08-18 | Adding scheduling thats usable from TypedActor [Viktor Klang]
|/ / / /  
* | | |   e9baf2b 2010-08-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * \ \ \   c7e65d3 2010-08-18 | Merge branch 'master' of github.com:jboner/akka [rossputin]
| |\ \ \ \  
| | * \ \ \   43db21b 2010-08-18 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| | |\ \ \ \  
| | | * | | | 7d289df 2010-08-18 | Adding lifecycle messages and listenability to RemoteServer [Viktor Klang]
| | | * | | | 4ba859c 2010-08-18 | Adding DiningHakkers as FSM example [Viktor Klang]
| | * | | | | dadbee5 2010-08-18 | Closes #398 Fix broken tests in akka-camel module [Martin Krasser]
| * | | | | | 52ef431 2010-08-18 | add akka-init-script.sh to allArtifacts in AkkaProject [rossputin]
* | | | | | | 275ce92 2010-08-18 | removed codefellow plugin [Jonas Bonér]
| |_|/ / / /  
|/| | | | |   
* | | | | | dfe4d77 2010-08-17 | Added a more Java-suitable, and less noisy become method [Viktor Klang]
| |/ / / /  
|/| | | |   
* | | | |   b6c782e 2010-08-17 | Merge branch 'master' of github.com:jboner/akka [Jonas Boner]
|\ \ \ \ \  
| * | | | | 606f811 2010-08-17 | Issue #388 Typeclass serialization of ActorRef/UntypedActor isn't Java-friendly : Added wrapper APIs for implicits. Also added test cases for serialization of UntypedActor [Debasish Ghosh]
| * | | | |   ced1cc5 2010-08-16 | merged with master [Michael Kober]
| |\ \ \ \ \  
| | * | | | | 71ec0bd 2010-08-16 | fixed properties for untyped actors [Michael Kober]
| | |/ / / /  
| * | | | |   914c603 2010-08-16 | Merge branch 'master' of github.com:jboner/akka [Michael Kober]
| |\ \ \ \ \  
| | * | | | | de98aa9 2010-08-16 | Changed signature of ActorRegistry.find [Viktor Klang]
| | |/ / / /  
| * | | | | b09537f 2010-08-16 | fixed properties for untyped actors [Michael Kober]
| |/ / / /  
* | | | | ba0b17f 2010-08-17 | Refactoring: TypedActor now extends Actor and is thereby a full citizen in the Akka actor-land [Jonas Boner]
* | | | | 0530065 2010-08-16 | Return Future from TypedActor message send [Jonas Boner]
|/ / / /  
* | | | 4318ad5 2010-08-16 | merged with upstream [Jonas Boner]
* | | |   c9b4b78 2010-08-16 | Merge branch 'master' of github.com:jboner/akka [Jonas Boner]
|\ \ \ \  
| * | | | 084fbe2 2010-08-16 | Added defaults to scan and debug in logback configuration [Viktor Klang]
| * | | | e30ae7a 2010-08-16 | Fixing logback config file locate [Viktor Klang]
| * | | | 6a5cb9b 2010-08-16 | Migrated test to new API [Viktor Klang]
| * | | | 8520f89 2010-08-16 | Added a lot of docs for the Java API [Viktor Klang]
| * | | |   8edfb16 2010-08-16 | Merge branch 'master' into java_actor [Viktor Klang]
| |\ \ \ \  
| | * \ \ \   c88f400 2010-08-16 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \  
| | * | | | | d6d2332 2010-08-13 | Added support for pool factors and executor bounds [Viktor Klang]
| * | | | | | 01af2c4 2010-08-13 | Holy crap, it actually works! [Viktor Klang]
| * | | | | | d2a5053 2010-08-13 | Initial conversion of UntypedActor [Viktor Klang]
| |/ / / / /  
* | | | | | 9f5d77b 2010-08-16 | Added shutdown of un-supervised Temporary that have crashed [Jonas Boner]
* | | | | | f6c64ce 2010-08-16 | minor edits [Jonas Boner]
| |/ / / /  
|/| | | |   
* | | | |   bc213c6 2010-08-16 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \  
| * | | | | bcff7fd 2010-08-16 | Some Java friendliness for STM [Peter Vlugter]
* | | | | | 2814feb 2010-08-16 | Fixed unessecary remote actor registration of sender reference [Jonas Bonér]
|/ / / / /  
* | | | | cffd70e 2010-08-15 | Closes #393 Redesign CamelService singleton to be a CamelServiceManager [Martin Krasser]
* | | | | 1b2ac2b 2010-08-14 | Cosmetic changes to akka-sample-camel [Martin Krasser]
* | | | | 979cd37 2010-08-14 | Closes #392 Support untyped Java actors as endpoint producer [Martin Krasser]
* | | | |   4b7ce4e 2010-08-14 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
|\ \ \ \ \  
| |/ / / /  
| * | | | e37608f 2010-08-13 | Removing legacy dispatcher id [Viktor Klang]
| * | | | 8b18770 2010-08-13 | Fix Atmosphere integration for the new dispatchers [Viktor Klang]
| * | | | eec2c40 2010-08-13 | Cleaned up code and verified tests [Viktor Klang]
| * | | | d9fe236 2010-08-13 | Added utility method and another test [Viktor Klang]
| * | | |   51d89c6 2010-08-13 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \  
| | * | | | 8c6852e 2010-08-13 | fixed untyped actor parsing [Michael Kober]
| | * | | | 4646783 2010-08-13 | closing ticket198: support for thread based dispatcher in spring config [Michael Kober]
| | * | | | b3e683a 2010-08-13 | added thread based dispatcher config for untyped actors [Michael Kober]
| | * | | | a6bff96 2010-08-13 | Update for Ref changes [Peter Vlugter]
| | * | | | 9f7d781 2010-08-13 | Small changes to Ref [Peter Vlugter]
| | * | | | 5fa3cbd 2010-08-12 | Cosmetic [momania]
| | * | | | b2d939f 2010-08-12 | Use static 'parseFrom' to create protobuf objects instead of creating a defaultInstance all the time. [momania]
| | * | | |   c0930ba 2010-08-12 | Merge branch 'rpc_amqp' [momania]
| | |\ \ \ \  
| | | * \ \ \   a22d15f 2010-08-12 | Merge branch 'master' of git-proxy:jboner/akka into rpc_amqp [momania]
| | | |\ \ \ \  
| | | * | | | | 357f3fc 2010-08-12 | disable tests again [momania]
| | | * | | | | 505c70d 2010-08-12 | making it more easy to start string and protobuf base consumers, producers and rpc style [momania]
| | | * | | | | b96f957 2010-08-12 | shutdown linked actors too when shutting down supervisor [momania]
| | | * | | | | 26d8abf 2010-08-12 | added shutdownAll to be able to kill the whole actor tree, incl the amqp supervisor [momania]
| | | * | | | |   a622dc8 2010-08-11 | Merge branch 'master' of git-proxy:jboner/akka into rpc_amqp [momania]
| | | |\ \ \ \ \  
| | | * | | | | | 7760a48 2010-08-11 | added async call with partial function callback to rpcclient [momania]
| | | * | | | | | 6f8750b 2010-08-11 | manual rejection of delivery (for now by making it fail until new rabbitmq version has basicReject) [momania]
| | | * | | | | |   38fb188 2010-08-11 | Merge branch 'master' of git-proxy:jboner/akka into rpc_amqp [momania]
| | | |\ \ \ \ \ \  
| | | * | | | | | | 0a0e546 2010-08-10 | types seem to help the parameter declaration :S [momania]
| | | * | | | | | | 9ef76c4 2010-08-10 | add durablility and auto-delete with defaults to rpc and with passive = true for client [momania]
| | | * | | | | | | 2692e6f 2010-08-09 | undo local repo settings (for the 25953467296th time :S ) [momania]
| | | * | | | | | | 32ece97 2010-08-09 | added optional routingkey and queuename to parameters [momania]
| | | * | | | | | | 6e6f1f3 2010-08-06 | remove rpcclient trait... [momania]
| | | * | | | | | | ab5c4f8 2010-08-06 | disable ampq tests [momania]
| | | * | | | | | | 94c9b5a 2010-08-06 | - moved all into package folder structure - added simple protobuf based rpc convenience [momania]
| | * | | | | | | | 00e215a 2010-08-12 | closing ticket 377, 376 and 200 [Michael Kober]
| | * | | | | | | | ef79bef 2010-08-12 | added config for WorkStealingDispatcher and HawtDispatcher; Tickets 200 and 377 [Michael Kober]
| | * | | | | | | | a16e909 2010-08-11 | ported unit tests for spring config from java to scala, removed akka-spring-test-java [Michael Kober]
| | | |_|_|/ / / /  
| | |/| | | | | |   
| | * | | | | | |   c7ddab8 2010-08-12 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | 5605895 2010-08-12 | Fixed #305. Invoking 'stop' on client-managed remote actors does not shut down remote instance (but only local) [Jonas Bonér]
| * | | | | | | | | b6444b1 2010-08-13 | Added tests are fixed some bugs [Viktor Klang]
| * | | | | | | | | f0ac45f 2010-08-12 | Adding first support for config dispatchers [Viktor Klang]
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | |   9421d1c 2010-08-12 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | |   59069fc 2010-08-12 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | 56c13fc 2010-08-12 | Added tests for remotely supervised TypedActor [Jonas Bonér]
| * | | | | | | | | 8df6676 2010-08-12 | Add default DEBUG to test output [Viktor Klang]
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | | 94e0162 2010-08-12 | Allow core threads to time out in dispatchers [Viktor Klang]
| * | | | | | | | 1513c2c 2010-08-12 | Moving logback-test.xml to /config [Viktor Klang]
| |/ / / / / / /  
| * | | | | | | 374dbde 2010-08-12 | Add actorOf with call-by-name for Java TypedActor [Jonas Bonér]
| * | | | | | |   32483c9 2010-08-12 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| * | | | | | | | 6db0a97 2010-08-12 | Refactored Future API to make it more Java friendly [Jonas Bonér]
* | | | | | | | | 78e2b3a 2010-08-14 | Full Camel support for untyped and typed actors (both Java and Scala API). Closes #356, closes 357. [Martin Krasser]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | | b0aaab2 2010-08-11 | Extra robustness for Logback [Viktor Klang]
* | | | | | | | 7557c4c 2010-08-11 | Minor perf improvement in Ref [Viktor Klang]
* | | | | | | | 24f5176 2010-08-11 | Ported TransactorSpec to UntypedActor [Viktor Klang]
* | | | | | | | 8cbcc9d 2010-08-11 | Changing akka-init-script.sh to use logback [Viktor Klang]
| |_|_|/ / / /  
|/| | | | | |   
* | | | | | |   2307cd5 2010-08-11 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \  
| |/ / / / / /  
| * | | | | | e362b3c 2010-08-11 | added init script [Jonas Bonér]
| | |/ / / /  
| |/| | | |   
* | | | | | 9dcc81a 2010-08-11 | Switch to Logback! [Viktor Klang]
* | | | | | 4ac6a4e 2010-08-11 | Ported ReceiveTimeoutSpec to UntypedActor [Viktor Klang]
* | | | | | 068fd34 2010-08-11 | Ported ForwardActorSpec to UntypedActor [Viktor Klang]
* | | | | |   40295d9 2010-08-11 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | |   10c6c52 2010-08-11 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| * | | | | | 093589d 2010-08-11 | Fixed issue in AMQP by not supervising a consumer handler that is already supervised [Jonas Bonér]
| * | | | | | 689f910 2010-08-10 | removed trailing whitespace [Jonas Bonér]
| * | | | | | b44580d 2010-08-10 | Converted tabs to spaces [Jonas Bonér]
| * | | | | | 8c8a2b0 2010-08-10 | Reformatting [Jonas Bonér]
* | | | | | | e67ba91 2010-08-10 | Performance optimization? [Viktor Klang]
| |/ / / / /  
|/| | | | |   
* | | | | | 08a0c50 2010-08-10 | Grouped JDMK modules [Viktor Klang]
* | | | | |   be0510d 2010-08-10 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | |   2090761 2010-08-10 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \  
| * | | | | | 1cbb6a6 2010-08-10 | Did some work on improving the Java API (UntypedActor) [Jonas Bonér]
* | | | | | | 48a3b76 2010-08-10 | Reduce memory use per Actor [Viktor Klang]
| |/ / / / /  
|/| | | | |   
* | | | | | 22cf40a 2010-08-09 | Closing ticket #372, added tests [Viktor Klang]
* | | | | |   19f00f1 2010-08-09 | Merge branch 'master' into ticket372 [Viktor Klang]
|\ \ \ \ \ \  
| * | | | | | e625a8f 2010-08-09 | Fixing a boot sequence issue with RemoteNode [Viktor Klang]
| * | | | | | 0ad0e42 2010-08-09 | Cleanup and Atmo+Lift version bump [Viktor Klang]
| |/ / / / /  
| * | | | | 9b44144 2010-08-09 | The unborkening [Viktor Klang]
| * | | | | d09be74 2010-08-09 | Updated docs [Viktor Klang]
| * | | | | c96436d 2010-08-09 | Removed if*-methods and improved performance for arg-less logging [Viktor Klang]
| * | | | | 4700e83 2010-08-09 | Formatting [Viktor Klang]
| * | | | | e5334f6 2010-08-09 | Closing ticket 370 [Viktor Klang]
* | | | | | 652c1ad 2010-08-06 | Fixing ticket 372 [Viktor Klang]
|/ / / / /  
* | | | |   8788e72 2010-08-06 | Merge branch 'master' into ticket337 [Viktor Klang]
|\ \ \ \ \  
| |/ / / /  
| * | | | bb006e6 2010-08-06 | - forgot the api commit - disable tests again :S [momania]
| * | | | c0a3ebe 2010-08-06 | - move helper object actors in specs companion object to avoid clashes with the server spec (where the helpers have the same name) [momania]
| * | | | 3214917 2010-08-06 | - made rpc handler reqular function instead of partial function - add queuename as optional parameter for rpc server (for i.e. loadbalancing purposes) [momania]
| * | | |   c48ca68 2010-08-06 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| |\ \ \ \  
| * \ \ \ \   b9a58f9 2010-08-03 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| |\ \ \ \ \  
| * | | | | | b8b6cfd 2010-07-27 | no need for the dummy tests anymore [momania]
* | | | | | | 525f94f 2010-08-06 | Closing ticket 337 [Viktor Klang]
| |_|/ / / /  
|/| | | | |   
* | | | | | d766dfc 2010-08-05 | Added unit test to test for race-condition in ActorRegistry [Viktor Klang]
* | | | | |   c496049 2010-08-05 | Fixed race condition in ActorRegistry [Viktor Klang]
|\ \ \ \ \ \  
| * | | | | | 14e00a4 2010-08-04 | update run-akka script to use 2.8.0 final [rossputin]
* | | | | | | 3af770b 2010-08-05 | Race condition should be patched now [Viktor Klang]
|/ / / / / /  
* | | | | | 2bf4a58 2010-08-04 | Uncommenting SSL support [Viktor Klang]
* | | | | | c4c5bab 2010-08-04 | Closing ticket 368 [Viktor Klang]
* | | | | | d6a874c 2010-08-03 | Closing ticket 367 [Viktor Klang]
* | | | | | 9331fc8 2010-08-03 | Closing ticket 355 [Viktor Klang]
* | | | | |   36b531e 2010-08-03 | Merge branch 'ticket352' [Viktor Klang]
|\ \ \ \ \ \  
| * | | | | | 88f5344 2010-08-03 | Closing ticket 352 [Viktor Klang]
| | |/ / / /  
| |/| | | |   
* | | | | |   98d3034 2010-08-03 | Merge with master [Viktor Klang]
|\ \ \ \ \ \  
| |/ / / / /  
| * | | | |   2b4c1f2 2010-08-02 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \  
| | * | | | | 6782cf3 2010-08-02 | Ref extends Multiverse BasicRef (closes #253) [Peter Vlugter]
| * | | | | | 4937736 2010-08-02 | closes #366: CamelService should be a singleton [Martin Krasser]
| |/ / / / /  
| * | | | | 00f6b62 2010-08-01 | Test cases for handling actor failures in Camel routes. [Martin Krasser]
| * | | | | c5eeade 2010-07-31 | formatting [Jonas Bonér]
| * | | | | 9548534 2010-07-31 | Removed TypedActor annotations and the method callbacks in the config [Jonas Bonér]
| * | | | | b7ed58c 2010-07-31 | Changed the Spring schema and the Camel endpoint names to the new typed-actor name [Jonas Bonér]
| * | | | | 0cb30a1 2010-07-30 | Removed imports not used [Jonas Bonér]
| * | | | | ce56734 2010-07-30 | Restructured test folder structure [Jonas Boner]
| * | | | |   c71e89e 2010-07-30 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \  
| | * | | | | 869e0f0 2010-07-30 | removed trailing whitespace [Jonas Boner]
| | * | | | | 4d29006 2010-07-30 | dos2unix [Jonas Boner]
| | * | | | |   881359c 2010-07-30 | Merge branch 'master' of github.com:jboner/akka [Jonas Boner]
| | |\ \ \ \ \  
| | | * | | | | 5966eb9 2010-07-29 | Fixing Comparable problem [Viktor Klang]
| | * | | | | | 663e79d 2010-07-30 | Added UntypedActor and UntypedActorRef (+ tests) to work with untyped MDB-style actors in Java. [Jonas Boner]
| * | | | | | | fc83d8f 2010-07-30 | Fixed failing (and temporarily disabled) tests in akka-spring after refactoring from ActiveObject to TypedActor. [Martin Krasser]
| | |/ / / / /  
| |/| | | | |   
| * | | | | | 992bced 2010-07-29 | removed trailing whitespace [Jonas Bonér]
| * | | | | | 76befa7 2010-07-29 | converted tabs to spaces [Jonas Bonér]
| * | | | | |   777c15d 2010-07-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | 75beddc 2010-07-29 | upgraded sjson to 0.7 [Debasish Ghosh]
| | |/ / / / /  
| * | | | | | b76b05f 2010-07-29 | minor reformatting [Jonas Bonér]
| |/ / / / /  
| * | | | |   a292e3b 2010-07-28 | Merge branch 'wip-typed-actor-jboner' into master [Jonas Bonér]
| |\ \ \ \ \  
| | * | | | | 7820a97 2010-07-28 | Initial draft of UntypedActor for Java API [Jonas Bonér]
| | * | | | | df0eddf 2010-07-28 | Implemented swapping TypedActor instance on restart [Jonas Bonér]
| | * | | | | 0d51c6f 2010-07-27 | TypedActor refactoring completed, all test pass except for some in the Spring module (commented them away for now). [Jonas Bonér]
| | * | | | | b8bdfc0 2010-07-27 | Converted all TypedActor tests to interface-impl, code and tests compile [Jonas Bonér]
| | * | | | | e48572f 2010-07-26 | Added TypedActor and TypedTransactor base classes. Renamed ActiveObject factory object to TypedActor. Improved network protocol for TypedActor. Remote TypedActors now identified by UUID. [Jonas Bonér]
| * | | | | |   b31a13c 2010-07-28 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \  
| | |/ / / / /  
| |/| | | | |   
| | * | | | | 5b2d683 2010-07-28 | match readme to scaladoc in sample [rossputin]
| | |/ / / /  
| | * | | | e7b45e5 2010-07-24 | Upload patched camel-jetty-2.4.0.1 that fixes concurrency bug (will be officially released with Camel 2.5.0) [Martin Krasser]
| | * | | | cf2e013 2010-07-23 | move into the new test dispach directory. [Hiram Chirino]
| | * | | |   e4c980b 2010-07-23 | Merge branch 'master' of git://github.com/jboner/akka [Hiram Chirino]
| | |\ \ \ \  
| | | * | | | 5c3cb7c 2010-07-23 | re-arranged tests into folders/packages [momania]
| | * | | | |   814f05a 2010-07-23 | Merge branch 'master' of git://github.com/jboner/akka [Hiram Chirino]
| | |\ \ \ \ \  
| | | |/ / / /  
| | * | | | | 3141c8a 2010-07-23 | update to the released version of hawtdispatch [Hiram Chirino]
| | * | | | | 080a66b 2010-07-21 | Simplify the hawt dispatcher class name added a hawt dispatch echo server exampe. [Hiram Chirino]
| | * | | | | 9a6651a 2010-07-21 | hawtdispatch dispatcher can now optionally use dispatch sources to agregate cross actor invocations [Hiram Chirino]
| | * | | | | 7a3a776 2010-07-21 | fixing HawtDispatchEventDrivenDispatcher so that it has at least one non-daemon thread while it's active [Hiram Chirino]
| | * | | | | 06f4a83 2010-07-21 | adding a HawtDispatch based message dispatcher [Hiram Chirino]
| | * | | | | 8f9e9f7 2010-07-21 | decoupled the mailbox implementation from the actor.  The implementation is now controled by dispatcher associated with the actor. [Hiram Chirino]
| * | | | | |   20464a3 2010-07-26 | Merge branch 'ticket_345' [Jonas Bonér]
| |\ \ \ \ \ \  
| | * | | | | | b3473c7 2010-07-26 | Fixed broken tests for Active Objects + added logging to Scheduler + fixed problem with SchedulerSpec [Jonas Bonér]
| | * | | | | | 474529e 2010-07-23 | cosmetic [momania]
| | * | | | | | 1f63800 2010-07-23 | - better restart strategy test - make sure actor stops when restart strategy maxes out - nicer patternmathing on lifecycle making sure lifecycle.get is never called anymore (sometimes gave nullpointer exceptions) - also applying the defaults in a nicer way [momania]
| | * | | | | | 9b55238 2010-07-23 | proof restart strategy [momania]
| * | | | | | |   3d9ce58 2010-07-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | | |_|/ / / /  
| | |/| | | | |   
| | * | | | | | 39d8128 2010-07-23 | clean end state [momania]
| | |/ / / / /  
| | * | | | | 45514de 2010-07-23 | Test #307 - Proof schedule continues with retarted actor [momania]
| | * | | | |   2d0e467 2010-07-22 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| | |\ \ \ \ \  
| | | * | | | | 358963f 2010-07-22 | MongoDB based persistent Maps now use Mongo updates. Also upgraded mongo-java driver to 2.0 [Debasish Ghosh]
| | | |/ / / /  
| | * | | | | cd3a90d 2010-07-22 | WIP [momania]
| * | | | | | 9f27825 2010-07-23 | Now uses 'Duration' for all time properties in config [Jonas Bonér]
| | |/ / / /  
| |/| | | |   
| * | | | |   f4df6a5 2010-07-21 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \  
| | * \ \ \ \   cfd4a26 2010-07-21 | Merge branch 'master' of github.com:jboner/akka [Heiko Seeberger]
| | |\ \ \ \ \  
| | | * | | | | fc178be 2010-07-21 | fix for idle client closing issues by redis server #338 and #340 [Debasish Ghosh]
| | * | | | | |   ba7be4d 2010-07-21 | Merge branch '342-hseeberger' [Heiko Seeberger]
| | |\ \ \ \ \ \  
| | | * | | | | | 107ffef 2010-07-21 | closes #342: Added parens to ActorRegistry.shutdownAll. [Heiko Seeberger]
| | * | | | | | |   39337dd 2010-07-21 | Merge branch '341-hseeberger' [Heiko Seeberger]
| | |\ \ \ \ \ \ \  
| | | |/ / / / / /  
| | |/| | | | | |   
| | | * | | | | | 7e56c35 2010-07-21 | closes #341: Fixed O-S-G-i example. [Heiko Seeberger]
| | |/ / / / / /  
| * | | | | | | 747b601 2010-07-21 | HTTP Producer/Consumer concurrency test (ignored by default) [Martin Krasser]
| * | | | | | | 764555d 2010-07-21 | Added example how to use JMS endpoints in standalone applications. [Martin Krasser]
| * | | | | | | c5f30d8 2010-07-21 | Closes #333 Allow applications to wait for endpoints being activated [Martin Krasser]
| | |/ / / / /  
| |/| | | | |   
| * | | | | |   122bba2 2010-07-21 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \  
| | |/ / / / /  
| | * | | | |   f76969c 2010-07-21 | Merge branch '31-hseeberger' [Heiko Seeberger]
| | |\ \ \ \ \  
| | | * | | | | 18929cd 2010-07-20 | closes #31: Some fixes to the O-S-G-i settings in SBT project file; also deleted superfluous bnd4sbt.jar in project/build/lib directory. [Heiko Seeberger]
| | | * | | | |   d9e3b11 2010-07-20 | Merge branch 'master' into osgi [Heiko Seeberger]
| | | |\ \ \ \ \  
| | | | |/ / / /  
| | | * | | | |   96d7915 2010-07-19 | Merge branch 'master' into osgi [Heiko Seeberger]
| | | |\ \ \ \ \  
| | | | | |/ / /  
| | | | |/| | |   
| | | * | | | |   1d7c4d7 2010-06-28 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \  
| | | * \ \ \ \ \   885ce14 2010-06-28 | Merge remote branch 'origin/osgi' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \  
| | | | * | | | | | 38fc975 2010-06-21 | OSGi work: Fixed plugin configuration => Added missing repo and module config for BND. [Heiko Seeberger]
| | | * | | | | | | 0832265 2010-06-28 | Removed pom.xml, not needed anymore [Roman Roelofsen]
| | | * | | | | | |   c2f8d20 2010-06-24 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \  
| | | | |/ / / / / /  
| | | |/| | | | | |   
| | | * | | | | | | dbb12da 2010-06-21 | OSGi work: Fixed packageAction for AkkaOSGiAssemblyProject (publish-local working now) and reverted to default artifactID (removed superfluous suffix '_osgi'). [Heiko Seeberger]
| | | * | | | | | | 54b48a3 2010-06-21 | OSGi work: Switched to bnd4sbt 1.0.0.RC3, using projectVersion for exported packages now and fixed a merge bug. [Heiko Seeberger]
| | | * | | | | | |   bd05761 2010-06-21 | Merge branch 'master' into osgi [Heiko Seeberger]
| | | |\ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \   530b94d 2010-06-18 | Merge remote branch 'origin/master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \   dff7cc6 2010-06-18 | Merge remote branch 'akollegger/master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | 3c3d9a4 2010-06-17 | synced with jboner/master; decoupled AkkaWrapperProject; bumped bnd4sbt to 1.0.0.RC2 [Andreas Kollegger]
| | | | * | | | | | | | |   13cc2de 2010-06-17 | Merge branch 'master' of http://github.com/jboner/akka [Andreas Kollegger]
| | | | |\ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | 2c5fd11 2010-06-08 | pulled wrappers into AkkaWrapperProject trait file; sjson, objenesis, dispatch-json, netty ok; multiverse is next [Andreas Kollegger]
| | | | * | | | | | | | | | 96728a0 2010-06-06 | initial implementation of OSGiWrapperProject, applied to jgroups dependency to make it OSGi-friendly [Andreas Kollegger]
| | | | * | | | | | | | | |   69f2321 2010-06-06 | merged with master; changed renaming of artifacts to use override def artifactID [Andreas Kollegger]
| | | | |\ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | 75dc847 2010-06-06 | initial changes for OSGification: added bnd4sbt plugin, changed artifact naming to include _osgi [Andreas Kollegger]
| | | * | | | | | | | | | | | 157956e 2010-06-17 | Started work on OSGi sample [Roman Roelofsen]
| | | * | | | | | | | | | | |   c0361de 2010-06-17 | Merge remote branch 'origin/master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | | | |_|/ / / / / / / / /  
| | | | |/| | | | | | | | | |   
| | | * | | | | | | | | | | | bfbdc7a 2010-06-17 | All bundles resolve! [Roman Roelofsen]
| | | * | | | | | | | | | | | f0b4404 2010-06-16 | Exclude transitive dependencies Ongoing work on finding the bundle list [Roman Roelofsen]
| | | * | | | | | | | | | | | 7a6465c 2010-06-16 | Updated bnd4sbt plugin [Roman Roelofsen]
| | | * | | | | | | | | | | |   1cfaded 2010-06-16 | Merge remote branch 'origin/master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | c325a74 2010-06-16 | Basic OSGi stuff working. Need to exclude transitive dependencies from the bundle list. [Roman Roelofsen]
| | | * | | | | | | | | | | | |   a4d8a33 2010-06-08 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | d0fe535 2010-06-08 | Use more idiomatic way to add the assembly task [Roman Roelofsen]
| | | * | | | | | | | | | | | | | 89f581a 2010-06-07 | Work in progress! Trying to find an alternative to mvn assembly [Roman Roelofsen]
| | | * | | | | | | | | | | | | | 556cbc3 2010-06-07 | Removed some dependencies since they will be provided by their own bundles [Roman Roelofsen]
| | | * | | | | | | | | | | | | |   b6dc53c 2010-06-07 | Merge remote branch 'origin/osgi' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | * \ \ \ \ \ \ \ \ \ \ \ \ \   a0d8a32 2010-03-06 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | b367851 2010-06-07 | Added dependencies-bundle. [Roman Roelofsen]
| | | * | | | | | | | | | | | | | | |   9230db2 2010-06-07 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | 15d6ce3 2010-06-07 | Removed Maven projects and added bnd4sbt [Roman Roelofsen]
| | | * | | | | | | | | | | | | | | | |   c863cc5 2010-05-25 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | | 7a03fba 2010-05-25 | changed karaf url [Roman Roelofsen]
| | | * | | | | | | | | | | | | | | | | |   6a52fe4 2010-03-19 | Merge branch 'master' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | | | ee66023 2010-03-12 | rewriting deployer in scala ... work in progress! [Roman Roelofsen]
| | | * | | | | | | | | | | | | | | | | | | d52a9cb 2010-03-05 | added akka-osgi module to parent pom [Roman Roelofsen]
| | | * | | | | | | | | | | | | | | | | | |   879ff02 2010-03-05 | Merge commit 'origin/osgi' into osgi [Roman Roelofsen]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | | |_|_|/ / / / / / / / / / / / / / /  
| | | | |/| | | | | | | | | | | | | | | | |   
| | | | * | | | | | | | | | | | | | | | | | 3fff3ad 2010-03-04 | Added OSGi proof of concept Very basic example Starting point to kick of discussions [Roman Roelofsen]
| * | | | | | | | | | | | | | | | | | | | | 8ed8835 2010-07-21 | Remove misleading term 'non-blocking' from comments. [Martin Krasser]
| |/ / / / / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | | | |   162376a 2010-07-20 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | | | 240ae68 2010-07-20 | Adding become to Actor [Viktor Klang]
| | | |_|_|_|_|_|_|_|_|_|_|_|_|_|_|_|/ / /  
| | |/| | | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | |   736444d 2010-07-20 | Merge branch '277-hseeberger' [Heiko Seeberger]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | | | b2ad145 2010-07-20 | closes #277: Transformed all subprojects to use Dependencies object; also reworked Plugins.scala accordingly. [Heiko Seeberger]
| | | * | | | | | | | | | | | | | | | | | |   eb3beba 2010-07-20 | Merge branch 'master' into 277-hseeberger [Heiko Seeberger]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | | |   9f372a0 2010-07-19 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | | | | c7be336 2010-07-19 | Fixing case 334 [Viktor Klang]
| | | |_|_|_|_|_|_|_|_|_|_|_|_|_|_|_|_|/ / /  
| | |/| | | | | | | | | | | | | | | | | | |   
| | | | * | | | | | | | | | | | | | | | | | f8b4aca 2010-07-20 | re #277: Created objects for repositories and dependencies and started transformig akka-core. [Heiko Seeberger]
| | | |/ / / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | | bd7bd16 2010-07-20 | Minor changes in akka-sample-camel [Martin Krasser]
| | |/ / / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | c9b3d36 2010-07-19 | Remove listener from listener list before stopping the listener (avoids warning that stopped listener cannot be notified) [Martin Krasser]
| |/ / / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | |   4a50271 2010-07-18 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   b6485fa 2010-07-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | | | 77ab1ac 2010-07-17 | Fixing bug in ActorRegistry [Viktor Klang]
| | * | | | | | | | | | | | | | | | | | | 4106cef 2010-07-18 | Fixed bug when trying to abort an already committed CommitBarrier [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | c802d2e 2010-07-18 | Fixed bug in using STM together with Active Objects [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | | | 7f04a9f 2010-07-18 | Completely redesigned Producer trait. [Martin Krasser]
| | |/ / / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | 52ae720 2010-07-17 | Added missing API documentation. [Martin Krasser]
| * | | | | | | | | | | | | | | | | | |   3056c8b 2010-07-17 | Merge commit 'remotes/origin/master' into 320-krasserm, resolve conflicts and compile errors. [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | | 2c4ef41 2010-07-16 | And multiverse module config [Peter Vlugter]
| | * | | | | | | | | | | | | | | | | | | d3d3bcb 2010-07-16 | Multiverse 0.6-SNAPSHOT again [Peter Vlugter]
| | * | | | | | | | | | | | | | | | | | | 9fafb98 2010-07-16 | Updated ants sample [Peter Vlugter]
| | * | | | | | | | | | | | | | | | | | | 3b9d4bd 2010-07-16 | Adding support for maxInactiveActivity [Viktor Klang]
| | * | | | | | | | | | | | | | | | | | | 1c4d8d7 2010-07-16 | Fixing case 286 [Viktor Klang]
| | * | | | | | | | | | | | | | | | | | | 470783b 2010-07-15 | Fixed race-condition in Cluster [Viktor Klang]
| | |/ / / / / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | | | |   e1e4196 2010-07-15 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | | 6e4c0cf 2010-07-15 | Upgraded to new fresh Multiverse with CountDownCommitBarrier bugfix [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | e43bb77 2010-07-15 | Upgraded Akka to Scala 2.8.0 final, finally... [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | 9b683d0 2010-07-15 | Added Scala 2.8 final versions of SBinary and Configgy [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | |   9580c85 2010-07-15 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   0a6e1e5 2010-07-15 | Added support for MaximumNumberOfRestartsWithinTimeRangeReachedException(this, maxNrOfRetries, withinTimeRange, reason) [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | | | | 9d8e6ad 2010-07-14 | Moved logging of actor crash exception that was by-passed/hidden by STM exception [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | | | 91da432 2010-07-14 | Changed Akka config file syntax to JSON-style instead of XML style Plus added missing test classes for ActiveObjectContextSpec [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | | | 809a00a 2010-07-14 | Added ActorRef.receiveTimout to remote protocol and LocalActorRef serialization [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | | | 17b752a 2010-07-14 | Removed 'reply' and 'reply_?' from Actor - now only usef 'self.reply' etc [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | | | 1df89f4 2010-07-14 | Removed Java Active Object tests, not needed now that we have them ported to Scala in the akka-core module [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | | | 49e572d 2010-07-14 | Fixed bug in Active Object restart, had no default life-cycle defined + added tests [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | | | 6ac052b 2010-07-14 | Added tests for ActiveObjectContext [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | | | 016c071 2010-07-14 | Fixed deadlock when Transactor is restarted in the middle of a transaction [Jonas Bonér]
| | * | | | | | | | | | | | | | | | | | | | | 815c0ab 2010-07-13 | Fixed 3 bugs in Active Objects and Actor supervision + changed to use Multiverse tryJoinCommit + improved logging + added more tracing + various misc fixes [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | | | | | 8707bb0 2010-07-16 | Non-blocking routing and transformation example with asynchronous HTTP request/reply [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | | | df1de81 2010-07-16 | closes #320 (non-blocking routing engine), closes #335 (producer to forward results) [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | | | e089d0f 2010-07-16 | Do not download sources [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | | | dc0e084 2010-07-16 | Remove Camel staging repo as Camel 2.4.0 can already be downloaded repo1. [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | | | 592ba27 2010-07-15 | Further tests [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | | | 8560eac 2010-07-15 | Fixed concurrency bug [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | | |   3e42d82 2010-07-15 | Merge commit 'remotes/origin/master' into 320-krasserm [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | | | | 5ba45bf 2010-07-15 | Camel's non-blocking routing engine now fully supported [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | | | 48aa70f 2010-07-13 | Further tests for non-blocking in-out message exchange with consumer actors. [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | | | f05b3b6 2010-07-13 | re #320 Non-blocking in-out message exchanges with actors [Martin Krasser]
* | | | | | | | | | | | | | | | | | | | | | |   66428ca 2010-07-15 | Merge remote branch 'origin/master' into wip-ssl-actors [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|_|_|/ / / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | | | |   8d2e7fa 2010-07-15 | Merge branch 'master' of git-proxy:jboner/akka [momania]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|/ / / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | | | | 14baf43 2010-07-15 | redisclient & sjson jar - 2.8.0 version [Debasish Ghosh]
| | | |/ / / / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | | | 5f8b2b1 2010-07-15 | Close #336 [momania]
| * | | | | | | | | | | | | | | | | | | | | d982e98 2010-07-15 | disable tests [momania]
| * | | | | | | | | | | | | | | | | | | | | fd110ac 2010-07-15 | - rpc typing and serialization - again [momania]
| * | | | | | | | | | | | | | | | | | | | | 5264a5a 2010-07-14 | rpc typing and serialization [momania]
* | | | | | | | | | | | | | | | | | | | | | 5abc835 2010-07-15 | Initial code, ble to turn ssl on/off, not verified [Viktor Klang]
* | | | | | | | | | | | | | | | | | | | | |   2f2d16a 2010-07-14 | Merge branch 'master' into wip_141_SSL_enable_remote_actors [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | | | 335946f 2010-07-14 | Laying the foundation for current-message-resend [Viktor Klang]
| |/ / / / / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | | | | 4d0b503 2010-07-14 | - make consumer restart when delegated handling actor fails - made single object to flag test enable/disable [momania]
| * | | | | | | | | | | | | | | | | | | | 8170b97 2010-07-14 | Test #328 [momania]
| * | | | | | | | | | | | | | | | | | | | ac5b770 2010-07-14 | small refactor - use patternmatching better [momania]
| * | | | | | | | | | | | | | | | | | | | 9351f6b 2010-07-12 | Closing ticket 294 [Viktor Klang]
| * | | | | | | | | | | | | | | | | | | | 5d5d479 2010-07-12 | Switching ActorRegistry storage solution [Viktor Klang]
| | |/ / / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | 29a7ba6 2010-07-11 | added new jar for sjson for 2.8.RC7 [Debasish Ghosh]
| * | | | | | | | | | | | | | | | | | | addeeb5 2010-07-11 | bug fix in redisclient, version upgraded to 1.4 [Debasish Ghosh]
| * | | | | | | | | | | | | | | | | | | 24da098 2010-07-11 | removed logging in cassandra [Jonas Boner]
| * | | | | | | | | | | | | | | | | | |   81eb6e1 2010-07-11 | Merge branch 'master' of github.com:jboner/akka [Jonas Boner]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   8ccb349 2010-07-08 | Merge branch 'amqp' [momania]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | |   
| | | * | | | | | | | | | | | | | | | | | 6022c63 2010-07-08 | cosmetic and disable the tests [momania]
| | | * | | | | | | | | | | | | | | | | | f1b090c 2010-07-08 | pimped the rpc a bit more, using serializers [momania]
| | | * | | | | | | | | | | | | | | | | | 20f0857 2010-07-08 | added rpc server and unit test [momania]
| | | * | | | | | | | | | | | | | | | | | 9e782f0 2010-07-08 | - split up channel parameters into channel and exchange parameters - initial setup for rpc client [momania]
| * | | | | | | | | | | | | | | | | | | | cfb2b34 2010-07-11 | Adding Ensime project file [Jonas Boner]
* | | | | | | | | | | | | | | | | | | | |   5220bbc 2010-07-07 | Merge branch 'master' of github.com:jboner/akka into wip_141_SSL_enable_remote_actors [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | |   fa97967 2010-07-07 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | | | 7da92cb 2010-07-07 | removed @PreDestroy functionality [Johan Rask]
| | * | | | | | | | | | | | | | | | | | | 2e7847c 2010-07-07 | Added support for springs @PostConstruct and @PreDestroy [Johan Rask]
| * | | | | | | | | | | | | | | | | | | | aa0459e 2010-07-07 | Dropped akka.xsd, updated all spring XML configurations to use akka-0.10.xsd [Martin Krasser]
| |/ / / / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | | | a890ccd 2010-07-07 | Closes #318: Race condition between ActorRef.cancelReceiveTimeout and ActorRegistry.shutdownAll [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | 0766047 2010-07-06 | Minor change, overriding destroyInstance instead of destroy [Johan Rask]
| * | | | | | | | | | | | | | | | | | | 16e246a 2010-07-06 | #301 DI does not work in akka-spring when specifying an interface [Johan Rask]
| * | | | | | | | | | | | | | | | | | | b6e0ae4 2010-07-06 | cosmetic logging change [momania]
| * | | | | | | | | | | | | | | | | | |   c918c59 2010-07-06 | Merge branch 'master' of git@github.com:jboner/akka and resolve conflicts in akka-spring [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | | e219b40 2010-07-05 | #304 Fixed Support for ApplicationContextAware in akka-spring [Johan Rask]
| | * | | | | | | | | | | | | | | | | | | e877064 2010-07-05 | set emtpy parens back [momania]
| | * | | | | | | | | | | | | | | | | | | 72e5181 2010-07-05 | - moved receive timeout logic to ActorRef - receivetimeout now only inititiated when receiveTimeout property is set [momania]
| * | | | | | | | | | | | | | | | | | | | 959873d 2010-07-06 | closes #314 akka-spring to support active object lifecycle management closes #315 akka-spring to support configuration of shutdown callback method [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | cd457ee 2010-07-05 | Tests for stopping active object endpoints; minor refactoring in ConsumerPublisher [Martin Krasser]
| |/ / / / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | | | 81745f1 2010-07-04 | Added test subject description [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | 94a0bed 2010-07-04 | Added comments. [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | 0b6ff86 2010-07-04 | Tests for ActiveObject lifecycle [Martin Krasser]
| * | | | | | | | | | | | | | | | | | |   e94693e 2010-07-04 | Resolved conflicts and compile errors after merging in master [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | | | | f964e64 2010-07-03 | Track stopping of Dispatcher actor [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | 09b78f4 2010-07-01 | re #297: Initial suport for shutting down routes to consumer active objects (both supervised and non-supervised). [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | cc8a112 2010-07-01 | Additional remote consumer test [Martin Krasser]
| * | | | | | | | | | | | | | | | | | | | 8dbcf6d 2010-07-01 | re #296: Initial support for active object lifecycle management [Martin Krasser]
* | | | | | | | | | | | | | | | | | | | | fff4d9d 2010-04-26 | ... [Viktor Klang]
* | | | | | | | | | | | | | | | | | | | | f0bbadc 2010-04-25 | Tests pass with Dummy SSL config! [Viktor Klang]
* | | | | | | | | | | | | | | | | | | | | 3cfe434 2010-04-25 | Added some Dummy SSL config to assist in proof-of-concept [Viktor Klang]
* | | | | | | | | | | | | | | | | | | | | 53f11d0 2010-04-25 | Adding SSL code to RemoteServer [Viktor Klang]
* | | | | | | | | | | | | | | | | | | | | f252aef 2010-04-25 | Initial code for SSL remote actors [Viktor Klang]
| |/ / / / / / / / / / / / / / / / / / /  
|/| | | | | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | | | | 44726ac 2010-07-04 | Fixed Issue #306: JSON serialization between remote actors is not transparent [Debasish Ghosh]
* | | | | | | | | | | | | | | | | | | |   afd814e 2010-07-02 | Merge branch 'master' of github.com:jboner/akka [Heiko Seeberger]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   d282f1f 2010-07-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | | 0cd3e3e 2010-07-02 | Do not log to error when interception NotFoundException from Cassandra [Jonas Bonér]
* | | | | | | | | | | | | | | | | | | | |   79e3240 2010-07-02 | Merge branch '290-hseeberger' [Heiko Seeberger]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |_|/ / / / / / / / / / / / / / / / / / /  
|/| | | | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | | 5fef4d1 2010-07-02 | re #290: Added parens to no-parameter methods returning Unit in all subprojects. [Heiko Seeberger]
| * | | | | | | | | | | | | | | | | | | | 2959ea3 2010-07-02 | re #290: Added parens to no-parameter methods returning Unit in rest of akka-core. [Heiko Seeberger]
| * | | | | | | | | | | | | | | | | | | | 73e6a2e 2010-07-02 | re #290: Added parens to no-parameter methods returning Unit in ActorRef. [Heiko Seeberger]
|/ / / / / / / / / / / / / / / / / / / /  
* | | | | | | | | | | | | | | | | | | | ad64d0b 2010-07-02 | Fixing flaky tests [Viktor Klang]
|/ / / / / / / / / / / / / / / / / / /  
* | | | | | | | | | | | | | | | | | | b278f47 2010-07-02 | Added codefellow to the plugins embeddded repo and upgraded to 0.3 [Jonas Bonér]
* | | | | | | | | | | | | | | | | | |   62cf14d 2010-07-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | | | e1b97fe 2010-07-02 |  - added dummy tests to make sure the test classes don't fail because of disabled tests, these tests need a local rabbitmq server running [momania]
| * | | | | | | | | | | | | | | | | | |   2e840b1 2010-07-02 | Merge branch 'master' of http://github.com/jboner/akka [momania]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | | | | fe8fc52 2010-07-02 | - moved deliveryHandler linking for consumer to the AMQP factory function - added the copyright comments [momania]
| * | | | | | | | | | | | | | | | | | | | cff82ba 2010-07-02 | No need for disconnect after a shutdown error [momania]
* | | | | | | | | | | | | | | | | | | | | 624e1b8 2010-07-02 | Addde codefellow plugin jars to embedded repo [Jonas Bonér]
| |/ / / / / / / / / / / / / / / / / / /  
|/| | | | | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | | | |   5d3d5a2 2010-07-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | | |   f9e5c44 2010-07-02 | Merge branch 'master' of http://github.com/jboner/akka [momania]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | | | | be78d01 2010-07-02 | removed akka.conf [momania]
| * | | | | | | | | | | | | | | | | | | | b4e2069 2010-07-02 | Redesigned AMQP [momania]
| * | | | | | | | | | | | | | | | | | | | 91536f4 2010-07-02 | RabbitMQ to 1.8.0 [momania]
* | | | | | | | | | | | | | | | | | | | | a3245bb 2010-07-02 | minor edits [Jonas Bonér]
| |/ / / / / / / / / / / / / / / / / / /  
|/| | | | | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | | | | 12dee4d 2010-07-02 | Changed Akka to use IllegalActorStateException instead of IllegalStateException [Jonas Bonér]
* | | | | | | | | | | | | | | | | | | | 26d757d 2010-07-02 | Merged in patch with method to find actor by function predicate on the ActorRegistry [Jonas Bonér]
* | | | | | | | | | | | | | | | | | | |   1d13528 2010-07-01 | Merge commit '02b816b893e1941b251a258b6403aa999c756954' [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / / / / / / /  
|/| | | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | d229a0f 2010-07-01 | CodeFellow integration [Jonas Bonér]
| |/ / / / / / / / / / / / / / / / / /  
* | | | | | | | | | | | | | | | | | | bb36faf 2010-07-01 | fixed bug in timeout handling that caused tests to fail [Jonas Bonér]
* | | | | | | | | | | | | | | | | | |   4e80e2f 2010-07-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | | | dc6f77b 2010-07-02 | type class based actor serialization implemented [Debasish Ghosh]
* | | | | | | | | | | | | | | | | | | | b174825 2010-07-01 | commented out failing tests [Jonas Bonér]
* | | | | | | | | | | | | | | | | | | |   c8318a8 2010-07-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | | |   c08fea5 2010-07-01 | Merge branch 'master' of http://github.com/jboner/akka [momania]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | | | | 10db236 2010-07-01 | Fix ActiveObjectGuiceConfiguratorSpec. Wait is now longer than set timeout [momania]
| * | | | | | | | | | | | | | | | | | | | 3d97ca3 2010-07-01 | Added ReceiveTimeout behaviour [momania]
* | | | | | | | | | | | | | | | | | | | | 7745db9 2010-07-01 | Added CodeFellow to gitignore [Jonas Bonér]
* | | | | | | | | | | | | | | | | | | | |   1a7d796 2010-07-01 | Merge commit '38e8bea3fe6a7e9fcc9c5f353124144739bdc234' [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |_|/ / / / / / / / / / / / / / / / / / /  
|/| | | | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | | e858112 2010-06-29 | Fixed bug in fault handling of TEMPORARY Actors + ported all Active Object Java tests to Scala (using Java POJOs) [Jonas Bonér]
* | | | | | | | | | | | | | | | | | | | |   be6767e 2010-07-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | | 23033b4 2010-07-01 | #292 - Added scheduleOne and re-created unit tests [momania]
| | |/ / / / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | | | | 1a663b1 2010-07-01 | Removed unused catch for IllegalStateException [Jonas Bonér]
|/ / / / / / / / / / / / / / / / / / /  
* | | | | | | | | | | | | | | | | | | 2d48098 2010-06-30 | Removed trailing whitespace [Jonas Bonér]
* | | | | | | | | | | | | | | | | | | 46d250f 2010-06-30 | Converted TAB to SPACE [Jonas Bonér]
* | | | | | | | | | | | | | | | | | | b7a7527 2010-06-30 | Fixed bug in remote deserialization + fixed some failing tests + cleaned up and reorganized code [Jonas Bonér]
* | | | | | | | | | | | | | | | | | | 67ed707 2010-06-29 | Fixed bug in fault handling of TEMPORARY Actors + ported all Active Object Java tests to Scala (using Java POJOs) [Jonas Bonér]
|/ / / / / / / / / / / / / / / / / /  
* | | | | | | | | | | | | | | | | | c6b7ba6 2010-06-28 | Added Java tests as Scala tests [Jonas Bonér]
* | | | | | | | | | | | | | | | | | 26a77a8 2010-06-28 | Added AspectWerkz 2.2 to embedded-repo [Jonas Bonér]
* | | | | | | | | | | | | | | | | | dbb33f8 2010-06-28 | Upgraded to AspectWerkz 2.2 + merged in patch for using Actor.isDefinedAt for akka-patterns stuff [Jonas Bonér]
| |_|_|_|_|_|_|_|_|_|_|_|_|_|/ / /  
|/| | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | d3a8b59 2010-06-27 | FP approach always makes one happy [Viktor Klang]
* | | | | | | | | | | | | | | | | 256e80b 2010-06-27 | Minor tidying [Viktor Klang]
* | | | | | | | | | | | | | | | | c55ed7b 2010-06-27 | Fix for #286 [Viktor Klang]
* | | | | | | | | | | | | | | | | 929c1e2 2010-06-26 | Updated to Netty 3.2.1.Final [Viktor Klang]
* | | | | | | | | | | | | | | | | 05cb3b2 2010-06-26 | Upgraded to Atmosphere 0.6 final [Viktor Klang]
* | | | | | | | | | | | | | | | | e83e7dc 2010-06-25 | Atmosphere bugfix [Viktor Klang]
* | | | | | | | | | | | | | | | | 0bf0532 2010-06-24 | Tests for #289 [Martin Krasser]
* | | | | | | | | | | | | | | | |   8abd26c 2010-06-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|_|_|_|_|_|_|_|_|_|_|_|/ / /  
| |/| | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | 87461dc 2010-06-24 | Documentation added. [Martin Krasser]
| * | | | | | | | | | | | | | | | e569b4f 2010-06-24 | Minor edits [Martin Krasser]
| * | | | | | | | | | | | | | | |   a298c91 2010-06-24 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | 3a448a3 2010-06-24 | closes #289: Support for <akka:camel-service> Spring configuration element [Martin Krasser]
| * | | | | | | | | | | | | | | | | 836f04f 2010-06-24 | Comment changed [Martin Krasser]
* | | | | | | | | | | | | | | | | | 67330e4 2010-06-24 | Increased timeout in Transactor in STMSpec [Jonas Bonér]
* | | | | | | | | | | | | | | | | | c3d723c 2010-06-24 | Added serialization of actor mailbox [Jonas Bonér]
| |/ / / / / / / / / / / / / / / /  
|/| | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | |   f1f3c92 2010-06-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | 421aec4 2010-06-23 | Added test for verifying pre/post restart invocations [Johan Rask]
| * | | | | | | | | | | | | | | | | f523671 2010-06-23 | Fixed #287,Old dispatcher settings are now copied to new dispatcher on restart [Johan Rask]
| |/ / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | 8d1a5d5 2010-06-23 | Updated sbt plugin [Peter Vlugter]
| * | | | | | | | | | | | | | | | ba7c2fe 2010-06-22 | Added akka.conf values as defaults and removed lift dependency [Viktor Klang]
* | | | | | | | | | | | | | | | | 95d4504 2010-06-23 | fixed mem-leak in Active Object + reorganized SerializableActor traits [Jonas Bonér]
|/ / / / / / / / / / / / / / / /  
* | | | | | | | | | | | | | | | e3d6615 2010-06-22 | Added test for serializing stateless actor + made mailbox accessible [Jonas Bonér]
* | | | | | | | | | | | | | | | aad7189 2010-06-22 | Fixed bug with actor unregistration in ActorRegistry, now we are using a Set instead of a List and only the right instance is removed, not all as before [Jonas Bonér]
* | | | | | | | | | | | | | | |   444bd5b 2010-06-22 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | 5bb912b 2010-06-21 | Removed comments from test [Johan Rask]
| * | | | | | | | | | | | | | | |   a569913 2010-06-21 | Merge branch 'master' of github.com:jboner/akka [Johan Rask]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | cb7e26e 2010-06-21 | Added missing files [Johan Rask]
* | | | | | | | | | | | | | | | | | 916cfe9 2010-06-22 | Protobuf deep actor serialization working and test passing [Jonas Bonér]
| |/ / / / / / / / / / / / / / / /  
|/| | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | 5397e81 2010-06-21 | commented out failing spring test [Jonas Bonér]
* | | | | | | | | | | | | | | | |   f436e9c 2010-06-21 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | |   9235f13 2010-06-21 | Merge branch 'master' of github.com:jboner/akka [Johan Rask]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|_|_|_|_|_|_|/ / /  
| | |/| | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | |   485ba41 2010-06-21 | Merge branch '281-hseeberger' [Heiko Seeberger]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | 3af484c 2010-06-21 | closes #281: Made all subprojects test after breaking changes introduced by removing the type parameter from ActorRef.!!. [Heiko Seeberger]
| | | * | | | | | | | | | | | | | | 07ac1d9 2010-06-21 | re #281: Made all subprojects compile  after breaking changes introduced by removing the type parameter from ActorRef.!!; test-compile still missing! [Heiko Seeberger]
| | | * | | | | | | | | | | | | | | 2128dcd 2010-06-21 | re #281: Made akka-core compile and test after breaking changes introduced by removing the type parameter from ActorRef.!!. [Heiko Seeberger]
| | | * | | | | | | | | | | | | | | 5668337 2010-06-21 | re #281: Added as[T] and asSilently[T] to Option[Any] via implicit conversions in object Actor. [Heiko Seeberger]
| | | * | | | | | | | | | | | | | |   a2dc201 2010-06-21 | Merge branch 'master' into 281-hseeberger [Heiko Seeberger]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | |   
| | | * | | | | | | | | | | | | | |   516cad8 2010-06-19 | Merge branch 'master' into 281-hseeberger [Heiko Seeberger]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | 4ba1c58 2010-06-18 | re #281: Removed type parameter from ActorRef.!! which now returns Option[Any] and added Helpers.narrow and Helpers.narrowSilently. [Heiko Seeberger]
| | | | |_|_|_|_|_|_|_|/ / / / / / /  
| | | |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | efdff1d 2010-06-21 | When interfaces are used, target instances are now created correctly [Johan Rask]
| * | | | | | | | | | | | | | | | | 920b4d0 2010-06-16 | Added support for scope and depdenency injection on target bean [Johan Rask]
| |/ / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | 265be35 2010-06-20 | Added more examples to akka-sample-camel [Martin Krasser]
| * | | | | | | | | | | | | | | | 098dadf 2010-06-20 | Changed return type of CamelService.load to CamelService [Martin Krasser]
| * | | | | | | | | | | | | | | |   b8090b3 2010-06-20 | Merge branch 'stm-pvlugter' [Peter Vlugter]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | 4e5a516 2010-06-20 | Some stm documentation changes [Peter Vlugter]
| | * | | | | | | | | | | | | | | | d382f9b 2010-06-20 | Improved transaction factory defaults [Peter Vlugter]
| | * | | | | | | | | | | | | | | | dd7dc9e 2010-06-20 | Removed isTransactionalityEnabled [Peter Vlugter]
| | * | | | | | | | | | | | | | | | db325d8 2010-06-20 | Updated ants sample [Peter Vlugter]
| | * | | | | | | | | | | | | | | | e85322c 2010-06-19 | Removing unused stm classes [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 3576f32 2010-06-19 | Moved data flow to its own package [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 4e47a68 2010-06-18 | Using actor id for transaction family name [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 79e54d1 2010-06-18 | Updated imports to use stm package objects [Peter Vlugter]
| | * | | | | | | | | | | | | | | | f3335ea 2010-06-18 | Added some documentation for stm [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 91f3c83 2010-06-17 | Added transactional package object - includes Multiverse data structures [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 9e8ff73 2010-06-14 | Added stm local and global package objects [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 8bf6e00 2010-06-14 | Removed some trailing whitespace [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 02b4403 2010-06-10 | Updated actor ref to use transaction factory [Peter Vlugter]
| | * | | | | | | | | | | | | | | | c5caf58 2010-06-10 | Configurable TransactionFactory [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 5b0a7d0 2010-06-10 | Fixed import in ants sample for removed Vector class [Peter Vlugter]
| | * | | | | | | | | | | | | | | | fce9d8e 2010-06-10 | Added Transaction.Util with methods for transaction lifecycle and blocking [Peter Vlugter]
| | * | | | | | | | | | | | | | | | b20b399 2010-06-10 | Removed unused stm config options from akka conf [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 5a03a8d 2010-06-10 | Removed some unused stm config options [Peter Vlugter]
| | * | | | | | | | | | | | | | | | d47c152 2010-06-10 | Added Duration utility class for working with j.u.c.TimeUnit [Peter Vlugter]
| | * | | | | | | | | | | | | | | | e18305c 2010-06-10 | Updated stm tests [Peter Vlugter]
| | * | | | | | | | | | | | | | | | ef4d926 2010-06-10 | Using Scala library HashMap and Vector [Peter Vlugter]
| | * | | | | | | | | | | | | | | | fa044ca 2010-06-10 | Removed TransactionalState and TransactionalRef [Peter Vlugter]
| | * | | | | | | | | | | | | | | | ce41e17 2010-06-10 | Removed for-comprehensions for transactions [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 3cc875e 2010-06-10 | Removed AtomicTemplate - new Java API will use Multiverse more directly [Peter Vlugter]
| | * | | | | | | | | | | | | | | | 2eeffb6 2010-06-10 | Removed atomic0 - no longer used [Peter Vlugter]
| | * | | | | | | | | | | | | | | | a0d4d7c 2010-06-10 | Updated to Multiverse 0.6-SNAPSHOT [Peter Vlugter]
| |/ / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | 9ca039c 2010-06-19 | Fixed bug with stm not being enabled by default when no AKKA_HOME is set. [Jonas Bonér]
| * | | | | | | | | | | | | | | | 7ba76a8 2010-06-19 | Enforce commons-codec version 1.4 for akka-core [Martin Krasser]
| * | | | | | | | | | | | | | | | 52b6dfe 2010-06-19 | Producer trait with default implementation of Actor.receive [Martin Krasser]
| | |/ / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | |   52d4bc3 2010-06-18 | Stateless and Stateful Actor serialization + Turned on class caching in Active Object [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | 35c5998 2010-06-14 | Added akka-sbt-plugin source [Peter Vlugter]
| | |_|_|_|_|_|_|_|_|_|_|/ / /  
| |/| | | | | | | | | | | | |   
* | | | | | | | | | | | | | | e7a7d6e 2010-06-18 | Fix for ticket #280 - Tests fail if there is no akka.conf set [Jonas Bonér]
|/ / / / / / / / / / / / / /  
* | | | | | | | | | | | | | 306d017 2010-06-18 | Fixed final issues in actor deep serialization; now Java and Protobuf support [Jonas Bonér]
* | | | | | | | | | | | | | ed62823 2010-06-17 | Upgraded commons-codec to 1.4 [Jonas Bonér]
* | | | | | | | | | | | | | 867fa80 2010-06-16 | Serialization of Actor now complete (using Java serialization of actor instance) [Jonas Bonér]
* | | | | | | | | | | | | | ac5b088 2010-06-15 | Added fromProtobufToLocalActorRef serialization, all old test passing [Jonas Bonér]
* | | | | | | | | | | | | | 068a6d7 2010-06-10 | Added SerializableActorSpec for testing deep actor serialization [Jonas Bonér]
* | | | | | | | | | | | | | 9d7877d 2010-06-10 | Deep serialization of Actors now works [Jonas Bonér]
* | | | | | | | | | | | | | fe9109d 2010-06-10 | Added SerializableActor trait and friends [Jonas Bonér]
* | | | | | | | | | | | | | 16671dc 2010-06-10 | Upgraded existing code to new remote protocol, all tests pass [Jonas Bonér]
| |_|_|_|_|_|_|_|/ / / / /  
|/| | | | | | | | | | | |   
* | | | | | | | | | | | | 27ae559 2010-06-16 | Fixed problem with Scala REST sample [Jonas Bonér]
|/ / / / / / / / / / / /  
* | | | | | | | | | | | 171888c 2010-06-15 | Made AMQP UnregisterMessageConsumerListener public [Jonas Bonér]
* | | | | | | | | | | | 4ebb17e 2010-06-15 | fixed problem with cassandra map storage in rest example [Jonas Bonér]
* | | | | | | | | | | | 55778b4 2010-06-11 | Marked Multiverse dependency as intransitive [Peter Vlugter]
* | | | | | | | | | | | 1b3d854 2010-06-11 | Redis persistence now handles serialized classes.Removed apis for increment / decrement atomically from Ref. Issue #267 fixed [Debasish Ghosh]
* | | | | | | | | | | |   c227c77 2010-06-10 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
|\ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | 7a271db 2010-06-10 | Added a isDefinedAt method on the ActorRef [Jonas Bonér]
| * | | | | | | | | | | | 9ba7120 2010-06-09 | Improved RemoteClient listener info [Jonas Bonér]
| * | | | | | | | | | | |   411038a 2010-06-08 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|/ / / / /  
| | |/| | | | | | | | | |   
| * | | | | | | | | | | | c56a049 2010-06-08 | added redis test from debasish [Jonas Bonér]
| * | | | | | | | | | | | 6b2013c 2010-06-08 | Added bench from akka-bench for convenience [Jonas Bonér]
| * | | | | | | | | | | | 92434bb 2010-06-08 | Fixed bug in setting sender ref + changed version to 0.10 [Jonas Bonér]
* | | | | | | | | | | | | 97ee7fd 2010-06-10 | remote consumer tests [Martin Krasser]
* | | | | | | | | | | | | 92f4e00 2010-06-10 | restructured akka-sample-camel [Martin Krasser]
* | | | | | | | | | | | | 0897038 2010-06-10 | tests for accessing active objects from Camel routes (ticket #266) [Martin Krasser]
| |/ / / / / / / / / / /  
|/| | | | | | | | | | |   
* | | | | | | | | | | |   e56c025 2010-06-08 | Merge commit 'remotes/origin/master' into 224-krasserm [Martin Krasser]
|\ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / /  
| * | | | | | | | | | | 23f6551 2010-06-08 | Bumped version to 0.10-SNAPSHOT [Jonas Bonér]
| * | | | | | | | | | | e4f08af 2010-06-07 | Added a method to get a List with all MessageInvocation in the Actor mailbox [Jonas Bonér]
| * | | | | | | | | | | 1892b56 2010-06-07 | Upgraded build.properties to 0.9.1 [Jonas Bonér]
| * | | | | | | | | | | abf6500 2010-06-07 | Upgraded to version 0.9.1 [Jonas Bonér]
| | |_|_|_|/ / / / / /  
| |/| | | | | | | | |   
| * | | | | | | | | | 22fe2ac 2010-06-07 | Added reply methods to Actor trait + fixed race-condition in Actor.spawn [Jonas Bonér]
| | |_|_|_|_|_|/ / /  
| |/| | | | | | | |   
| * | | | | | | | | 7afe411 2010-06-06 | Removed legacy code [Viktor Klang]
* | | | | | | | | | 6a17cbc 2010-06-08 | Support for using ActiveObjectComponent without Camel service [Martin Krasser]
* | | | | | | | | | 8b4c111 2010-06-07 | Extended documentation (active object support) [Martin Krasser]
* | | | | | | | | | 37e0670 2010-06-06 | Added remote active object example [Martin Krasser]
* | | | | | | | | |   e34a835 2010-06-06 | Merge remote branch 'remotes/origin/master' into 224-krasserm [Martin Krasser]
|\ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / /  
| * | | | | | | | |   ec59d12 2010-06-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | bf8f4be 2010-06-05 | Tidying up more debug statements [Viktor Klang]
| | * | | | | | | | | 4d60d14 2010-06-05 | Tidying up debug statements [Viktor Klang]
| | * | | | | | | | | be159c9 2010-06-05 | Fixing Jersey classpath resource scanning [Viktor Klang]
| * | | | | | | | | | 733acc1 2010-06-05 | Added methods to retreive children from a Supervisor [Jonas Bonér]
| |/ / / / / / / / /  
| * | | | | | | | | 5694fde 2010-06-04 | Freezing Atmosphere dep [Viktor Klang]
| * | | | | | | | |   59e1ba4 2010-06-04 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | c2f775f 2010-06-02 | Cleanup and refactored code a bit and added javadoc to explain througput parameter in detail. [Jan Van Besien]
| | * | | | | | | | | 90eefd2 2010-06-02 | formatting, comment fixup [rossputin]
| | * | | | | | | | | 6bd5719 2010-06-02 | update README docs for chat sample [rossputin]
| * | | | | | | | | | cc06410 2010-06-04 | Fixed bug in remote actors + improved scaladoc [Jonas Bonér]
| |/ / / / / / / / /  
* | | | | | | | | | 8f2ef6e 2010-06-06 | Fixed wrong test description [Martin Krasser]
* | | | | | | | | | f2e5fb1 2010-06-04 | Initial tests for active object support [Martin Krasser]
* | | | | | | | | | 5402165 2010-06-04 | make all classes/traits module-private that are not part of the public API [Martin Krasser]
* | | | | | | | | | 6972b66 2010-06-03 | Cleaned main sources from target actor instance access. Minor cleanups. [Martin Krasser]
* | | | | | | | | | 2fcb218 2010-06-03 | Dropped service package and moved contained classes one level up. [Martin Krasser]
* | | | | | | | | | 9241599 2010-06-03 | Refactored tests to interact with actors only via message passing [Martin Krasser]
* | | | | | | | | | bfa3ee2 2010-06-03 | ActiveObjectComponent now written in Scala [Martin Krasser]
* | | | | | | | | |   9af36d6 2010-06-02 | Merge commit 'remotes/origin/master' into 224-krasserm [Martin Krasser]
|\ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / /  
| * | | | | | | | | fb2ef58 2010-06-01 | Re-adding runnable Active Object Java tests, which all pass [Jonas Bonér]
| * | | | | | | | |   2ed0fea 2010-06-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | c4a72fd 2010-05-31 | Fixed Jersey dependency [Viktor Klang]
| * | | | | | | | | | abfde20 2010-06-01 | Upgraded run script [Jonas Bonér]
| * | | | | | | | | | 56e7428 2010-06-01 | Removed trailing whitespace [Jonas Bonér]
| * | | | | | | | | | c873256 2010-06-01 | Converted tabs to spaces [Jonas Bonér]
| * | | | | | | | | | 87c336b 2010-06-01 | Added activemq-data to .gitignore [Jonas Bonér]
| * | | | | | | | | | 89f53ea 2010-06-01 | Removed redundant servlet spec API jar from dist manifest [Jonas Bonér]
| * | | | | | | | | | 8aa730d 2010-06-01 | Added guard for NULL inital values in Agent [Jonas Bonér]
| * | | | | | | | | | 754c8a9 2010-06-01 | Added assert for if message is NULL [Jonas Bonér]
| * | | | | | | | | | 20c2ed2 2010-06-01 | Removed MessageInvoker [Jonas Bonér]
| * | | | | | | | | | 79bfc20 2010-06-01 | Removed ActorMessageInvoker [Jonas Bonér]
| * | | | | | | | | | 781293d 2010-06-01 | Fixed race condition in Agent + improved ScalaDoc [Jonas Bonér]
| * | | | | | | | | | ceda35d 2010-05-31 | Added convenience method to ActorRegistry [Jonas Bonér]
| |/ / / / / / / / /  
| * | | | | | | | | 3ad1118 2010-05-31 | Refactored Java REST example to work with the new way of doing REST in Akka [Jonas Bonér]
| | |_|_|_|_|/ / /  
| |/| | | | | | |   
| * | | | | | | | a6edff9 2010-05-31 | Cleaned up 'Supervisor' code and ScalaDoc + renamed dispatcher throughput option in config to 'dispatcher.throughput' [Jonas Bonér]
| * | | | | | | | 8b303fa 2010-05-31 | Renamed 'toProtocol' to 'toProtobuf' [Jonas Bonér]
| * | | | | | | | dc22b8c 2010-05-30 | Upgraded Atmosphere to 0.6-SNAPSHOT [Viktor Klang]
| * | | | | | | |   a6b5903 2010-05-30 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | d8cd6e6 2010-05-30 | Added test for tested Transactors [Jonas Bonér]
| * | | | | | | | | ad3e2ae 2010-05-30 | minor fix in test case [Debasish Ghosh]
| |/ / / / / / / /  
| * | | | | | | | 1904363 2010-05-30 | Upgraded ScalaTest to Scala 2.8.0.RC3 compat lib [Jonas Bonér]
| * | | | | | | |   ce064cc 2010-05-30 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | fb44990 2010-05-30 | Fixed bug in STM and Persistence integration: added trait Abortable and added abort methods to all Persistent datastructures and removed redundant errornous atomic block [Jonas Bonér]
* | | | | | | | | | 42fff6b 2010-06-02 | Prepare merge with master [Martin Krasser]
* | | | | | | | | | 1d2a6c5 2010-06-01 | initial support for publishing ActiveObject methods at Camel endpoints [Martin Krasser]
| |/ / / / / / / /  
|/| | | | | | | |   
* | | | | | | | | 2d2bdda 2010-05-30 | Upgrade to Camel 2.3.0 [Martin Krasser]
* | | | | | | | | 2dd2118 2010-05-29 | Prepare for master merge [Viktor Klang]
* | | | | | | | | b8fd1d1 2010-05-29 | Ported akka-sample-secure [Viktor Klang]
* | | | | | | | |   c660a9c 2010-05-29 | Merge branch 'master' of github.com:jboner/akka into wip-akka-rest-fix [Viktor Klang]
|\ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \   f8b8bc7 2010-05-29 | Merge branch '247-hseeberger' [Heiko Seeberger]
| |\ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| |/| | | | | | | |   
| | * | | | | | | | d1d8773 2010-05-29 | closes #247: Added all missing module configurations. [Heiko Seeberger]
| | * | | | | | | |   c21e2d1 2010-05-29 | Merge branch 'master' into 247-hseeberger [Heiko Seeberger]
| | |\ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| |/| | | | | | | |   
| * | | | | | | | | 1c37f0c 2010-05-29 | Upgraded to Protobuf 2.3.0 [Jonas Bonér]
| * | | | | | | | |   47e6534 2010-05-29 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | caa6020 2010-05-28 | no need to start supervised actors [Martin Krasser]
| * | | | | | | | | | a891ddd 2010-05-29 | Upgraded Configgy to one build for Scala 2.8 RC3 [Jonas Bonér]
| * | | | | | | | | | c22e564 2010-05-28 | Fixed issue with CommitBarrier and its registered callbacks + Added compensating 'barrier.decParties' to each 'barrier.incParties' [Jonas Bonér]
| | | * | | | | | | | b5f4cfa 2010-05-28 | re #247: Added module configuration for akka-persistence-cassandra. Attention: Necessary to delete .ivy2 directory! [Heiko Seeberger]
| | | * | | | | | | | f4298eb 2010-05-28 | re #247: Removed all vals for repositories except for embeddedRepo. Introduced module configurations necessary for akka-core; other modules still missing. [Heiko Seeberger]
* | | | | | | | | | | 2a060c9 2010-05-29 | Ported samples rest scala to the new akka-http [Viktor Klang]
* | | | | | | | | | |   64f4b36 2010-05-28 | Looks promising! [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | d587d1a 2010-05-28 | Fixing sbt run (exclude slf4j 1.5.11) [Viktor Klang]
| * | | | | | | | | | | b94f584 2010-05-28 | ClassLoader issue [Viktor Klang]
| | |/ / / / / / / / /  
| |/| | | | | | | | |   
* | | | | | | | | | |   f1cc27a 2010-05-28 | Merge branch 'master' of github.com:jboner/akka into wip-akka-rest-fix [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / /  
| * | | | | | | | | | 78e69f0 2010-05-29 | checked in wrong jar: now pushing the correct one for sjson [Debasish Ghosh]
| | |/ / / / / / / /  
| |/| | | | | | | |   
| * | | | | | | | | c7b2352 2010-05-28 | project file updated for redisclient for 2.8.0.RC3 [Debasish Ghosh]
| * | | | | | | | | ef25f05 2010-05-28 | redisclient upped to 2.8.0.RC3 [Debasish Ghosh]
| * | | | | | | | | b526973 2010-05-28 | updated project file for sjson for 2.8.RC3 [Debasish Ghosh]
| * | | | | | | | | 414f366 2010-05-28 | added 2.8.0.RC3 for sjson jar [Debasish Ghosh]
| |/ / / / / / / /  
| * | | | | | | | 89bf596 2010-05-28 | Switched Listeners impl from Agent to CopyOnWriteArraySet [Jonas Bonér]
| * | | | | | | |   c708521 2010-05-28 | Merge branch 'scala_2.8.RC3' [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | 178708d 2010-05-28 | Added Scala 2.8RC3 version of SBinary [Jonas Bonér]
| | * | | | | | | | 7e3a46b 2010-05-28 | Upgraded to Scala 2.8.0 RC3 [Jonas Bonér]
| * | | | | | | | |   cdcacb7 2010-05-28 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| * | | | | | | | | | 5aaf127 2010-05-28 | Fix of issue #235 [Jonas Bonér]
| | |/ / / / / / / /  
| |/| | | | | | | |   
| * | | | | | | | |   11ca821 2010-05-28 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| * | | | | | | | | | 9624e13 2010-05-28 | Added senderFuture to ActiveObjectContext, eg. fixed issue #248 [Jonas Bonér]
* | | | | | | | | | |   547f4c9 2010-05-28 | Merge branch 'master' of github.com:jboner/akka into wip-akka-rest-fix [Viktor Klang]
|\ \ \ \ \ \ \ \ \ \ \  
| | |_|/ / / / / / / /  
| |/| | | | | | | | |   
| * | | | | | | | | |   6ba1442 2010-05-28 | Merge branch 'master' of github.com:jboner/akka [rossputin]
| |\ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / /  
| | |/| | | | | | | |   
| | * | | | | | | | | 01ed99f 2010-05-28 | Correct fix for no logging on sbt run [Peter Vlugter]
| | |/ / / / / / / /  
| | * | | | | | | | b29ce25 2010-05-28 | Fixed issue #240: Supervised actors not started when starting supervisor [Jonas Bonér]
| * | | | | | | | | 24486be 2010-05-28 | minor log message change for consistency [rossputin]
| |/ / / / / / / /  
| * | | | | | | | e0477ba 2010-05-28 | Fixed issue with AMQP module [Jonas Bonér]
| * | | | | | | | 0416eb5 2010-05-28 | Made 'sender' and 'senderFuture' in ActorRef public [Jonas Bonér]
| * | | | | | | |   64b1b1a 2010-05-28 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | c6b99a2 2010-05-28 | Fix for no logging on sbt run (#241) [Peter Vlugter]
| * | | | | | | | | 61a7cdf 2010-05-28 | Fixed issue with sender reference in Active Objects [Jonas Bonér]
| |/ / / / / / / /  
| * | | | | | | | 87276a4 2010-05-27 | fixed publish-local-mvn [Michael Kober]
| * | | | | | | | 419d18a 2010-05-27 | Added default dispatch.throughput value to akka-reference.conf [Peter Vlugter]
| * | | | | | | | 488f1ae 2010-05-27 | Configurable throughput for ExecutorBasedEventDrivenDispatcher (#187) [Peter Vlugter]
| * | | | | | | | 718aac2 2010-05-27 | Updated to Multiverse 0.5.2 [Peter Vlugter]
* | | | | | | | | 8d5e685 2010-05-26 | Tweaking akka-reference.conf [Viktor Klang]
* | | | | | | | | f6c1cbf 2010-05-26 | Elaborated on classloader handling [Viktor Klang]
* | | | | | | | |   96333fc 2010-05-26 | Merge branch 'master' of github.com:jboner/akka [Viktor Klang]
|\ \ \ \ \ \ \ \ \  
| |/ / / / / / / /  
| * | | | | | | |   360a5cd 2010-05-26 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \ \ \  
| * | | | | | | | | eadff41 2010-05-26 | Workaround temporary issue by starting supervised actors explicitly. [Martin Krasser]
* | | | | | | | | | 4218595 2010-05-25 | Initial attempt at fixing akka rest [Viktor Klang]
| |/ / / / / / / /  
|/| | | | | | | |   
* | | | | | | | |   829ab8d 2010-05-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| | |_|_|_|/ / / /  
| |/| | | | | | |   
| * | | | | | | | 3af5f5e 2010-05-25 | implemented updateVectorStorageEntryFor in akka-persistence-mongo (issue #165) and upgraded mongo-java-driver to 1.4 [Debasish Ghosh]
* | | | | | | | | 06bff76 2010-05-25 | Added option to specify class loader when deserializing RemoteActorRef [Jonas Bonér]
* | | | | | | | | dbbad46 2010-05-25 | Added option to specify class loader to load serialized classes in the RemoteClient + cleaned up RemoteClient and RemoteServer API in this regard [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | 83e22cb 2010-05-25 | Fixed issue #157 [Jonas Bonér]
* | | | | | | |   1b11899 2010-05-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| * | | | | | | | 9429ada 2010-05-25 | fixed merge error [Michael Kober]
* | | | | | | | | 087e421 2010-05-25 | Fixed issue #156 and #166 [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | 83884eb 2010-05-25 | Changed order of peristence operations in Storage::commit, now clear is done first [Jonas Bonér]
* | | | | | | |   09ef577 2010-05-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| * | | | | | | | f0bbb1e 2010-05-25 | fixed merge error [Michael Kober]
| * | | | | | | |   d76e8e3 2010-05-25 | Merge branch '221-sbt-publish' [Michael Kober]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | 98b569c 2010-05-25 | added new task publish-local-mvn [Michael Kober]
| | |/ / / / / / /  
* | | | | | | | | ea9253c 2010-05-25 | Upgraded to Cassandra 0.6.1 [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | 436bfba 2010-05-25 | Upgraded to SBinary for Scala 2.8.0.RC2 [Jonas Bonér]
* | | | | | | | e414ec0 2010-05-25 | Fixed bug in Transaction.Local persistence management [Jonas Bonér]
|/ / / / / / /  
* | | | | | | b503e29 2010-05-24 | Upgraded to 2.8.0.RC2-1.4-SNAPSHOT version of Redis Client [Jonas Bonér]
* | | | | | | 10e9530 2010-05-24 | Fixed wrong code rendering [Jonas Bonér]
* | | | | | | c9481da 2010-05-24 | Added akka-sample-ants as a sample showcasing STM and Transactors [Jonas Bonér]
* | | | | | |   2c32320 2010-05-24 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | 1cdf832 2010-05-24 | disabled tests for akka-persistence-redis to be run automatically [Debasish Ghosh]
| * | | | | | | d72caf7 2010-05-24 | updated redisclient to 2.8.0.RC2 [Debasish Ghosh]
| * | | | | | | bf9e502 2010-05-22 | Removed some LoC [Viktor Klang]
* | | | | | | | 60c08e9 2010-05-24 | Fixed bug in issue #211; Transaction.Global.atomic {...} management [Jonas Bonér]
* | | | | | | | 7cbe355 2010-05-24 | Added failing test for issue #211; triggering CommitBarrierOpenException [Jonas Bonér]
* | | | | | | | bc88704 2010-05-24 | Updated pom.xml for Java test to 0.9 [Jonas Bonér]
* | | | | | | | 555e657 2010-05-24 | Updated to JGroups 2.9.0.GA [Jonas Bonér]
* | | | | | | | 540b4e0 2010-05-24 | Added ActiveObjectContext with sender reference [Jonas Bonér]
|/ / / / / / /  
* | | | | | |   4ec7acb 2010-05-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * \ \ \ \ \ \   124bfd8 2010-05-22 | Merged with origin/master [Viktor Klang]
| |\ \ \ \ \ \ \  
| * | | | | | | | 5abdbe8 2010-05-22 | Switched to primes and !! + cleanup [Viktor Klang]
* | | | | | | | | b1e299a 2010-05-23 | Fixed regression bug in AMQP supervisor code [Jonas Bonér]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | | ef45288 2010-05-21 | Removed trailing whitespace [Jonas Bonér]
* | | | | | | |   7894d15 2010-05-21 | Merge branch 'scala_2.8.RC2' into rc2 [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| * | | | | | | | e8c3ebd 2010-05-21 | Upgraded to Scala RC2 version of ScalaTest, but still some problems [Jonas Bonér]
| * | | | | | | | f9b21cd 2010-05-20 | Port to Scala RC2. All compile, but tests fail on ScalaTest not being RC2 compatible [Jonas Bonér]
* | | | | | | | | 65b6c21 2010-05-21 | Add the possibility to start Akka kernel or use Akka as dependency JAR *without* setting AKKA_HOME or have an akka.conf defined somewhere. Also moved JGroupsClusterActor into akka-core and removed akka-cluster module [Jonas Bonér]
* | | | | | | | | 2fce167 2010-05-21 | Fixed issue #190: RemoteClient shutdown ends up in endless loop [Jonas Bonér]
* | | | | | | | | 86a8fd0 2010-05-21 | Fixed regression in Scheduler [Jonas Bonér]
* | | | | | | | |   c34f084 2010-05-20 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | | 6fe7d7c 2010-05-20 | Added regressiontest for spawn [Viktor Klang]
| * | | | | | | | 37b8524 2010-05-20 | Fixed cluster [Viktor Klang]
| |/ / / / / / /  
* | | | | | | | 4375f82 2010-05-20 | Fixed race-condition in creation and registration of RemoteServers [Jonas Bonér]
|/ / / / / / /  
* | | | | | | a162f47 2010-05-19 | Fixed problem with ordering when invoking self.start from within Actor [Jonas Bonér]
* | | | | | | 8842362 2010-05-19 | Re-introducing 'sender' and 'senderFuture' references. Now 'sender' is available both for !! and !!! message sends [Jonas Bonér]
* | | | | | | 05058af 2010-05-18 | Added explicit nullification of all ActorRef references in Actor to make the Actor instance eligable for GC [Jonas Bonér]
* | | | | | | 3d973fe 2010-05-18 | Fixed race-condition in Supervisor linking [Jonas Bonér]
* | | | | | |   bec9a96 2010-05-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * \ \ \ \ \ \   60fbd4e 2010-05-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
* | \ \ \ \ \ \ \   04b5142 2010-05-18 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| |/ / / / / / / /  
|/| / / / / / / /   
| |/ / / / / / /    
| * | | | | | | 25a6806 2010-05-17 | Removing old, unused, dependencies [Viktor Klang]
| * | | | | | | ffedcf4 2010-05-16 | Added Receive type [Viktor Klang]
| * | | | | | | f6ef127 2010-05-16 | Took the liberty of adding the redisclient pom and changed the name of the jar [Viktor Klang]
* | | | | | | | b079a12 2010-05-18 | Fixed supervision bugs [Jonas Bonér]
|/ / / / / / /  
* | | | | | | 844fa2d 2010-05-16 | Improved error handling and message for Config [Jonas Bonér]
* | | | | | |   02ee7ec 2010-05-16 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | c935262 2010-05-15 | changed version of sjson to 0.5 [Debasish Ghosh]
| * | | | | | | e007f68 2010-05-15 | changed redisclient version to 1.3 [Debasish Ghosh]
| * | | | | | | c2efd39 2010-05-12 | Allow applications to disable stream-caching (#202) [Martin Krasser]
* | | | | | | |   9aaad8c 2010-05-16 | Merged with master and fixed last issues [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | 9baedab 2010-05-12 | Fixed wrong instructions in sample-remote README [Jonas Bonér]
| * | | | | | | c5518e4 2010-05-12 | Updated to Guice 2.0 [Jonas Bonér]
| * | | | | | | 56d4273 2010-05-11 | AKKA-192 - Upgrade slf4j to 1.6.0 [Viktor Klang]
| * | | | | | | 990e55b 2010-05-10 | Upgraded to Netty 3.2.0-RC1 [Viktor Klang]
| * | | | | | | 2fc4ca1 2010-05-10 | Fixed potential stack overflow [Viktor Klang]
| * | | | | | | 63f0aa4 2010-05-10 | Fixing bug with !! and WorkStealing? [Viktor Klang]
| * | | | | | | 9a1c10a 2010-05-10 | Message API improvements [Martin Krasser]
| * | | | | | | 2f5bbb9 2010-05-10 | Changed the order for detecting akka.conf [Peter Vlugter]
| * | | | | | | 7d00a2b 2010-05-09 | Deactivate endpoints of stopped consumer actors (AKKA-183) [Martin Krasser]
| * | | | | | | 6b30bc8 2010-05-08 | Switched newActor for actorOf [Viktor Klang]
| * | | | | | | ae6eb54 2010-05-08 | newActor(() => refactored [Viktor Klang]
| * | | | | | | 57e46e2 2010-05-08 | Refactored Actor [Viktor Klang]
| * | | | | | | 8797239 2010-05-08 | Fixing the test [Viktor Klang]
| * | | | | | | 7460e96 2010-05-08 | Closing ticket 150 [Viktor Klang]
* | | | | | | | d7727a8 2010-05-16 | Added failing test to supervisor specs [Jonas Bonér]
* | | | | | | | 7d9df10 2010-05-16 | Fixed final bug in remote protocol, now refactoring should (finally) be complete [Jonas Bonér]
* | | | | | | | 5ff29d2 2010-05-16 | added lock util class [Jonas Bonér]
* | | | | | | | f7407d3 2010-05-16 | Rewritten "home" address management and protocol, all test pass except 2 [Jonas Bonér]
* | | | | | | | dfc45e0 2010-05-13 | Refactored code into ActorRef, LocalActorRef and RemoteActorRef [Jonas Bonér]
* | | | | | | | 48c6dbc 2010-05-12 | Added scaladoc [Jonas Bonér]
* | | | | | | | 2441d60 2010-05-11 | Splitted up Actor and ActorRef in their own files [Jonas Bonér]
* | | | | | | | 3b67d21 2010-05-09 | Actor and ActorRef restructuring complete, still need to refactor tests [Jonas Bonér]
* | | | | | | |   b9d2c13 2010-05-08 | Merge branch 'ActorRef-FaultTolerance' of git@github.com:jboner/akka into ActorRef-FaultTolerance [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| * | | | | | | | 6f1a574 2010-05-08 | Moved everything from Actor to ActorRef: akka-core compiles [Jonas Bonér]
| |/ / / / / / /  
* | | | | | | | cb7bc48 2010-05-08 | Fixed Actor initialization problem with DynamicVariable initialied by ActorRef [Jonas Bonér]
* | | | | | | | 931f8b4 2010-05-08 | Added isOrRemoteNode field to ActorRef [Jonas Bonér]
* | | | | | | | 5acf932 2010-05-08 | Moved everything from Actor to ActorRef: akka-core compiles [Jonas Bonér]
|/ / / / / / /  
* | | | | | |   494e443 2010-05-07 | Merge branch 'ActorRefSerialization' [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | fb3ae7e 2010-05-07 | Rewrite of remote protocol to use the new ActorRef protocol [Jonas Bonér]
| * | | | | | |   17fc19b 2010-05-06 | Merge branch 'master' into ActorRefSerialization [Jonas Bonér]
| |\ \ \ \ \ \ \  
| * | | | | | | | a820b6f 2010-05-05 | converted tabs to spaces [Jonas Bonér]
| * | | | | | | | f90e9c3 2010-05-05 | Add Protobuf serialization and deserialization of ActorID [Jonas Bonér]
* | | | | | | | |   50f4fb9 2010-05-07 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| |_|/ / / / / / /  
|/| | | | | | | |   
| * | | | | | | | 1a4f80c 2010-05-06 | Added Kerberos config [Viktor Klang]
| * | | | | | | | 3015855 2010-05-06 | Added ScalaDoc for akka-patterns [Viktor Klang]
| * | | | | | | | a61af1a 2010-05-06 | Merged akka-utils and akka-java-utils into akka-core [Viktor Klang]
| * | | | | | | |   70f6cbf 2010-05-06 | Merge branch 'master' into multiverse-0.5 [Peter Vlugter]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| * | | | | | | | 54fa595 2010-05-06 | Updated to Multiverse 0.5 release [Peter Vlugter]
| * | | | | | | | 0fc4303 2010-05-02 | Updated to Multiverse 0.5 [Peter Vlugter]
* | | | | | | | | df8f0e0 2010-05-06 | Renamed ActorID to ActorRef [Jonas Bonér]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | | 5dd8841 2010-05-05 | Cleanup and minor refactorings, improved documentation etc. [Jonas Bonér]
* | | | | | | |   573ba89 2010-05-05 | Merged with master [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| * | | | | | | | c1d81d3 2010-05-05 | Removed Serializable.Protobuf since it did not work, use direct Protobuf messages for remote messages instead [Jonas Bonér]
| * | | | | | | | b0dd4b5 2010-05-05 | Renamed Reactor.scala to MessageHandling.scala [Jonas Bonér]
| * | | | | | | |   44c1fbc 2010-05-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | b93a893 2010-05-03 | Split up Patterns.scala in different files [Viktor Klang]
| | * | | | | | | | 39a4f72 2010-05-02 | Added start utility method [Viktor Klang]
| * | | | | | | | | f168a36 2010-05-05 | Fixed remote actor protobuf message serialization problem + added tests [Jonas Bonér]
| * | | | | | | | | 3f38822 2010-05-04 | Changed suffix on source JAR from -src to -sources [Jonas Bonér]
| * | | | | | | | | 29c20bf 2010-05-04 | minor edits [Jonas Bonér]
* | | | | | | | | | e8513c2 2010-05-04 | merged in akka-sample-remote [Jonas Bonér]
* | | | | | | | | |   413c37d 2010-05-04 | Merge branch 'master' into actor-handle [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / /  
| * | | | | | | | | 78b1020 2010-05-04 | Added sample module for remote actors [Jonas Bonér]
| |/ / / / / / / /  
* | | | | | | | | 4f66e90 2010-05-03 | ActorID: now all test pass, mission accomplished, ready for master [Jonas Bonér]
* | | | | | | | |   7b1e43c 2010-05-03 | Merge branch 'master' into actor-handle [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| |/ / / / / / / /  
| * | | | | | | |   4cfda90 2010-05-02 | Merge branch 'master' of git@github.com:jboner/akka into wip_restructure [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | | 622f264 2010-05-02 | Fixed Ref initial value bug by removing laziness [Peter Vlugter]
| | * | | | | | | 38e8881 2010-05-02 | Added test for Ref initial value bug [Peter Vlugter]
| * | | | | | | |   794731f 2010-05-01 | Merge branch 'master' of git@github.com:jboner/akka into wip_restructure [Viktor Klang]
| |\ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| | * | | | | | | 058cbc1 2010-05-01 | Fixed problem with PersistentVector.slice : Issue #161 [Debasish Ghosh]
| * | | | | | | | 98f9148 2010-04-29 | Moved Grizzly logic to Kernel and renamed it to EmbeddedAppServer [Viktor Klang]
| * | | | | | | | 7f51e74 2010-04-29 | Moving akka-patterns into akka-core [Viktor Klang]
| * | | | | | | | d09427c 2010-04-29 | Consolidated akka-security, akka-rest, akka-comet and akka-servlet into akka-http [Viktor Klang]
| * | | | | | | | d889b66 2010-04-29 | Removed Shoal and moved jGroups to akka-cluster, packages remain intact [Viktor Klang]
| |/ / / / / / /  
* | | | | | | | 5ca4e74 2010-05-03 | ActorID: all tests passing except akka-camel [Jonas Bonér]
* | | | | | | | 7cdda0f 2010-05-03 | All tests compile [Jonas Bonér]
* | | | | | | | 076a29d 2010-05-03 | All modules are building now [Jonas Bonér]
* | | | | | | |   3a69a51 2010-05-02 | Merge branch 'actor-handle' of git@github.com:jboner/akka into actor-handle [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \   a9b26b7 2010-05-02 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
* | \ \ \ \ \ \ \ \   01892c2 2010-05-02 | Chat sample now compiles with newActor[TYPE] [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / /  
|/| / / / / / / / /   
| |/ / / / / / / /    
| * | | | | | | |   c3093ee 2010-05-02 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
* | \ \ \ \ \ \ \ \   5e3ee65 2010-05-02 | merged with upstream [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / /  
|/| / / / / / / / /   
| |/ / / / / / / /    
| * | | | | | | | 8c8bf19 2010-05-01 | akka-core now compiles [Jonas Bonér]
* | | | | | | | | 22116b9 2010-05-01 | akka-core now compiles [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | 803838d 2010-04-30 | Mid ActorID refactoring [Jonas Bonér]
* | | | | | | |   f35ff94 2010-04-27 | mid merge [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | ac7cd72 2010-04-26 | Made ActiveObject non-advisable in AW terms [Jonas Bonér]
| * | | | | | | 2b9f7be 2010-04-25 | Added Future[T] as return type for await and awaitBlocking [Viktor Klang]
| * | | | | | |   4bc833a 2010-04-24 | Added parameterized Futures [Viktor Klang]
| |\ \ \ \ \ \ \  
| | * | | | | | | e05f39d 2010-04-23 | Minor cleanup [Viktor Klang]
| | * | | | | | |   8238c0d 2010-04-23 | Merge branch 'master' of git@github.com:jboner/akka into 151_parameterize_future [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | cccfd51 2010-04-23 | Initial parametrization [Viktor Klang]
| * | | | | | | | | 4f90ace 2010-04-24 | Added reply_? that discards messages if it cannot find reply target [Viktor Klang]
| * | | | | | | | | e7a5595 2010-04-24 | Added Listeners to akka-patterns [Viktor Klang]
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | | 1e7b08f 2010-04-22 | updated dependencies in pom [Michael Kober]
| * | | | | | | | 41b78d0 2010-04-22 | JTA: Added option to register "joinTransaction" function and which classes to NOT roll back on [Jonas Bonér]
| * | | | | | | | 8605b85 2010-04-22 | Added StmConfigurationException [Jonas Bonér]
| |/ / / / / / /  
| * | | | | | | f58503a 2010-04-21 | added scaladoc [Jonas Bonér]
| * | | | | | | 3578789 2010-04-21 | Moved ActiveObjectConfiguration to ActiveObject.scala file [Jonas Bonér]
| * | | | | | | 559689d 2010-04-21 | Made JTA Synchronization management generic and allowing more than one + refactoring [Jonas Bonér]
| * | | | | | |   628e553 2010-04-20 | Renamed to JTA.scala [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | * | | | | | | bed0f71 2010-04-20 | Added STM Synchronization registration to JNDI TransactionSynchronizationRegistry [Jonas Bonér]
| * | | | | | | | b4176e2 2010-04-20 | Added STM Synchronization registration to JNDI TransactionSynchronizationRegistry [Jonas Bonér]
| |/ / / / / / /  
| * | | | | | |   b0e5fe7 2010-04-20 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \  
| | * \ \ \ \ \ \   34bc489 2010-04-20 | Merge branch 'master' of github.com:jboner/akka [Michael Kober]
| | |\ \ \ \ \ \ \  
| | * | | | | | | | c9189ac 2010-04-20 | fixed #154 added ActiveObjectConfiguration with fluent API [Michael Kober]
| * | | | | | | | | 0494add 2010-04-20 | Cleaned up JTA stuff [Jonas Bonér]
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | |   1818950 2010-04-20 | Merge branch 'master' into jta [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | * | | | | | | | 7f9a9c7 2010-04-20 | fix for Vector from Dean (ticket #155) [Peter Vlugter]
| | * | | | | | | | ca83d0a 2010-04-20 | added Dean's test for Vector bug (blowing up after 32 items) [Peter Vlugter]
| | * | | | | | | | ca88bf2 2010-04-19 | Removed jndi.properties [Viktor Klang]
| | * | | | | | | |   c0b35fe 2010-04-19 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | |\ \ \ \ \ \ \ \  
| | | |/ / / / / / /  
| | * | | | | | | | e42b1f8 2010-04-15 | Removed Scala 2.8 deprecation warnings [Viktor Klang]
| * | | | | | | | | dbc9125 2010-04-20 | Finalized the JTA support [Jonas Bonér]
| * | | | | | | | | 085d472 2010-04-17 | added logging to jta detection [Jonas Bonér]
| * | | | | | | | | 908156e 2010-04-17 | jta-enabled stm [Jonas Bonér]
| * | | | | | | | | fa50bda 2010-04-17 | upgraded to 0.9 [Jonas Bonér]
| * | | | | | | | |   085d796 2010-04-17 | Merge branch 'master' into jta [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | | |/ / / / / / /  
| | |/| | | | | | |   
| | * | | | | | | | 4ec7f8f 2010-04-16 | added sbt plugin file [Jonas Bonér]
| | * | | | | | | | 1dee9d5 2010-04-16 | Added Cassandra logging dependencies to the compile jars [Jonas Bonér]
| | * | | | | | | | b60604e 2010-04-14 | updating TransactionalRef to be properly monadic [Peter Vlugter]
| | * | | | | | | | 10016df 2010-04-14 | tests for TransactionalRef in for comprehensions [Peter Vlugter]
| | * | | | | | | | 9545efa 2010-04-14 | tests for TransactionalRef [Peter Vlugter]
| | * | | | | | | | 5fc8ad2 2010-04-16 | converted tabs to spaces [Jonas Bonér]
| | * | | | | | | | 2ce690d 2010-04-16 | Updated old scaladoc [Jonas Bonér]
| | * | | | | | | |   ce173d7 2010-04-15 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| | |\ \ \ \ \ \ \ \  
| | | |/ / / / / / /  
| | | * | | | | | | 3c4efcb 2010-04-14 | update instructions for chat running chat sample [rossputin]
| | | * | | | | | | 6b83b84 2010-04-14 | Redis client now implements pubsub. Also included a sample app for RedisPubSub in akka-samples [Debasish Ghosh]
| | | * | | | | | |   4cedc47 2010-04-14 | Merge branch 'link-active-objects' [Michael Kober]
| | | |\ \ \ \ \ \ \  
| | | | * | | | | | | 396ae43 2010-04-14 | implemented link/unlink for active objects [Michael Kober]
| | | * | | | | | | |   baabcef 2010-04-13 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | | e41da82 2010-04-11 | Documented replyTo [Viktor Klang]
| | | * | | | | | | | | e83a57b 2010-04-11 | Moved a runtime error to compile time [Viktor Klang]
| | | * | | | | | | | | cd9477d 2010-04-10 | Refactored _isEventBased into the MessageDispatcher [Viktor Klang]
| | * | | | | | | | | | 0f3803d 2010-04-14 | Added AtomicTemplate to allow atomic blocks from Java code [Jonas Bonér]
| | * | | | | | | | | | 0268f6f 2010-04-14 | fixed bug with ignoring timeout in Java API [Jonas Bonér]
| | | |/ / / / / / / /  
| | |/| | | | | | | |   
| * | | | | | | | | | 51aea9b 2010-04-17 | added TransactionManagerDetector [Jonas Bonér]
| * | | | | | | | | | ed9fc04 2010-04-08 | Added JTA module, monadic and higher-order functional API [Jonas Bonér]
* | | | | | | | | | | 2b63861 2010-04-14 | added ActorRef [Jonas Bonér]
| |/ / / / / / / / /  
|/| | | | | | | | |   
* | | | | | | | | | d1dcb81 2010-04-12 | added compile options [Jonas Bonér]
* | | | | | | | | | 4f64769 2010-04-12 | fixed bug in config file [Jonas Bonér]
| |/ / / / / / / /  
|/| | | | | | | |   
* | | | | | | | | 043f9f6 2010-04-10 | Readded more SBinary functionality [Viktor Klang]
* | | | | | | | |   bcd8132 2010-04-09 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| | |/ / / / / / /  
| |/| | | | | | |   
| * | | | | | | | 85756e1 2010-04-09 | added test for supervised remote active object [Michael Kober]
* | | | | | | | | 5893b04 2010-04-09 | cleaned up remote tests + remvod akkaHome from sbt build file [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | 6ed887f 2010-04-09 | fixed bug in Agent.scala, fixed bug in RemoteClient.scala, fixed problem with tests [Jonas Bonér]
* | | | | | | | 1e789b3 2010-04-09 | Initial values possible for TransactionalRef, TransactionalMap, and TransactionalVector [Peter Vlugter]
* | | | | | | | ebab76b 2010-04-09 | Added alter method to TransactionalRef [Peter Vlugter]
* | | | | | | | e780ba0 2010-04-09 | fix for HashTrie: apply and + now return HashTrie rather than Map [Peter Vlugter]
* | | | | | | |   edba42e 2010-04-08 | Merge branch 'master' of git@github.com:jboner/akka [Jan Kronquist]
|\ \ \ \ \ \ \ \  
| * | | | | | | | 441ad40 2010-04-08 | improved scaladoc for Actor.scala [Jonas Bonér]
| * | | | | | | | ed7b51e 2010-04-08 | removed Actor.remoteActor factory method since it does not work [Jonas Bonér]
| |/ / / / / / /  
* | | | | | | | cda65ba 2010-04-08 | Started working on issue #121 Added actorFor to get the actor for an activeObject [Jan Kronquist]
|/ / / / / / /  
* | | | | | |   7b28f56 2010-04-07 | Merge branch 'master' of git@github.com:jboner/akka into sbt [Jonas Bonér]
|\ \ \ \ \ \ \  
| * \ \ \ \ \ \   a7abe34 2010-04-07 | Merge branch 'either_sender_future' [Viktor Klang]
| |\ \ \ \ \ \ \  
| | * | | | | | | acd6f19 2010-04-07 | Removed uglies [Viktor Klang]
| | * | | | | | | 2593fd3 2010-04-06 | Change sender and senderfuture to Either [Viktor Klang]
* | | | | | | | | 76d1e13 2010-04-07 | Cleaned up sbt build file + upgraded to sbt 0.7.3 [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | fdaa6f4 2010-04-07 | Improved ScalaDoc in Actor [Jonas Bonér]
* | | | | | | | 2246cd8 2010-04-07 | added a method to retrieve the supervisor for an actor + a message Unlink to unlink himself [Jonas Bonér]
* | | | | | | |   c095a1c 2010-04-07 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| * | | | | | | | 8dfa12d 2010-04-07 | fixed @inittransactionalstate, updated pom for spring java tests [Michael Kober]
* | | | | | | | | 3679865 2010-04-07 | fixed bug in nested supervisors + added tests + added latch to agent tests [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | 0d60400 2010-04-07 | Fixed: Akka kernel now loads all jars wrapped up in the jars in the ./deploy dir [Jonas Bonér]
* | | | | | | | e4e96f6 2010-04-06 | Added API to add listeners to subscribe to Error, Connect and Disconnect events on RemoteClient [Jonas Bonér]
|/ / / / / / /  
* | | | | | | 784ca9e 2010-04-06 | Added Logging trait back to Actor [Jonas Bonér]
* | | | | | | 91fe6a3 2010-04-06 | Now doing a 'reply(..)' to remote sender after receiving a remote message through '!' works. Added tests. Also removed the Logging trait from Actor for lower memory footprint. [Jonas Bonér]
* | | | | | |   94d472e 2010-04-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | 60b374b 2010-04-05 | Rename file [Viktor Klang]
| * | | | | | |   9ad4491 2010-04-05 | Merged in akka-servlet [Viktor Klang]
| |\ \ \ \ \ \ \  
| * | | | | | | | a40d1ed 2010-04-05 | Changed module name, packagename and classnames :-) [Viktor Klang]
| * | | | | | | | 04c45b2 2010-04-05 | Created jxee module [Viktor Klang]
* | | | | | | | | 4a7b721 2010-04-05 | renamed tests from *Test -> *Spec [Jonas Bonér]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | | b2ba933 2010-04-05 | Improved scaladoc for Transaction [Jonas Bonér]
* | | | | | | | 30badd6 2010-04-05 | cleaned up packaging in samples to all be "sample.x" [Jonas Bonér]
* | | | | | | | f04fbba 2010-04-05 | Refactored STM API into Transaction.Global and Transaction.Local, fixes issues with "atomic" outside actors [Jonas Bonér]
* | | | | | | |   3ae5521 2010-04-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | 0cfe5e2 2010-04-04 | fix for comments [rossputin]
* | | | | | | | 4afbc4c 2010-04-04 | fixed broken "sbt dist" [Jonas Bonér]
|/ / / / / / /  
* | | | | | | 297a335 2010-04-03 | fixed bug with creating anonymous actor, renamed some anonymous actor factory methods [Jonas Bonér]
* | | | | | | f001a1e 2010-04-02 | changed println -> log.info [Jonas Bonér]
* | | | | | | 3f2c9a2 2010-03-17 | Added load balancer which prefers actors with small mailboxes (discussed on mailing list a while ago). [Jan Van Besien]
* | | | | | |   a0c3d4e 2010-04-02 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
|\ \ \ \ \ \ \  
| * | | | | | | d3654ac 2010-04-02 | simplified tests using CountDownLatch.await with a timeout by asserting the count reached zero in a single statement. [Jan Van Besien]
* | | | | | | | d6de99b 2010-04-02 | new redisclient with support for clustering [Debasish Ghosh]
|/ / / / / / /  
* | | | | | |   183fcfb 2010-04-01 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | a23cc9b 2010-04-01 | Minor cleanups and fixing super.unregister [Viktor Klang]
| * | | | | | | c3381d8 2010-04-01 | Improved unit test performance by replacing Thread.sleep with more clever approaches (CountDownLatch, BlockingQueue and others). Here and there Thread.sleep could also simply be removed. [Jan Van Besien]
* | | | | | | | b513e57 2010-04-01 | cleaned up [Jonas Bonér]
* | | | | | | | 500d967 2010-04-01 | refactored build file [Jonas Bonér]
|/ / / / / / /  
* | | | | | | 215b45d 2010-04-01 | release v0.8 [Jonas Bonér]
* | | | | | |   2f32b85 2010-04-01 | merged with upstream [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | 08fdc7a 2010-03-31 | updated copyright header [Jonas Bonér]
| * | | | | | | 40291b5 2010-03-31 | updated Agent scaladoc with monadic examples [Jonas Bonér]
| * | | | | | | 089cf01 2010-03-31 | Agent is now monadic, added more tests to AgentTest [Jonas Bonér]
| * | | | | | | 7d465f6 2010-03-31 | Gave the sbt deploy plugin richer API [Jonas Bonér]
| * | | | | | | 855acd2 2010-03-31 | added missing scala-library.jar to dist and manifest.mf classpath [Jonas Bonér]
| * | | | | | | 819a6d8 2010-03-31 | reverted back to sbt 0.7.1 [Jonas Bonér]
| * | | | | | | b0bee0d 2010-03-30 | Removed Actor.send function [Jonas Bonér]
| * | | | | | |   d8461f4 2010-03-30 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \ \  
| * \ \ \ \ \ \ \   48ff060 2010-03-30 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \   7430dc8 2010-03-30 | Merge branch '2.8-WIP' of git@github.com:jboner/akka into 2.8-WIP [Viktor Klang]
| | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | 5566b21 2010-03-31 | upgraded redisclient to version 1.2: includes api name changes for conformance with redis server (earlier ones deprecated). Also an implementation of Deque that can be used for Durable Q in actors [Debasish Ghosh]
| | * | | | | | | | | 71ad8f6 2010-03-30 | Forward-ported bugfix in Security to 2.8-WIP [Viktor Klang]
| | |/ / / / / / / /  
| * | | | | | | | |   eaaa9b1 2010-03-30 | Merged with new Redis 1.2 code from master, does not compile since the redis-client is build with 2.7.7, need to get correct JAR [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | |/ / / / / / / /  
| |/| | | | | | | |   
| * | | | | | | | |   594ba80 2010-03-30 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | 7e3a4b5 2010-03-29 | Added missing + sign [viktorklang]
| * | | | | | | | | | ef79034 2010-03-30 | Updated version to 0.8x [Jonas Bonér]
| * | | | | | | | | | 8800f70 2010-03-30 | Rewrote distribution generation, now it also packages sources and docs [Jonas Bonér]
| |/ / / / / / / / /  
| * | | | | | | | | dc9cae4 2010-03-29 | minor edit [Jonas Bonér]
| * | | | | | | | | d037bfa 2010-03-29 | improved scaladoc [Jonas Bonér]
| * | | | | | | | | 27333f9 2010-03-29 | removed usused code [Jonas Bonér]
| * | | | | | | | | 3825ce5 2010-03-29 | updated to commons-pool 1.5.4 [Jonas Bonér]
| * | | | | | | | | 5736cd9 2010-03-29 | fixed all deprecations execept in grizzly code [Jonas Bonér]
| * | | | | | | | | 8c67eeb 2010-03-29 | fixed deprecation warnings in akka-core [Jonas Bonér]
| * | | | | | | | | 75e887c 2010-03-26 | fixed warning, usage of 2.8 features: default arguments and generated copy method. [Martin Krasser]
| * | | | | | | | | 5104b5b 2010-03-25 | And we`re back! [Viktor Klang]
| * | | | | | | | | 2cd1448 2010-03-25 | Bumped version [Viktor Klang]
| * | | | | | | | | 41a35f6 2010-03-25 | Removing Redis waiting for 1.2-SNAPSHOT for 2.8-Beta1 [Viktor Klang]
| * | | | | | | | |   b4c6196 2010-03-25 | Resolved conflicts [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | bae6c9e 2010-03-02 | upgraded redisclient jar to 1.1 [Debasish Ghosh]
| * | | | | | | | | | fbef975 2010-03-25 | compiles, tests and dists without Redis + samples [Viktor Klang]
| * | | | | | | | | |   e571161 2010-03-23 | Merged latest master, fighting missing deps [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | e2c661f 2010-03-03 | Toying with manifests [Viktor Klang]
| * | | | | | | | | | |   85dd185 2010-02-28 | Merge branch '2.8-WIP' of git@github.com:jboner/akka into 2.8-WIP [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / /  
| | |/| | | | | | | | |   
| | * | | | | | | | | | 9e0b1c0 2010-02-23 | redis storage support ported to Scala 2.8.Beta1. New jar for redisclient for 2.8.Beta1 [Debasish Ghosh]
| * | | | | | | | | | |   c14484c 2010-02-26 | Merge with master [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / /  
| |/| | | | | | | | | |   
| * | | | | | | | | | | c28a283 2010-02-22 | Akka LIVES! [Viktor Klang]
| * | | | | | | | | | | a16c6da 2010-02-21 | And now akka-core builds! [Viktor Klang]
| * | | | | | | | | | | 787e7e4 2010-02-20 | Working nine to five ... [Viktor Klang]
| * | | | | | | | | | | b1bb790 2010-02-20 | Updated more deps [Viktor Klang]
| * | | | | | | | | | | 178102f 2010-02-20 | Deleted old version of configgy [Viktor Klang]
| * | | | | | | | | | | 3ac33fc 2010-02-20 | Added new version of configgy [Viktor Klang]
| * | | | | | | | | | | 37cdcd1 2010-02-20 | Partial version updates [Viktor Klang]
| * | | | | | | | | | |   634caf5 2010-02-19 | Merge branch 'master' into 2.8-WIP [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | e453f06 2010-01-20 | And the pom... [Viktor Klang]
| * | | | | | | | | | | | e1c9e7e 2010-01-20 | Stashing away work so far [Viktor Klang]
| * | | | | | | | | | | | 93a4c14 2010-01-18 | Updated dep versions [Viktor Klang]
* | | | | | | | | | | | |   b974ac2 2010-03-31 | Merge branch 'master' of git@github.com:janvanbesien/akka [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \ \ \ \ \   d6789b0 2010-03-31 | Merge branch 'workstealing' [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \  
* | | | | | | | | | | | | | | 37f535d 2010-03-31 | added jsr166x library from doug lea [Jan Van Besien]
* | | | | | | | | | | | | | | af0e029 2010-03-31 | Added jsr166x to the embedded repo. Use jsr166x.ConcurrentLinkedDeque in stead of LinkedBlockingDeque as colletion for the actors mailbox [Jan Van Besien]
* | | | | | | | | | | | | | |   3dcd319 2010-03-31 | Merge branch 'master' of git@github.com:jboner/akka into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / /  
| | / / / / / / / / / / / / /   
| |/ / / / / / / / / / / / /    
|/| | | | | | | | | | | | |     
| * | | | | | | | | | | | |   9cf5121 2010-03-31 | Merge branch 'spring-dispatcher' [Michael Kober]
| |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|_|_|_|_|/ / / / / / /  
| |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | da325c9 2010-03-30 | added spring dispatcher configuration [Michael Kober]
| | | |_|_|/ / / / / / / /  
| | |/| | | | | | | | | |   
* | | | | | | | | | | | |   b710737 2010-03-31 | Merge branch 'master' of git@github.com:jboner/akka into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / /  
| * | | | | | | | | | | | 6d6fc79 2010-03-30 | Fixed reported exception in Akka-Security [Viktor Klang]
| * | | | | | | | | | | | edbd5c1 2010-03-30 | Added missing dependency [Viktor Klang]
| | |_|_|_|/ / / / / / /  
| |/| | | | | | | | | |   
* | | | | | | | | | | |   6272f71 2010-03-30 | Merge branch 'master' of git@github.com:jboner/akka into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / /  
| * | | | | | | | | | | e76cb00 2010-03-30 | upgraded redisclient to version 1.2: includes api name changes for conformance with redis server (earlier ones deprecated). Also an implementation of Deque that can be used for Durable Q in actors [Debasish Ghosh]
| |/ / / / / / / / / /  
* | | | | | | | | | | 831a71d 2010-03-30 | renamed some variables for clarity [Jan Van Besien]
* | | | | | | | | | | abcaef5 2010-03-30 | fixed name of dispatcher in log messages [Jan Van Besien]
* | | | | | | | | | | b0b3e2f 2010-03-30 | use forward in stead of send when stealing work from another actor [Jan Van Besien]
* | | | | | | | | | | fb1f679 2010-03-30 | fixed round robin work stealing algorithm [Jan Van Besien]
* | | | | | | | | | | a5e50d6 2010-03-29 | javadoc and comments [Jan Van Besien]
* | | | | | | | | | | 0fa8585 2010-03-29 | fix [Jan Van Besien]
* | | | | | | | | | | d1ad3f6 2010-03-29 | minor refactoring of the round robin work stealing algorithm [Jan Van Besien]
* | | | | | | | | | | e60421c 2010-03-29 | Simplified the round robin scheme [Jan Van Besien]
* | | | | | | | | | |   f1d360b 2010-03-29 | Merge commit 'upstream/master' into workstealing Implemented a simple round robin schema for the work stealing dispatcher [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / /  
| * | | | | | | | | | 2b4053c 2010-03-22 | force projects to use higher versions of redundant libs instead of only excluding them later. This ensures that unit tests use the same libraries that are included into the distribution. [Martin Krasser]
| * | | | | | | | | | c2bfb4c 2010-03-22 | fixed bug in REST module [Jonas Bonér]
| * | | | | | | | | | 919e1e3 2010-03-21 | upgrading to multiverse 0.4 [Jonas Bonér]
| * | | | | | | | | | 4836bf2 2010-03-21 | Exclusion of redundant dependencies from distribution. [Martin Krasser]
| * | | | | | | | | | 8255831 2010-03-20 | Upgrade of akka-sample-camel to spring-jms 3.0 [Martin Krasser]
| * | | | | | | | | | 6ce9383 2010-03-20 | Fixing akka-rest breakage from Configurator.getInstance [Viktor Klang]
| * | | | | | | | | | ba3c3ba 2010-03-20 | upgraded to 0.7 [Jonas Bonér]
| * | | | | | | | | | 9fdc29f 2010-03-20 | converted tabs to spaces [Jonas Bonér]
| * | | | | | | | | | ae4d9d8 2010-03-20 | Documented ActorRegistry and stablelized subscription API [Jonas Bonér]
| * | | | | | | | | | 5dad902 2010-03-20 | Cleaned up build file [Jonas Bonér]
| * | | | | | | | | |   8e84bd5 2010-03-20 | merged in the spring branch [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | 3236599 2010-03-17 | added integration tests [Michael Kober]
| | * | | | | | | | | | fe8e445 2010-03-17 | added integration tests, spring 3.0.1 and sbt [Michael Kober]
| | * | | | | | | | | |   581b968 2010-03-15 | merged master into spring [Michael Kober]
| | |\ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | 6a852d6 2010-03-15 | removed old source files [Michael Kober]
| | * | | | | | | | | | |   53e1cfc 2010-03-14 | pulled and merged [Michael Kober]
| | |\ \ \ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \ \   4bee774 2010-01-02 | merged with master [Jonas Bonér]
| | | |\ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | aef5201 2010-01-02 | Added tests to Spring module, currently failing [Jonas Bonér]
| | | * | | | | | | | | | | | 21001a7 2010-01-02 | Cleaned up Spring interceptor and helpers [Jonas Bonér]
| | | * | | | | | | | | | | | f30741d 2010-01-01 | updated spring module pom to latest akka module layout. [Jonas Bonér]
| | | * | | | | | | | | | | |   623f8de 2009-12-31 | Merge branch 'master' of git://github.com/staffanfransson/akka into spring [Jonas Bonér]
| | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | | * | | | | | | | | | | | 319c761 2009-12-02 | modified pom.xml to include akka-spring [Staffan Fransson]
| | | | * | | | | | | | | | | | 95b5fc6 2009-12-02 | Added contructor to Dispatcher and AspectInit [Staffan Fransson]
| | | | * | | | | | | | | | | | 71ff7e4 2009-12-02 | Added akka-spring [Staffan Fransson]
| | * | | | | | | | | | | | | | 2bceb82 2010-03-14 | initial version of spring custom namespace [Michael Kober]
| | * | | | | | | | | | | | | | 7f5f709 2010-01-02 | Added tests to Spring module, currently failing [Jonas Bonér]
| | * | | | | | | | | | | | | | 5c6f6fb 2010-01-02 | Cleaned up Spring interceptor and helpers [Jonas Bonér]
| | * | | | | | | | | | | | | | 647dccc 2010-01-01 | updated spring module pom to latest akka module layout. [Jonas Bonér]
| | * | | | | | | | | | | | | | b5aabb0 2009-12-02 | modified pom.xml to include akka-spring [Staffan Fransson]
| | * | | | | | | | | | | | | | fdfbe68 2009-12-02 | Added contructor to Dispatcher and AspectInit [Staffan Fransson]
| | * | | | | | | | | | | | | | ef36304 2009-12-02 | Added akka-spring [Staffan Fransson]
| * | | | | | | | | | | | | | | a3f7fb1 2010-03-20 | added line count script [Jonas Bonér]
| * | | | | | | | | | | | | | | aeea8d3 2010-03-20 | Improved Agent doc [Jonas Bonér]
| * | | | | | | | | | | | | | |   cfa97cd 2010-03-20 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | 13b9d1b 2010-03-20 | Extension/rewriting of remaining unit and functional tests [Martin Krasser]
| | * | | | | | | | | | | | | | | b0fa275 2010-03-20 | traits for configuring producer behaviour [Martin Krasser]
| | * | | | | | | | | | | | | | | 036f79f 2010-03-19 | Extension/rewrite of CamelService unit and functional tests [Martin Krasser]
| | | |_|_|_|_|_|_|_|_|_|/ / / /  
| | |/| | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | fba843e 2010-03-20 | Added tests to AgentTest and cleaned up Agent [Jonas Bonér]
| * | | | | | | | | | | | | | | 79bfc4e 2010-03-18 | Fixed problem with Agent, now tests pass [Jonas Bonér]
| * | | | | | | | | | | | | | | 1f13030 2010-03-18 | Changed Supervisors actor map to hold a list of actors per class entry [Jonas Bonér]
| * | | | | | | | | | | | | | | 86e656d 2010-03-17 | tabs -> spaces [Jonas Bonér]
* | | | | | | | | | | | | | | | d37b82b 2010-03-19 | Don't allow two different actors (different types) to share the same work stealing dispatcher. Added unit test. [Jan Van Besien]
* | | | | | | | | | | | | | | |   fe0849d 2010-03-19 | Merge branch 'master' into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | 8ebb13a 2010-03-19 | camel-cometd example disabled [Martin Krasser]
| * | | | | | | | | | | | | | | 1e69215 2010-03-19 | Fix for InstantiationException on Kernel startup [Martin Krasser]
| * | | | | | | | | | | | | | | 61aa156 2010-03-18 | Fixed issue with file URL to embedded repository on Windows. [Martin Krasser]
| * | | | | | | | | | | | | | |   d453aab 2010-03-18 | Merge branch 'master' of github.com:jboner/akka [Martin Krasser]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | c8a7b3c 2010-03-18 | extension/rewrite of actor component unit and functional tests [Martin Krasser]
* | | | | | | | | | | | | | | | |   eff1cd3 2010-03-18 | Merge branch 'master' into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | d50bfe9 2010-03-18 | added new jar 1.2-SNAPSHOT for redisclient [Debasish Ghosh]
| * | | | | | | | | | | | | | | | 02e6f58 2010-03-18 | added support for Redis based SortedSet persistence in Akka transactors [Debasish Ghosh]
| | |/ / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | 3010cd1 2010-03-17 | Refactored Serializer [Jonas Bonér]
| * | | | | | | | | | | | | | | 592e4d3 2010-03-17 | reformatted patterns code [Jonas Bonér]
| * | | | | | | | | | | | | | |   93d7a49 2010-03-17 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | ed11671 2010-03-17 | Fixed typo in docs. [Jonas Bonér]
| | * | | | | | | | | | | | | | | bf9bbd0 2010-03-17 | Updated how to run the sample docs. [Jonas Bonér]
| | * | | | | | | | | | | | | | | cfbc585 2010-03-17 | Updated README with new running procedure [Jonas Bonér]
| * | | | | | | | | | | | | | | | eb4e624 2010-03-17 | Created an alias to TransactionalRef; Ref [Jonas Bonér]
| |/ / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | 6acd56a 2010-03-17 | Changed Chat sample to use server-managed remote actors + changed the how-to-run-sample doc. [Jonas Bonér]
* | | | | | | | | | | | | | | | c253faa 2010-03-17 | only allow actors of the same type to be registered with a work stealing dispatcher. [Jan Van Besien]
* | | | | | | | | | | | | | | | 3f98d55 2010-03-17 | when searching for a thief, only consider thiefs with empty mailboxes. [Jan Van Besien]
* | | | | | | | | | | | | | | |   2f5792f 2010-03-17 | Merge branch 'master' into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | b9451a5 2010-03-17 | Made "sbt publish" publish artifacts to local Maven repo [Jonas Bonér]
* | | | | | | | | | | | | | | |   fd40d15 2010-03-17 | Merge branch 'master' into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | f0e53bc 2010-03-17 | moved akka.annotation._ to akka.actor.annotation._ to be merged in with akka-core OSGi bundle [Jonas Bonér]
| |/ / / / / / / / / / / / / /  
| * | | | | | | | | | | | | |   10a6a7d 2010-03-17 | Merged in Camel branch [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | 08e3a61 2010-03-16 | Minor syntax edits [Jonas Bonér]
| | * | | | | | | | | | | | | | 1481d1d 2010-03-16 | akka-camel added to manifest classpath. All examples enabled. [Martin Krasser]
| | * | | | | | | | | | | | | | 82f411a 2010-03-16 | Move to sbt [Martin Krasser]
| | * | | | | | | | | | | | | |   3c90654 2010-03-15 | initial resolution of conflicts after merge with master [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|/ / / / / / / / /  
| | | |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | | | 7eb4245 2010-03-15 | prepare merge with master [Martin Krasser]
| | * | | | | | | | | | | | | | e97e944 2010-03-14 | publish/subscribe examples using jms and cometd [Martin Krasser]
| | * | | | | | | | | | | | | | e056af1 2010-03-11 | support for remote actors, consumer actor publishing at any time [Martin Krasser]
| | * | | | | | | | | | | | | | f8fab07 2010-03-08 | error handling enhancements [Martin Krasser]
| | * | | | | | | | | | | | | | 35a557d 2010-03-06 | performance improvement [Martin Krasser]
| | * | | | | | | | | | | | | | a6ffa67 2010-03-06 | Added lifecycle methods to CamelService [Martin Krasser]
| | * | | | | | | | | | | | | | 9f31bf5 2010-03-06 | Fixed mess-up of previous commit (rollback changes to akka.iml), CamelService companion object for standalone applications to create their own CamelService instances [Martin Krasser]
| | * | | | | | | | | | | | | | c04ebf4 2010-03-06 | CamelService companion object for standalone applications to create their own CamelService instances [Martin Krasser]
| | * | | | | | | | | | | | | |   8100cdc 2010-03-06 | Merge branch 'master' into camel [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|_|_|_|_|_|/ / /  
| | | |/| | | | | | | | | | | |   
| | * | | | | | | | | | | | | | 68fcbe8 2010-03-05 | fixed compile errors after merging with master [Martin Krasser]
| | * | | | | | | | | | | | | |   8d5c3fd 2010-03-05 | Merge remote branch 'remotes/origin/master' into camel [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | c0b4137 2010-03-05 | Producer trait for producing messages to Camel endpoints (sync/async, oneway/twoway), Immutable representation of Camel message, consumer/producer examples, refactorings/improvements/cleanups. [Martin Krasser]
| | * | | | | | | | | | | | | | | 3b62a7a 2010-03-01 | use immutable messages for communication with actors [Martin Krasser]
| | * | | | | | | | | | | | | | |   15b9787 2010-03-01 | Merge branch 'camel' of github.com:jboner/akka into camel [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \ \ \ \ \ \   fb75d2b 2010-03-01 | merge branch 'remotes/origin/master' into camel; resolved conflicts in ActorRegistry.scala and ActorRegistryTest.scala; removed initial, commented-out test class. [Martin Krasser]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | | | 94483e1 2010-03-01 | changed actor URI format, cleanup unit tests. [Martin Krasser]
| | | * | | | | | | | | | | | | | | | 17ffeb3 2010-02-28 | Fixed actor deregistration-by-id issue and added ActorRegistry unit test. [Martin Krasser]
| | | * | | | | | | | | | | | | | | |   5ae5e8a 2010-02-27 | Merge branch 'master' into camel [Martin Krasser]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | | |_|_|_|_|_|_|_|_|_|/ / / / /  
| | | | |/| | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | |   25240d1 2010-02-27 | Merge branch 'master' into camel [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | | |/ / / / / / / / / / / / / / /  
| | | |/| | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | |   e56682f 2010-02-26 | Merge branch 'master' into camel [Martin Krasser]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|/ / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | | |   
| | * | | | | | | | | | | | | | | | | a86fc10 2010-02-25 | initial camel integration (early-access, see also http://doc.akkasource.org/Camel) [Martin Krasser]
| | | |_|_|_|_|_|_|_|_|_|_|/ / / / /  
| | |/| | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | |   8e6212e 2010-03-16 | Merge branch 'jans_dispatcher_changes' [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   2a43c2a 2010-03-16 | Merge branch 'dispatcherimprovements' of git@github.com:jboner/akka into jans_dispatcher_changes [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   24b20e6 2010-03-14 | merged [Jonas Bonér]
| | |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | | | d569b29 2010-03-14 | dispatcher speed improvements [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | | | db092f1 2010-03-16 | Added the run_akka.sh script [Viktor Klang]
| * | | | | | | | | | | | | | | | | | | | 2f4d45a 2010-03-16 | Removed dead code [Viktor Klang]
| | |_|_|_|_|_|_|_|_|/ / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | | | |   5b844f5 2010-03-16 | Merge branch 'dispatcherimprovements' into workstealing. Also applied the same improvements on the work stealing dispatcher. [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|_|/ / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | | 823661c 2010-03-16 | Fixed bug which allowed messages to be "missed" if they arrived after looping through the mailbox, but before releasing the lock. [Jan Van Besien]
| * | | | | | | | | | | | | | | | | | |   d6a91f0 2010-03-15 | Merge branch 'master' into dispatcherimprovements [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / / / / /  
| | | | / / / / / / / / / / / / / / / /   
| | |_|/ / / / / / / / / / / / / / / /    
| |/| | | | | | | | | | | | | | | | |     
| | * | | | | | | | | | | | | | | | | ae0ef2d 2010-03-15 | OS-specific substring search in paths (fixes 'sbt dist' issue on Windows) [Martin Krasser]
| * | | | | | | | | | | | | | | | | |   97a9ce6 2010-03-10 | Merge commit 'upstream/master' into dispatcherimprovements Fixed conflict in actor.scala [Jan Van Besien]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | | | 5f29407 2010-03-10 | fixed layout [Jan Van Besien]
| * | | | | | | | | | | | | | | | | | | 4fa0b5f 2010-03-04 | only unlock if locked. [Jan Van Besien]
| * | | | | | | | | | | | | | | | | | | 3693ac6 2010-03-04 | remove println's in test [Jan Van Besien]
| * | | | | | | | | | | | | | | | | | | 1e7a6c0 2010-03-04 | Release the lock when done dispatching. [Jan Van Besien]
| * | | | | | | | | | | | | | | | | | | f78b253 2010-03-04 | Improved event driven dispatcher by not scheduling a task for dispatching when another is already busy. [Jan Van Besien]
| | |_|_|_|_|_|_|_|_|_|_|_|_|_|_|/ / /  
| |/| | | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | | | d8a1c44 2010-03-14 | don't just steal one message, but continue as long as there are more messages available. [Jan Van Besien]
* | | | | | | | | | | | | | | | | | |   f615bf8 2010-03-13 | Merge branch 'master' into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|/ / / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | 7e517ed 2010-03-13 | Revert to Atmosphere 0.5.4 because of issue in 0.6-SNAPSHOT [Viktor Klang]
| * | | | | | | | | | | | | | | | | | d1a9e4a 2010-03-13 | Fixed deprecation warning [Viktor Klang]
| * | | | | | | | | | | | | | | | | | 81b35c1 2010-03-13 | Return 408 is authentication times out [Viktor Klang]
| * | | | | | | | | | | | | | | | | | 6cadb0d 2010-03-13 | Fixing container detection for SBT console mode [Viktor Klang]
| | |_|/ / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | | bb90d42 2010-03-13 | cleanup, added documentation. [Jan Van Besien]
* | | | | | | | | | | | | | | | | | ac8efe5 2010-03-13 | switched from "work stealing" implementation to "work donating". Needs more testing, cleanup and documentation but looks promissing. [Jan Van Besien]
* | | | | | | | | | | | | | | | | |   df7bc4c 2010-03-11 | Merge branch 'master' into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | |   dda8e51 2010-03-11 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |/ / / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | 4f70889 2010-03-11 | removed changes.xml (online instead) [Jonas Bonér]
| * | | | | | | | | | | | | | | | |   073c0cb 2010-03-11 | merged osgi-refactoring and sbt branch [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | bb4945b 2010-03-10 | Renamed packages in the whole project to be OSGi-friendly, A LOT of breaking changes [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | 526a43b 2010-03-10 | Added maven artifact publishing to sbt build [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | 79fa971 2010-03-10 | fixed warnins in PerformanceTest [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | bde7563 2010-03-10 | Finalized SBT packaging task, now Akka is fully ported to SBT [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | 5e7f928 2010-03-09 | added final tasks (package up distribution and executable JAR) to SBT build [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | 7873a7a 2010-03-07 | added assembly task and dist task to package distribution [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | 38251f6 2010-03-07 | added java fun tests back to sbt project [Jonas Bonér]
| * | | | | | | | | | | | | | | | | |   974ebf3 2010-03-07 | merged sbt branch with master [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | | | | | 9705ee5 2010-03-05 | added test filter to filter away all tests that end with Spec [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | | 4aabc07 2010-03-05 | cleaned up buildfile [Jonas Bonér]
| * | | | | | | | | | | | | | | | | | | 53d9158 2010-03-02 | remove pom files [peter hausel]
| * | | | | | | | | | | | | | | | | | | 399ee1d 2010-03-02 | added remaining projects [peter hausel]
| * | | | | | | | | | | | | | | | | | | 71b82c5 2010-03-02 | new master parent [peter hausel]
| * | | | | | | | | | | | | | | | | | | 927edd9 2010-03-02 | second phase [peter hausel]
| * | | | | | | | | | | | | | | | | | | 81f5f8f 2010-03-01 | initial sbt support [peter hausel]
| | |_|_|/ / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | | |   29a4970 2010-03-10 | Merge commit 'upstream/master' into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|_|/ / / / / / / / / / / / / / /  
| |/| | | | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | | 156ed2d 2010-03-10 | remove redundant method in tests [ross.mcdonald]
| | |_|_|_|_|_|_|_|_|/ / / / / / / /  
| |/| | | | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | | | f067e9c 2010-03-10 | added todo [Jan Van Besien]
* | | | | | | | | | | | | | | | | | df28413 2010-03-10 | use Actor.forward(...) when redistributing work. [Jan Van Besien]
* | | | | | | | | | | | | | | | | |   d7a85c0 2010-03-09 | Merge commit 'upstream/master' into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | | edf1d9a 2010-03-09 | added atomic increment and decrement in RedisStorageBackend [Debasish Ghosh]
| * | | | | | | | | | | | | | | | | f94dc3f 2010-03-08 | fix classloader error when starting AKKA as a library in jetty (fixes http://www.assembla.com/spaces/akka/tickets/129 ) [Eckart Hertzler]
| * | | | | | | | | | | | | | | | | 198dfc4 2010-03-08 | prevent Exception when shutting down cluster [Eckart Hertzler]
| * | | | | | | | | | | | | | | | | b209e1c 2010-03-07 | Cleanup of onLoad [Viktor Klang]
| * | | | | | | | | | | | | | | | |   49da43d 2010-03-07 | Merge branch 'master' into ticket_136 [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | | | | | | b284760 2010-03-07 | Added documentation for all methods of the Cluster trait [Viktor Klang]
| * | | | | | | | | | | | | | | | | | 4840f78 2010-03-07 | Making it possile to turn cluster on/off in config [Viktor Klang]
| * | | | | | | | | | | | | | | | | |   d0a3f12 2010-03-07 | Merge branch 'master' into ticket_136 [Viktor Klang]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |/ / / / / / / / / / / / / / / / /  
| | * | | | | | | | | | | | | | | | | 92a3daa 2010-03-07 | Revert change to RemoteServer port [Viktor Klang]
| | | |_|/ / / / / / / / / / / / / /  
| | |/| | | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | | e33cf4d 2010-03-07 | Should do the trick [Viktor Klang]
| |/ / / / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | | | 14579aa 2010-03-07 | fixed bug in using akka as dep jar in app server [Jonas Bonér]
| * | | | | | | | | | | | | | | | af8a877 2010-03-06 | update docs, and comments [ross.mcdonald]
| | |_|_|_|_|_|_|/ / / / / / / /  
| |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | a1e58b5 2010-03-05 | Default-enabling JGroups [Viktor Klang]
| | |_|_|_|_|_|/ / / / / / / /  
| |/| | | | | | | | | | | | |   
| * | | | | | | | | | | | | | 722d3f2 2010-03-05 | do not include *QSpec.java for testing [Martin Krasser]
| | |/ / / / / / / / / / / /  
| |/| | | | | | | | | | | |   
| * | | | | | | | | | | | | f084e6e 2010-03-05 | removed log.trace that gave bad perf [Jonas Bonér]
| * | | | | | | | | | | | |   90f8eb3 2010-03-05 | merged with master [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|_|_|_|/ / /  
| | |/| | | | | | | | | | |   
| * | | | | | | | | | | | | 954c0ad 2010-03-05 | Fixed last persistence issues with new STM, all test pass [Jonas Bonér]
| * | | | | | | | | | | | | dc88402 2010-03-04 | Redis tests now passes with new STM + misc minor changes to Cluster [Jonas Bonér]
| * | | | | | | | | | | | |   c3fef4e 2010-03-03 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \ \ \ \ \ \   16fe4bc 2010-03-01 | merged with upstream [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | | | | | 2e81ac1 2010-02-23 | Upgraded to Multiverse 0.4 and its 2PC CommitBarriers, all tests pass [Jonas Bonér]
| * | | | | | | | | | | | | | | 4efb212 2010-02-23 | renamed actor api [Jonas Bonér]
| * | | | | | | | | | | | | | | 7a40f1a 2010-02-22 | upgraded to multiverse 0.4-SNAPSHOT [Jonas Bonér]
| * | | | | | | | | | | | | | | 6f1a9d2 2010-02-18 | updated to 0.4 multiverse [Jonas Bonér]
* | | | | | | | | | | | | | | | b4bd4d5 2010-03-09 | enhanced test such that it uses the same actor type as slow and fast actor [Jan Van Besien]
* | | | | | | | | | | | | | | | 71ab645 2010-03-07 | Improved work stealing algorithm such that work is stolen only after having processed at least all our own outstanding messages. [Jan Van Besien]
* | | | | | | | | | | | | | | | 8a46209 2010-03-07 | Documentation and some cleanup. [Jan Van Besien]
* | | | | | | | | | | | | | | | 41e7d13 2010-03-05 | removed some logging and todo comments. [Jan Van Besien]
* | | | | | | | | | | | | | | |   0ace9a7 2010-03-05 | Merge commit 'upstream/master' into workstealing [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | |_|_|/ / / / / / / / / / / /  
| |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | 2519db5 2010-03-04 | Fixing a bug in JGroupsClusterActor [Viktor Klang]
| | |_|_|/ / / / / / / / / / /  
| |/| | | | | | | | | | | | |   
* | | | | | | | | | | | | | | 823747f 2010-03-04 | fixed differences with upstream master. [Jan Van Besien]
* | | | | | | | | | | | | | | d7fa4a6 2010-03-04 | Merged with dispatcher improvements. Cleanup unit tests. [Jan Van Besien]
* | | | | | | | | | | | | | | 390d45e 2010-03-04 | Conflicts: 	akka-core/src/main/scala/actor/Actor.scala [Jan Van Besien]
* | | | | | | | | | | | | | | 4c3ed25 2010-03-04 | added todo [Jan Van Besien]
* | | | | | | | | | | | | | |   00966fd 2010-03-04 | Merge commit 'upstream/master' [Jan Van Besien]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| |/ / / / / / / / / / / / / /  
| * | | | | | | | | | | | | | 45e40f6 2010-03-03 | shutdown (and unbind) Remote Server even if the remoteServerThread is not alive [Eckart Hertzler]
| | |_|/ / / / / / / / / / /  
| |/| | | | | | | | | | | |   
* | | | | | | | | | | | | | 809821f 2010-03-03 | Had to remove the withLock method, otherwize java.lang.AbstractMethodError at runtime. The work stealing now actually works and gives a real improvement. Actors seem to be stealing work multiple times (going back and forth between actors) though... might need to tweak that. [Jan Van Besien]
* | | | | | | | | | | | | | c2d3680 2010-03-03 | replaced synchronization in actor with explicit lock. Use tryLock in the dispatcher to give up immediately when the lock is already held. [Jan Van Besien]
* | | | | | | | | | | | | | 71155bd 2010-03-03 | added documentation about the intended thread safety guarantees of the isDispatching flag. [Jan Van Besien]
* | | | | | | | | | | | | | e9c6cc1 2010-03-03 | Forgot these files... seems I have to get use to git a little still ;-) [Jan Van Besien]
* | | | | | | | | | | | | | b0ee1da 2010-03-03 | first version of the work stealing idea. Added a dispatcher which considers all actors dispatched in that dispatcher part of the same pool of actors. Added a test to verify that a fast actor steals work from a slower actor. [Jan Van Besien]
|/ / / / / / / / / / / / /  
* | | | | | | | | | | | | 309e54d 2010-03-03 | Had to revert back to synchronizing on actor when processing mailbox in dispatcher [Jonas Bonér]
* | | | | | | | | | | | |   215e6c7 2010-03-02 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \  
| |_|/ / / / / / / / / / /  
|/| | | | | | | | | | | |   
| * | | | | | | | | | | | e8e918c 2010-03-02 | upgraded version in pom to 1.1 [Debasish Ghosh]
| * | | | | | | | | | | |   365b18b 2010-03-02 | Merge branch 'master' of git@github.com:jboner/akka [Debasish Ghosh]
| |\ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | 7c6138a 2010-03-02 | Fix for link(..) [Viktor Klang]
| | | |_|_|_|/ / / / / / /  
| | |/| | | | | | | | | |   
| * | | | | | | | | | | | 4b7b713 2010-03-02 | upgraded redisclient to 1.1 - api changes, refactorings [Debasish Ghosh]
| |/ / / / / / / / / / /  
* | | | | | | | | | | | 15ed113 2010-03-01 | improved perf with 25 % + renamed FutureResult -> Future + Added lightweight future factory method [Jonas Bonér]
|/ / / / / / / / / / /  
* | | | | | | | | | | 47d1911 2010-02-28 | ActorRegistry: now based on ConcurrentHashMap, now have extensive tests, now has actorFor(uuid): Option[Actor] [Jonas Bonér]
* | | | | | | | | | | 7babcc9 2010-02-28 | fixed bug in aspect registry [Jonas Bonér]
| |_|_|/ / / / / / /  
|/| | | | | | | | |   
* | | | | | | | | | b4a4601 2010-02-26 | fixed bug with init of tx datastructs + changed actor id management [Jonas Bonér]
| |_|/ / / / / / /  
|/| | | | | | | |   
* | | | | | | | |   9246613 2010-02-23 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \   df251a5 2010-02-22 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | * | | | | | | | | 454255a 2010-02-21 | added plain english aliases for methods in CassandraSession [Eckart Hertzler]
| | | |/ / / / / / /  
| | |/| | | | | | |   
| * | | | | | | | | a09074a 2010-02-22 | Cleanup [Viktor Klang]
| |/ / / / / / / /  
| * | | | | | | | a99888c 2010-02-19 | transactional storage access has to be through lazy vals: changed in Redis test cases [Debasish Ghosh]
* | | | | | | | | f03ecb6 2010-02-23 | Added "def !!!: Future" to Actor + Futures.* with util methods [Jonas Bonér]
* | | | | | | | | e361b9d 2010-02-19 | added auto shutdown of "spawn" [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | 398bff0 2010-02-18 | fixed bug with "spawn" [Jonas Bonér]
|/ / / / / / /  
* | | | | | |   ae58883 2010-02-17 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | a5e201a 2010-02-17 |  [Jonas Bonér]
* | | | | | | | a630266 2010-02-17 | added check that transactional ref is only touched within a transaction [Jonas Bonér]
|/ / / / / / /  
* | | | | | |   b923c89 2010-02-17 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | 3800272 2010-02-17 | upgrade cassandra to 0.5.0 [Eckart Hertzler]
* | | | | | | | f4572a7 2010-02-17 | added possibility to register a remote actor by explicit handle id [Jonas Bonér]
|/ / / / / / /  
* | | | | | |   faab24d 2010-02-17 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | d7b8f86 2010-02-17 | remove old and unused 'storage-format' config element for cassandra storage [Eckart Hertzler]
| * | | | | | | c871e01 2010-02-17 | fixed bug in Serializer API, added a sample test case for Serializer, added a new jar for sjson to embedded_repo [Debasish Ghosh]
| * | | | | | | 21f7df2 2010-02-16 | Added foreach to Cluster [Viktor Klang]
| * | | | | | | c5c5c94 2010-02-16 | Restructure loader to accommodate booting from a container [Viktor Klang]
* | | | | | | | 8454a47 2010-02-17 | added sample for new server-initated remote actors [Jonas Bonér]
* | | | | | | | fecb15f 2010-02-16 | fixed failing tests [Jonas Bonér]
* | | | | | | | 680a605 2010-02-16 | added some methods to the AspectRegistry [Jonas Bonér]
* | | | | | | | 41766be 2010-02-16 | Added support for server-initiated remote actors with clients getting a dummy handle to the remote actor [Jonas Bonér]
|/ / / / / / /  
* | | | | | | 8fb281f 2010-02-16 | Deployment class loader now inhertits from system class loader [Jonas Bonér]
* | | | | | | 6aebe4b 2010-02-15 | converted tabs to spaces [Jonas Bonér]
* | | | | | |   b65e9ed 2010-02-15 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| * | | | | | | 6215c83 2010-02-15 | fixed some readme typo's [ross.mcdonald]
* | | | | | | | 675101c 2010-02-15 | Added clean automatic shutdown of RemoteClient, based on reference counting + fixed bug in shutdown of RemoteClient [Jonas Bonér]
|/ / / / / / /  
* | | | | | | 1355dd2 2010-02-13 | Merged patterns code into module [Viktor Klang]
* | | | | | | 653281b 2010-02-13 | Added akka-patterns module [Viktor Klang]
* | | | | | | 6de0b92 2010-02-12 | Moving to actor-based broadcasting, atmosphere 0.5.2 [Viktor Klang]
* | | | | | |   4776704 2010-02-12 | Merge branch 'master' into wip-comet [Viktor Klang]
|\ \ \ \ \ \ \  
| * | | | | | | 47955b3 2010-02-10 | upgrade version in akka.conf and Config.scala to 0.7-SNAPSHOT [Eckart Hertzler]
| * | | | | | | 14182b3 2010-02-10 | upgrade akka version in pom to 0.7-SNAPSHOT [Eckart Hertzler]
* | | | | | | | 93fb34c 2010-02-06 | Tweaking impl [Viktor Klang]
* | | | | | | |   c0c3a23 2010-02-06 | Updated deps [Viktor Klang]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | 3da9bc2 2010-02-06 | Upgraded Atmosphere and Jersey to 1.1.5 and 0.5.1 respectively [Viktor Klang]
| * | | | | | | 020fade 2010-02-04 | upgraded sjson to 0.4 [debasishg]
* | | | | | | |   446694b 2010-02-03 | Merge with master [Viktor Klang]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
| * | | | | | | fa009e4 2010-02-03 | Now requiring Cluster to be started and shut down manually when used outside of the Kernel [Viktor Klang]
| * | | | | | | ba2b66b 2010-02-03 |  Switched to basing it on JerseyBroadcaster for now, plus setting the correct ID [Viktor Klang]
* | | | | | | | 3678ea1 2010-02-02 | Tweaks [Viktor Klang]
* | | | | | | | db446cc 2010-02-02 | Cleaned up cluster instantiation [Viktor Klang]
* | | | | | | | 4764e1c 2010-02-02 | Initial cleanup [Viktor Klang]
|/ / / / / / /  
* | | | | | | b1d3267 2010-01-30 | Updated Akka to use JerseySimpleBroadcaster via AkkaBroadcaster [Viktor Klang]
* | | | | | | 3041964 2010-01-28 | Merged enforcers [Viktor Klang]
* | | | | | | 1d6f472 2010-01-28 | Added more documentation [Viktor Klang]
* | | | | | | 9637256 2010-01-28 | Added more conf-possibilities and documentation [Viktor Klang]
* | | | | | | 5695e0a 2010-01-28 | Added the Buildr Buildfile [Viktor Klang]
* | | | | | | 4c778c3 2010-01-28 | Removed WS and de-commented enforcer [Viktor Klang]
* | | | | | | 78ced18 2010-01-27 | Shoal will boot, but have to add jars to cp manually cause of signing of jar vs. shade [Viktor Klang]
* | | | | | | 56ac30a 2010-01-27 | Created BasicClusterActor [Viktor Klang]
* | | | | | | e77b10c 2010-01-27 | Compiles... :) [Viktor Klang]
* | | | | | | 3fbd024 2010-01-24 | Added enforcer of AKKA_HOME [Viktor Klang]
* | | | | | |   bfae9ec 2010-01-20 | merge with master [Viktor Klang]
|\ \ \ \ \ \ \  
| * | | | | | | c1fe41f 2010-01-18 | Minor code refresh [Viktor Klang]
| * | | | | | | 8093c1b 2010-01-18 | Updated deps [Viktor Klang]
| | |_|_|/ / /  
| |/| | | | |   
* | | | | | | 05fe5f7 2010-01-20 | Tidied sjson deps [Viktor Klang]
* | | | | | | 5ff3ab1 2010-01-20 | Deactored Sender [Viktor Klang]
|/ / / / / /  
* | | | | | 2f01fc7 2010-01-16 | Updated bio [Viktor Klang]
* | | | | | dab4479 2010-01-16 | Should use the frozen jars right? [Viktor Klang]
* | | | | |   152f032 2010-01-16 | Merge branch 'cluster_restructure' [Viktor Klang]
|\ \ \ \ \ \  
| * | | | | | 6af106d 2010-01-14 | Cleanup [Viktor Klang]
| * | | | | |   0852eed 2010-01-14 | Merge branch 'master' into cluster_restructure [Viktor Klang]
| |\ \ \ \ \ \  
| * | | | | | | 4091dc9 2010-01-11 | Added Shoal and Tribes to cluster pom [Viktor Klang]
| * | | | | | | 54e2668 2010-01-11 | Added modules for Shoal and Tribes [Viktor Klang]
| * | | | | | | 02a859e 2010-01-11 | Moved the cluster impls to their own modules [Viktor Klang]
* | | | | | | | 1751fde 2010-01-16 | Actor now uses default contact address for makeRemote [Viktor Klang]
| |/ / / / / /  
|/| | | | | |   
* | | | | | | 126a26c 2010-01-13 | Queue storage is only implemented in Redis. Base trait throws UnsupportedOperationException [debasishg]
|/ / / / / /  
* | | | | | a9371c2 2010-01-11 | Implemented persistent transactional queue with Redis backend [debasishg]
* | | | | | fee16e4 2010-01-09 | Added some FIXMEs for 2.8 migration [Viktor Klang]
* | | | | | 9b9cba2 2010-01-06 | Added docs [Viktor Klang]
* | | | | | 8dfc57c 2010-01-05 | renamed shutdown tests to spec [Jonas Bonér]
* | | | | | 7366b35 2010-01-05 | dos2unix formatting [Jonas Bonér]
* | | | | | ba8b35d 2010-01-05 | Added test for Actor shutdown, RemoteServer shutdown and Cluster shutdown [Jonas Bonér]
* | | | | | f98d618 2010-01-05 | Updated pom.xml files to new dedicated Atmosphere and Jersey JARs [Jonas Bonér]
* | | | | |   37470b7 2010-01-05 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \  
| * | | | | | 01c7dda 2010-01-04 | Comet fixed! JFA FTW! [Viktor Klang]
* | | | | | |   ce6e55f 2010-01-04 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| |/ / / / / /  
| * | | | | | f35da1a 2010-01-04 | Fixed redisclient pom and embedded repo path [Viktor Klang]
| * | | | | | d706db1 2010-01-04 | jndi.properties, now in jar [Viktor Klang]
| * | | | | | 45f8553 2010-01-03 | Typo broke auth [Viktor Klang]
* | | | | | | 7c7d32d 2010-01-04 | Fixed issue with shutting down cluster correctly + Improved chat sample README [Jonas Bonér]
|/ / / / / /  
* | | | | | 72d5c31 2010-01-03 | added pretty print to chat sample [Jonas Bonér]
| |_|/ / /  
|/| | | |   
* | | | | 5276288 2010-01-02 | changed README [Jonas Bonér]
* | | | | f0286da 2010-01-02 | Restructured persistence modules into its own submodule [Jonas Bonér]
* | | | | 2c560fe 2010-01-02 | removed unecessary parent pom directive [Jonas Bonér]
* | | | | baa685a 2010-01-02 | moved all samples into its own subproject [Jonas Bonér]
* | | | | 3adf348 2010-01-02 | Fixed bug with not shutting down remote node cluster correctly [Jonas Bonér]
* | | | | fd2af28 2010-01-02 | Fixed bug in shutdown management of global event-based dispatcher [Jonas Bonér]
|/ / / /  
* | | | d2e67f0 2009-12-31 | added postRestart to RedisChatStorage [Jonas Bonér]
* | | |   74c1077 2009-12-31 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * | | | 0c7deee 2009-12-31 | fixed bug in 'actor' methods [Jonas Bonér]
* | | | | fdb7bbf 2009-12-31 | fixed bug in 'actor' methods [Jonas Bonér]
|/ / / /  
* | | | 8130e69 2009-12-30 | refactored chat sample [Jonas Bonér]
* | | | ceb9b69 2009-12-30 | refactored chat server [Jonas Bonér]
* | | | 056cb47 2009-12-30 | Added test for forward of !! messages + Added StaticChannelPipeline for RemoteClient [Jonas Bonér]
* | | |   ea673ff 2009-12-30 | removed tracing [Jonas Bonér]
|\ \ \ \  
| * | | | feb1d4b 2009-12-29 | Fixing ticket 89 [Viktor Klang]
* | | | | 0567a57 2009-12-30 | Added registration of remote actors in declarative supervisor config + Fixed bug in remote client reconnect + Added Redis as backend for Chat sample + Added UUID utility + Misc minor other fixes [Jonas Bonér]
|/ / / /  
* | | | 90f7e0e 2009-12-29 | Fixed bug in RemoteClient reconnect, now works flawlessly + Added option to declaratively configure an Actor to be remote [Jonas Bonér]
* | | | 89178ae 2009-12-29 | renamed Redis test from *Test to *Spec + removed requirement to link Actor only after start + refactored Chat sample to use mixin composition of Actor [Jonas Bonér]
* | | | b05424a 2009-12-29 | upgraded sjson to 0.3 to handle json serialization of classes loaded through an externally specified classloader [debasishg]
* | | | af73f86 2009-12-28 | fixed shutdown bug [Jonas Bonér]
* | | | faf3289 2009-12-28 | added README how to run the chat server sample [Jonas Bonér]
* | | |   b94b8d0 2009-12-28 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * | | | 2b7c6d1 2009-12-28 | added redis module for persistence in parent pom [debasishg]
* | | | | 322ff01 2009-12-28 | Enhanced sample chat application [Jonas Bonér]
* | | | | 22a2e98 2009-12-27 | added new chat server sample [Jonas Bonér]
* | | | | 56d6c0d 2009-12-27 | Now forward works with !! + added possibility to set a ClassLoader for the Serializer.* classes [Jonas Bonér]
* | | | | 206c6ee 2009-12-27 | removed scaladoc [Jonas Bonér]
* | | | | 90451b4 2009-12-27 | Updated copyright header [Jonas Bonér]
|/ / / /  
* | | | ac5451a 2009-12-27 | fixed misc FIXMEs and TODOs [Jonas Bonér]
* | | | 5ee81af 2009-12-27 | changed order of config elements [Jonas Bonér]
* | | | 3b3b87e 2009-12-26 | Upgraded to RabbitMQ 1.7.0 [Jonas Bonér]
* | | | 8b72777 2009-12-26 | added implicit transaction family name for the atomic { .. } blocks + changed implicit sender argument to Option[Actor] (transparent change) [Jonas Bonér]
* | | | 7873a0a 2009-12-26 | renamed ..comet.AkkaCometServlet to ..comet.AkkaServlet [Jonas Bonér]
* | | |   3e339e5 2009-12-26 | Merge branch 'Christmas_restructure' [Viktor Klang]
|\ \ \ \  
| * | | | ecc6406 2009-12-26 | Adding docs [Viktor Klang]
| * | | |   308cabd 2009-12-26 | Merge branch 'master' into Christmas_restructure [Viktor Klang]
| |\ \ \ \  
| * \ \ \ \   fa42ccc 2009-12-24 | Merge branch 'master' into Christmas_restructure [Viktor Klang]
| |\ \ \ \ \  
| * | | | | | 2e7b749 2009-12-24 | Some renaming and some comments [Viktor Klang]
| * | | | | | 1d62c87 2009-12-24 | Additional tidying [Viktor Klang]
| * | | | | | 6169955 2009-12-24 | Cleaned up the code [Viktor Klang]
| * | | | | | 5ed2c71 2009-12-24 | Got it working! [Viktor Klang]
| * | | | | | bea3254 2009-12-23 | Tweaking [Viktor Klang]
| * | | | | | 429ce06 2009-12-23 | Experimenting with Comet cluster support [Viktor Klang]
| * | | | | | 194fc86 2009-12-22 | Forgot to add the Main class [Viktor Klang]
| * | | | | | e163b4c 2009-12-22 | Added Kernel class for web kernel [Viktor Klang]
| * | | | | | 41a90d0 2009-12-22 | Added possibility to use Kernel as j2ee context listener [Viktor Klang]
| * | | | | |   d9c5838 2009-12-22 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| |\ \ \ \ \ \  
| * | | | | | | b2dc468 2009-12-22 | Christmas cleaning [Viktor Klang]
* | | | | | | | f5a4191 2009-12-26 | added tests for actor.forward [Jonas Bonér]
| |_|_|/ / / /  
|/| | | | | |   
* | | | | | |   acc1233 2009-12-25 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \  
| | |_|/ / / /  
| |/| | | | |   
| * | | | | | 60dd640 2009-12-24 | small typo to catch up with docs [ross.mcdonald]
| * | | | | | b98a683 2009-12-24 | changed default port for redis server [debasishg]
| * | | | | | b8080f3 2009-12-24 | added redis backend storage for akka transactors [debasishg]
| | |/ / / /  
| |/| | | |   
* | | | | | 48dff08 2009-12-25 | Added durable and auto-delete to AMQP [Jonas Bonér]
|/ / / / /  
* | | | | 1f3a382 2009-12-22 | pre/postRestart now takes a Throwable as arg [Jonas Bonér]
|/ / / /  
* | | | b4ea27d 2009-12-22 | fixed problem in aop.xml [Jonas Bonér]
* | | |   ed3a9ca 2009-12-22 | merged [Jonas Bonér]
|\ \ \ \  
| * | | | fd7fb17 2009-12-21 | reverted back to working pom files [Jonas Bonér]
* | | | | e522ff3 2009-12-21 | cleaned up pom.xml files [Jonas Bonér]
|/ / / /  
* | | | 7b507df 2009-12-21 | removed dbDispatch from embedded repo [Jonas Bonér]
* | | | e517a08 2009-12-21 | forgot to add Cluster.scala [Jonas Bonér]
* | | | 8fcd273 2009-12-21 | moved Cluster into akka-core + updated dbDispatch jars [Jonas Bonér]
* | | |   4012c79 2009-12-21 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \  
| * | | | 0bf3efb 2009-12-18 | Updated conf aswell [Viktor Klang]
| * | | | d4193a5 2009-12-18 | Moved Cluster package [Viktor Klang]
| * | | |   b4559ab 2009-12-18 | Merge with Atmosphere0.5 [Viktor Klang]
| |\ \ \ \  
| | * \ \ \   ec8feae 2009-12-18 | merged with master [Viktor Klang]
| | |\ \ \ \  
| | * | | | | adf73db 2009-12-15 | Isn´t needed [Viktor Klang]
| | * | | | |   718e4e1 2009-12-15 | Merge branch 'master' into Atmosphere0.5 [Viktor Klang]
| | |\ \ \ \ \  
| | | * \ \ \ \   8f6c217 2009-12-15 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \  
| | * | | | | | | a714495 2009-12-13 | removed wrongly added module [Viktor Klang]
| | * | | | | | | 87435a1 2009-12-13 | Merged with master and refined API [Viktor Klang]
| | * | | | | | |   1c3380a 2009-12-13 | Merge branch 'master' into Atmosphere0.5 [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | |/ / / / / /  
| | * | | | | | | 113a0af 2009-12-13 | Upgrading to latest Atmosphere API [Viktor Klang]
| | * | | | | | | be21c77 2009-12-09 | Fixing comet support [Viktor Klang]
| | * | | | | | | af00a6b 2009-12-09 | Upgraded API for Jersey and Atmosphere [Viktor Klang]
| | * | | | | | |   3985a64 2009-12-09 | Merge branch 'master' into Atmosphere0.5 [Viktor Klang]
| | |\ \ \ \ \ \ \  
| | | | |_|_|/ / /  
| | | |/| | | | |   
| | * | | | | | |   8972294 2009-12-03 | Merge branch 'master' into Atmosphere0.5 [Viktor Klang]
| | |\ \ \ \ \ \ \  
| * | \ \ \ \ \ \ \   d5a3193 2009-12-18 | Merge branch 'Cluster' of git@github.com:jboner/akka into Cluster [Viktor Klang]
| |\ \ \ \ \ \ \ \ \  
| | * \ \ \ \ \ \ \ \   fab7179 2009-12-18 | merged master [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|/ / /  
| | | |/| | | | | | |   
| | | * | | | | | | |   3ac57d6 2009-12-17 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \ \ \  
| | | * | | | | | | | | 6d17d0c 2009-12-17 | re-adding NodeWriter [Viktor Klang]
| | | * | | | | | | | |   da660aa 2009-12-17 | merge with master [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \   b6c7704 2009-12-16 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | f726088 2009-12-16 | Fixing Jersey resources shading [Viktor Klang]
| * | | | | | | | | | | | | e3c83da 2009-12-18 | Merging [Viktor Klang]
| |/ / / / / / / / / / / /  
| * | | | | | | | | | | | fc1d7b3 2009-12-15 | Removed boring API method [Viktor Klang]
| * | | | | | | | | | | | f06df21 2009-12-14 | Added ask-back [Viktor Klang]
| * | | | | | | | | | | | 0e23334 2009-12-14 | fixed the API, bugs etc [Viktor Klang]
| * | | | | | | | | | | | d76fc3a 2009-12-14 | minor formatting edits [Jonas Bonér]
| * | | | | | | | | | | |   f3caedb 2009-12-14 | Merge branch 'Cluster' of git@github.com:jboner/akka into Cluster [Jonas Bonér]
| |\ \ \ \ \ \ \ \ \ \ \ \  
| | * | | | | | | | | | | | eb5c39b 2009-12-13 | A better solution for comet conflict resolve [Viktor Klang]
| | * | | | | | | | | | | | c614574 2009-12-13 | Updated to latest Atmosphere API [Viktor Klang]
| | * | | | | | | | | | | | 162a9a8 2009-12-13 | Minor tweaks [Viktor Klang]
| | * | | | | | | | | | | | 78a281a 2009-12-13 | Adding more comments [Viktor Klang]
| | * | | | | | | | | | | | 16dcbe2 2009-12-13 | Excluding self node from member list [Viktor Klang]
| | * | | | | | | | | | | | f9cfc76 2009-12-13 |  Added additional logging and did some slight tweaks. [Viktor Klang]
| | * | | | | | | | | | | |   407b02e 2009-12-13 | Merge branch 'master' into Cluster [Viktor Klang]
| | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|_|_|/ / / /  
| | | |/| | | | | | | | | |   
| | | * | | | | | | | | | |   ca0046f 2009-12-13 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | 423ee7b 2009-12-09 | Adding the cluster module skeleton [Viktor Klang]
| | | * | | | | | | | | | | |   f82f393 2009-12-09 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | | |_|_|_|_|_|/ / / / / /  
| | | |/| | | | | | | / / / /   
| | | | | |_|_|_|_|_|/ / / /    
| | | | |/| | | | | | | | |     
| | | * | | | | | | | | | | 98c4bae 2009-12-02 | Tweaked Jersey version [Viktor Klang]
| | | * | | | | | | | | | |   4d8d09f 2009-12-02 | Fixed deps [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | 3262264 2009-12-02 | Fixed JErsey broadcaster issue [Viktor Klang]
| | | * | | | | | | | | | | | ae0e0e1 2009-12-02 | Added version [Viktor Klang]
| | | * | | | | | | | | | | |   86ac72a 2009-12-02 | Merge commit 'origin/master' into Atmosphere0.5 [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \ \ \ \  
| | | * \ \ \ \ \ \ \ \ \ \ \ \   1f16b37 2009-11-26 | Merge branch 'master' into Atmosphere5.0 [Viktor Klang]
| | | |\ \ \ \ \ \ \ \ \ \ \ \ \  
| | | * | | | | | | | | | | | | | f46e819 2009-11-25 | Atmosphere5.0 [Viktor Klang]
| | * | | | | | | | | | | | | | | bda7537 2009-12-13 | Ack, fixing the conf [Viktor Klang]
| | * | | | | | | | | | | | | | | 7d7db8d 2009-12-13 | Sprinkling extra output for debugging [Viktor Klang]
| | * | | | | | | | | | | | | | | 81151db 2009-12-12 | Working on one node anyways... [Viktor Klang]
| | * | | | | | | | | | | | | | | ddcf294 2009-12-12 | Hooked the clustering into RemoteServer [Viktor Klang]
| | * | | | | | | | | | | | | | | 40be6c9 2009-12-12 | Tweaked logging [Viktor Klang]
| | * | | | | | | | | | | | | | | 406a1e1 2009-12-12 | Moved Cluster to akka-actors [Viktor Klang]
| | * | | | | | | | | | | | | | | 70aa028 2009-12-12 | Moved cluster into akka-actor [Viktor Klang]
| | * | | | | | | | | | | | | | | b93738b 2009-12-12 | Tidying some code [Viktor Klang]
| | * | | | | | | | | | | | | | | 54ab039 2009-12-12 | Atleast compiles [Viktor Klang]
| | * | | | | | | | | | | | | | | 6b952e4 2009-12-09 | Updated conf docs [Viktor Klang]
| | * | | | | | | | | | | | | | | f8bbb9b 2009-12-09 | Create and link new cluster module [Viktor Klang]
| | | |_|_|_|/ / / / / / / / / /  
| | |/| | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | 6c9795a 2009-12-21 | minor reformatting [Jonas Bonér]
* | | | | | | | | | | | | | | |   aaa7174 2009-12-18 | merged in teigen's persistence structure refactoring [Jonas Bonér]
|\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| * \ \ \ \ \ \ \ \ \ \ \ \ \ \ \   a6ca27f 2009-12-17 | Merge branch 'master' of git@github.com:teigen/akka into ticket_82 [Jon-Anders Teigen]
| |\ \ \ \ \ \ \ \ \ \ \ \ \ \ \ \  
| | | |_|_|_|_|_|_|_|_|_|/ / / / /  
| | |/| | | | | | | | | | | | | |   
| * | | | | | | | | | | | | | | | 41d66ae 2009-12-17 | #82 - Split up persistence module into a module per backend storage [Jon-Anders Teigen]
| * | | | | | | | | | | | | | | | 70c7b29 2009-12-17 | #82 - Split up persistence module into a module per backend storage [Jon-Anders Teigen]
| | |_|_|_|_|_|_|_|_|_|/ / / / /  
| |/| | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | | 98f6501 2009-12-18 | renamed akka-actor to akka-core [Jonas Bonér]
| |/ / / / / / / / / / / / / /  
|/| | | | | | | | | | | | | |   
* | | | | | | | | | | | | | | 4053674 2009-12-17 | fixed broken h2-lzf jar [Jonas Bonér]
* | | | | | | | | | | | | | | 0abf520 2009-12-17 | upgraded many dependencies and removed some in embedded-repo that are in public repos now [Jonas Bonér]
|/ / / / / / / / / / / / / /  
* | | | | | | | | | | | | | 4fff133 2009-12-17 | Removed MessageBodyWriter causing problems + added a Compression class with support for LZF compression and uncomression + added new flag to Actor defining if actor is currently dead [Jonas Bonér]
| |_|_|_|_|_|_|_|/ / / / /  
|/| | | | | | | | | | | |   
* | | | | | | | | | | | | b0991bf 2009-12-16 | renamed 'nio' package to 'remote' [Jonas Bonér]
| |_|_|_|_|_|_|/ / / / /  
|/| | | | | | | | | | |   
* | | | | | | | | | | | c87812a 2009-12-15 | fixed broken runtime name of threads + added Transactor trait to some samples [Jonas Bonér]
* | | | | | | | | | | | 3a069f0 2009-12-15 | minor edits [Jonas Bonér]
* | | | | | | | | | | | 3de9af9 2009-12-15 | Moved {AllForOneStrategy, OneForOneStrategy, FaultHandlingStrategy} from 'actor' to 'config' [Jonas Bonér]
| |_|_|_|_|_|_|_|/ / /  
|/| | | | | | | | | |   
* | | | | | | | | | | ca3aa0f 2009-12-15 | cleaned up kernel module pom.xml [Jonas Bonér]
* | | | | | | | | | | 1411565 2009-12-15 | updated changes.xml [Jonas Bonér]
* | | | | | | | | | | f9ac8c3 2009-12-15 | added test timeout [Jonas Bonér]
* | | | | | | | | | | b8eea97 2009-12-15 | Fixed bug in event-driven dispatcher + fixed bug in makeRemote when run on a remote instance [Jonas Bonér]
* | | | | | | | | | | c1e74fb 2009-12-15 | Fixed bug with starting actors twice in supervisor + moved init method in actor into isRunning block + ported DataFlow module to akka actors [Jonas Bonér]
* | | | | | | | | | | 9b2e32e 2009-12-14 | - added remote actor reply changes [Mikael Högqvist]
* | | | | | | | | | |   19e5c73 2009-12-14 | Merge branch 'remotereply' [Mikael Högqvist]
|\ \ \ \ \ \ \ \ \ \ \  
| * | | | | | | | | | | 2b59378 2009-12-14 | - Support for implicit sender with remote actors (fixes Issue #71) - The RemoteServer and RemoteClient was modified to support a clean shutdown when testing using multiple remote servers [Mikael Högqvist]
| |/ / / / / / / / / /  
* | | | | | | | | | | 71b7339 2009-12-14 | add a jersey MessageBodyWriter that serializes scala lists to JSON arrays [Eckart Hertzler]
* | | | | | | | | | | 42be719 2009-12-14 | removed the Init(config) life-cycle message and the config parameters to pre/postRestart instead calling init right after start has been invoked for doing post start initialization [Jonas Bonér]
|/ / / / / / / / / /  
* | | | | | | | | | 1db8c8c 2009-12-14 | fixed bug in dispatcher [Jonas Bonér]
| |_|_|_|_|/ / / /  
|/| | | | | | | |   
* | | | | | | | |   54653e4 2009-12-13 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \ \  
| |/ / / / / / / /  
| * | | | | | | | 8437345 2009-12-09 | Upgraded MongoDB Java driver to 1.0 and fixed API incompatibilities [debasishg]
* | | | | | | | | c955ac9 2009-12-13 | removed fork-join scheduler [Jonas Bonér]
* | | | | | | | | 7ea53e1 2009-12-13 | Rewrote new executor based event-driven dispatcher to use actor-specific mailboxes [Jonas Bonér]
* | | | | | | | | 2b2f037 2009-12-11 | Rewrote the dispatcher APIs and internals, now event-based dispatchers are 10x faster and much faster than Scala Actors. Added Executor and ForkJoin based dispatchers. Added a bunch of dispatcher tests. Added performance test [Jonas Bonér]
* | | | | | | | | b5c9c6a 2009-12-11 | refactored dispatcher invocation API [Jonas Bonér]
* | | | | | | | | 4c685a2 2009-12-11 | added forward method to Actor, which forwards the message and maintains the original sender [Jonas Bonér]
|/ / / / / / / /  
* | | | | | | | 81a0e94 2009-12-08 | fixed actor bug related to hashcode [Jonas Bonér]
* | | | | | | | 753bcd5 2009-12-08 | fixed bug in storing user defined Init(config) in Actor [Jonas Bonér]
* | | | | | | | 708a9e3 2009-12-08 | changed actor message type from AnyRef to Any [Jonas Bonér]
* | | | | | | | b01df1e 2009-12-07 | added memory footprint test + added shutdown method to Kernel + added ActorRegistry.shutdownAll to shut down all actors [Jonas Bonér]
* | | | | | | |   46b42d3 2009-12-07 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| | |_|_|_|/ / /  
| |/| | | | | |   
| * | | | | | | 10c117e 2009-12-03 | Upgrading to Grizzly 1.9.18-i [Viktor Klang]
* | | | | | | |   9cd836c 2009-12-07 | merged after reimpl of persistence API [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| * | | | | | | | 5edc933 2009-12-05 | refactoring of persistence implementation and its api [Jonas Bonér]
* | | | | | | | | 310742a 2009-12-05 | fixed bug in anon actor [Jonas Bonér]
| |/ / / / / / /  
|/| | | | | | |   
* | | | | | | |   730cc91 2009-12-03 | Merge branch 'master' of git@github.com:jboner/akka [Jonas Bonér]
|\ \ \ \ \ \ \ \  
| |/ / / / / / /  
|/| | | | / / /   
| | |_|_|/ / /    
| |/| | | | |     
| * | | | | | 1afe9e8 2009-12-02 | Added jersey.version and atmosphere.version and fixed jersey broadcaster bug [Viktor Klang]
| | |_|/ / /  
| |/| | | |   
* | | | | | 7ad879d 2009-12-03 | minor reformatting [jboner]
* | | | | | d64d2fb 2009-12-02 | fixed bug in start/spawnLink, now atomic [jboner]
|/ / / / /  
* | | | | 04f9afa 2009-12-02 | removed unused jars in embedded repo, added to changes.xml [jboner]
* | | | | 691dbb8 2009-12-02 | fixed type in rabbitmq pom file in embedded repo [jboner]
* | | | | e5f232b 2009-12-01 | added memory footprint test [jboner]
* | | | | 7b1bae3 2009-11-30 | Added trapExceptions to declarative supervisor configuration [jboner]
* | | | | 72eda97 2009-11-30 | Fixed issue #35: @transactionrequired as config element in declarative config [jboner]
* | | | |   b61c843 2009-11-30 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
|\ \ \ \ \  
| * | | | | 20aac72 2009-11-30 | typos in modified actor [ross.mcdonald]
* | | | | | 879512a 2009-11-30 | edit of logging [jboner]
|/ / / / /  
* | | | | ce171e0 2009-11-30 | added PersistentMap.newMap(id) and PersistinteMap.getMap(id) for Map, Vector and Ref [jboner]
| |/ / /  
|/| | |   
* | | | 7ddc2d8 2009-11-26 | shaped up scaladoc for transaction [jboner]
* | | | 6677370 2009-11-26 | improved anonymous actor and atomic block syntax [jboner]
* | | |   c2de5b6 2009-11-25 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
|\ \ \ \  
| * | | | 9216161 2009-11-25 | fixed MongoDB tests and fixed bug in transaction handling with PersistentMap [debasishg]
* | | | | 4318d68 2009-11-25 | Upgraded to latest Mulitverse SNAPSHOT [jboner]
|/ / / /  
* | | | 2f12275 2009-11-25 | Addded reference count for dispatcher to allow shutdown and GC of event-driven actors using the global event-driven dispatcher [jboner]
* | | | 30d8dec 2009-11-25 | renamed RemoteServerNode -> RemoteNode [jboner]
* | | | 389182c 2009-11-24 | removed unused dependencies [jboner]
* | | | 1787307 2009-11-24 | changed remote server API to allow creating multiple servers (RemoteServer) or one (RemoteServerNode), also added a shutdown method [jboner]
* | | | 794750f 2009-11-24 | cleaned up and fixed broken error logging [jboner]
* | | | bd29420 2009-11-23 | reverted back to original mongodb test, still failing though [jboner]
* | | | 2fcf027 2009-11-23 | Fixed problem with implicit sender + updated changes.xml [jboner]
* | | | f98184f 2009-11-22 | cleaned up logging and error reporting [jboner]
* | | | f41c1ac 2009-11-22 | added support for LZF compression [jboner]
* | | | fadf2b2 2009-11-22 | added compression level config options [jboner]
* | | | e5fd40c 2009-11-21 | Added zlib compression to remote actors [jboner]
* | | | f5b9e98 2009-11-21 | Fixed issue #46: Remote Actor should be defined by target class and UUID [jboner]
* | | | 805fac6 2009-11-21 | Cleaned up the Actor and Supervisor classes. Added implicit sender to actor ! methods, works with 'sender' field and 'reply' [jboner]
* | | | 9606dbf 2009-11-20 | added stop method to actor [jboner]
* | | | 3dd8401 2009-11-20 | removed the .idea dirr [jboner]
* | | | a1adfd4 2009-11-20 | cleaned up supervisor and actor api, breaking changes [jboner]
|/ / /  
* | | 50e73c2 2009-11-19 | added eclipse files to .gitignore [jboner]
* | |   30e6de3 2009-11-19 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
|\ \ \  
| * | | e105d97 2009-11-18 | update package of AkkaServlet in the sample's web.xml after the refactoring of AkkaServlet [Eckart Hertzler]
| * | |   4b36846 2009-11-18 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| |\ \ \  
| * | | | 340fe67 2009-11-18 | Unbr0ked the comet support loading [Viktor Klang]
* | | | | 6a540df 2009-11-19 | upgraded to Protobuf 2.2 and Netty 3.2-ALPHA [jboner]
| |/ / /  
|/| | |   
* | | | 2cbc15e 2009-11-18 | fixed bug in remote server [jboner]
* | | | ceeb7d8 2009-11-17 | changed trapExit from Boolean to "trapExit = List(classOf[..], classOf[..])" + cleaned up security code [jboner]
* | | | eef81f8 2009-11-17 | added .idea project files [jboner]
* | | | 79e4319 2009-11-17 | removed idea project files [jboner]
* | | |   fe51842 2009-11-16 | Merge branch 'master' of git@github.com:jboner/akka into dev [jboner]
|\ \ \ \  
| |/ / /  
| * | | 8d24b1e 2009-11-14 | Added support for CometSupport parametrization [Viktor Klang]
| * | | e5f0a4c 2009-11-12 | Updated Atmosphere deps [Viktor Klang]
* | | | ea3ccbc 2009-11-16 | added system property settings for max Multiverse speed [jboner]
|/ / /  
* | |   e4be135 2009-11-12 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
|\ \ \  
| * | | 8def0dc 2009-11-11 | fixed typo in comment [ross.mcdonald]
* | | | 12efc98 2009-11-12 | added changes to changes.xml [jboner]
* | | | c3e2cc2 2009-11-11 | fixed potential memory leak with temporary actors [jboner]
* | | | 015bf58 2009-11-11 | removed transient life-cycle and restart-within-time attribute [jboner]
* | | |   d8e5c1c 2009-11-09 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
|\ \ \ \  
| |/ / /  
| * | | 8061ad3 2009-11-07 | Fixing master [Viktor Klang]
| * | | e5f00d5 2009-11-05 | bring lift dependencies into embedded repo [ross.mcdonald]
| * | | 2dca2a5 2009-11-04 | rename our embedded repo lift dependencies [ross.mcdonald]
| * | | 24431ff 2009-11-04 | bring lift 1.1-SNAPSHOT into the embedded repo [ross.mcdonald]
| * | | 9d37c58 2009-11-03 | minor change to comments [ross.mcdonald]
* | | | 69b0e45 2009-11-04 | added lightweight actor syntax + fixed STM/persistence issue [jboner]
|/ / /  
* | | 7f59f18 2009-11-02 | added monadic api to the transaction [jboner]
* | | e5f3b47 2009-11-02 | added the ability to kill another actor [jboner]
* | | ef2e44e 2009-11-02 | added support for finding actor by id in the actor registry + made senderFuture available to user code [jboner]
* | | ff83935 2009-10-30 | refactored and cleaned up [jboner]
* | | eee7e09 2009-10-30 | Changed the Cassandra consistency level semantics to fit with new 0.4 nomenclature [jboner]
* | | aca5536 2009-10-28 | renamed lifeCycleConfig to lifeCycle + fixed AMQP bug/isses [jboner]
* | | 62ff0db 2009-10-28 | cleaned up actor field access modifiers and prefixed internal fields with _ to avoid name clashes [jboner]
* | | c145380 2009-10-28 | Improved AMQP module code [jboner]
* | | 92eb574 2009-10-27 | removed transparent serialization/deserialization on AMQP module [jboner]
* | | 58fe1bf 2009-10-27 | changed AMQP messages access modifiers [jboner]
* | | fd9070a 2009-10-27 | Made the AMQP message consumer listener aware of if its is using a already defined queue or not [jboner]
* | | e65a4f1 2009-10-27 | upgrading to lift 1.1 snapshot [jboner]
* | | f0035b5 2009-10-27 | Added possibility of sending reply messages directly by sending them to the AMQP.Consumer [jboner]
* | | fe3ee20 2009-10-27 | fixing compile errors due to api changes in multiverse [Jon-Anders Teigen]
* | | a4625b3 2009-10-26 | added scaladoc for all modules in the ./doc directory [jboner]
* | | 7c70fcd 2009-10-26 | upgraded scaladoc module [jboner]
* | |   5c6d629 2009-10-26 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
|\ \ \  
| * | | e30cd4a 2009-10-26 | tests can now be run with out explicitly defining AKKA_HOME [Eckart Hertzler]
| * | | 1001261 2009-10-25 | bump lift sample akka version [ross.mcdonald]
| * | | 131a9aa 2009-10-24 | test cases for basic authentication actor added [Eckart Hertzler]
* | | | 18f125c 2009-10-26 | fixed issue with needing AKKA_HOME to run tests [jboner]
* | | | 3cc8671 2009-10-26 | migrated over to ScalaTest 1.0 [jboner]
|/ / /  
* | | adf3eeb 2009-10-24 | messed with the config file [jboner]
* | |   d437175 2009-10-24 | merged with updated security sample [jboner]
|\ \ \  
| * | | 915e48e 2009-10-23 | remove old commas [ross.mcdonald]
| * | | c3f158f 2009-10-23 | updated FQN of sample basic authentication service [Eckart Hertzler]
| * | |   4ac453f 2009-10-23 | Merge branch 'master' of git://github.com/jboner/akka [Eckart Hertzler]
| |\ \ \  
| | * | | 3b051fa 2009-10-23 | Updated FQN of Security module [Viktor Klang]
| * | | | aef72cd 2009-10-23 | add missing @DenyAll annotation [Eckart Hertzler]
| |/ / /  
| * | |   650c9b5 2009-10-23 | Merge branch 'master' of git://github.com/jboner/akka [Eckart Hertzler]
| |\ \ \  
| * | | | 516c5f9 2009-10-22 | added a sample webapp for the security actors including examples for all authentication actors [Eckart Hertzler]
* | | | | 784a419 2009-10-24 | upgraded to multiverse 0.3-SNAPSHOT + enriched the AMQP API [jboner]
| |/ / /  
|/| | |   
* | | | dc0f863 2009-10-22 | added API for creating and binding new queues to existing AMQP producer/consumer [jboner]
* | | | 2ac6862 2009-10-22 | commented out failing lift-samples module [jboner]
* | | | 2d0ca68 2009-10-22 | Added reconnection handler and config to RemoteClient [jboner]
* | | | ba30bef 2009-10-21 | fixed wrong timeout semantics in actor [jboner]
|/ / /  
* | | 8e9d4b0 2009-10-21 | AMQP: added API for creating and deleting new queues for a producer and consumer [jboner]
* | |   25612b2 2009-10-20 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
|\ \ \  
| * | | 80708cd 2009-10-19 | Fix NullpointerException in the BasicAuth actor when called without "Authorization" header [Eckart Hertzler]
| * | | 85ff3b2 2009-10-18 | fix a bug in the retrieval of resource level role annotation [Eckart Hertzler]
| * | | ba61d3c 2009-10-18 | Added Kerberos/SPNEGO Authentication for REST Actors [Eckart Hertzler]
| * | | 224b4b4 2009-09-16 | Fixed misspelled XML namespace in pom. Removed twitter scala-json dependency from pom. [Odd Moller]
* | | | 0047247 2009-10-20 | commented out the persistence tests [jboner]
* | | | 84a19bc 2009-10-20 | fixed SJSON bug in Mongo [jboner]
* | | |   ae4a02c 2009-10-19 | merged with master head [jboner]
|\ \ \ \  
| |/ / /  
| * | | c73bb89 2009-10-16 | added wrong config by mistake [jboner]
| * | | 230c9ad 2009-10-14 | added NOOP serializer + fixed wrong servlet name in web.xml [jboner]
| * | |   d6d9b0e 2009-10-14 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\ \ \  
| | * \ \   dadce02 2009-10-14 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
| | |\ \ \  
| | * | | | be09361 2009-10-14 | Changed to only exclude jars [Viktor Klang]
| | * | | | 5b990d5 2009-10-13 | Added webroot [Viktor Klang]
| * | | | | 61d0b44 2009-10-14 | fixed bug with using ThreadBasedDispatcher + added tests for dispatchers [jboner]
| * | | | | 2c517f2 2009-10-13 | changed persistent structures names [jboner]
| | |/ / /  
| |/| | |   
| * | | | 4909592 2009-10-13 | fixed broken remote server api [jboner]
| * | | | 59b4da7 2009-10-13 | fixed remote server bug [jboner]
| |/ / /  
| * | | 9e88576 2009-10-12 | Fitted the Atmosphere Chat example onto Akka [Viktor Klang]
| * | | 899c86a 2009-10-12 | Refactored Atmosphere support to be container agnostic + fixed a couple of NPEs [Viktor Klang]
| * | |   9c04f47 2009-10-11 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\ \ \  
| | * | | fe822d2 2009-10-11 | enhanced the RemoteServer API [jboner]
| * | | | de982e7 2009-10-11 | enhanced the RemoteServer API [jboner]
| |/ / /  
* | | | 5695b72 2009-10-19 | upgraded to cassandra 0.4.1 [jboner]
* | | | 112c7c8 2009-10-19 | refactored Dispatchers + made Supervisor private[akka] [jboner]
* | | | a9a89b1 2009-10-19 | fixed sample problem [jboner]
* | | | 3ce5372 2009-10-17 | removed println [jboner]
* | | |   900a7ed 2009-10-17 | finalized new STM with Multiverse backend + cleaned up Active Object config and factory classes [jboner]
|\ \ \ \  
| |/ / /  
| * | | 44556dc 2009-10-08 | upgraded dependencies [Viktor Klang]
| * | | 405aa64 2009-10-06 | upgraded sjson jar to 0.2 [debasishg]
| * | | f4f9495 2009-09-26 | Removed bad conf [Viktor Klang]
* | | | 97f8517 2009-10-08 | renamed methods for or-else [jboner]
* | | | f6c9484 2009-10-08 | stm cleanup and refactoring [jboner]
* | | | c073c2b 2009-10-08 | refactored and renamed AMQP code, refactored STM, fixed persistence bugs, renamed reactor package to dispatch, added programmatic API for RemoteServer [jboner]
* | | | 059502b 2009-10-06 | fixed a bunch of persistence bugs [jboner]
* | | | 5b8b46d 2009-10-01 | migrated storage over to cassandra 0.4 [jboner]
* | | | 905438c 2009-09-30 | upgraded to Cassandra 0.4.0 [jboner]
* | | | 4caa81b 2009-09-30 | moved the STM Ref to correct package [jboner]
* | | | 8899efd 2009-09-24 | adapted tests to the new STM and tx datastructures [jboner]
* | | |   99ba8ac 2009-09-23 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
|\ \ \ \  
| |/ / /  
| * | | f927c67 2009-09-22 | Switched to Shade, upgraded Atmosphere, synched libs [Viktor Klang]
| * | | 750bc7f 2009-09-21 | Added scala-json to the embedded repo [Viktor Klang]
* | | | c895ab0 2009-09-23 | added camel tests [jboner]
* | | | 08a914f 2009-09-23 | added @inittransactionalstate [jboner]
* | | | cdd8a35 2009-09-23 | added init tx state hook for active objects, rewrote mongodb test [jboner]
* | | | 6cc3d87 2009-09-18 | fixed mongodb test issues [jboner]
* | | |   4b1f736 2009-09-17 | merged multiverse STM rewrite with master [jboner]
|\ \ \ \  
| |/ / /  
| | / /   
| |/ /    
|/| |     
| * | 42ba426 2009-09-12 | Changed title to Akka Transactors [jboner]
| |/  
| * be2b9a4 2009-09-11 | commented the persistence tests [jboner]
| *   4970447 2009-09-11 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\  
| | * 4788188 2009-09-07 | Added HTTP Security and samples [Viktor Klang]
| | * 7b9ffcc 2009-09-07 | Moved Scheduler to actors [Viktor Klang]
| * | adaab72 2009-09-11 | fixed screwed up merge [jboner]
| |/  
* | 2998fa4 2009-09-17 | added monadic ops to TransactionalRef, fixed bug with nested txs [jboner]
* | 3be1939 2009-09-13 | added new multiverse managed reference [jboner]
* | da0ce0a 2009-09-10 | finalized persistence refactoring; more performant, richer API and using new STM based on Multiverse [jboner]
* | dfc08f5 2009-09-10 | rewrote the persistent storage with support for unit-of-work and new multiverse stm [jboner]
* | e4a4451 2009-09-04 | deleted old project files [jboner]
* | 6702a5f 2009-09-04 | deleted old project files [jboner]
* |   89aca17 2009-09-04 | merged multiverse branch with upstream [jboner]
|\ \  
| * | 9193822 2009-09-04 | updated changes.xml [jboner]
| |/  
| * 46cff1a 2009-09-04 | adapted fun tests to new module layout [jboner]
| * b865a84 2009-09-04 | removed akka project files [jboner]
| *   9689d09 2009-09-04 | merged module refactoring with master [jboner]
| |\  
| | *   eeb74b0 2009-09-04 | merged modules refactoring with master [jboner]
| | |\  
| | | * c1a90ab 2009-09-03 | kicked out twitter json in favor of sjson [jboner]
| | | * f93e797 2009-09-03 | kicked out twitter json in favor of sjson [jboner]
| | * |   1c2121c 2009-09-04 | merged module refactoring with master [jboner]
| | |\ \  
| | | |/  
| | * | 904cdcf 2009-09-03 | modified .gitignore [jboner]
| | * | 6015b09 2009-09-03 | 3:d iteration of modularization (all but fun tests done) [jboner]
| | * | 91ad702 2009-09-02 | 2:nd iteration of modularization [jboner]
| | * | ab66370 2009-09-01 | splitted kernel up in core + many sub modules [jboner]
| * | | 79ea2c4 2009-09-02 | removed idea project files [jboner]
| | |/  
| |/|   
| * | 6a5fbc4 2009-09-02 | added more sophisticated error handling to AMQP module + better examples [jboner]
| * | b9941f5 2009-09-02 | added sample session [jboner]
| * | b8d794e 2009-09-02 | added supervision for message consumers + added clean cancellation and shutdown of message consumers [jboner]
| * | 71283af 2009-09-02 | made messages routing-aware + added supervision of Client and Endpoint instances + added pre/post restart hooks that does disconnect/reconnect [jboner]
| * | 61e5f42 2009-09-01 | added optional ShutdownListener to AMQP client and endpoint [jboner]
| * | d3374f4 2009-09-01 | enhanced AMQP impl with error handling and more... [jboner]
| * | a129681 2009-08-31 | fixed guiceyfruit pom problem [Jonas Boner]
| |/  
| * deeaa92 2009-08-28 | created persistent and in-memory versions of all samples [jboner]
| *   931b1c7 2009-08-28 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\  
| | * 050083a 2009-08-28 | new scala json serialization library and integration with MongoStorage [debasishg]
| * | 834d443 2009-08-28 | wrong version in pom [jboner]
| |/  
| * 4513b1b 2009-08-27 | cleaned up example session [jboner]
| * a9ad5b8 2009-08-27 | init commit of AMQP module [jboner]
| * 981166c 2009-08-27 | added RabbitMQ 0.9.1 to embedded repo [jboner]
| * 3e5132e 2009-08-27 | init commit of AMQP module [jboner]
| * 01258e2 2009-08-23 | removed idea files from git [jboner]
| * bc7b9dd 2009-08-21 | parameterized all spawn/start/link methods + enhanced maintanance scripts [jboner]
| * d48afd0 2009-08-20 | added enforcer plugin to enforce Java 1.6 [jboner]
| * 07eecc5 2009-08-20 | removed monitoring, statistics and management [jboner]
| * 5d41b79 2009-08-19 | removed Cassandra startup procedure [jboner]
| * c1fa2e8 2009-08-18 | removed redundant test from lift sample [Timothy Perrett]
| * 6b20b9c 2009-08-17 | added lift header [jboner]
| * d138560 2009-08-17 | added scheduler test [jboner]
| * 69aeb2a 2009-08-17 | added generic actor scheduler [jboner]
| * fc39435 2009-08-17 | bumped up the version number of samples to 0.6 [jboner]
| * b94e602 2009-08-17 | updated cassandra conf to 0.4 format [jboner]
* | 09231f0 2009-08-20 | added more tracing to stm [jboner]
* | 540618e 2009-08-16 | mid multiverse bug tracing [jboner]
* |   ae9c93d 2009-08-16 | merge with multiverse stm branch [jboner]
|\ \  
| |/  
|/|   
| * 8e55322 2009-08-15 | multiverse stm for in-memory datastructures done (with one bug left to do) [Jonas Boner]
| *   ce02e9f 2009-08-14 | merged with upstream [Jonas Boner]
| |\  
| * | 2ade75b 2009-08-14 | added to changes.xml [Jonas Boner]
| * | 9bfa994 2009-08-04 | Beginning of Multiverse STM integration [Jonas Boner]
| * | 39e0817 2009-08-03 | Added Multiverse STM to embedded repo [U-GONZ\jboner]
* | | 159abff 2009-08-15 | Fixing trunk [Viktor Klang]
* | |   5e4a939 2009-08-15 | Merge branch 'master' of git@github.com:jboner/akka [Viktor Klang]
|\ \ \  
| * | | 8adf071 2009-08-15 | added testcase for persistent actor based on MongoDB [debasishg]
* | | | bdb71d2 2009-08-15 | Fix trunk [Viktor Klang]
|/ / /  
* | |   05908f9 2009-08-14 | Merge branch 'master' of git@github.com:jboner/akka [debasishg]
|\ \ \  
| * | | 0afbdb4 2009-08-14 | renamed jersey package to rest [Jonas Boner]
| * | | 5a6ea76 2009-08-14 | version is now 0.6 [Jonas Boner]
| * | | 4faeab9 2009-08-14 | added changes to changes.xml [Jonas Boner]
| * | | 59f3113 2009-08-14 | removed buildfile and makefiles [Jonas Boner]
| * | | e125ce7 2009-08-14 | kernel is now started with "java -jar dist/akka-0.6.jar", deleted ./lib and ./bin [Jonas Boner]
| | |/  
| |/|   
| * |   e6db95b 2009-08-14 | restructured distribution and maven files, removed unused jars, added bunch of maven plugins, added ActorRegistry, using real AOP aspects for proxies [Jonas Boner]
| |\ \  
| * | | d3c62e4 2009-08-14 | restructured distribution and maven files, removed unused jars, added bunch of maven plugins, added ActorRegistry, using real AOP aspects for proxies [Jonas Boner]
| * | | e3de827 2009-08-12 | changed cassandra name to avoid name clash with user repo [Jonas Boner]
| * | | 265df3d 2009-08-12 | restructured maven modules, removed unused jars [Jonas Boner]
| * | |   16a7962 2009-08-12 | merge master [Jonas Boner]
| |\ \ \  
| * | | | 6458837 2009-08-12 | added MBean for thread pool management + added a REST statistics service [Jonas Boner]
| * | | | 43e43a2 2009-08-11 | added windows bat startup script [Jonas Boner]
| * | | | 9d2aaeb 2009-08-11 | implemented statistics recording with JMX and REST APIs (based on scala-stats) [Jonas Boner]
| * | | | 8316c90 2009-08-04 | added scala-stats jar [Jonas Boner]
* | | | | 73bc685 2009-08-14 | added Ref semantics to MongoStorage + refactoring for commonality + added test cases [debasishg]
| |_|/ /  
|/| | |   
* | | | cee665b 2009-08-13 | externalized MongoDB configurations in akka-reference.conf [debasishg]
* | | | 7eeede9 2009-08-13 | added remove functions for Map storage + added test cases + made getStorageRangeFor same in semantics as Cassandra impl [debasishg]
* | | | b7ef738 2009-08-13 | added more test cases for MongoStorage and fixed bug in getMapStorageRangeFor [debasishg]
* | | | cbb1dc1 2009-08-12 | testcase for MongoStorage : needs a running Mongo instance [debasishg]
* | | | b4bb7a0 2009-08-12 | wip: adding storage for MongoDB and refactoring common storage logic into template methods [debasishg]
* | | | c6e2451 2009-08-12 | added mongo jar to embedded repo and modified kernel/pom [debasishg]
| |/ /  
|/| |   
* | | f514ad9 2009-08-12 | Added it in /lib/ as well [Viktor Klang]
* | | 79b1b39 2009-08-11 | Updated Cassidy jar [Viktor Klang]
* | | 21ec8dd 2009-08-11 | updated cassandra jar [jboner]
* | | 4276f5c 2009-08-11 | disabled persistence tests, since req a running Cassandra instance [jboner]
* | | 6aac260 2009-08-11 | fixed sample after cassandra map generelization [jboner]
* | |   1bd2abf 2009-08-11 | Merge branch 'master' of git@github.com:jboner/akka into cassadra_rewrite [jboner]
|\ \ \  
| * | | 7419c86 2009-08-05 | Updated Lift / Akka Sample [Timothy Perrett]
| * | | a0e6b53 2009-08-05 | removed tmp file [jboner]
| * | | a0686ed 2009-08-05 | added web.xml to lift sample [jboner]
* | | | 4232278 2009-08-11 | CassandraStorage is now working with external Cassandra cluster + added CassandraSession for pooled client connections and a nice Scala/Java API [jboner]
* | | | 59fa40b 2009-08-05 | minor edits [jboner]
* | | |   e11fa7b 2009-08-05 | merge with origin cassandra branch [jboner]
|\ \ \ \  
| |/ / /  
|/| | |   
| * | | 31c48dd 2009-08-03 | mid cassandra rewrite [Jonas Boner]
| * | | 32ef59c 2009-08-02 | mid cassandra rewrite [Jonas Boner]
* | | | ef991ba 2009-08-05 | rearranged samples-lift module dir structure [jboner]
* | | | b0e70ab 2009-08-05 | added support for running akka as part of Lift app in Jetty, made akka web app aware, added sample module [jboner]
* | | | 720736d 2009-08-05 | screwed up commit [jboner]
* | | | 62411d1 2009-08-05 | added support for running akka as part of Lift app in Jetty, made akka web app aware, added sample module [jboner]
* | | | 23b1313 2009-08-05 | added support for running akka as part of Lift app in Jetty, made akka web app aware, added sample module [jboner]
| |/ /  
|/| |   
* | | ece9539 2009-08-04 | fixed pom problem [Jonas Boner]
| |/  
|/|   
* |   e18a3a6 2009-08-02 | merge with master [Jonas Boner]
|\ \  
| * | e200d7e 2009-08-02 | minor reformattings [jboner]
| * |   873d5b6 2009-08-02 | merged comet stuff from viktorklang [jboner]
| |\ \  
| | * | b8fe12c 2009-08-01 | scalacount should work now. [Viktor Klang]
| * | | 019a25f 2009-08-02 | added comet stuff in deploy/root [jboner]
| * | | bc2937f 2009-08-02 | adde lib to start script [jboner]
| * | | b3d15d3 2009-08-01 | added jersey 1.1.1 and atmosphere 0.3 jars [jboner]
| * | | 5ac4d90 2009-08-01 | added new protobuf lib to start script + code formatting [jboner]
| * | | a388783 2009-07-31 | removed akka jars from deploy directory [jboner]
| * | | c7fb4cd 2009-07-31 | removed akka jars from lib directory [jboner]
| * | |   4958166 2009-07-31 | merged in jersey-scala and atmosphere support [jboner]
| |\ \ \  
| | |/ /  
| | * | 352d4b6 2009-07-29 | Switched to JerseyBroadcaster [Viktor Klang]
| | * | daeae68 2009-07-29 | Sample cleanup [Viktor Klang]
| | * | 8dd626f 2009-07-29 | Comet support added. [Viktor Klang]
| | * | 3adb704 2009-07-29 | tweaks... [Viktor Klang]
| | * | aad4a54 2009-07-28 | Almost there... [Viktor Klang]
| | * | 0e4a97f 2009-07-27 | Added Atmosphere chat example. DYNAMITE VERSION, MIGHT NOT WORK [Viktor Klang]
| | * | 6b02d12 2009-07-27 | removed comments [Viktor Klang]
| | * | 6ae9a3e 2009-07-27 | Atmosphere anyone? [Viktor Klang]
| | * | 6fcf2a2 2009-07-27 | Cleaned up Atmosphere integration but JAXB classloader problem persists... [Viktor Klang]
| | * | 3e4d6f3 2009-07-26 | removed junk [Viktor Klang]
| | * | f7bfb06 2009-07-26 | Atmosphere almost integrated, ClassLoader issue. [Viktor Klang]
| | * | 13bc651 2009-07-25 | Experimenting trying to get Lift views to work. (And they don't) [Viktor Klang]
| | * | 33b0303 2009-07-25 | Added support for Scala XML literals (jersey-scala), updated the scala sample service... [Viktor Klang]
| * | | a4c61da 2009-07-31 | added images for wiki [jboner]
| * | |   8beb562 2009-07-31 | Merge branch 'master' of git@github.com:jboner/akka [jboner]
| |\ \ \  
| * | | | 8b3a31e 2009-07-28 | concurrent mode is now per-dispatcher [jboner]
| * | | | 0ff677f 2009-07-28 | added protobuf to storage serialization [jboner]
* | | | | c2c2aab 2009-08-02 | rewrote README [Jonas Boner]
| |_|_|/  
|/| | |   
* | | | 136cb4e 2009-08-02 | checking for AKKA_HOME at boot + added Cassidy [Jonas Boner]
| |/ /  
|/| |   
* | | 9bb152d 2009-07-29 | fixed wrong path in embedded repository [Jonas Boner]
|/ /  
* | 6d6d815 2009-07-28 | fixed race bug in supervisor:Exit(..) handling [jboner]
* | b26c3fa 2009-07-28 | added generated protocol buffer test file [jboner]
* | a8f1861 2009-07-28 | added missing methods to JSON serializers [jboner]
* | 0eb577b 2009-07-28 | added Protobuf serialization of user messages, detects protocol serialization transparently [jboner]
* | 5b56bd0 2009-07-28 | fixed performance problem in dispatcher [jboner]
* |   bf50299 2009-07-27 | Merge branch 'master' of git://github.com/sergiob/akka [jboner]
|\ \  
| * | a6c8382 2009-07-25 | EventBasedThreadPoolDispatcherTest now fails due to dispatcher bug. [Sergio Bossa]
| |/  
* | 34b001a 2009-07-27 | added protobuf, jackson, sbinary, scala-json to embedded repository [jboner]
* | 7473afd 2009-07-27 | completed scala binary serialization' [jboner]
* |   2aca074 2009-07-24 | merged in protocol branch [jboner]
|\ \  
| |/  
|/|   
| * f016ed4 2009-07-24 | fixed outstanding remoting bugs and issues [jboner]
| * 1570fc6 2009-07-23 | added deploy dir to .gitignore [jboner]
| * 4878c9f 2009-07-23 | based remoting protocol Protobuf + added SBinary, Scala-JSON, Java-JSON and Protobuf serialization traits and protocols for remote and storage usage [jboner]
| * a3fac43 2009-07-21 | added scala-json lib [jboner]
| * f26110e 2009-07-18 | completed protobuf protocol for remoting [jboner]
| * a4d22af 2009-07-13 | mid hacking of protobuf nio protocol [jboner]
* | 6ccba1f 2009-07-18 | updated jars after merge with viktorklang [jboner]
* | 3e83d70 2009-07-15 | Removed unused repository [Viktor Klang]
* | 1895384 2009-07-15 | Fixed dependencies [Viktor Klang]
* | fa0c3c3 2009-07-13 | added license to readme (v0.5) [jboner]
* | 0265fcb 2009-07-13 | added apache 2 license [jboner]
* | 13b8c98 2009-07-13 | added distribution link to readme [jboner]
|/  
* 22bd4f5 2009-07-12 | added configurator trait [jboner]
* 27355ec 2009-07-12 | added scala and java sample modules + jackson jars [jboner]
* 95d598f 2009-07-12 | clean up and stabilization, getting ready for M1 [jboner]
* 6a65c67 2009-07-07 | changed oneway to be defined by void in AO + added restart callback def in config [Jonas Boner]
* ff96904 2009-07-06 | fixed last stm issues and failing tests + added new thread-based dispatcher (plus test) [Jonas Boner]
* 3830aed 2009-07-04 | implemented waiting for pending transactions to complete before aborting + config [Jonas Boner]
* d75d769 2009-07-04 | fixed async bug in active object + added AllTests for scala tests [Jonas Boner]
* 800f3bc 2009-07-03 | fixed most remaining failing tests for persistence [Jonas Boner]
* 011aee4 2009-07-03 | added new cassandra jar [Jonas Boner]
* 83ada02 2009-07-03 | added configuration system based on Configgy, now JMX enabled + fixed a couple of bugs [Jonas Boner]
* c1b6740 2009-07-03 | fixed bootstrap [Jonas Boner]
* afe3282 2009-07-02 | added netty jar [Jonas Boner]
* 35e3d97 2009-07-02 | various code clean up + fixed kernel startup script [Jonas Boner]
* 5c99b4e 2009-07-02 | added prerestart and postrestart annotations and hooks into the supervisor fault handler for active objects [Jonas Boner]
* 45bd6eb 2009-07-02 | added configuration for remote active objects and services [Jonas Boner]
* a4f1092 2009-07-01 | fixed some major bugs + wrote thread pool builder and dispatcher config + various spawnLink variations on Actor [Jonas Boner]
* 2cfeda0 2009-06-30 | fixed bugs regarding oneway transaction managament [Jonas Boner]
* 6359920 2009-06-29 | complete refactoring of transaction and transactional item management + removed duplicate tx management in ActiveObject [Jonas Boner]
* 7083737 2009-06-29 | iteration 1 of nested transactional components [Jonas Boner]
* 0a915ea 2009-06-29 | transactional actors and remote actors implemented [Jonas Boner]
* a585e0c 2009-06-25 | finished remote actors (with tests) plus half-baked distributed transactions (not complete) [Jonas Boner]
* 10a0c16 2009-06-25 | added remote active objects configuration + remote tx semantics [Jonas Boner]
* 47abc14 2009-06-24 | completed remote active objects (1:st iteration) - left todo are: TX semantics, supervision and remote references + tests [Jonas Boner]
* 8ff45da 2009-06-22 | new factory for transactional state [Jonas Boner]
* 93f712e 2009-06-22 | completed new actor impl with supervision and STM [Jonas Boner]
* de846d4 2009-06-21 | completed new actor impl with link/unlink trapExit etc. all tests pass + reorganized package structure [Jonas Boner]
* be2aa08 2009-06-11 | renamed dispatchers to more suitable names [Jonas Boner]
* 795c7b3 2009-06-11 | finished STM and persistence test for Ref, Vector, Map + implemented STM for Ref [Jonas Boner]
*   f3ac665 2009-06-10 | fixed hell after merge [Jonas Boner]
|\  
| * 7f60a4a 2009-06-03 | finished actor library together with tests [Jonas Boner]
* | ac52556 2009-06-10 | Swapped out Scala Actors to a reactor based impl (still restart and linking to do) + finalized transactional persisted cassandra based vector (ref to go) [Jonas Boner]
* | 167b724 2009-06-05 | fixed TX Vector and TX Ref plus added tests + rewrote Reactor impl + added custom Actor impl(currently not used though) [Jonas Boner]
|/  
* 74bd8de 2009-05-25 | project cleanup [Jonas Boner]
* 28c8a0d 2009-05-25 | upgraded to Scala 2.7.4 [Jonas Boner]
* 9235b0e 2009-05-25 | renamed api-java to fun-test-java + upgrade guiceyfruit to 2.0 [Jonas Boner]
* 8c11f04 2009-05-25 | added invocation object + equals/hashCode methods [Jonas Boner]
* eadd316 2009-05-25 | added reactor implementation in place for Scala actors [Jonas Boner]
* 8bc4077 2009-05-24 | cleanup and refactoring of active object code [Jonas Boner]
* bbec315 2009-05-23 | fixed final issues with AW proxy integration and remaining tests [Jonas Boner]
* e059100 2009-05-20 | added custom managed actor scheduler [Jonas Boner]
* 0ad6151 2009-05-20 | switched from DPs to AW proxy [Jonas Boner]
* 33a333e 2009-05-18 | added AW [Jonas Boner]
* b38ac06 2009-05-18 | mid jax-rs impl [Jonas Boner]
* 8c8ba29 2009-05-16 | added interface for active object configurator, now with only guice impl class + added default servlet hooking into akka rest support via jersey [Jonas Boner]
* 0243a60 2009-05-15 | first step towards jax-rs support, first tests passing [Jonas Boner]
* b1900b0 2009-05-15 | first step towards jax-rs support, first tests passing [Jonas Boner]
* 9349bc3 2009-05-13 | fixed cassandra persistenc STM tests + added generic Map and Seq traits to Transactional datastructures [Jonas Boner]
* 1a06a67 2009-05-13 | fixed broken eclipse project files [Jonas Boner]
* d470aee 2009-05-13 | fixed bug STM bug, in-mem tests now pass [Jonas Boner]
* 4ad378b 2009-05-11 | fixed test [Jonas Boner]
* b602a86 2009-05-11 | init impl of camel bean:actor routing [Jonas Boner]
*   d4eb763 2009-05-09 | fixed merging [Jonas Boner]
|\  
| * ffdda0a 2009-05-09 | commented out camel stuff for now [Jonas Boner]
| * b1d9181 2009-05-09 | mid camel impl [Jonas Boner]
| * 46ede93 2009-05-09 | initial camel integration [Jonas Boner]
| * c5062e5 2009-05-09 | initial camel integration [Jonas Boner]
* | d37d203 2009-05-09 | added kernel iml [Jonas Boner]
* | d9b904f 2009-05-01 | removed unused jars [Jonas Boner]
|/  
* a153ece 2009-05-01 | upgraded to latest version of Cassandra, some API changes [Jonas Boner]
* 49f433b 2009-04-28 | fixed tests to work with new transactional items [Jonas Boner]
* 21e7a6f 2009-04-27 | improved javadoc documentation [Jonas Boner]
* 7dec0a7 2009-04-27 | completed cassandra read/write (and bench) + added transactional vector and ref + cleaned up transactional state hierarchy + rewrote tx state wiring [Jonas Boner]
* e9f7162 2009-04-25 | added .manager to .gitignore [Jonas Boner]
* ba455ec 2009-04-25 | added .manager by mistake, removing [Jonas Boner]
* 75a9c34 2009-04-25 | added eclipse project files [Jonas Boner]
* 1f39c6a 2009-04-25 | 1. buildr now works on mac. 2. rudimentary cassandra persistence for actors. [Jonas Boner]
* fed6def 2009-04-19 | readded maven pom.xml files [Jonas Boner]
* 88c891a 2009-04-19 | initial draft of cassandra storage [Jonas Boner]
* cd1ef83 2009-04-09 | second iteration of STM done, simple tests work now [Jonas Boner]
*   2639d14 2009-04-06 | Merge commit 'origin/master' into dev [Jonas Boner]
|\  
| * 8586110 2009-04-06 | rewrote the state management, tx system still to rewrite [Jonas Boner]
* | ff047e1 2009-04-05 | deleted license file [Jonas Boner]
* | 0d949cf 2009-04-05 | added license [Jonas Boner]
|/  
* 3e703a5 2009-04-04 | first draft of MVCC using Clojure's Map as state holder, still one test failing though [Jonas Boner]
* a453930 2009-04-04 | made netbeans ant build my default build (until buildr works) [Jonas Boner]
* be6bd4a 2009-04-04 | added new scalatest + junit to libs [Jonas Boner]
* 8488b1c 2009-04-03 | added state class with snapshot and unit of work [Jonas Boner]
* 7abd917 2009-04-03 | added immutable persistent Map and Vector [Jonas Boner]
* 0f6ca61 2009-04-03 | netbeans files [Jonas Boner]
* 2195c80 2009-03-26 | modded .gitignore [Jonas Boner]
* 5d5f547 2009-03-26 | added jersey akka componentprovider [Jonas Boner]
* 1f9801f 2009-03-26 | added netbeans project files [Jonas Boner]
* 4a47fc5 2009-03-26 | added some new annotations [Jonas Boner]
* 123fa5b 2009-03-26 | fixed misc bugs and completed first iteration of transactions [Jonas Boner]
* 6cb38f6 2009-03-23 | serializer to do actor cloning [Jonas Boner]
* 9d4b4ef 2009-03-23 | initial draft of transactional actors [Jonas Boner]
* a8700dd 2009-03-23 | initial draft of transactional actors [Jonas Boner]
* bcb850a 2009-03-22 | changed com.scalablesolutions to se.scalablesolutions [Jonas Boner]
* 75ba938 2009-03-22 | removed supervisor + changed com. to se. [Jonas Boner]
* 550d910 2009-03-22 | moved supervisor code into kernel [Jonas Boner]
* be82023 2009-03-22 | added mina scala [Jonas Boner]
* 8d8e867 2009-03-15 | added scala mina sample [Jonas Boner]
* eaadcbd 2009-03-15 | added db module [Jonas Boner]
* 40bac5d 2009-03-15 | moved lib [Jonas Boner]
* 18794aa 2009-03-15 | added runner to buildr [Jonas Boner]
* c3bbf79 2009-03-12 | adding dataflow concurrency support [Jonas Boner]
* e048d92 2009-03-12 | minor reformatting [Jonas Boner]
* 4a79888 2009-03-12 | fixed env path bug preventing startup of dist, now working fine starting up voldemort [Jonas Boner]
* c809eac 2009-03-12 | merged all buildr files to one top level, now working really nice [Jonas Boner]
* bcc7d5f 2009-03-12 | backup of supervisor tests that are not yet ported to specs [Jonas Boner]
* d6b43b9 2009-03-12 | added top level buildr file for packaging a dist [Jonas Boner]
* 50e8d36 2009-03-12 | modified gitignore [Jonas Boner]
* b1beba4 2009-03-12 | added buildr file [Jonas Boner]
* 1a0e277 2009-03-12 | cleaned up buildr config [Jonas Boner]
* cd9ef46 2009-03-12 | added start script [Jonas Boner]
* 66468ff 2009-03-12 | added Buildr buildfiles [Jonas Boner]
* 0d06b0c 2009-03-12 | renamed test files to end with Specs [Jonas Boner]
* d947783 2009-03-12 | minor changes to pom [Jonas Boner]
* 975cdba 2009-03-12 | minor changes' [Jonas Boner]
* 41b3221 2009-03-12 | changed voldemort settings to single server [Jonas Boner]
* d50671d 2009-03-10 | removed unused jars [Jonas Boner]
* d29e4b1 2009-03-10 | removed unused jars [Jonas Boner]
* 25aac2b 2009-03-10 | removed unused jars [Jonas Boner]
* bbe2d5e 2009-03-10 | removed unused jars [Jonas Boner]
* 6d8cbee 2009-03-10 | reM removed tmp file [Jonas Boner]
* d9a5762 2009-03-10 | added libs for kernel, needed for Boot [Jonas Boner]
* 1ce8c51 2009-03-10 | added bootstrapper [Jonas Boner]
* db43727 2009-03-10 | added config files for voldemort and akka configgy stuff [Jonas Boner]
* 240261e 2009-03-10 | added the supervisor module to akka [Jonas Boner]
* 606f34e 2009-03-10 | added util-java module with annotations [Jonas Boner]
* 23c1040 2009-03-10 | changed package imports for supervisor [Jonas Boner]
* 7133c42 2009-02-19 | added basic JAX-RS, Jersey and Grizzly HTTP server support [Jonas Boner]
* cee06a2 2009-02-17 | added intellij files [Jonas Boner]
* 755fc6d 2009-02-17 | added intellij files [Jonas Boner]
* a2e3406 2009-02-17 | completed Guice API for ActiveObjects [Jonas Boner]
* 42cc47b 2009-02-16 | initial guice integration started [Jonas Boner]
* d01a293 2009-02-16 | added Java compat API for defining up ActiveObjects [Jonas Boner]
* 0a31ad7 2009-02-16 | init project setup [Jonas Boner]