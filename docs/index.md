# Ximmer User Guide

## Introduction

Ximmer is a tool designed to help users of targeted high throughput (or "next generation") 
genomic sequencing data (such as exome data) to accurately detect copy number variants
(CNVs). Ximmer is not a copy number detection tool itself. Rather, it is a framework for
running and evaluating other copy number detection tools. It offers three essential features
that users of CNV detection tools need:

 * A suite of pipelines for running a variety of well known CNV detection tools
 * A simulation tool that can create artificial CNVs in sequencing data for 
   the purpose of evaluating performance
 * A visualisation and curation tool that can combine results from multiple 
   CNV detection tools and allow the user to inspect them, along with 
   relevant annotations.

We created Ximmer because although there are very many CNV detection tools,
they can be hard to run and their performance can be highly variable and
hard to estimate. This is why Ximmer builds in simulation: to allow 
a quick and easy estimation of the performance of any tool on any data set.


## Installation and Requirements

To make Ximmer easier to use we have included support to automatically 
download and build a range of tools. You should make sure before starting
that you have at minimum the following requirements:

 * Java 1.7 (note: Java 1.8 does not work, unless you upgrade the bundled GATK)
 * Python 2.7, preferably the Anaconda installation
 * R 3.2 or higher

Ideally, these should all be directly accessible from your environment. 
If necessary, you can specify custom locations for them in the configuration file.

You should also make sure you have internet access while doing the installation
because Ximmer will try to download some components. It may be necessary to set 
the "http_proxy" environment variable if your network uses a proxy.


### Run Installer

Ximmer includes a simple installer script to help set up and configure
it for basic operation. To get started:

```
git clone git@github.com:ssadedin/ximmer.git
cd ximmer
./bin/install
```


## Configuring Ximmer

Ximmer sets all the basic configuration parameters to sensible defaults all by 
itself. You may like to inspect these and check if they are optimal for your
environment.  To do this, open the file `eval/pipeline/config.groovy` in a text
editor. 

See the [Configuration](config.md) documentation for more details. 

## Running Ximmer

See information about how to run an analysis using Ximmer in the [Analysis](analyses.md) 
section.