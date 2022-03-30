#!/usr/bin/env python3

# This file is part of ballot_box.
# Copyright (C) 2014-2016  Agora Voting SL <agora@agoravoting.com>

# ballot_box is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License.

# ballot_box  is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.

# You should have received a copy of the GNU Affero General Public License
# along with ballot_box.  If not, see <http://www.gnu.org/licenses/>.

import admin
import cycle

import sys
from functools import partial
import time
import json
import os
import traceback

import argparse
from argparse import RawTextHelpFormatter

def get_election_configs(dir, start_id, end_id):
    election_configs = [ f for f in os.listdir(dir) if os.path.isfile(os.path.join(dir, f)) and f.endswith('config.json')]
    election_configs.sort(key = lambda x: int(x.split('.')[0]))
    election_configs = [ f for f in election_configs if int(f.split('.')[0]) >= start_id and int(f.split('.')[0]) <= end_id]

    return election_configs

def get_results_configs(dir, start_id, end_id):
    results_configs = [ f for f in os.listdir(dir) if os.path.isfile(os.path.join(dir, f)) and f.endswith('results.json')]
    results_configs.sort(key = lambda x: int(x.split('.')[0]))
    results_configs = [ f for f in results_configs if int(f.split('.')[0]) >= start_id and int(f.split('.')[0]) <= end_id]

    return results_configs

