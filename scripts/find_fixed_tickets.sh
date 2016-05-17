#!/bin/bash
#
# Finds fixed tickets in repository
#
# This script takes Organization, Repository, Github username, Password as inputs.
# It writes all the closed tickets in "closed-issues.txt" file.
#
read -p "Organization: " ORG; read -p "Repository: " REPO; read -p "Github username: " USER; read -s -p "Password: " PASS
CLOSED_FILE="closed-issues.txt"
if [[ ! -e $CLOSED_FILE ]]; then
	for (( i = 1 ; i < 200; i++ )); do
		URL="https://api.github.com/repos/$ORG/$REPO/issues?state=closed&page=$i&per_page=100"
		echo "fetching $URL"
		HEADERS=""
		{ HEADERS=$(curl -vs -u "$USER:$PASS" "$URL" 2>&1 1>&3-); } 3> >(grep -oP "(?<=^[[:space:]]{4}\"number\":[[:space:]])([0-9]{1,5})" >> $CLOSED_FILE)
		echo "$HEADERS"
		if [[ ! "$HEADERS" == *"rel=\"next\""* ]]; then
			break
		fi
	done
else
	echo "$CLOSED_FILE found"
fi

ISSUE_PATTERN="[^&A-Za-z](#)([0-9]{1,5})($|[^0-9A-Za-z])"
grep -rn --include=\*.{scala,java} -E "$ISSUE_PATTERN" . | while read LINE; do
	if [[ $LINE =~ $ISSUE_PATTERN ]]; then
		if grep -xq ${BASH_REMATCH[2]} $CLOSED_FILE; then
			echo "$LINE"
		fi
	fi
done
