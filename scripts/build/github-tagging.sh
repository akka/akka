#!/bin/bash

# add and remove tags from an github issue, api token is taken from env ($PR_VALIDATOR_GH_TOKEN)
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

  # checking for existing tags
  currentTagsJson=`curl -s -H "Authorization: token $PR_VALIDATOR_GH_TOKEN" https://api.github.com/repos/$owner/$repo/issues/$issue/labels`
  currentTags=`echo "$currentTagsJson" | python -mjson.tool | grep '"name":' | awk '{ print $2 }' | sed s/\"//g | sed s/,//g`

  # adding new tags - we do want to show these, always performing
  curl -s -H "Authorization: token $PR_VALIDATOR_GH_TOKEN" https://api.github.com/repos/$owner/$repo/issues/$issue/labels -X POST -d $add > /dev/null

  # removing tags
  for d in $remove
  do
    # only delete tags that actually are set on the issue, otherwise github shows these "removed tag" events,
    # even if the tag never was previously set - which is confusing / verbose
    if [[ $currentTags == *$d* ]]
    then
      curl -s -H "Authorization: token $PR_VALIDATOR_GH_TOKEN" https://api.github.com/repos/$owner/$repo/issues/$issue/labels/$d -X DELETE > /dev/null
    fi
  done
}

echoerr() { echo "$@" 1>&2; }
