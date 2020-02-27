#!/usr/bin/env python

# This file is part of agora_elections.
# Copyright (C) 2014-2016  Agora Voting SL <agora@agoravoting.com>

# agora_elections is free software: you can redistribute it and/or modify
# it under the terms of the GNU Affero General Public License as published by
# the Free Software Foundation, either version 3 of the License.

# agora_elections  is distributed in the hope that it will be useful,
# but WITHOUT ANY WARRANTY; without even the implied warranty of
# MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
# GNU Affero General Public License for more details.

# You should have received a copy of the GNU Affero General Public License
# along with agora_elections.  If not, see <http://www.gnu.org/licenses/>.

from __future__ import print_function
import requests
import json
import time
import re

import subprocess

import argparse
import sys
import __main__
from argparse import RawTextHelpFormatter

from datetime import datetime
import hashlib
import codecs
import traceback
import string

import os.path
import os
from prettytable import PrettyTable
import random
import shutil

import warnings as _warnings
import os as _os

from tempfile import mkdtemp

from sqlalchemy import create_engine, select, func, text
from sqlalchemy import Table, Column, Integer, String, TIMESTAMP, MetaData, ForeignKey
from sqlalchemy import distinct

from utils.votesfilter import VotesFilter

# set configuration parameters
datastore = '/home/agoraelections/datastore'
shared_secret = '<password>'
db_user = 'agora_elections'
db_password = 'agora_elections'
db_name = 'agora_elections'
db_port = 5432
app_host = 'localhost'
app_port = 9000
authapi_port = 10081
authapi_credentials = dict()
authapi_admin_eid = 1
authapi_db_user = 'authapi'
authapi_db_password = 'authapi'
authapi_db_name = 'authapi'
authapi_db_port = 5432
node = '/usr/local/bin/node'

class TemporaryDirectory(object):
    """Create and return a temporary directory.  This has the same
    behavior as mkdtemp but can be used as a context manager.  For
    example:

        with TemporaryDirectory() as tmpdir:
            ...

    Upon exiting the context, the directory and everything contained
    in it are removed.
    """

    def __init__(self, suffix="", prefix="tmp", dir=None):
        self._closed = False
        self.name = None # Handle mkdtemp raising an exception
        self.name = mkdtemp(suffix, prefix, dir)

    def __repr__(self):
        return "<{} {!r}>".format(self.__class__.__name__, self.name)

    def __enter__(self):
        return self.name

    def cleanup(self, _warn=False):
        if self.name and not self._closed:
            try:
                self._rmtree(self.name)
            except (TypeError, AttributeError) as ex:
                # Issue #10188: Emit a warning on stderr
                # if the directory could not be cleaned
                # up due to missing globals
                if "None" not in str(ex):
                    raise
                print("ERROR: {!r} while cleaning up {!r}".format(ex, self,),
                      file=_sys.stderr)
                return
            self._closed = True
            if _warn:
                self._warn("Implicitly cleaning up {!r}".format(self),
                           ResourceWarning)
    def __exit__(self, exc, value, tb):
        self.cleanup()

    def __del__(self):
        # Issue a ResourceWarning if implicit cleanup needed
        self.cleanup(_warn=True)

    # XXX (ncoghlan): The following code attempts to make
    # this class tolerant of the module nulling out process
    # that happens during CPython interpreter shutdown
    # Alas, it doesn't actually manage it. See issue #10188
    _listdir = staticmethod(_os.listdir)
    _path_join = staticmethod(_os.path.join)
    _isdir = staticmethod(_os.path.isdir)
    _islink = staticmethod(_os.path.islink)
    _remove = staticmethod(_os.remove)
    _rmdir = staticmethod(_os.rmdir)
    _warn = _warnings.warn

    def _rmtree(self, path):
        # Essentially a stripped down version of shutil.rmtree.  We can't
        # use globals because they may be None'ed out at shutdown.
        for name in self._listdir(path):
            fullname = self._path_join(path, name)
            try:
                isdir = self._isdir(fullname) and not self._islink(fullname)
            except OSError:
                isdir = False
            if isdir:
                self._rmtree(fullname)
            else:
                try:
                    self._remove(fullname)
                except OSError:
                    pass
        try:
            self._rmdir(path)
        except OSError:
            pass







