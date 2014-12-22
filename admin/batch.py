#!/usr/bin/env python

import admin
import cycle

import sys
import StringIO
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
    parser.add_argument('-d', '--directory', help='configurations directory', required=True)
    parser.add_argument('-s', '--start-id', help='start id', type=int, default=0)
    parser.add_argument('-e', '--end-id', help='end id', type=int, default=100000000000)
    args = parser.parse_args()

    if not os.path.isdir(args.directory):
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
                cycle.wait_for_state(cfg['id'], 'created', 30)
                cycle.start(cfg['id'])
                cycle.wait_for_state(cfg['id'], 'started', 5)

    elif args.command == 'tally':
        election_configs = get_election_configs(args.directory, args.start_id, args.end_id)
        print(election_configs)

        for config in results_configs:
            with open(os.path.join(args.directory, config), 'r') as f:
                cfg = json.loads(f.read())
                print('next id %d' % cfg['id'])

                cycle.tally(cfg['id'])
                cycle.wait_for_state(cfg['id'], ['tally_ok', 'results_ok'], 500)

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