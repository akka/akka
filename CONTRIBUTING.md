# Contributing to Akka HTTP

In case of questions about the contribution process or for discussion of specific issues please visit the [akka/dev gitter chat](https://gitter.im/akka/dev).

# Navigating around the project & codebase

## Branches summary

Depending on which version (or sometimes module) you want to work on, you should target a specific branch as explained below:

* `master` – active development branch of akka-http 10.0-SNAPSHOT

## Tags

Akka-http uses tags to categorise issues into groups or mark their phase in development.

Most notably many tags start `t:` prefix (as in `topic:`), which categorises issues in terms of which module they relate to. Examples are:

- [t:http:core](https://github.com/akka/akka-http/issues?q=is%3Aopen+is%3Aissue+label%3At%3Ahttp%3Acore)
- [t:server:dsl](https://github.com/akka/akka-http/issues?q=is%3Aopen+is%3Aissue+label%3At%3Ahttp%3Aserver%3Adsl)
- see [all tags here](https://github.com/akka/akka-http/labels)

In general *all issues are open for anyone working on them*, however if you're new to the project and looking for an issue
that will be accepted and likely is a nice one to get started you should check out the following tags:

- [community](https://github.com/akka/akka-http/labels/community) - which identifies issues that the core team will likely not have time to work on, or the issue is a nice entry level ticket. If you're not sure how to solve a ticket but would like to work on it feel free to ask in the issue about clarification or tips.
- [nice-to-have (low-priority)](https://github.com/akka/akka-http/labels/nice-to-have%20%28low-prio%29) - are tasks which make sense, however are not very high priority (in face of other very high priority issues). If you see something interesting in this list, a contribution would be really wonderful!

Another group of tickets are those which start from a number. They're used to signal in what phase of development an issue is:

- [0 - new](https://github.com/akka/akka-http/labels/0%20-%20new) - is assigned when a ticket is unclear on it's purpose or if it is valid or not. Sometimes the additional tag `discuss` is used to mark such tickets, if they propose large scale changed and need more discussion before moving into triaged (or being closed as invalid)
- [1 - triaged](https://github.com/akka/akka-http/labels/1%20-%20triaged) - roughly speaking means "this ticket makes sense". Triaged tickets are safe to pick up for contributing in terms of likeliness of a patch for it being accepted. It is not recommended to start working on a ticket that is not triaged.
- [2 - pick next](https://github.com/akka/akka-http/labels/2%20-%20pick%20next) - used to mark issues which are next up in the queue to be worked on. Sometimes it's also used to mark which PRs are expected to be reviewed/merged for the next release. The tag is non-binding, and mostly used as organisational helper.
- [3 - in progress](https://github.com/akka/akka-http/labels/3%20-%20in%20progress) - means someone is working on this ticket. If you see a ticket that has the tag, however seems inactive, it could have been an omission with removing the tag, feel free to ping the ticket then if it's still being worked on.

The last group of special tags indicate specific states a ticket is in:

- [bug](https://github.com/akka/akka-http/labels/bug) - bugs take priority in being fixed above features. The core team dedicates a number of days to working on bugs each sprint. Bugs which have reproducers are also great for community contributions as they're well isolated. Sometimes we're not as lucky to have reproducers though, then a bugfix should also include a test reproducing the original error along with the fix.
- [failed](https://github.com/akka/akka-http/labels/failed) - tickets indicate a Jenkins failure (for example from a nightly build). These tickets usually start with the `FAILED: ...` message, and include a stacktrace + link to the Jenkins failure. The tickets are collected and worked on with priority to keep the build stable and healthy. Often times it may be simple timeout issues (Jenkins boxes are slow), though sometimes real bugs are discovered this way.

Pull Request validation states:

- `validating => [tested | needs-attention]` - signify pull request validation status

# Akka-http contributing guidelines

These guidelines apply to all Akka projects, by which we mean both the `akka/akka` repository,
as well as any plugins or additional repos located under the Akka GitHub organisation, e.g. `akka/akka-http` and others.

These guidelines are meant to be a living document that should be changed and adapted as needed.
We encourage changes that make it easier to achieve our goals in an efficient way.

## General Workflow

The below steps are how to get a patch into a main development branch (e.g. `master`). 
The steps are exactly the same for everyone involved in the project (be it core team, or first time contributor).

1. Make sure an issue exists in the [issue tracker](https://github.com/akka/akka-http/issues) for the work you want to contribute. 
   - If there is no ticket for it, [create one](https://github.com/akka/akka-http/issues/new) first.
1. [Fork the project](https://github.com/akka/akka-http#fork-destination-box) on GitHub. You'll need to create a feature-branch for your work on your fork, as this way you'll be able to submit a PullRequest against the mainline Akka-http.
1. Create a branch on your fork and work on the feature. For example: `git checkout -b wip-custom-headers-akka-http`
   - Please make sure to follow the general quality guidelines (specified below) when developing your patch.
   - Please write additional tests covering your feature and adjust existing ones if needed before submitting your Pull Request. The `validatePullRequest` sbt task ([explained below](#the-validatepullrequest-task)) may come in handy to verify your changes are correct.
1. Once your feature is complete, prepare the commit following our [Creating Commits And Writing Commit Messages](#creating-commits-and-writing-commit-messages). For example, a good commit message would be: `Adding compression support for Manifests #22222` (note the reference to the ticket it aimed to resolve).
1. Now it's finally time to [submit the Pull Request](https://help.github.com/articles/using-pull-requests)!
1. If you have not already done so, you will be asked by our CLA bot to [sign the Lightbend CLA](http://www.lightbend.com/contribute/cla) online CLA stands for Contributor License Agreement and is a way of protecting intellectual property disputes from harming the project.
1. If you're not already on the contributors white-list, the @akka-ci bot will ask `Can one of the repo owners verify this patch?`, to which a core member will reply by commenting `OK TO TEST`. This is just a sanity check to prevent malicious code from being run on the Jenkins cluster.
1. Now both committers and interested people will review your code. This process is to ensure the code we merge is of the best possible quality, and that no silly mistakes slip through. You're expected to follow-up these comments by adding new commits to the same branch. The commit messages of those commits can be more lose, for example: `Removed debugging using printline`, as they all will be squashed into one commit before merging into the main branch.
    - The community and team are really nice people, so don't be afraid to ask follow up questions if you didn't understand some comment, or would like to clarify how to continue with a given feature. We're here to help, so feel free to ask and discuss any kind of questions you might have during review!
1. After the review you should fix the issues as needed (pushing a new commit for new review etc.), iterating until the reviewers give their thumbs up–which is signalled usually by a comment saying `LGTM`, which means "Looks Good To Me". 
    - In general a PR is expected to get 2 LGTMs from the team before it is merged. If the PR is trivial, or under under special circumstances (such as most of the team being on vacation, a PR was very thoroughly reviewed/tested and surely is correct) one LGTM may be fine as well.
1. If the code change needs to be applied to other branches as well (for example a bugfix needing to be backported to a previous version), one of the team will either ask you to submit a PR with the same commit to the old branch, or do this for you.
   - Backport pull requests such as these are marked using the phrase`for validation` in the title to make the purpose clear in the pull request list. They can be merged once validation passes without additional review (if no conflicts).
1. Once everything is said and done, your Pull Request gets merged :tada: Your feature will be available with the next “earliest” release milestone (i.e. if back-ported so that it will be in release x.y.z, find the relevant milestone for that release). And of course you will be given credit for the fix in the release stats during the release's announcement. You've made it!

The TL;DR; of the above very precise workflow version is:

1. Fork akka-http
2. Hack and test on your feature (on a branch)
3. Submit a PR
4. Sign the CLA if necessary
4. Keep polishing it until received enough LGTM
5. Profit!

Note that the akka-http sbt project is not as large as the Akka one, so `sbt` should be able to run with less heap than with the Akka project. In case you need to increase the heap, this can be specified using a command line argument `sbt -mem 2048` or in the environment variable `SBT_OPTS` but then as a regular JVM memory flag, for example `SBT_OPTS=-Xmx2G`, on some platforms you can also edit the global defaults for sbt in `/usr/local/etc/sbtopts`.

## The `validatePullRequest` task

The Akka-http build includes a special task called `validatePullRequest` which investigates the changes made as well as dirty
(uncommitted changes) in your local working directory and figures out which projects are impacted by those changes,
then running tests only on those projects.

For example changing something in `akka-http-core` would cause tests to be run in all projects which depend on it
(e.g. `akka-http-tests`, `akka-http-marshallers-*`, `akka-docs` etc.).

To use the task simply type, and the output should include entries like shown below:

```
> validatePullRequest
[info] Diffing [HEAD] to determine changed modules in PR...
[info] Detected uncomitted changes in directories (including in dependency analysis): [akka-protobuf,project]
[info] Detected changes in directories: [akka-docs, project, akka-http-tests, akka-protobuf, akka-http-testkit, akka-http, akka-http-core, akka-stream]
```

By default changes are diffed with the `master` branch when working locally, if you want to validate against a different
target PR branch you can do so by setting the PR_TARGET_BRANCH environment variable for SBT:

```
PR_TARGET_BRANCH=origin/example sbt validatePullRequest
```

## Developing against Akka `master`

Since Akka HTTP is released separately to Akka "core" yet some features require changes in Akka itself, it is sometimes very useful
to be able to develop Akka HTTP with Akka's sources used directly instead of the binary dependency. You can check out the Akka 
repository and run sbt with `-Dakka.sources=$HOME/akka` to develop Akka HTTP with Akka as a source dependency instead of a binary one.

This allows simple and fast iterations on changes that would need to be intoduced in Akka to develop a feature in HTTP that would require those.

## Binary compatibility
Binary compatibility rules and guarantees are described in depth in the [Binary Compatibility Rules
](http://doc.akka.io/docs/akka/snapshot/common/binary-compatibility-rules.html) section of the documentation.

Akka-http uses MiMa (which is short for [Lightbend Migration Manager](https://github.com/typesafehub/migration-manager)) to
validate binary compatibility of incoming Pull Requests. If your PR fails due to binary compatibility issues, you may see 
an error like this:

```
[info] akka-stream: found 1 potential binary incompatibilities while checking against com.typesafe.akka:akka-stream_2.11:2.4.2  (filtered 222)
[error]  * method foldAsync(java.lang.Object,scala.Function2)akka.stream.scaladsl.FlowOps in trait akka.stream.scaladsl.FlowOps is present only in current version
[error]    filter with: ProblemFilters.exclude[ReversedMissingMethodProblem]("akka.stream.scaladsl.FlowOps.foldAsync")
```

In such situations it's good to consult with a core team member if the violation can be safely ignored (by adding the above snippet to the project's
`src/main/mima-filters`), or if it would indeed break binary compatibility.

Situations when it may be fine to ignore a MiMa issued warning include:

- if it is touching any class marked as `private[akka]`, `/** INTERNAL API*/` or similar markers
- if it is concerning internal classes (often recognisable by package names like `dungeon`, `impl`, `internal` etc.)
- if it is adding API to classes / traits which are only meant for extension by Akka itself, i.e. should not be extended by end-users
- other tricky situations


## Pull Request Requirements

For a Pull Request to be considered at all it has to meet these requirements:

1. Regardless if the code introduces new features or fixes bugs or regressions, it must have comprehensive tests.
1. The code must be well documented in the Lightbend's standard documentation format (see the ‘Documentation’ section below).
1. The commit messages must properly describe the changes, see further below.
1. All Lightbend projects must include Lightbend copyright notices.  Each project can choose between one of two approaches:

    1. All source files in the project must have a Lightbend copyright notice in the file header.
    1. The Notices file for the project includes the Lightbend copyright notice and no other files contain copyright notices.  See http://www.apache.org/legal/src-headers.html for instructions for managing this approach for copyrights.

    Akka-http uses the first choice, having copyright notices in every file header.


### Additional guidelines

Some additional guidelines regarding source code are:

- files should start with a ``Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>`` copyright header 
- keep the code [DRY](http://programmer.97things.oreilly.com/wiki/index.php/Don%27t_Repeat_Yourself)
- apply the [Boy Scout Rule](http://programmer.97things.oreilly.com/wiki/index.php/The_Boy_Scout_Rule) whenever you have the chance to
- Never delete or change existing copyright notices, just add additional info.  
- Do not use ``@author`` tags since it does not encourage [Collective Code Ownership](http://www.extremeprogramming.org/rules/collective.html).
  - Contributors , each project should make sure that the contributors gets the credit they deserve—in a text file or page on the project website and in the release notes etc.

If these requirements are not met then the code should **not** be merged into master, or even reviewed - regardless of how good or important it is. No exceptions.

Whether or not a pull request (or parts of it) shall be back- or forward-ported will be discussed on the pull request discussion page, it shall therefore not be part of the commit messages. If desired the intent can be expressed in the pull request description.

## Documentation

All documentation must abide by the following maxims:

- Example code should be run as part of an automated test suite.
- Version should be **programmatically** specifiable to the build.
- Generation should be **completely automated** and available for scripting.
- Artifacts that must be included in the Lightbend stack should be published to a maven “documentation” repository as documentation artifacts.

All documentation is preferred to be in Lightbend's standard documentation format [Paradox](https://github.com/lightbend/paradox).
The language used by Paradox is a super-set or Markdown which supports most Github Flavored Markdown extensions as well as additional directives to facilitate writing documentation for software projects.
Refer to its documentation to learn about the more advanced features it provides (including code etc).

To generate documentation you can:

```
> project docs
> paradox
```

The rendered documentation will be available under `docs/target/paradox/site/index.html`. 

### JavaDoc

Akka-http generates JavaDoc-style API documentation using the [genjavadoc](https://github.com/typesafehub/genjavadoc) sbt plugin, since the sources are written mostly in Scala.

Generating JavaDoc is not enabled by default, as it's not needed on day-to-day development as it's expected to just work.
If you'd like to check if you links and formatting looks good in JavaDoc (and not only in ScalaDoc), you can generate it by running:

```
sbt -Dakka.genjavadoc.enabled=true javaunidoc:doc
```

Which will generate JavaDoc style docs in `./target/javaunidoc/index.html`

## External Dependencies

All the external runtime dependencies for the project, including transitive dependencies, must have an open source license that is equal to, or compatible with, [Apache 2](http://www.apache.org/licenses/LICENSE-2.0).

This must be ensured by manually verifying the license for all the dependencies for the project:

1. Whenever a committer to the project changes a version of a dependency (including Scala) in the build file.
2. Whenever a committer to the project adds a new dependency.
3. Whenever a new release is cut (public or private for a customer).

Which licenses are compatible with Apache 2 are defined in [this doc](http://www.apache.org/legal/3party.html#category-a), where you can see that the licenses that are listed under ``Category A`` automatically compatible with Apache 2, while the ones listed under ``Category B`` needs additional action:

> Each license in this category requires some degree of [reciprocity](http://www.apache.org/legal/3party.html#define-reciprocal); therefore, additional action must be taken in order to minimize the chance that a user of an Apache product will create a derivative work of a reciprocally-licensed portion of an Apache product without being aware of the applicable requirements.

Each project must also create and maintain a list of all dependencies and their licenses, including all their transitive dependencies. This can be done either in the documentation or in the build file next to each dependency.

## Creating Commits And Writing Commit Messages

Follow these guidelines when creating public commits and writing commit messages.

1. If your work spans multiple local commits (for example; if you do safe point commits while working in a feature branch or work in a branch for a long time doing merges/rebases etc.) then please do not commit it all but rewrite the history by squashing the commits into a single big commit which you write a good commit message for (like discussed in the following sections). For more info read this article: [Git Workflow](http://sandofsky.com/blog/git-workflow.html). Every commit should be able to be used in isolation, cherry picked etc.

2. First line should be a descriptive sentence what the commit is doing, including the ticket number. It should be possible to fully understand what the commit does—but not necessarily how it does it—by just reading this single line. We follow the “imperative present tense” style for commit messages ([more info here](http://tbaggery.com/2008/04/19/a-note-about-git-commit-messages.html)).

   It is **not ok** to only list the ticket number, type "minor fix" or similar.
   If the commit is a small fix, then you are done. If not, go to 3.

3. Following the single line description should be a blank line followed by an enumerated list with the details of the commit.

4. You can request review by a specific team member  for your commit (depending on the degree of automation we reach, the list may change over time):
    * ``Review by @gituser`` - if you want to notify someone on the team. The others can, and are encouraged to participate.

Example:

    enable Travis CI #1

    * Details 1
    * Details 2
    * Details 3

## Pull request validation workflow details

Akka-http uses [Jenkins GitHub pull request builder plugin](https://wiki.jenkins-ci.org/display/JENKINS/GitHub+pull+request+builder+plugin)
that automatically merges the code, builds it, runs the tests and comments on the Pull Request in GitHub.

Upon a submission of a Pull Request the GitHub pull request builder plugin will post a following comment:

    Can one of the repo owners verify this patch?

This requires a member from a core team to start Pull Request validation process by posting comment consisting only of `OK TO TEST`.
From now on, whenever new commits are pushed to the Pull Request, a validation job will be automatically started and the results of the validation posted to the Pull Request.

A Pull Request validation job can be started manually by posting `PLS BUILD` comment on the Pull Request.

In order to speed up PR validation times, the Akka-http build contains a special sbt task called `validatePullRequest`,
which is smart enough to figure out which projects should be built if a PR only has changes in some parts of the project.
For example, if your PR only touches `akka-http-testkit`, no `akka-parsing` tests need to be run, however the task
will validate all projects that depend on `akka-http-testkit` (including samples).
Also, tests tagged as `PerformanceTest` and the likes of it are excluded from PR validation.

In order to force the `validatePullRequest` task to build the entire project, regardless of dependency analysis of a PRs
changes one can use the special `PLS BUILD ALL` command (typed in a comment on GitHub, on the Pull Request), which will cause
the validator to test all projects.

## Source style

### Scala style 

Akka-http uses [Scalariform](https://github.com/daniel-trinh/scalariform) to enforce some of the code style rules.

### Java style

Java code is currently not automatically reformatted by sbt (expecting to have a plugin to do this soon).
Thus we ask Java contributions to follow these simple guidelines:

- 2 spaces
- `{` on same line as method name
- in all other aspects, follow the [Oracle Java Style Guide](http://www.oracle.com/technetwork/java/codeconvtoc-136057.html)

### Preferred ways to use timeouts in tests

Avoid short test timeouts, since Jenkins server may GC heavily causing spurious test failures. GC pause or other hiccup of 2 seconds is common in our CI environment. Please note that usually giving a larger timeout *does not slow down the tests*, as in an `expectMessage` call for example it usually will complete quickly.

There is a number of ways timeouts can be defined in Akka tests. The following ways to use timeouts are recommended (in order of preference): 

* `remaining` is first choice (requires `within` block)
* `remainingOrDefault` is second choice
* `3.seconds` is third choice if not using testkit
* lower timeouts must come with a very good reason (e.g. Awaiting on a known to be "already completed" `Future`)

Special care should be given `expectNoMsg` calls, which indeed will wait the entire timeout before continuing, therefore a shorter timeout should be used in those, for example `200` or `300.millis`.

You can read up on remaining and friends in [TestKit.scala](https://github.com/akka/akka/blob/master/akka-testkit/src/main/scala/akka/testkit/TestKit.scala)

# Supporting infrastructure

## Continuous Integration

Each project should be configured to use a continuous integration (CI) tool (i.e. a build server à la Jenkins). 

Lightbend is sponsoring a [Jenkins server farm](https://jenkins.akka.io/), sometimes referred to as "the Lausanne cluster".
The cluster is made out of real bare-metal boxes, and maintained by the Akka team (and other very helpful people at Lightbend).

In addition to PR Validation the cluster is also used for nightly and performance test runs. 

## Related links

* [Akka Contributor License Agreement](http://www.lightbend.com/contribute/cla)
* [Akka Issue Tracker](http://doc.akka.io/docs/akka/current/project/issue-tracking.html)
* [Scalariform](https://github.com/daniel-trinh/scalariform)
