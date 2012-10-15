#Contributing to Akka#

##Infrastructure##

* [Akka Contributor License Agreement](http://www.typesafe.com/contribute/cla)
* [Akka Issue Tracker](http://doc.akka.io/docs/akka/current/project/issue-tracking.html)
* [Scalariform](https://github.com/mdr/scalariform)

# Typesafe Project & Developer Guidelines

These guidelines are meant to be a living document that should be changed and adapted as needed. We encourage changes that makes it easier to achieve our goals in an efficient way.

These guidelines mainly applies to Typesafe’s “mature” projects - not necessarily to projects of the type ‘collection of scripts’ etc.

## General Workflow

This is the process for committing code into master. There are of course exceptions to these rules, for example minor changes to comments and documentation, fixing a broken build etc.

1. Make sure you have signed the [Typesafe CLA](http://www.typesafe.com/contribute/cla), if not, sign it online.
2. Before starting to work on a feature or a fix, you have to make sure that:
    1. There is a ticket for your work in the project's issue tracker. If not, create it first.
    2. The ticket has been scheduled for the current milestone.
    3. The ticket is estimated by the team.
    4. The ticket have been discussed and prioritized by the team.
3. You should always perform your work in a Git feature branch. The branch should be given a descriptive name that explains its intent. Some teams also like adding the ticket number and/or the [GitHub](http://github.com) user ID to the branch name, these details is up to each of the individual teams.
4. When the feature or fix is completed you should open a [Pull Request](https://help.github.com/articles/using-pull-requests) on GitHub.
5. The Pull Request should be reviewed by other maintainers (as many as feasible/practical). Note that the maintainers can consist of outside contributors, both within and outside Typesafe. Outside contributors (for example from EPFL or independent committers) are encouraged to participate in the review process, it is not a closed process.
6. After the review you should fix the issues as needed (pushing a new commit for new review etc.), iterating until the reviewers give their thumbs up.
7. Once the code has passed review the Pull Request can be merged into the master branch.

## Pull Request Requirements

For a Pull Request to be considered at all it has to meet these requirements:

1. Live up to the current code standard:
   - Not violate [DRY](http://programmer.97things.oreilly.com/wiki/index.php/Don%27t_Repeat_Yourself).
   - [Boy Scout Rule](http://programmer.97things.oreilly.com/wiki/index.php/The_Boy_Scout_Rule) needs to have been applied.
2. Regardless if the code introduces new features or fixes bugs or regressions, it must have comprehensive tests.
3. The code must be well documented in the Typesafe's standard documentation format (see the ‘Documentation’ section below).

If these requirements are not met then the code should **not** be merged into master, or even reviewed - regardless of how good or important it is. No exceptions.

## Continuous Integration

Each project should be configured to use a continuous integration (CI) tool (i.e. a build server ala Jenkins). Typesafe has a Jenkins server farm that can be used. The CI tool should, on each push to master, build the **full** distribution and run **all** tests, and if something fails it should email out a notification with the failure report to the committer and the core team. The CI tool should also be used in conjunction with Typesafe’s Pull Request Validator (discussed below).

## Documentation

All documentation should be generated using the sbt-site-plugin, *or* publish artifacts to a repository that can be consumed by the typesafe stack.

All documentation must abide by the following maxims:

- Example code should be run as part of an automated test suite.
- Version should be **programmatically** specifiable to the build.
- Generation should be **completely automated** and available for scripting.
- Artifacts that must be included in the Typesafe Stack should be published to a maven “documentation” repository as documentation artifacts.

All documentation is preferred to be in Typesafe's standard documentation format [reStructuredText](http://doc.akka.io/docs/akka/snapshot/dev/documentation.html) compiled using Typesafe's customized [Sphinx](http://sphinx.pocoo.org/) based documentation generation system, which among other things allows all code in the documentation to be externalized into compiled files and imported into the documentation.

For more info, or for a starting point for new projects, look at the [Typesafe Documentation Sample project](https://github.com/typesafehub/doc-example).

For larger projects that have invested a lot of time and resources into their current documentation and samples scheme (like for example Play), it is understandable that it will take some time to migrate to this new model. In these cases someone from the project needs to take the responsibility of manual QA and verifier for the documentation and samples.

## Work In Progress

It is ok to work on a public feature branch in the GitHub repository. Something that can sometimes be useful for early feedback etc. If so then it is preferable to name the branch accordingly. This can be done by either prefix the name with ``wip-`` as in ‘Work In Progress’, or use hierarchical names like ``wip/..``, ``feature/..`` or ``topic/..``. Either way is fine as long as it is clear that it is work in progress and not ready for merge. This work can temporarily have a lower standard. However, to be merged into master it will have to go through the regular process outlined above, with Pull Request, review etc..

Also, to facilitate both well-formed commits and working together, the ``wip`` and ``feature``/``topic`` identifiers also have special meaning.   Any branch labelled with ``wip`` is considered “git-unstable” and may be rebased and have its history rewritten.   Any branch with ``feature``/``topic`` in the name is considered “stable” enough for others to depend on when a group is working on a feature.

## Creating Commits And Writing Commit Messages

Follow these guidelines when creating public commits and writing commit messages.

1. If your work spans multiple local commits (for example; if you do safe point commits while working in a feature branch or work in a branch for long time doing merges/rebases etc.) then please do not commit it all but rewrite the history by squashing the commits into a single big commit which you write a good commit message for (like discussed in the following sections). For more info read this article: [Git Workflow](http://sandofsky.com/blog/git-workflow.html). Every commit should be able to be used in isolation, cherry picked etc.
2. First line should be a descriptive sentence what the commit is doing. It should be possible to fully understand what the commit does by just reading this single line. It is **not ok** to only list the ticket number, type "minor fix" or similar. Include reference to ticket number, prefixed with #, at the end of the first line. If the commit is a small fix, then you are done. If not, go to 3.
3. Following the single line description should be a blank line followed by an enumerated list with the details of the commit.
4. Add keywords for your commit (depending on the degree of automation we reach, the list may change over time):
    * ``Review by @gituser`` - if you want to notify someone on the team. The others can, and are encouraged to participate.
    * ``Fix/Fixing/Fixes/Close/Closing/Refs #ticket`` - if you want to mark the ticket as fixed in the issue tracker (Assembla understands this).
    * ``backport to _branch name_`` - if the fix needs to be cherry-picked to another branch (like 2.9.x, 2.10.x, etc)

Example:

    Added monadic API to Future. Fixes #2731

      * Details 1
      * Details 2
      * Details 3

##Source style##

Akka uses [Scalariform](https://github.com/mdr/scalariform) to enforce some of the code style rules.

##Contributing Modules##

For external contributions of entire features, the normal way is to establish it
as a stand-alone feature first, to show that there is a need for the feature. The
next step would be to add it to Akka as an "experimental feature" (in the
akka-contrib subproject), then when the feature is hardened, well documented and
tested it becomes an officially supported Akka feature.

[List of experimental Akka features](http://doc.akka.io/docs/akka/current/experimental/index.html)
