#Contributing to Akka#

Greetings traveller!

##Infrastructure##

* [Akka Contributor License Agreement](www.typesafe.com/contribute/cla)
* [Akka Issue Tracker](http://doc.akka.io/docs/akka/current/project/issue-tracking.html)
* [Scalariform](https://github.com/mdr/scalariform)

##Workflow##

0. Sign the Akka Contributor License Agreement,
   we won't accept anything from anybody who has not signed it.
1. Find-or-create a ticket in the issue tracker
2. Assign that ticket to yourself
3. Create a local branch with the following name format: wip-X-Y-Z
   where the X is the number of the ticket in the tracker,
   and Y is some brief keywords of the ticket title and Z is your initials or similar.
   Example: wip-2373-add-contributing-md-√
4. Do what needs to be done (with tests and docs if applicable).
   Your branch should pass all tests before going any further.
5. Push the branch to your clone of the Akka repository
6. Create a Pull Request onto the applicable Akka branch,
   if the number of commits are more than a few, please squash the
   commits first.
7. Change the status of your ticket to "Test"
8. The Pull Request will be reviewed by the Akka committers
9. Modify the Pull Request as agreed upon during the review,
   then push the changes to your branch in your Akka repository,
   the Pull Request should be automatically updated with the new
   content.
10. Several cycles of review-then-change might occur.
11. Pull Request is either merged by the Akka committers,
    or rejected, and the associated ticket will be updated to
    reflect that.
12. Delete the local and remote wip-X-Y-Z

##Code Reviews##

Akka utilizes peer code reviews to streamline the codebase, reduce the defect ratio,
increase maintainability and spread knowledge about how things are solved.

Core review values:

* Rule: [The Boy Scout Rule](http://programmer.97things.oreilly.com/wiki/index.php/The_Boy_Scout_Rule)
  - Why: Small improvements add up over time, keeping the codebase in shape.
* Rule: [Don't Repeat Yourself](http://programmer.97things.oreilly.com/wiki/index.php/Don't_Repeat_Yourself)
  - Why: Repetitions are not maintainable, keeping things DRY makes it easier to fix bugs and refactor,
  since you only need to apply the correction in one place, or perform the refactoring at one place.
* Rule: Feature tests > Integration tests > Unit tests
  - Why: Without proving that a feature works, the code is only liability.
         Without proving that a feature works with other features, the code is of limited value.
         Without proving the individual parts of a feature works, the code is harder to debug.

##Source style##

Akka uses [Scalariform](https://github.com/mdr/scalariform) to enforce some of the code style rules.

##Contributing Modules##

For external contributions of entire modules, the normal way is to establish it as a stand-alone module first,
 to show that there is a need for the module. The next step would be to add it to Akka as an "experimental module" (in the akka-contrib subproject),
 then when the module is hardened, well documented and tested it becomes an officially supported Akka module.

[List of experimental Akka modules](http://doc.akka.io/docs/akka/current/experimental/index.html)
