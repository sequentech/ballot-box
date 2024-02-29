#!/usr/bin/env python3

# This file is part of ballot_box.
# Copyright (C) 2024  Sequent Tech Inc <legal@sequentech.io>

# ballot_box is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License.

# ballot_box  is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.

# You should have received a copy of the GNU Affero General Public License
# along with ballot_box.  If not, see <http://www.gnu.org/licenses/>.

import argparse
import time
import os
import csv
import signal
import tempfile
import json
import subprocess
from sqlalchemy import select

from utils.asyncproc import Process
from admin import (
    get_db_connection,
    get_elections_table
)

def call_cmd(cmd, timeout=-1, output_filter=None, cwd=None, check_ret=None):
    '''
    Utility to call a command.
    timeout is in seconds.
    '''
    print("call_cmd: calling " + " ".join(cmd))
    p = Process(cmd, cwd=cwd, stderr=subprocess.STDOUT)
    launch_time = time.process_time()
    output = ""

    while True:
        # check to see if process has ended
        ret = p.wait(os.WNOHANG)
        # print any new output
        o = p.read().decode('utf-8')
        if len(o) > 0:
            print("output = %s" % o)

        if output_filter:
            output_filter(p, o, output)
        output += o
        time.sleep(1)

        if ret is not None:
            if check_ret is not None:
                assert check_ret == ret
            return ret, output

        if timeout > 0 and time.process_time() - launch_time > timeout:
            p.kill(signal.SIGKILL)
            if check_ret is not None:
                assert check_ret == -1
            return -1, output

def get_ballots_with_voters_path(election_id):
    '''
    Returns a temporal file containing in CSV format the list of cast
    ballots along with their voter ids. Sorted by voter id descending.
    '''
    temp_dir_path = tempfile.mkdtemp()
    ballots_with_voters_path = os.path.join(
        temp_dir_path, "ballots_with_voters"
    )
    call_cmd(
        cmd=[
            "psql",
            "service = ballot_box",
            "-tAc",
            f"""
            SELECT 
                DISTINCT ON (voter_id)
                voter_id, vote
            FROM vote
            WHERE election_id={election_id} ORDER BY voter_id ASC, CREATED DESC;
            """,
            "-o",
            ballots_with_voters_path
        ],
        timeout=20,
    )
    return ballots_with_voters_path

def get_voters_info_path(
    election_id,
    vote_weight_column_name,
    active_voters_only,
    voters_info_path
):
    '''
    Returns a temporal file containing in CSV format the eligible voter
    list of an election, with the following columns:
    - voter_id
    - voter_weight (1 if `--vote-weight-column-name' was not specified)

    Sorted by voter id descending.
    '''
    active_voters_only_filter = (
        "auth_user.is_active = true AND " if active_voters_only else ""
    )
    vote_weight_column = (
        f"api_userdata.metadata->>'{vote_weight_column_name}'::SMALLINT AS vote_weight"
            if vote_weight_column_name
            else "1 AS vote_weight"
    )
    call_cmd(
        cmd=[
            "psql",
            "service = iam",
            "-tAc",
            f"""
            SELECT
                auth_user.username AS voter_id,
                {vote_weight_column}
            FROM api_acl
            INNER JOIN
                api_userdata ON
                    api_acl.user_id = api_userdata.id
            INNER JOIN
                auth_user ON
                    auth_user.id = api_userdata.user_id
            INNER JOIN
                api_authevent ON
                api_authevent.id = '{election_id}'
            WHERE
                {active_voters_only_filter}
                api_acl.object_id IS NOT NULL
                AND api_acl.object_type = 'AuthEvent'
                AND api_acl.perm = 'vote'
                AND (
                    (
                      api_acl.object_id = '{election_id}' 
                      AND api_authevent.parent_id IS NULL
                    ) OR (
                      api_acl.object_id = api_authevent.parent_id::text
                      AND api_authevent.parent_id IS NOT NULL
                      AND api_userdata.children_event_id_list::text LIKE '%{election_id}%'
                    )
                )
            ORDER BY
                auth_user.username ASC;
            """,
            "-o",
            voters_info_path
        ],
        timeout=20,
    )
    return voters_info_path