def get_local_hostport():
    return app_host, app_port

def votes_table():
    metadata = MetaData()
    votes = Table('vote', metadata,
        Column('id', Integer, primary_key=True),
        Column('election_id', String),
        Column('voter_id', String),
        Column('vote', String),
        Column('hash', String),
        Column('created', TIMESTAMP),
    )
    return votes

def elections_table():
    metadata = MetaData()
    elections = Table('election', metadata,
        Column('id', Integer, primary_key=True),
        Column('configuration', String),
        Column('state', String),
        Column('start_date', TIMESTAMP),
        Column('end_date', TIMESTAMP),
        Column('pks', String),
        Column('results', String),
        Column('results_updated', String)
    )
    return elections

def acls_table():
    metadata = MetaData()
    elections = Table('api_acl', metadata,
        Column('id', Integer, primary_key=True),
        Column('perm', String),
        Column('user_id', Integer),
        Column('object_id', String),
        Column('object_type', String),
        Column('created', TIMESTAMP)
    )
    return elections

def truncate(data):
    data = unicode(data)
    return (data[:20] + '..') if len(data) > 20 else data

def show_votes(result):
    v = PrettyTable(['id', 'election_id', 'voter_id', 'vote', 'hash', 'created'])
    v.padding_width = 1
    if args.ips_log:
        ips_re = "^(?P<ip>\\S+).*POST /elections/api/election/(?P<election_id>\\d+)/voter/(?P<voter_id>\\w+)\\s"
        import re
        voter_ips = {}
        prog = re.compile(ips_re)
        with open(args.ips_log, mode='r') as f:
            for line in f:
                res = prog.match(line)
                if res is not None:
                    voter_ips[res.group('voter_id')] = {
                        'election_id': res.group('election_id'),
                        'ip': res.group('ip')
                    }
    for row in result:
        ip = "no-ip"
        if row[2] in voter_ips:
            ip = voter_ips[row[2]]['ip']
        row.append(ip)
        v.add_row(map(truncate, row))
    print(v)

def show_elections(result):
    v = PrettyTable(['id', 'configuration', 'state', 'start_date', 'end_date', 'pks', 'results', 'results_updated'])
    v.padding_width = 1
    for row in result:
        v.add_row(map(truncate, row))
    print(v)

def get_max_electionid():
    conn = get_db_connection()
    elections = elections_table()
    s = select([func.max(elections.c.id)])
    result = conn.execute(s)
    return result.first()[0]

def get_db_connection():
    engine = create_engine('postgresql+psycopg2://%s:%s@localhost:%d/%s' % (db_user, db_password, db_port, db_name))
    conn = engine.connect()

    return conn

def get_authapi_db_connection():
    engine = create_engine(
        'postgresql+psycopg2://%s:%s@localhost:%d/%s' % (
            authapi_db_user,
            authapi_db_password,
            authapi_db_port,
            authapi_db_name
        )
    )
    conn = engine.connect()

    return conn

def authapi_ensure_acls(cfg, args):
    conn = get_authapi_db_connection()
    acls = []
    with codecs.open(args.acls_path, encoding='utf-8', mode='w+') as f:
        acls = [line.split(',') for line in f.read().splitlines()]

    '(email|tlf),(email@example.com|+34666777888),permission_name,object_type,object_id,user_election_id'

    # TODO: do an UPSERT
    for (user_type, user_id, perm_name, obj_type, obj_id, user_eid) in acls:
        if user_type == tlf:
            q_uid = '''SELECT'''
        else:
            q = '''
            '''
        q_insert = '''
        INSERT INTO api_acl(perm,user_id,object_id,object_type)
        SELECT ('%s',%s,%s,'%s')
        ''' % (
            perm, user_id,object_id,object_type
        )
        conn.execute(q_insert)


