#!/bin/bash
# ./results.sh -t tally.tar.gz -c config.json -s
AGORA_RESULTS=/tmp/agora-results
VENV=/root/.virtualenvs
source $VENV/agora-results/bin/activate
$AGORA_RESULTS/agora-results $*
