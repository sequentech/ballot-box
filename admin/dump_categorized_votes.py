#!/usr/bin/env python3

# This file is part of ballot_box.
# Copyright (C) 2022  Sequent Tech Inc <legal@sequentech.io>

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

def dump_election_config(election_id, election_config_path):
    '''
    Dumps the election config of the specified election in the specified
    election config path and returning the election's segmentation category
    name.
    '''
    db_connection = get_db_connection()
    elections = get_elections_table()
    sentence = select([elections])\
        .where(elections.c.id == election_id)\
        .where(elections.c.segmented_mixing == True)
    result = db_connection.execute(sentence)
    rows = list(result.fetchall())
    assert(len(rows) == 1)
    election_config_str = rows[0]['configuration']
    election_pub_keys = rows[0]['pks']
    election_config = dict(
        configuration=json.loads(election_config_str),
        pks=election_pub_keys
    )
    with open(election_config_path, "w") as election_config_file:
        election_config_file.write(json.dumps(election_config))
    
    assert("mixingCategorySegmentation" in election_config['configuration'])
    return election_config['configuration']['mixingCategorySegmentation']['categoryName']

def get_categorized_voters_path(
    election_id,
    segmentation_category_name,
    active_voters_only
):
    '''
    Returns a temporal file containing in CSV format the eligible voter
    list of an election, with two columns: the segmentation category of the
    voter and the voter id. Sorted by voter id descending.
    '''
    temp_dir_path = tempfile.mkdtemp()
    categorized_voters_file_path = os.path.join(
        temp_dir_path, "categorized_voters_file"
    )
    active_voters_only_filter = (
        "auth_user.is_active = true" if active_voters_only else ""
    )
    call_cmd(
        cmd=[
            "psql",
            "service = iam",
            "-tAc",
            f"""
SELECT auth_user.username,api_userdata.metadata->>'{segmentation_category_name}'
FROM api_acl
INNER JOIN api_userdata ON api_acl.user_id = api_userdata.id
INNER JOIN auth_user ON auth_user.id = api_userdata.user_id
INNER JOIN api_authevent ON api_authevent.id = '{election_id}'
WHERE
  {active_voters_only_filter}
  AND api_acl.object_id IS NOT NULL
  AND api_acl.object_type = 'AuthEvent'
  AND api_acl.perm = 'vote'
  AND api_acl.object_id = '{election_id}'
ORDER BY auth_user.username ASC;
            """,
            "-o",
            categorized_voters_file_path
        ],
        timeout=20,
    )
    return categorized_voters_file_path

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
            SELECT DISTINCT ON (voter_id) voter_id,vote
            FROM vote
            WHERE election_id={election_id} ORDER BY voter_id ASC, CREATED DESC;
            """,
            "-o",
            ballots_with_voters_path
        ],
        timeout=20,
    )
    return ballots_with_voters_path

def dump_categorized_votes(
    categorized_voters_path,
    ballots_with_voters_path,
    categorized_ballots_path
):
    '''
    Performs a join between the CSV files with categorized voters and ballots
    with voters using the voter-id as the join key, and dumping the resulting
    CSV file containing cast ballots with their category in the specified output
    path.
    '''
    call_cmd(
        cmd=[
            "bash",
            "-lc",
            f"""join --nocheck-order \
                {ballots_with_voters_path} {categorized_voters_path} \
                -t '|' \
                -o '2.2,1.2' \
                > {categorized_ballots_path}"""
        ],
        timeout=20,
    )

def main():
    parser = argparse.ArgumentParser(
        description=(
            'dump votes with two columns in CSV format: category and ' +
            'encrypted ballot'
        )
    )
    parser.add_argument(
        '--output-ballots-path',
        required=True,
        help=(
            'Path where to write the output ballots. Output will be in CSV ' +
            'format with two columns, with no header line, and the first ' +
            'column being the category name and the second column being the ' +
            'encrypted ballot.'
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
        '--election-config-path',
        required=True,
        help=(
            'File path where the election config should be written to'
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
    args = parser.parse_args()
    election_id = args.election_id
    election_config_path = args.election_config_path
    output_ballots_path = args.output_ballots_path
    active_voters_only = args.active_voters_only

    segmentation_category_name = dump_election_config(
        election_id,
        election_config_path
    )
    try:
        categorized_voters_path = get_categorized_voters_path(
            election_id,
            segmentation_category_name,
            active_voters_only
        )
        ballots_with_voters_path = get_ballots_with_voters_path(election_id)
        dump_categorized_votes(
            categorized_voters_path,
            ballots_with_voters_path,
            output_ballots_path
        )
    finally:
        os.unlink(ballots_with_voters_path)
        os.unlink(categorized_voters_path)


if __name__ == "__main__":
    main()
