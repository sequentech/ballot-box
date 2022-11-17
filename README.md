Agora Elections [![tests_badge]][tests_link] 
=============================================

[tests_badge]: https://github.com/sequentech/ballot-box/workflows/Tests/badge.svg
[tests_link]: https://github.com/sequentech/ballot-box/actions?query=workflow%3A%22Tests%22

Installation
=========

Installing java 7

    apt-get install  openjdk-7-jdk

Download the play activator from

    wget https://downloads.typesafe.com/typesafe-activator/1.3.2/typesafe-activator-1.3.2.zip

Once you have installed, clone the repository

    git clone https://github.com/sequentech/ballot-box.git

Database setup

    postgres=# create user ballot_box;

    postgres=# create database ballot_box owner ballot_box;

    postgres=# create database ballot_box_test owner ballot_box;

    postgres=# ALTER ROLE ballot_box WITH PASSWORD 'mypassword';

Example configuration files

application.local.conf

    db.default.url="jdbc:postgresql://localhost:5432/ballot_box"
    db.default.driver="org.postgresql.Driver"
    db.default.user=ballot_box
    db.default.pass=ballot_box

    app.datastore.public="/tmp/ballot_box/datastore/public"
    app.datastore.private="/tmp/ballot_box/datastore/private"

    app.api.root="http://vota.podemos.info:8000"
    app.datastore.root="http://94.23.34.20:8000"
    app.datastore.ssl_root="https://94.23.34.20:14453"

    app.api.max_revotes=5

    app.eopeers.dir=/etc/eopeers/

    app.partial-tallies=false

    elections.auth.secret=hohoho
    elections.auth.expiry=60
    elections.auth.admin_expiry=3600

    ws.ssl {
      loose.disableHostnameVerification=true
      keyManager = {
        stores = [
          { type = "JKS", path = "/tmp/ballot_box/keystore.jks", password = "password" }
        ]
      }
      trustManager = {
        stores = [
          { type = "JKS", path = "/tmp/ballot_box/keystore.jks", password = "password" }
        ]
      }
    }

    app.authorities {
      test-auth1.sequentech.io = {
        name = "Sequent Tech auth 1"
        description = "My authority 1"
        url = "http://foo.bar.1"
        image = "http://foo.bar.1/img.img"
      }
      "test-auth2.sequentech.io" = {
        name = "Sequent Tech auth 2"
        description = "My authority 2"
        url = "http://foo.bar.2"
        image = "http://foo.bar.2/img"
      }
    }

    app.trustee_users {
      user-auth1 = {
        password = "password",
        authority_id = "test-auth1.sequentech.io"
      },
      user-auth2 = {
        password = "password",
        authority_id = "test-auth2.sequentech.io"
      }
    }

    # app.vote_callback_url="podemos.sequentech.io"

    # memcached
    # ehcacheplugin=disabled
    # memcached.host="127.0.0.1:11211"
    # logger.memcached=DEBUG

test.local.conf

    db.default.url="jdbc:postgresql://localhost:5432/ballot_box_test"
    db.default.driver="org.postgresql.Driver"
    db.default.user=ballot_box
    db.default.pass=ballot_box

    app.datastore.public="/tmp/ballot_box/datastore/public"
    app.datastore.private="/tmp/ballot_box/datastore/private"

    app.api.root="http://vota.podemos.info:8000"
    app.datastore.root="http://94.23.34.20:8000"
    app.datastore.ssl_root="https://94.23.34.20:14453"

    app.eopeers.dir=./test

    app.partial-tallies=false

    elections.auth.secret=hohoho
    elections.auth.expiry=60
    elections.auth.admin_expiry=3600

    ws.ssl {
      loose.disableHostnameVerification=true
      keyManager = {
        stores = [
          { type = "JKS", path = "/tmp/ballot_box/keystore.jks", password = "password" }
        ]
      }
      trustManager = {
        stores = [
          { type = "JKS", path = "/tmp/ballot_box/keystore.jks", password = "password" }
        ]
      }
    }

    app.authorities {
      test-auth1.sequentech.io = {
        name = "Sequent Tech auth 1"
        description = "My authority 1"
        url = "http://foo.bar.1"
        image = "http://foo.bar.1/img.img"
      }
      "test-auth2.sequentech.io" = {
        name = "Sequent Tech auth 2"
        description = "My authority 2"
        url = "http://foo.bar.2"
        image = "http://foo.bar.2/img"
      }
    }

    app.trustee_users {
      user-auth1 = {
        password = "password",
        authority_id = "test-auth1.sequentech.io"
      },
      user-auth2 = {
        password = "password",
        authority_id = "test-auth2.sequentech.io"
      }
    }

    logger.scala.slick.jdbc.JdbcBackend.statement=DEBUG