def dump_votes(
    voters_info_path,
    ballots_with_voters_path,
    output_ballots_path
):
    '''
    Performs a join between the CSV files, by joining them by the voter_id 
    field and duplicating the ballots per voter by the cardinality of the 
    vote_weight column. When doing so, the voter_id would be changed to 
    f'{voter_id}.{nth_duplication}' in the output file.
    '''
    voters_file = None
    # if voters_info_path is not provided, just dump all ballots
    if voters_info_path is None:
        with open(ballots_with_voters_path, 'r') as ballots_file, \
            open(output_ballots_path, 'w', newline='') as output_file:

            ballots_reader = csv.reader(ballots_file, delimiter="|")
            output_writer = csv.writer(output_file, delimiter="|")

            try:
                ballot_row = next(ballots_reader)
                ballot_voter_id, ballot = ballot_row[0], ballot_row[1]

                modified_voter_id = f'{ballot_voter_id}.1'
                output_writer.writerow([ballot, modified_voter_id])
            except StopIteration:
                # Reached the end of one of the files
                pass

    # if voters_info_path is provided, apply filtering and duplication
    with open(voters_info_path, 'r') as voters_file, \
         open(ballots_with_voters_path, 'r') as ballots_file, \
         open(output_ballots_path, 'w', newline='') as output_file:

        voters_reader = csv.reader(voters_file, delimiter="|")
        ballots_reader = csv.reader(ballots_file, delimiter="|")
        output_writer = csv.writer(output_file, delimiter="|")

        try:
            voter_row = next(voters_reader)
            ballot_row = next(ballots_reader)

            while True:
                voter_id, vote_weight = voter_row[0], int(voter_row[1])
                ballot_voter_id, ballot = ballot_row[0], ballot_row[1]

                if voter_id == ballot_voter_id:
                    # Duplicate the ballot based on vote_weight
                    for i in range(vote_weight):
                        modified_voter_id = f'{voter_id}.{i+1}'
                        output_writer.writerow([ballot, modified_voter_id])
                    
                    # Move to the next ballot
                    ballot_row = next(ballots_reader)
                elif voter_id < ballot_voter_id:
                    # The current voter has no ballot, move to the next voter
                    voter_row = next(voters_reader)
                else:
                    # The current ballot has no corresponding voter, move to the
                    # next ballot
                    ballot_row = next(ballots_reader)
        except StopIteration:
            # Reached the end of one of the files
            pass

def main():
    parser = argparse.ArgumentParser(
        description=(
            """
            Dump ballots, voteids and filter them if needed, considering
            weighting voting if needed.
            """
        )
    )
    parser.add_argument(
        '--output-ballots-path',
        required=True,
        help=(
            '''
            Path where to write the output ballots. Output will be in CSV format
            with 2 columns, with no header line:
            
            1. First column is the encrypted ballot. 
            
            2. Second column is the voterid, with a postfix of the multiplicated
               weight index if `--vote-weight-column-name` is provided.
            '''
        )
    )
    parser.add_argument(
        '--voters-info-path',
        required=False,
        help=(
            """
            Path where to write the voter-ids if filtering by them. Output will
            be in CSV format with two columns, with no header line:
            1. The first column being the voter-id. 
            2. the second column will contain `1` if `--vote-weight-column-name`
               is not provided, or the vote weight associated to that voter
               otherwise.
            """
        )
    )
    parser.add_argument(
        '--election-id',
        type=int,
        required=True,
        help=(
            'Id of the election to dump'
        )
    )
    parser.add_argument(
        '--active-voters-only',
        action='store_true',
        default=False,
        required=False,
        help=(
            'If set, dump only votes from enabled voters'
        )
    )
    parser.add_argument(
        '--vote-weight-column-name',
        required=False,
        help=(
            'If set, vote weight will be calculated using this column name'
        )
    )
    args = parser.parse_args()
    if args.vote_weight_column_name and not args.active_voters_only:
        parser.error(
            """
            `--vote-weight-column-name` requires `--active-voters-only` to be
            set.
            """
        )
    if args.active_voters_only and not args.voters_info_path:
        parser.error(
            """
            `--active-voters-only` requires `--voters-info-path` to be set.
            """
        )
    election_id = args.election_id
    output_ballots_path = args.output_ballots_path
    active_voters_only = args.active_voters_only
    voters_info_path = args.voters_info_path
    vote_weight_column_name = args.vote_weight_column_name

    try:
        ballots_with_voters_path = get_ballots_with_voters_path(election_id)
        if voters_info_path:
            get_voters_info_path(
                election_id,
                vote_weight_column_name,
                active_voters_only,
                voters_info_path
            )
        dump_votes(
            voters_info_path,
            ballots_with_voters_path,
            output_ballots_path
        )
    finally:
        os.unlink(ballots_with_voters_path)



if __name__ == "__main__":
    main()