# writes the votes in the format expected by eo
def write_node_votes(votesData, filePath):
    # forms/election.py:save
    votes = []
    for vote in votesData:
        data = {
            "proofs": [],
            "choices": [],
            "issue_date": str(datetime.now()),
        }

        q_answer = vote['question0']
        data["proofs"].append(dict(
            commitment=q_answer['commitment'],
            response=q_answer['response'],
            challenge=q_answer['challenge']
        ))
        data["choices"].append(dict(
            alpha=q_answer['alpha'],
            beta=q_answer['beta']
        ))

        votes.append(data)

    # tasks/election.py:launch_encrypted_tally
    # this is the format expected by eo, newline separated
    with codecs.open(filePath, encoding='utf-8', mode='w+') as votes_file:
        for vote in votes:
            votes_file.write(json.dumps(vote, sort_keys=True) + "\n")

''' commands '''

def register(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['electionConfig']['id'], "edit")
    host,port = get_local_hostport()
    headers = {'content-type': 'application/json', 'Authorization': auth}
    url = 'http://%s:%d/api/election/%d' % (host, port, cfg['electionConfig']['id'])
    r = requests.post(url, data=json.dumps(cfg['electionConfig']), headers=headers)
    print(r.status_code, r.text)

def update(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'content-type': 'application/json', 'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/update' % (host, port, cfg['election_id'])
    r = requests.post(url, data=json.dumps(cfg['electionConfig']), headers=headers)
    print(r.status_code, r.text)

def get(cfg, args):
    election_id = cfg['election_id']
    host,port = get_local_hostport()
    headers = {'content-type': 'application/json'}
    url = 'http://%s:%d/api/election/%d' % (host, port, election_id)
    r = requests.get(url, headers=headers)
    print(r.status_code, r.text[:200])
    return r.status_code, r.text

def create(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/create' % (host, port, cfg['election_id'])
    r = requests.post(url, headers=headers)
    print(r.status_code, r.text)

def start(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/start' % (host, port, cfg['election_id'])
    r = requests.post(url, headers=headers)
    print(r.status_code, r.text)

def stop(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/stop' % (host, port, cfg['election_id'])
    r = requests.post(url, headers=headers)
    print(r.status_code, r.text)

def set_start_date(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'content-type': 'application/json', 'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/set-start-date' % (host, port, cfg['election_id'])
    r = requests.post(
        url,
        headers=headers,
        data=json.dumps(dict(date=args.date))
    )
    print(r.status_code, r.text)

def set_stop_date(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'content-type': 'application/json', 'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/set-stop-date' % (host, port, cfg['election_id'])
    r = requests.post(
        url,
        headers=headers,
        data=json.dumps(dict(date=args.date))
    )
    print(r.status_code, r.text)

def set_tally_date(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'content-type': 'application/json', 'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/set-tally-date' % (host, port, cfg['election_id'])
    r = requests.post(
        url,
        headers=headers,
        data=json.dumps(dict(date=args.date))
    )
    print(r.status_code, r.text)

def cast_votes(cfg, args):
    ctexts = cfg['ciphertexts']
    electionId = cfg['election_id']

    if(os.path.isfile(ctexts)):
        with open(ctexts) as votes_file:
            votes = json.load(votes_file)

            voter_id = 0
            print("casting %d votes.." % len(votes))
            for vote in votes:
                vote_string = json.dumps(vote)
                vote_hash = hashlib.sha256(vote_string).hexdigest()
                vote = {
                    "vote": vote_string,
                    "vote_hash": vote_hash
                }

                auth = get_hmac(cfg, voter_id, "AuthEvent", cfg['election_id'], 'vote')
                host,port = get_local_hostport()
                headers = {'Authorization': auth, 'content-type': 'application/json'}
                url = 'http://%s:%d/api/election/%d/voter/%d' % (host, port, cfg['election_id'], voter_id)
                data = json.dumps(vote)
                # print("casting vote for voter %d, %s" % (voter_id, data))
                voter_id += 1
                r = requests.post(url, data=data, headers=headers)
                if r.status_code != 200:
                    print(r.status_code, r.text)

            # only show the status code for the last vote cast
            print(r.status_code, r.text)
    else:
        print("No public key or votes file, exiting..")
        exit(1)


def dump_votes(cfg, args):
    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/dump-votes' % (host, port, cfg['election_id'])
    r = requests.post(url, headers=headers)
    print(r.status_code, r.text)

def dump_votes_with_ids(cfg, args):
    path = args.voter_ids
    if path != None and os.path.isfile(path):
        with open(path) as ids_file:
            ids = json.load(ids_file)

        auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
        host,port = get_local_hostport()
        headers = {'Authorization': auth, 'content-type': 'application/json'}
        url = 'http://%s:%d/api/election/%d/dump-votes-voter-ids' % (host, port, cfg['election_id'])
        # print('json is %s' % json.dumps(ids))
        r = requests.post(url, headers=headers, data=json.dumps(ids))
        print(r.status_code, r.text)
        return r.status_code
    else:
        print("no valid ids file %s" % path)
        return 400

# remove
def dump_ids(cfg, args):
    conn = get_db_connection()
    votes = votes_table()

    if args.elections_file:
        with open(args.elections_file, 'r') as f:
            groups = [line.split(',') for line in f.read().splitlines()]
    else:
        groups = [cfg['election_id']]

    if args.voter_ids:
        with open(args.voter_ids, 'r') as f:
            ids = set(f.read().splitlines())
    else:
        ids = set()

    filter_obj = None
    if args.filter_config is not None:
        filter_obj = VotesFilter(args.filter_config)

    allowed_by_voter = {}

    for group in groups:
        for election in group:
            s = select([votes]).where(votes.c.election_id == election).order_by(votes.c.created)
            result = conn.execute(s)
            rows = result.fetchall()
            for row in rows:
                if (args.voter_ids is None or row[2] in ids) and\
                    (filter_obj is None or filter_obj.check(row, election)):
                    allowed_by_voter[row[2]] = election

        sys.stdout.write('.')

    allowed_by_election = {}

    for voter in allowed_by_voter:
        election = allowed_by_voter[voter]
        if election not in allowed_by_election:
            allowed_by_election[election] = [voter]
        else:
            allowed_by_election[election].append(voter)

    total = 0
    for election in allowed_by_election:
        dir_path = os.path.join(datastore, 'private', election)
        if not os.path.exists(dir_path):
            os.makedirs(dir_path)
        file_path = os.path.join(dir_path, 'ids')
        num_votes = len(allowed_by_election[election])

        s = select([func.count(distinct(votes.c.voter_id))]).where(votes.c.election_id == election)
        result = conn.execute(s)
        row = result.fetchall()
        total_votes = row[0][0]

        print("election %s: %d votes (%.2f%% from %d total)" % (
            election, num_votes, num_votes*100.0/total_votes, total_votes))

        total += num_votes
        with codecs.open(file_path, encoding='utf-8', mode='w+') as ids_file:
            ids_file.write(json.dumps(allowed_by_election[election]))
    print("total = %d votes" % total)

def dump_pks(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/dump-pks' % (host, port, cfg['election_id'])
    r = requests.post(url, headers=headers)
    print(r.status_code, r.text)

def tally(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/tally' % (host, port, cfg['election_id'])
    r = requests.post(url, headers=headers)
    print(r.status_code, r.text)

def tally_voter_ids(cfg, args):
    path = args.voter_ids
    if path != None and os.path.isfile(path):
        with open(path) as ids_file:
            ids = json.load(ids_file)

        auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
        host,port = get_local_hostport()
        headers = {'Authorization': auth}
        url = 'http://%s:%d/api/election/%d/tally' % (host, port, cfg['election_id'])
        r = requests.post(url, headers=headers, data=json.dumps(ids))
        print(r.status_code, r.text)
        return r.status_code
    else:
        print("no valid ids file %s" % path)
        return 400

def tally_no_dump(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/tally-no-dump' % (host, port, cfg['election_id'])
    r = requests.post(url, headers=headers)
    print(r.status_code, r.text)

def calculate_results(cfg, args):
    path = args.results_config
    jconfig = None
    if path != None and os.path.isfile(path):
        with open(path) as config_file:
            config = json.load(config_file)
            jconfig = json.dumps(config)
    else:
        print("continuing with no config file %s" % path)

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth, 'content-type': 'application/json'}
    url = 'http://%s:%d/api/election/%d/calculate-results' % (host, port, cfg['election_id'])
    r = request_post(url, headers=headers, data=jconfig)
    print(r.status_code, r.text)

def update_ballot_boxes_config(cfg, args):
    path = args.results_config
    jconfig = None
    if path != None and os.path.isfile(path):
        with open(path) as config_file:
            config = json.load(config_file)
            jconfig = json.dumps(config)
    else:
        print("continuing with no config file %s" % path)

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth, 'content-type': 'application/json'}
    url = 'http://%s:%d/api/election/%d/update-ballot-boxes-config' % (host, port, cfg['election_id'])
    r = request_post(url, headers=headers, data=jconfig)
    print(r.status_code, r.text)

def publish_results(cfg, args):

    auth = get_hmac(cfg, "", "AuthEvent", cfg['election_id'], "edit")
    host,port = get_local_hostport()
    headers = {'Authorization': auth}
    url = 'http://%s:%d/api/election/%d/publish-results' % (host, port, cfg['election_id'])
    r = request_post(url, headers=headers)

def request_post(url, *args, **kwargs):
    print("POST %s" % url)
    kwargs['verify'] = False
    req = requests.post(url, *args, **kwargs)
    print(req.status_code, req.text)
    return req

def get_authapi_auth_headers():
    '''
    Returns logged in headers
    '''
    base_url = 'http://%s:%d/authapi/api/' % (app_host, authapi_port)
    event_id = authapi_admin_eid
    req = request_post(
        base_url + 'auth-event/%d/authenticate/' % event_id,
        data=json.dumps(authapi_credentials)
    )
    if req.status_code != 200:
        raise Exception("authapi login failed")

    auth_token = req.json()['auth-token']
    return {'AUTH': auth_token}

def send_codes(eid, payload):
    base_url = 'http://%s:%d/authapi/api/' % (app_host, authapi_port)
    headers = get_authapi_auth_headers()
    url = base_url + 'auth-event/%d/census/send_auth/' % eid
    r = request_post(url, headers=headers, data=payload)

def auth_start(eid):
    base_url = 'http://%s:%d/authapi/api/' % (app_host, authapi_port)
    headers = get_authapi_auth_headers()
    url = base_url + 'auth-event/%d/started/' % eid
    r = request_post(url, headers=headers)

def auth_stop(eid):
    base_url = 'http://%s:%d/authapi/api/' % (app_host, authapi_port)
    headers = get_authapi_auth_headers()
    url = base_url + 'auth-event/%d/stopped/' % eid
    r = request_post(url, headers=headers)

def list_votes(cfg, args):
    conn = get_db_connection()
    votes = votes_table()
    s = select([votes]).where(votes.c.election_id == cfg['election_id'])
    for filter in cfg['filters']:
        if "~" in filter:
            key, value = filter.split("~")
            s = s.where(getattr(votes.c, key).like(value))
        else:
            key, value = filter.split("==")
            s = s.where(getattr(votes.c, key) == (value))

    result = conn.execute(s)
    show_votes(result)

def list_elections(cfg, args):
    conn = get_db_connection()
    elections = elections_table()
    s = select([elections]).order_by(elections.c.id)
    for filter in cfg['filters']:
        if "~" in filter:
            key, value = filter.split("~")
            s = s.where(getattr(elections.c, key).like(value))
        else:
            key, value = filter.split("==")
            s = s.where(getattr(elections.c, key) == (value))

    result = conn.execute(s)
    show_elections(result)

def count_votes(cfg, args):
    conn = get_db_connection()
    votes = votes_table()
    if 'election_id' in cfg:
        s = select([func.count(distinct(votes.c.voter_id))]).where(votes.c.election_id.in_(cfg['election_id']))
        s2 = select([func.count(votes.c.voter_id)]).where(votes.c.election_id.in_(cfg['election_id']))
    else:
        s = select([func.count(distinct(votes.c.voter_id))])
        s2 = select([func.count(votes.c.voter_id)])

    result = conn.execute(s)
    row = result.fetchall()
    result2 = conn.execute(s2)
    row2 = result2.fetchall()

    print("%d (%d)" % (row[0][0], row2[0][0]))

def show_column(cfg, args):
    conn = get_db_connection()
    elections = elections_table()
    s = select([elections]).where(elections.c.id == cfg['election_id'])
    result = conn.execute(s)
    for row in result:
        print(row[args.column])

def encryptNode(cfg, args):
    electionId = cfg['election_id']
    pkFile = 'pks'
    votesFile = cfg['plaintexts']
    votesCount = cfg['encrypt-count']
    ctexts = cfg['ciphertexts']

    print("> Encrypting votes (" + votesFile + ", pk = " + pkFile + ", " + str(votesCount) + ")..")
    publicPath = os.path.join(datastore, 'public', str(cfg['election_id']))
    pkPath = os.path.join(publicPath, pkFile)
    votesPath = votesFile
    ctextsPath = ctexts

    if(os.path.isfile(pkPath)) and (os.path.isfile(votesPath)):
        print("> Encrypting with %s %s %s %s %s" % (node, "js/encrypt.js", pkPath, votesPath, str(votesCount)))
        output, error = subprocess.Popen([node, "js/encrypt.js", pkPath, votesPath, str(votesCount)], stdout = subprocess.PIPE).communicate()

        print("> Received Nodejs output (" + str(len(output)) + " chars)")
        parsed = json.loads(output)

        print("> Writing file to " + ctextsPath)
        write_node_votes(parsed, ctextsPath)
    else:
        print("No public key or votes file, exiting..")
        exit(1)

# writes votes to file, in raw format (ready to submit to the ballotbox)
def encrypt(cfg, args):
    electionId = cfg['election_id']
    votesFile = cfg['plaintexts']
    votesCount = cfg['encrypt-count']
    ctextsPath = cfg['ciphertexts']

    publicPath = os.path.join(datastore, 'public', str(cfg['election_id']))
    pkPath = os.path.join(publicPath, 'pks')
    votesPath = votesFile
    print("Encrypting votes (" + votesFile + ", pk = " + pkPath + ", " + str(votesCount) + ")..")

    if(os.path.isfile(pkPath)) and (os.path.isfile(votesPath)):
        print("Encrypting with %s %s %s %s %s" % ("bash", "encrypt.sh", pkPath, votesPath, str(votesCount)))
        output, error = subprocess.Popen(["bash", "encrypt.sh", pkPath, votesPath, str(votesCount)], stdout = subprocess.PIPE).communicate()

        print("Received encrypt.sh output (" + str(len(output)) + " chars)")
        parsed = json.loads(output.decode('utf-8'))

        print("Writing file to " + ctextsPath)
        with codecs.open(ctextsPath, encoding='utf-8', mode='w+') as votes_file:
            votes_file.write(json.dumps(parsed, sort_keys=True))
        #    for vote in parsed:
        #        votes_file.write(json.dumps(vote, sort_keys=True) + "\n")
    else:
        print("No public key or votes file, exiting..")
        exit(1)

def change_social(cfg, args):

    if args.share_config != None and os.path.isfile(args.share_config):
        with open(args.share_config) as share_config_file:
            share_config = json.load(share_config_file)

        for election in cfg['election_id']:
            electionId = int(election)
            auth = get_hmac(cfg, "", "AuthEvent", electionId, "edit")
            host,port = get_local_hostport()
            headers = {'Authorization': auth, 'content-type': 'application/json'}
            url = 'http://%s:%d/api/election/%d/update-share' % (host, port, electionId)
            r = requests.post(url, data=json.dumps(share_config), headers=headers)
            print(r.status_code, r.text)
    else:
        print("invalid share-config file %s" % args.share_config)
        return 400

def get_hmac(cfg, userId, objType, objId, perm):
    import hmac

    secret = shared_secret
    now = 1000*int(time.time())
    message = "%s:%s:%d:%s:%d" % (userId, objType, objId, perm, now)
    _hmac = hmac.new(str.encode(secret), str.encode(message), hashlib.sha256).hexdigest()
    ret  = 'khmac:///sha-256;%s/%s' % (_hmac, message)

    return ret

class JClient:
    def __init__(self):
        self.auth_token = ''

    def set_auth_token(self, token):
        self.auth_token = token

    def post(self, url, data):
        base_url = 'http://%s:%d/authapi/api/' % (app_host, authapi_port)
        jdata = json.dumps(data)
        headers = {'content-type': 'application/json', 'Authorization': self.auth_token}
        r = requests.post(base_url + url, data=jdata, headers=headers)
        return r

    def authenticate(self, authevent, data):
        auth = get_hmac(None, "", "AuthEvent", 1, "edit")
        self.set_auth_token(auth)
        response = self.post('auth-event/%d/authenticate/' % authevent, data)
        r = json.loads(response.content.decode('utf-8'))
        self.set_auth_token(r.get('auth-token'))
        return response

def deregister(cfg, args):
    '''
    deregister admin user
    '''
    if args.tel is None and args.email is None:
        raise Exception("missing email/tel parameter")
    if args.code is None:
        raise Exception("missing auth code parameter")
    credentials = {
      'code': args.code
    }
    if args.tel:
        credentials['tlf'] = args.tel
    else:
        credentials['email'] = args.email

    event_id = authapi_admin_eid
    
    c = JClient()
    req = c.authenticate(event_id, credentials)

    if req.status_code != 200:
        raise Exception("authapi login failed")

    req = c.post("user/deregister/",{})

    if req.status_code != 200:
        raise Exception("authapi deregister failed")

    print("user deregistration successful")

def is_int(s):
    try:
        int(s)
        return True
    except ValueError:
        return False

def main(argv):
    parser = argparse.ArgumentParser(description='agora-elections admin script', formatter_class=RawTextHelpFormatter)
    parser.add_argument('command', nargs='+', help='''register <election_json>: registers an election (uses local <id>.json file)
update <election_id>: updates an election (uses local <id>.json file)
create <election_id>: creates an election
start <election_id>: starts an election (votes can be cast)
stop <election_id>: stops an election (votes cannot be cast)
set_start_date <election_id> --date <start_date>: set start date, start_date in format "yyyy-MM-dd HH:mm:ss"
set_stop_date <election_id> --date <stop_date>: set stop date, stop_date in format "yyyy-MM-dd HH:mm:ss"
set_tally_date <election_id> --date <stop_date>: set tally date, tally_date in format "yyyy-MM-dd HH:mm:ss"
tally <election_dir>: launches tally
tally_voter_ids <election_id>: launches tally, only with votes matching passed voter ids file
tally_no_dump <election_id>: launches tally (does not dump votes)
calculate_results <election_id>: uses agora-results to calculate the election's results (stored in db)
update_ballot_boxes_config <election_id>: uses agora-results to calculate the election's results (stored in db)
publish_results <election_id>: publishes an election's results (puts results.json and tally.tar.gz in public datastore)
show_column <election_id>: shows a column for an election
count_votes [election_id, [election_id], ...]: count votes
dump_votes [election_id, [election_id], ...]: dump voter ids
list_votes <election_dir>: list votes
list_elections: list elections
cast_votes <election_dir>: cast votes from ciphertetxs
dump_pks <election_id>: dumps pks for an election (public datastore)
encrypt <election_id>: encrypts votes using scala (public key must be in datastore)
encryptNode <election_id>: encrypts votes using node (public key must be in datastore)
dump_votes <election_id>: dumps votes for an election (private datastore)
change_social <election_id>: changes the social netoworks share buttons configuration
authapi_ensure_acls --acls-path <acl_path>: ensure that the acls inside acl_path exist
deregister [--email <email>] [--tel <telephone number>] --code <code>: deregister user in authapi
''')
    parser.add_argument('--ciphertexts', help='file to write ciphertetxs (used in dump, load and encrypt)')
    parser.add_argument('--acls-path', help='''the file has one line per acl with format: '(email:email@example.com|tlf:+34666777888),permission_name,object_type,object_id,user_election_id' ''')
    parser.add_argument('--plaintexts', help='json file to read votes from when encrypting', default = 'votes.json')
    parser.add_argument('--filter-config', help='file with filter configuration', default = None)
    parser.add_argument('--encrypt-count', help='number of votes to encrypt (generates duplicates if more than in json file)', type=int, default = 0)
    parser.add_argument('--vote-count', help='number of votes to generate', type=int, default = 0)
    parser.add_argument('--results-config', help='config file for agora-results')
    parser.add_argument('--voter-ids', help='json file with list of valid voter ids to tally (used with tally_voter_ids)')
    parser.add_argument('--email', help='User email ')
    parser.add_argument('--tel', help='User telephone number ')
    parser.add_argument('--date', help='Date to update')
    parser.add_argument('--code', help='User code for authentication')
    parser.add_argument('--ips-log', help='')
    parser.add_argument('--share-config', help='json file with the social networks share buttons configuration')
    # remove
    parser.add_argument('--elections-file', help='file with grouped elections')
    parser.add_argument('-c', '--column', help='column to display when using show_column', default = 'state')
    parser.add_argument('-f', '--filters', nargs='+', default=[], help="key==value(s) filters for queries (use ~ for like)")
    args = parser.parse_args()
    command = args.command[0]
    if hasattr(__main__, command):
        config = {}

        # commands that use an election id
        if len(args.command) == 2:
            if command in ['count_votes', 'dump_ids', 'change_social']:
                if is_int(args.command[1]) or ',' in args.command[1]:
                    config['election_id'] = args.command[1].split(',')
                else:
                    with open(args.command[1], 'r') as f:
                        lines = f.read().splitlines()
                        config['election_id'] = lines
            else:
                config['election_id'] = int(args.command[1])

                if args.ciphertexts is None:
                    config['ciphertexts'] = 'ciphertexts_' + str(config['election_id'])
                else:
                    config['ciphertexts'] = args.ciphertexts

                if command in ["register", "update"]:
                    jsonPath = '%s.json' % config['election_id']
                    if not os.path.isfile(jsonPath):
                        print("%s is not a file" % jsonPath)
                        exit(1)
                    print("> loading config in %s" % jsonPath)
                    with open(jsonPath, 'r') as f:
                        electionConfig = json.loads(f.read())

                    config['electionConfig'] = electionConfig

        config['plaintexts'] = args.plaintexts
        config['encrypt-count'] = args.encrypt_count
        config['filters'] = args.filters

        eval(command + "(config, args)")

    else:
        parser.print_help()

if __name__ == "__main__":
	main(sys.argv[1:])