Key store set up

create the client keystore with the client certificate

    openssl pkcs12 -export -in /srv/certs/selfsigned/cert.pem -inkey /srv/certs/selfsigned/key-nopass.pem -out certs.p12 -name client

    keytool -importkeystore -deststorepass password -destkeypass password -destkeystore keystore.jks -srckeystore certs.p12 -srcstoretype PKCS12 -srcstorepass password -alias client

add the director ca to the keystore

    keytool -import -file auth1.pem -keystore keystore.jks

Admin tool set up

specify the follwing configuration parameters in the admin tool, at <app_root>/admin/admin.py

    # set configuration parameters
    datastore = '/tmp/ballot_box/datastore'
    shared_secret = 'hohoho'
    db_user = 'ballot_box'
    db_password = 'ballot_box'
    db_name = 'ballot_box'
    app_host = 'localhost'
    app_port = 9000
    node = '/usr/local/bin/node'

set the executable permissions if not already set for several admin scripts

    chmod u+x admin.py
    chmod u+x cycle.py
    chmod u+x batch.py
    chmod u+x encrypt.sh
    chmod u+x results.sh

Create a virtualenv for the admin script and install admin script requirements:

    mkvirtualenv ballot_box -p $(which python2)
    workon ballot_box
    cd ballot_box/admin
    pip install -r requirements.txt

Agora-Results set up

Clone and install tally-pipes

    git clone https://github.com/sequentech/tally-pipes.git
    mkvirtualenv tally-pipes -p $(which python3)
    workon tally-pipes
    cd tally-pipes
    pip install -r requirements.txt

you must also configure these two settings in results.sh, found in ballot-box/admin

    SEQUENT_RESULTS=/tmp/tally-pipes
    VENV=/root/.virtualenvs

Local vote encryption set up

If you want to encrypt votes locally (for testing purposes, see below), you must configure these
settings in admin/encrypt.sh

    IVY=/root/.ivy2/cache/

you must also make sure that the software is packaged from the activator console, with

    [ballot-box] $ package

which will generate the required jar in the target directory (note that encrypt.sh assumes scala v2.11)

Postgresql parameters

Some tuning changes can be made in /etc/postgresql/<version>/main/postgresql.conf

    max_connections = 200
    checkpoint_segments = 16
    checkpoint_completion_target = 0.7

restart the server

    service restart postgresql

Unit tests
=====

You can run units tests with

    activator
    test

Note that for tests to pass the datastore directory structure specified in test.local.conf must exist
and be writable.

Administration
==============

An election cycle can be run with the admin.py tool in the admin directory

You must first create an election config json file. Here's an example

    {
    "id": 66,
    "title": "My vote",
    "description": "choose stuff",
    "director": "my_director",
    "authorities": ["my_authority"],
    "layout": "pcandidates-election",
    "presentation": {
      "share_text": "share this",
      "theme": "foo",
      "urls": [
        {
          "title": "",
          "url": ""
        }
      ],
      "theme_css": "whatever"
    },
    "end_date": "2013-12-09T18:17:14.457000",
    "start_date": "2013-12-06T18:17:14.457000",
    "questions": [
        {
            "description": "",
            "layout": "pcandidates-election",
            "max": 1,
            "min": 0,
            "num_winners": 1,
            "title": "My question",
            "extra_options": {
                "shuffle_categories": true,
                "shuffle_all_options": true,
                "shuffle_category_list": []
            }
            "tally_type": "plurality-at-large",
            "answer_total_votes_percentage": "over-total-valid-votes",
            "answers": [
                {
                    "id": 0,
                    "category": "Cat A",
                    "details": "",
                    "sort_order": 1,
                    "urls": [
                      {
                        "title": "",
                        "url": ""
                      }
                    ],
                    "text": "Option A"
                },
                   {
                    "id": 1,
                    "category": "Cat B",
                    "details": "",
                    "sort_order": 1,
                    "urls": [
                      {
                        "title": "",
                        "url": ""
                      }
                    ],
                    "text": "Option B"
                }

            ]
        }
    ]
    }


Register the election (config must be named 50.json here)

     ./admin.py register 50

create the election

    ./admin.py create 50

dump the pks

    ./admin.py dump_pks 50

encrypt votes (normally only used for testing; you need an votes.json file to do this)

    ./admin.py encrypt 50

start the election

    ./admin.py start 50

