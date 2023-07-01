## Adjusting parameters demo

This repo contains a [QuPath extension](https://qupath.github.io) used to demonstrate the extent 
to which image analysis results can be affected by seemingly-small choices in algorithm parameters.

It's intended to be used with *small, 8-bit, single-channel images* where thresholding could give 
a sensible result.

For a good example of this, see [BBBC001](https://bbbc.broadinstitute.org/BBBC001) from the 
[Broad Bioimage Benchmark Collection](https://bbbc.broadinstitute.org).

## Installation

The jar file is installed like any QuPath extension: drag it into the viewer.

## Usage

You should find a new command in QuPath's menus.
So run that.

## What it does

The command will run a simple algorithm that is using ImageJ in the background:

* Convert the image to 8-bit grayscale (if it isn't already)
* Optionally apply a Gaussian filter
* Apply a threshold (either manually or automatically selected)
* Apply a watershed transform, via ImageJ's *Find Maxima* command, with an optional 'noise tolerance'

The usefulness comes in the interactivity: as you adjust parameters, the results should update (almost) immediately.

You can also see a table and bar chart showing the count of objects, along with their mean area and intensity.

What's more, if you have multiple images open simultaneously, the command will be applied to all of them.

## Why?

I first used this to create some slides for a talk at *Microscience Microscopy Congress 2023*.