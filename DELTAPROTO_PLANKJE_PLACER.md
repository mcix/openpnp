# Plankjeplacer

In the DeltaProto panel add a configuration similar to the Feeder layout, we call the Plankje layout.
We define the location of pin1 and pin40
On each pin is space for a strip of components, so there are 40 pins in total.
By defining pin1 and pin40 we know all locations of the 40 DeltaProtoStripFeeders

We should also configure the direction of the strips, this is either Y-up, Y-down, X-left or X-right. Default Y-down.

We also should set the Z height, this will be the same for all DeltaProtoStripFeeders

## DeltaProtoStripFeeder
The location is defined by the pin
The first pickup location is relative to the pin: 8.5mm to the right and 2mm down, or point of view is that the 
first pickup is on top, and all other placements are in the direction Y down.
Then a strip can either have a pitch of 4mm or 2mm

We should store the last pickup location per feeder, we call it feedposition, and need to persist this to disk because we need to persist 
this information even when we restart or crash.

Add some manual controls in the UI of the DeltaProtoStripFeeder to set the feedposition, location etc similar to a
HwgcFeeder we have.