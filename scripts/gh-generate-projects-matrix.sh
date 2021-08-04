#! /bin/bash

# Reset project list in jobs
yq e -i '.jobs.*.strategy.matrix.project = []' .github/workflows/multi-node.yml
# Append the aggregated projects one-by-one
sbt -no-colors --error printAggregatedProjects | xargs -I {} sh -c 'yq e -i ".jobs.*.strategy.matrix.project += \"$1\"" .github/workflows/multi-node.yml' sh {}
