#!/bin/sh

# tag an github issue with the given tag, api token is taken from env ($PR_VALIDATOR_GH_TOKEN)
# usage: ghtag ISSUE_NR OWNER REPO ADD_TAGS RM_TAGS
#
# ADD_TAGS should be json with tokens you want to add, as in: ["tested"]
# RM_TAGS should be a list of words, like "building bananas"
function ghtag {
  if [[ "$PR_VALIDATOR_GH_TOKEN" == "" ]]
  then
    echoerr "Env variable PR_VALIDATOR_GH_TOKEN was empty, unable to call github api!"
    exit 1;
  fi

  issue=$1
  owner=$2
  repo=$3
  add=$4
  remove=$5

  curl -H "Authorization: token $PR_VALIDATOR_GH_TOKEN" https://api.github.com/repos/$owner/$repo/issues/$issue/labels -X POST -d $add
  for d in $remove
  do
    curl -H "Authorization: token $PR_VALIDATOR_GH_TOKEN" https://api.github.com/repos/$owner/$repo/issues/$issue/labels/$d -X DELETE
  done
}

echoerr() { echo "$@" 1>&2; }
