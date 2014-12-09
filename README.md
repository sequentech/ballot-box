Agora Elections
===============

Installation
=========

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
          { type = "JKS", path = "/tmp/agora_elections/truststore.jks", password = "password" }
        ]
      }
    }


test.local.conf

    # FIXME change this to use a test database
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
          { type = "JKS", path = "/tmp/agora_elections/truststore.jks", password = "password" }
        ]
      }
    }

Installing java 7 in debian

    http://www.webupd8.org/2012/06/how-to-install-oracle-java-7-in-debian.html

Key store set up

create the client keystore with the client certificate

    openssl pkcs12 -export -in /srv/certs/selfsigned/cert.pem -inkey /srv/certs/selfsigned/key-nopass.pem -out certs.p12 -name client

    keytool -importkeystore -deststorepass password -destkeypass password -destkeystore keystore.jks -srckeystore certs.p12 -srcstoretype PKCS12 -srcstorepass password -alias client

add the director ca to the keystore

    keytool -import -file auth1.pem -keystore keystore.jks

Sublime sync settings

    |.git/;conf;/logs/;target/;activator;activator.bat;*.jar

Running
======

    activator
    run

Tests
=====

    activator
    test

Manual testing an election cycle
==============

An election cycle can be run with the admin tool in the admin directory

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

     ./admin register 50

create the election

    ./admin create 50

dump the pks

    ./admin dump_pks 50

encrypt votes (you need an votes.json file to do this)

    ./admin encrypt 50

copy the ciphertexts file to the private datastore

    mkdir ../datastore/private/50
    cp ciphertexts_50 ../datastore/private/50/ciphertexts

tally the election

    ./admin tally_no_dump 50

check the tally was downloaded correctly

    ls ../datastore/private/50/tally.tar.gz