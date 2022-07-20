# dmn-avro

This tool applies a [Decision Model and Notation](https://en.wikipedia.org/wiki/Decision_Model_and_Notation) (DMN) file to the data in an Avro binary object file, generating additional derivations and/or validations.

## Installation

Compile it using:

    $ make compile

## Usage

Run it using:

    $ make run

## Third-Party Libraries

### Camunda DMN Engine

This tool uses the open source DMN engine from the Community Edition of the [Camunda Platform 7](https://github.com/camunda/camunda-bpm-platform) which is available under the Apache-2.0 license.
