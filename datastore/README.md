Datastore
=========

This directory holds the public and private datastore for agora elections.

* datastore/public

Holds publically accessible information such as:

public keys
results (once published)
tallies (once published)

* datastore/private

Holds private information such as:

ciphertexts
results (not yet published)
tallies (not yet published)

Example nginx configuration blocks

    location /public {
        root /tmp/ballot_box/datastore;
    }
    location /private {
        root /tmp/ballot_box/datastore;
    }