Agora Elections
===============

Installation
=========

Installing java 7 in debian

    http://www.webupd8.org/2012/06/how-to-install-oracle-java-7-in-debian.html

Download the play activator from

    https://www.playframework.com/download

Once you have installed, clone the repository

    git clone https://github.com/agoravoting/agora_elections.git

Database setup

    postgres=# create user agora_elections;

    CREATE ROLE

    postgres=# create database agora_elections owner agora_elections;
    CREATE DATABASE

    ALTER ROLE agora_elections WITH PASSWORD 'mypassword';

Example configuration files

application.local.conf

    db.default.url="jdbc:postgresql://localhost:5432/agora_elections"
    db.default.driver="org.postgresql.Driver"
    db.default.user=agora_elections
    db.default.pass=agora_elections

    app.datastore.public="/tmp/agora_elections/datastore/public"
    app.datastore.private="/tmp/agora_elections/datastore/private"

    app.api.root="http://vota.podemos.info:8000"
    app.datastore.root="http://94.23.34.20:8000"

    app.eopeers.dir=/etc/eopeers/

    booth.auth.secret=hohoho
    booth.auth.expiry=600000

    ws.ssl {
      loose.disableHostnameVerification=true
      keyManager = {
        stores = [
          { type = "JKS", path = "/tmp/agora_elections/keystore.jks", password = "password" }
        ]
      }
      trustManager = {
        stores = [
          { type = "JKS", path = "/tmp/agora_elections/keystore.jks", password = "password" }
        ]
      }
    }


test.local.conf

    db.default.url="jdbc:postgresql://localhost:5432/agora_elections_test"
    db.default.driver="org.postgresql.Driver"
    db.default.user=agora_elections
    db.default.pass=agora_elections

    app.datastore.public="/tmp/agora_elections/datastore/public"
    app.datastore.private="/tmp/agora_elections/datastore/private"

    app.api.root="http://vota.podemos.info:8000"
    app.datastore.root="http://94.23.34.20:8000"

    app.eopeers.dir=/etc/eopeers/

    booth.auth.secret=hohoho
    booth.auth.expiry=600000

    ws.ssl {
      loose.disableHostnameVerification=true
      keyManager = {
        stores = [
          { type = "JKS", path = "/tmp/agora_elections/keystore.jks", password = "password" }
        ]
      }
      trustManager = {
        stores = [
          { type = "JKS", path = "/tmp/agora_elections/keystore.jks", password = "password" }
        ]
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
    datastore = '/tmp/agora_elections/datastore'
    shared_secret = 'hohoho'
    db_user = 'agora_elections'
    db_password = 'agora_elections'
    db_name = 'agora_elections'
    app_host = 'localhost'
    app_port = 9000
    node = '/usr/local/bin/node'

set the executable permissions if not already set

    chmod u+x admin.py

do this also for the cycle tool

    chmod u+x cycle.py

Agora-Results set up

Clone and install agora-results

    git clone https://github.com/agoravoting/agora-results.git
    mkvirtualenv agora-results -p $(which python3)
    workon agora-results
    cd agora-results
    pip install -r requirements.txt

you must also configure these two settings in results.sh, found in agora-elections/admin

    AGORA_RESULTS=/tmp/agora-results
    VENV=/root/.virtualenvs

Local vote encryption set up

If you want to encrypt votes locally, you must configure these settings in admin/encrypt.sh

    IVY=/root/.ivy2/cache/

you must also make sure that the software is packaged from the activator console, with

    [agora-elections] $ package

which will generate the required jar in the target directory (note that encrypt.sh assumes scala v2.11)

Winscp sync settings

    |.git/;conf;/logs/;target/;activator;activator.bat;*.jar

Unit tests
=====

    activator
    test

Manual testing an election cycle
==============

An election cycle can be run with the admin.py tool in the admin directory

You must first create an election config json file. Here's an example

    {
      "election_id": 50,
      "director": "wadobo-auth1",
      "authorities": ["wadobo-auth3"],
      "title": "Test election",
      "url": "https://example.com/election/url",
      "description": "election description",
      "questions_data": [{
          "question": "Who Should be President?",
          "tally_type": "ONE_CHOICE",
          "answers": [
              {"a": "ballot/answer",
              "details": "",
              "value": "Alice"},
              {"a": "ballot/answer",
              "details": "",
              "value": "Bob"}
          ],
          "max": 1, "min": 0
      }],
      "voting_start_date": "2015-12-06T18:17:14.457",
      "voting_end_date": "2015-12-09T18:17:14.457",
      "is_recurring": false,
      "extra": []
    }

Register the election (config must be named 50.json here)

     ./admin.py register 50

create the election

    ./admin.py create 50

dump the pks

    ./admin.py dump_pks 50

encrypt votes (you need an votes.json file to do this)

    ./admin.py encrypt 50

start the election

    ./admin.py start 50

cast votes

    ./admin cast_votes 50

tally the election

    ./admin.py tally 50

calculate results

    ./admin results 50 --results-config config.json

publish results

    ./admin.py publish_results 50

Automated testing of an election cycle
==============

You can do automated tests of the above with the cycle.py tool in the admin directory. This requires
correctly setting up admin.py first, as described earlier.

To run 5 cycles serially, starting with id 50

    ./cycle.py -t 5 -i 50

to run 5 cycles serially, casting 100 votes in each (votes duplicated)

    ./cycle.py -t 5 -i 50 -e 100

to run 10 cycles in parallel, using election.json as election config, config.json as agora-results config,
casting 100 votes and starting at id 50

    ./cycle.py -t 10 -i 50 -e 100 -c election.json -r config.json -p

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
    target/universal/stage/bin/agora-elections -v