def main(argv):
    parser = argparse.ArgumentParser(description='batch admin script', formatter_class=RawTextHelpFormatter)
    parser.add_argument('-c', '--command', help='command, <create|tally|results>', required=True)
    parser.add_argument('-d', '--directory', help='configurations directory')
    parser.add_argument('-mb', '--message-body', help='send message body')
    parser.add_argument('-ms', '--message-subject', help='send message subject')
    parser.add_argument(
        '--election-ids',
        metavar='ID',
        type=int,
        nargs='*',
        help='list of election ids to which the command should be applied')
    parser.add_argument('-s', '--start-id', help='start id', type=int, default=0)
    parser.add_argument('-e', '--end-id', help='end id', type=int, default=100000000000)
    args = parser.parse_args()

    if args.directory is not None and not os.path.isdir(args.directory):
        print("not a directory %s" % args.directory)

    if args.command == 'create':
        election_configs = get_election_configs(args.directory, args.start_id, args.end_id)
        print(election_configs)

        for config in election_configs:
            with open(os.path.join(args.directory, config), 'r') as f:
                cfg = json.loads(f.read())
                print('next id %d' % cfg['id'])

                cycle.register(cfg)
                cycle.wait_for_state(cfg['id'], 'registered', 5)
                cycle.create(cfg['id'])
                cycle.wait_for_state(cfg['id'], 'created', 300)
                cycle.start(cfg['id'])
                cycle.wait_for_state(cfg['id'], 'started', 5)

    elif args.command == 'list-start':
        for eid in args.election_ids:
            cfg = dict(id=eid)
            print('next id %d' % cfg['id'])

            cycle.start(cfg['id'])
            cycle.wait_for_state(cfg['id'], 'started', 5)

    elif args.command == 'list-stop':
        for eid in args.election_ids:
            cfg = dict(id=eid)

            print('next id %d, stopping election' % cfg['id'])
            ret = cycle.stop(cfg['id'])
            cycle.wait_for_state(cfg['id'], ['stopped'], 4)

    elif args.command == 'list-tally':
        for eid in args.election_ids:
            cfg = dict(id=eid)

            ciphertexts_path = publicPath = os.path.join(
                admin.datastore,
                'private',
                str(cfg['id']),
                'ciphertexts')
            if len(open(ciphertexts_path, 'r').readlines()) == 0:
                print("no votes in election %s, continuing to the next" % cfg['id'])
                continue

            print('next id %d, tallying' % cfg['id'])
            cycle.tally(cfg['id'])
            cycle.wait_for_state(cfg['id'], ['tally_ok', 'results_ok'], 10000)

    elif args.command == 'list-tally-with-ids':
        for eid in args.election_ids:
            print('next id %d, stopping election' % eid)
            ret = cycle.stop(eid)
            if ret in [400, 500]:
                  print("stop returned %d, continuing without it" % ret)
                  continue

            print('next id %d, tallying' % eid)
            ret = cycle.tally_no_dump(eid)
            cycle.wait_for_state(eid, ['tally_ok', 'results_ok'], 10000)
            if ret in [400, 500]:
                  print("tally_no_dump ids returned %d, continuing without it" % ret)
                  continue

    elif args.command == 'list-results':
        for eid in args.election_ids:
            cfg = dict(id=eid)

            ciphertexts_path = publicPath = os.path.join(
                admin.datastore,
                'private',
                str(cfg['id']),
                'ciphertexts')
            if len(open(ciphertexts_path, 'r').readlines()) == 0:
                print("no votes in election %s, continuing to the next" % cfg['id'])
                continue

            print('next id %d, calculating results' % cfg['id'])
            cycle.calculate_results(cfg['id'])
            cycle.wait_for_state(cfg['id'], 'results_ok', 5)

    elif args.command == 'list-auth-message':
        for eid in args.election_ids:
            print('next id %d, sending message' % eid)
            payload = {
              "msg":args.message_body,
              "user-ids": None
            }
            if args.message_subject:
                payload['subject'] = args.message_subject
            print('> sending message..')
            print('payload = %s' % json.dumps(payload))
            admin.send_codes(eid, json.dumps(payload))

    elif args.command == 'list-auth-start':
        for eid in args.election_ids:
            print('next id %d, starting auth event' % eid)
            admin.auth_start(eid)

    elif args.command == 'list-auth-stop':
        for eid in args.election_ids:
            print('next id %d, stopping auth event' % eid)
            admin.auth_stop(eid)

    elif args.command == 'list-publish':
        for eid in args.election_ids:
            cfg = dict(id=eid)

            ciphertexts_path = publicPath = os.path.join(
                admin.datastore,
                'private',
                str(cfg['id']),
                'ciphertexts')
            if len(open(ciphertexts_path, 'r').readlines()) == 0:
                print("no votes in election %s, continuing to the next" % cfg['id'])
                continue

            print('next id %d, publishing results' % cfg['id'])
            cycle.publish_results(cfg['id'])
            cycle.wait_for_state(cfg['id'], 'results_pub', 5)

    elif args.command == 'count':
        election_configs = get_election_configs(args.directory, args.start_id, args.end_id)
        print(election_configs)
        from sqlalchemy import create_engine, select, func, text
        from sqlalchemy import Table, Column, Integer, String, TIMESTAMP, MetaData, ForeignKey
        from sqlalchemy import distinct

        conn = admin.get_db_connection()
        votes = admin.votes_table()
        total1 = 0
        total2 = 0

        for config in election_configs:
            with open(os.path.join(args.directory, config), 'r') as f:
                cfg = json.loads(f.read())
                if 'payload' in cfg:
                    elid = cfg['payload']['id']
                else:
                    elid = cfg['id']

                s = select([func.count(distinct(votes.c.voter_id))]).where(votes.c.election_id.in_([elid,]))
                s2 = select([func.count(votes.c.voter_id)]).where(votes.c.election_id.in_([elid,]))

                result = conn.execute(s)
                row = result.fetchall()
                result2 = conn.execute(s2)
                row2 = result2.fetchall()
                print("%d: %d (%d)" % (elid, row[0][0], row2[0][0]))
                total1 += row[0][0]
                total2 += row2[0][0]

        print("total: %d (%d)" % (total1, total2))

    elif args.command == 'tally':
        election_configs = get_election_configs(args.directory, args.start_id, args.end_id)
        print(election_configs)

        for config in election_configs:
            with open(os.path.join(args.directory, config), 'r') as f:
                cfg = json.loads(f.read())
                if 'payload' in cfg:
                    elid = cfg['payload']['id']
                else:
                    elid = cfg['id']
                print('next id %d' % elid)

                ciphertexts_path = publicPath = os.path.join(
                    admin.datastore,
                    'private',
                    str(next_id),
                    'ciphertexts')
                if len(open(ciphertexts_path, 'r').readlines()) == 0:
                    print("no votes in election %s, continuing to the next" % next_id)
                    continue

                cycle.tally(elid)
                cycle.wait_for_state(elid, ['tally_ok', 'results_ok'], 10000)

    elif args.command == 'tally_with_ids':
        election_configs = get_election_configs(args.directory, args.start_id, args.end_id)
        print(election_configs)

        for config in election_configs:
            with open(os.path.join(args.directory, config), 'r') as f:
                cfg = json.loads(f.read())
                if 'payload' in cfg:
                    next_id = cfg['payload']['id']
                else:
                    next_id = cfg['id']

                print('next id %d, stopping election' % next_id)
                ret = cycle.stop(next_id)
                if ret in [400, 500]:
                     print("stop returned %d, continuing without it" % ret)
                     continue

                ciphertexts_path = publicPath = os.path.join(
                    admin.datastore,
                    'private',
                    str(next_id),
                    'ciphertexts')
                if len(open(ciphertexts_path, 'r').readlines()) == 0:
                    print("no votes in election %s, continuing to the next" % next_id)
                    continue

                print('next id %d, tallying' % next_id)
                ret = cycle.tally_no_dump(next_id)
                cycle.wait_for_state(next_id, ['tally_ok', 'results_ok', 'stopped'], 10000)
                if ret in [400, 500]:
                     print("tally_no_dump ids returned %d, continuing without it" % ret)
                     continue

    elif args.command == 'results':
        results_configs = get_results_configs(args.directory, args.start_id, args.end_id)
        print(results_configs)

        for config in results_configs:
            with open(os.path.join(args.directory, config), 'r') as f:
                cfg = json.loads(f.read())
                id = long(config.split('.')[0])
                print('next id %d' % id)
                cycle.calculate_results(cfg['id'], args.results_config)
                cycle.wait_for_state(cfg['id'], 'results_ok', 5)
                cycle.publish_results(cfg['id'])
    else:
        parser.print_help()

if __name__ == "__main__":
    main(sys.argv[1:])