cast votes (normally only used for testing, votes are generated with encrypt)

    ./admin cast_votes 50

stop the election

    ./admin.py stop 50

tally the election

    ./admin.py tally 50

calculate results

    ./admin results 50 --results-config config.json

publish results

    ./admin.py publish_results 50

You can also carry out administration commands in batch mode, with batch.py. batch.py expects a directory
with configuration files for both election definition and tally-pipes set up. The files must be in this
format

    <id>.results.json

for election configuration files, and

    <id>.config.results.json

for tally-pipes configuration files. batch.py will run the command for all the configuration files
found in the given directory, in increasing order of id. For example

    ./batch.py -c create -d json_files

will register, create and start elections for all configuration files in directory 'json_files'. If you
only want to operate on a range, you can use this form

    ./batch.py -c create -d json_files -s 1000 -e 2000

which will create elections with id beginning with 1000 up to 2000 (both inclusive)

Automated testing of an election cycle
==============

You can do automated tests with the cycle.py tool in the admin directory. This requires
correctly setting up admin.py first, as described earlier. You need an election configuration as base,
you can use one like the one above. cycle.py looks for such a file with default name 'election.json'.
You also need an tally-pipes configuration file to calculate results. cycle.py looks for such a file
at 'config.json'. Here's an example tally-pipes configuration file

    [
      [
        "tally_pipes.pipes.results.do_tallies",
        {"ignore_invalid_votes": true}
      ],
      [
        "tally_pipes.pipes.results.to_files",
        {"paths": ["results.json"]}
      ]
    ]

To run 5 cycles serially, starting with id 50

    ./cycle.py -t 5 -i 50

to run 5 cycles serially, casting 100 votes in each (votes duplicated)

    ./cycle.py -t 5 -i 50 -e 100

to run 10 cycles in parallel, using election.json as election config, config.json as tally-pipes config,
casting 100 votes and starting at id 50:

    ./cycle.py -t 10 -i 50 -e 100 -c election.json -r config.json -p

Casting test votes
==============

You can cast test votes into the ballotbox with the following steps. First, edit the encrypt.sh file
and specify the correct value for variables

    IVY=/root/.ivy2/cache
    SEQUENT_HOME=..

In order to cast votes for an election, that election must be in the correct state, which is 'started'. This
state implies the existence of public keys with which to encrypt votes, and also that the ballotbox will accept
incoming votes. Casting votes is done in three steps, first dump the public keys

    ./admin.py dump_pks <election_id>

then encrypt some votes

    ./admin.py encrypt <election_id>

This command will look for plaintexts in the file 'votes.json' in the same directory, otherwise override with

    ./admin.py encrypt <election_id> --plaintexts myvotes.json

The votes.json file must be in json array following format, for example to encrypt the values '1' and '2'

    [1, 2]

Once this command completes it will have placed a file 'ciphertexts_electionid' in the same directory. To
cast the ciphertexts into the ballotbox run

    ./admin.py cast_votes <election_id>

which will automatically look for the ciphertexts file generated above in the same directory. You can override
this location in both commands with the '--ciphertexts' switch.


Running (dev mode)
======

    activator
    run

Running (production mode)
======

    activator
    clean
    stage
    exit
    target/universal/stage/bin/ballot-box -v

you probably want to pass in a configuration file

    target/universal/stage/bin/ballot-box -Dconfig.file=/full/path/to/conf/application-prod.conf

the log will be found in

     target/universal/stage/logs/application.log

# License

Copyright (C) 2015 Sequent Tech Inc and/or its subsidiary(-ies).
Contact: legal@sequentech.io

This file is part of the sequent-core-view module of the Sequent Tech project.

This program is distributed in the hope that it will be useful, but WITHOUT ANY
WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
PARTICULAR PURPOSE.  See the GNU General Public License for more details.

Commercial License Usage
Licensees holding valid commercial Sequent Tech project licenses may use this
file in accordance with the commercial license agreement provided with the
Software or, alternatively, in accordance with the terms contained in
a written agreement between you and Sequent Tech Inc. For licensing terms and
conditions and further information contact us at legal@sequentech.io .

GNU Affero General Public License Usage
Alternatively, this file may be used under the terms of the GNU Affero General
Public License version 3 as published by the Free Software Foundation and
appearing in the file LICENSE.AGPL3 included in the packaging of this file, or
alternatively found in <http://www.gnu.org/licenses/>.

External libraries
This program distributes libraries from external sources. If you follow the
compilation process you'll download these libraries and their respective
licenses, which are compatible with our licensing.
