# Project versioning with SNAPSHOT's

Date: 2022-01-25

## Status

Proposed

## Context

We need a versioning scheme for the project that will allow simultaneous development of the library modules (`kernel` and `sdk`) and the projects in the `examples/` directory that can depend 
on the library modules. SNAPSHOT versioning will allow this.

## Decision

1. `main` branch contains changes that have been released
2. `develop` branch contains changes that haven't been released yet
3. Project version on the `main` branch follow semver's scheme `MAJOR.MINOR.PATCH`  
4. Project version on the `develop` branch reflects the next development version 
   of the latest release, so `MAJOR.MINOR+1.0-SNAPSHOT`
   

Example: when the latest released version is `v0.5.0`, the project version on `main` branch is `0.5.0` while 
on `develop` branch, the project version is `0.6.0-SNAPSHOT`.

## Consequences

* Merge conflicts may arise when merging hotfixes into the `develop`
* Introduction of a tool that will automate the version management
